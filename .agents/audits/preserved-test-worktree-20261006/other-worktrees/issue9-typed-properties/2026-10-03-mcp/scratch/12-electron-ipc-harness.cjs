// Two-phase disposable Electron IPC acceptance harness.
// Phase A: real Electron main/renderer IPC -> real MCP HTTP -> public CLI API -> real db-worker.
// Phase B: relaunch the same persisted graph after deleting the search index dir to
//          exercise the full-index build path, then assert scoped search + recycle invisibility.
const path = require('path');
const fs = require('fs');
const net = require('net');
const crypto = require('crypto');
const { _electron: electron } = require('playwright-core');
const { Client } = require('@modelcontextprotocol/sdk/client/index.js');
const { StreamableHTTPClientTransport } = require('@modelcontextprotocol/sdk/client/streamableHttp.js');

const ROOT = path.resolve(__dirname, '..');
const TMP = __dirname;
const electronExe = path.join(ROOT, 'resources', 'node_modules', 'electron', 'dist', 'electron.exe');
const USER_DATA = path.join(TMP, 'userData');
const LOG_PATH = path.join(TMP, 'harness.log');
const SESSION_PATH = path.join(TMP, 'harness-session.json');
fs.writeFileSync(LOG_PATH, '');
const LOG = fs.createWriteStream(LOG_PATH, { flags: 'a' });

function log(...a) {
  const s = a.map(x => typeof x === 'string' ? x : JSON.stringify(x)).join(' ');
  LOG.write(s + '\n');
  process.stdout.write(s + '\n');
}
function freePort() {
  return new Promise((resolve, reject) => {
    const s = net.createServer();
    s.on('error', reject);
    s.listen(0, '127.0.0.1', () => { const p = s.address().port; s.close(() => resolve(p)); });
  });
}
const checks = [];
function check(name, ok, detail) {
  checks.push({ name, ok: !!ok, detail });
  log((ok ? 'PASS' : 'FAIL') + ' :: ' + name + (detail === undefined ? '' : ' :: ' + JSON.stringify(detail).slice(0, 600)));
}
const delay = ms => new Promise(r => setTimeout(r, ms));
const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

function writeConfigs(port, token) {
  fs.mkdirSync(USER_DATA, { recursive: true });
  fs.writeFileSync(path.join(USER_DATA, 'configs.edn'),
    `{:server/mcp-enabled? true\n :server/host "127.0.0.1"\n :server/port ${port}\n :server/tokens ["${token}"]}\n`);
}
function wipeRuntime() {
  for (const d of ['userData', 'graphs', 'home', 'appdata', 'localappdata', '.graph-lifecycle']) {
    fs.rmSync(path.join(TMP, d), { recursive: true, force: true });
  }
}

async function launchAndConnect(port, token, tag) {
  const app = await electron.launch({ executablePath: electronExe, args: ['.tmp-electron-ipc/app'], cwd: ROOT, timeout: 180000 });
  log(`[${tag}] launched pid=` + app.process().pid);
  app.process().stderr.on('data', d => {
    const s = d.toString();
    if (!s.includes('HARNESS heartbeat')) LOG.write(`[${tag}] MAIN-ERR: ` + s);
  });
  const win = await app.firstWindow({ timeout: 180000 });
  let pageClosed = false;
  win.on('close', () => { pageClosed = true; });
  win.on('crash', () => { pageClosed = true; });
  win.on('console', m => { if (m.type() === 'error') LOG.write(`[${tag}] PAGE-ERROR: ` + m.text().slice(0, 400) + '\n'); });

  let graph = null;
  for (let i = 0; i < 90 && !pageClosed; i++) {
    await delay(2000);
    graph = await win.evaluate(async () => {
      try { return await window.logseq.api.get_current_graph(); } catch (e) { return null; }
    }).catch(() => null);
    if (graph && graph.name) break;
  }
  log(`[${tag}] current graph=` + JSON.stringify(graph) + ' pageClosed=' + pageClosed);

  const base = `http://127.0.0.1:${port}/mcp`;
  let client = null;
  let connected = false;
  for (let i = 0; i < 30 && !pageClosed && !connected; i++) {
    try {
      await win.evaluate(async () => { await window.apis.doAction(['server/do', 'start']); }).catch(() => {});
      const transport = new StreamableHTTPClientTransport(new URL(base), { requestInit: { headers: { Authorization: `Bearer ${token}` } } });
      client = new Client({ name: 'electron-ipc-harness', version: '0.0.1' });
      await client.connect(transport);
      connected = true;
    } catch (e) {
      LOG.write(`[${tag}] connect attempt ${i} failed: ${String(e && e.message || e)} pageClosed=${pageClosed}\n`);
      try { if (client) await client.close(); } catch (_) {}
      client = null;
      await delay(2000);
    }
  }
  if (!connected) throw new Error(`MCP server did not start for ${tag} (pageClosed=${pageClosed})`);
  log(`[${tag}] MCP connected`);

  const callText = async (name, args) => {
    try {
      const r = await client.callTool({ name, arguments: args });
      const c = r.content && r.content[0];
      return c ? c.text : JSON.stringify(r);
    } catch (e) { return String(e && e.message || e); }
  };
  const callJson = async (name, args) => {
    const t = await callText(name, args);
    try { return JSON.parse(t); } catch (e) { return { raw: t }; }
  };
  return { app, win, client, graph, callText, callJson };
}

// ---------------------------------------------------------------------------
// Assertions (ported from src/dev-cljs/electron/mcp_verify.cljs)
// ---------------------------------------------------------------------------
const REQUIRED_TOOLS = ['getBlock', 'getPage', 'getRecycledBlock', 'listPages', 'listProperties',
  'listTags', 'recycleBlock', 'restoreBlock', 'searchBlocks', 'upsertNodes'];
const PROP_TITLES = { cv: 'MCP CV', orcid: 'MCP ORCID', description: 'MCP Description',
  topics: 'MCP Topics', score: 'MCP Score', enabled: 'MCP Enabled' };
const PROP_SPECS = {
  cv: { type: 'url', cardinality: 'one' },
  orcid: { type: 'default', cardinality: 'one' },
  description: { type: 'default', cardinality: 'one' },
  topics: { type: 'node', cardinality: 'many' },
  score: { type: 'number', cardinality: 'one' },
  enabled: { type: 'checkbox', cardinality: 'one' },
};

function findProp(props, title) { return (props || []).find(p => (p.title || p['block/title']) === title); }
function propKey(props, title) { const p = findProp(props, title); return p ? p.ident : null; }
function entityVal(entity, props, title) { const k = propKey(props, title); return k ? entity[k] : undefined; }
function refUuid(block) { return block && block.uuid ? String(block.uuid) : null; }
// Ref values arrive in three shapes: plain UUID strings (receipts), `{uuid}` objects,
// or entity-serialized `{block/uuid, block/title, db/ident}` objects (getPage/getBlock).
function refUuidOf(x) {
  if (x == null) return null;
  if (typeof x === 'string') return x;
  return String(x.uuid || x['block/uuid'] || '') || null;
}
function topicSet(v) {
  const s = new Set();
  for (const x of (v || [])) { const u = refUuidOf(x); if (u) s.add(u); }
  return s;
}

function runAssertions(r) {
  const A = [];
  const push = (name, ok, detail) => A.push({ name, ok: !!ok, detail });
  const schemas = r.schemas || {};
  const toolNames = r.toolNames || [];
  const pageUuid = r.pageUuid;
  const rootUuid = r.rootUuid;
  const childUuid = r.childUuid;
  const orderedUuid = r.orderedUuid;
  const taskUuid = r.taskUuid;
  const props = r.props || [];
  const statusPropUuid = r.statusPropUuid;
  const propUuids = r.propUuids || {};
  const topicAlpha = r.topicAlpha;
  const topicBeta = r.topicBeta;

  // scenario 1: registration + schemas
  push('all required tool names are registered', REQUIRED_TOOLS.every(t => toolNames.includes(t)), toolNames);
  const req = n => (schemas[n] && schemas[n].required) || [];
  push('recycleBlock inputSchema requires blockUuid', JSON.stringify(req('recycleBlock')) === JSON.stringify(['blockUuid']), req('recycleBlock'));
  push('restoreBlock inputSchema requires blockUuid', JSON.stringify(req('restoreBlock')) === JSON.stringify(['blockUuid']), req('restoreBlock'));
  push('getRecycledBlock inputSchema requires blockUuid', JSON.stringify(req('getRecycledBlock')) === JSON.stringify(['blockUuid']), req('getRecycledBlock'));
  push('getBlock inputSchema requires uuid', JSON.stringify(req('getBlock')) === JSON.stringify(['uuid']), req('getBlock'));

  // seed / hierarchy
  const pageSeed = r.pageSeed || {};
  const seed = r.seed || {};
  const seedOps = seed.operations || [];
  push('page add receipt is verified with title',
    pageSeed.mode === 'verified' && pageSeed.operations && pageSeed.operations[0] &&
    pageSeed.operations[0].entity && pageSeed.operations[0].entity.title === 'MCP IPC Page',
    pageSeed);
  push('block add receipts are verified and parent chain matches requested hierarchy',
    seedOps.length === 4 && seedOps.every(o => o.status === 'verified') &&
    seedOps[0].uuid === rootUuid && seedOps[0]['parent-uuid'] === pageUuid &&
    seedOps[1].uuid === childUuid && seedOps[1]['parent-uuid'] === rootUuid,
    seedOps.map(o => ({ op: o['op-id'], uuid: o.uuid, parent: o['parent-uuid'] })));

  const pageTree = r.pageTree || {};
  const treeBlocks = pageTree.blocks || [];
  const rootNode = treeBlocks.find(b => b.uuid === rootUuid);
  const childInRoot = rootNode && (rootNode.children || []).find(c => c.uuid === childUuid);
  const orderedNode = treeBlocks.find(b => b.uuid === orderedUuid);
  const taskNode = treeBlocks.find(b => b.uuid === taskUuid);
  // `:block/tree-has-more?` is only emitted when the tree is truncated; a complete
  // tree omits the key entirely. Treat absent as "no more blocks".
  const treeMore = pageTree['tree-has-more?'] ?? pageTree['block/tree-has-more?'];
  push('getPage includeChildren nests root->child and returns real budget',
    !!childInRoot && treeMore !== true, { more: treeMore, child: !!childInRoot });
  push('ordered item exposes the real numbered-list marker (no literal prefix)',
    orderedNode && orderedNode['order-list-type'] && orderedNode['order-list-type'].title === 'number',
    orderedNode && orderedNode['order-list-type']);
  push('task item exposes the real Task status closed value Done',
    taskNode && taskNode.status && taskNode.status.title === 'Done' &&
    taskNode.status.ident === 'logseq.property/status.done',
    taskNode && taskNode.status);

  const rootRead = r.rootRead || {};
  const orderedRead = r.orderedRead || {};
  const taskRead = r.taskRead || {};
  push('getBlock reads root title/parent/page', rootRead.uuid === rootUuid && rootRead.title === 'mcp root' &&
    rootRead.parent === pageUuid && rootRead.page === pageUuid, rootRead);
  push('getBlock reads numbered-list marker', orderedRead['order-list-type'] && orderedRead['order-list-type'].title === 'number', orderedRead['order-list-type']);
  push('getBlock reads Task status Done', taskRead.status && taskRead.status.title === 'Done', taskRead.status);

  // scenario 2: user properties discovery
  const discovery = {};
  for (const key of Object.keys(PROP_TITLES)) {
    const got = findProp(props, PROP_TITLES[key]);
    const spec = PROP_SPECS[key];
    discovery[key] = {
      found: !!got, uuid: got && got.uuid, ident: got && got.ident,
      type: got && got.type, cardinality: got && got.cardinality,
      match: !!(got && got.type === spec.type && (got.cardinality || 'one') === spec.cardinality),
    };
  }
  push('listProperties discovers all six disposable typed user properties with real metadata',
    Object.values(discovery).every(d => d.found && d.match && UUID_RE.test(d.uuid || '')), discovery);

  const personAdd = r.personAdd || {};
  const personBlockAdd = r.personBlockAdd || {};
  const personPage = r.personPage || {};
  const personEntity = personPage.entity || {};
  const personBlockRead = r.personBlockRead || {};
  // Entity serialization projects each property attr as a reference envelope:
  // url/default -> {title, uuid}, number -> {value, uuid}, checkbox -> raw boolean.
  const propText = v => (v && typeof v === 'object') ? (v.title ?? v.value) : v;
  const propNum = v => (v && typeof v === 'object') ? v.value : v;
  // Receipt property maps use full idents (`user.property/x`, `logseq.property/status`);
  // entity serialization uses short ident names. Match either.
  const receiptProps = rc => (rc.operations && rc.operations[0] && rc.operations[0].entity && rc.operations[0].entity.properties) || {};
  const receiptGet = (rc, shortIdent) => {
    const m = receiptProps(rc);
    const k = Object.keys(m).find(k => k === shortIdent || k.endsWith('/' + shortIdent));
    return k ? m[k] : undefined;
  };
  const receiptTopics = rc => topicSet(receiptGet(rc, propKey(props, PROP_TITLES.topics)) || []);
  push('person page add returns verified receipt with typed url/default/node-many values',
    personAdd.mode === 'verified' &&
    receiptGet(personAdd, propKey(props, PROP_TITLES.cv)) === 'https://example.org/people/alpha' &&
    receiptGet(personAdd, propKey(props, PROP_TITLES.orcid)) === '0000-0002-1825-0097' &&
    receiptGet(personAdd, propKey(props, PROP_TITLES.description)) === 'Researcher' &&
    receiptTopics(personAdd).has(topicAlpha), receiptProps(personAdd));
  // personPage is read before the later union edit, so only topicAlpha is present yet.
  push('getPage exposes typed cv/orcid/topics on the entity',
    personEntity.title === 'Person Alpha' &&
    propText(entityVal(personEntity, props, PROP_TITLES.cv)) === 'https://example.org/people/alpha' &&
    propText(entityVal(personEntity, props, PROP_TITLES.orcid)) === '0000-0002-1825-0097' &&
    topicSet(entityVal(personEntity, props, PROP_TITLES.topics)).has(topicAlpha),
    { title: personEntity.title, cv: propText(entityVal(personEntity, props, PROP_TITLES.cv)),
      orcid: propText(entityVal(personEntity, props, PROP_TITLES.orcid)),
      topics: Array.from(topicSet(entityVal(personEntity, props, PROP_TITLES.topics))) });
  push('person block add returns verified receipt',
    personBlockAdd.mode === 'verified' &&
    personBlockAdd.operations && personBlockAdd.operations[0] && personBlockAdd.operations[0].entity &&
    personBlockAdd.operations[0].entity.uuid === r.personBlockUuid,
    { mode: personBlockAdd.mode });
  const readScore = entityVal(personBlockRead, props, PROP_TITLES.score);
  push('getBlock exposes typed description, number, checkbox and many node refs',
    propText(entityVal(personBlockRead, props, PROP_TITLES.description)) === 'Block level description' &&
    propNum(readScore) === 0 &&
    entityVal(personBlockRead, props, PROP_TITLES.enabled) === false &&
    topicSet(entityVal(personBlockRead, props, PROP_TITLES.topics)).has(topicAlpha) &&
    topicSet(entityVal(personBlockRead, props, PROP_TITLES.topics)).has(topicBeta),
    { description: propText(entityVal(personBlockRead, props, PROP_TITLES.description)),
      score: readScore, enabled: entityVal(personBlockRead, props, PROP_TITLES.enabled),
      topics: Array.from(topicSet(entityVal(personBlockRead, props, PROP_TITLES.topics))) });

  const personPageAfter = r.personPageAfter || {};
  const ppEntity = personPageAfter.entity || {};
  const unionReceipt = r.pageUnion || {};
  push('many node refs are additive: editing one ref retains existing refs',
    topicSet(receiptGet(unionReceipt, propKey(props, PROP_TITLES.topics))).has(topicAlpha) &&
    topicSet(receiptGet(unionReceipt, propKey(props, PROP_TITLES.topics))).has(topicBeta) &&
    receiptGet(unionReceipt, propKey(props, PROP_TITLES.description)) === 'Senior Researcher',
    receiptProps(unionReceipt));
  push('property-only page edit unions refs and retains entity title',
    ppEntity.title === 'Person Alpha' &&
    topicSet(entityVal(ppEntity, props, PROP_TITLES.topics)).has(topicAlpha) &&
    topicSet(entityVal(ppEntity, props, PROP_TITLES.topics)).has(topicBeta) &&
    propText(entityVal(ppEntity, props, PROP_TITLES.description)) === 'Senior Researcher',
    { title: ppEntity.title, description: propText(entityVal(ppEntity, props, PROP_TITLES.description)),
      topics: Array.from(topicSet(entityVal(ppEntity, props, PROP_TITLES.topics))) });
  const dryScore = entityVal(r.personBlockAfterDry || {}, props, PROP_TITLES.score);
  push('dry-run typed property edit reports dry-run and does not mutate',
    r.blockDryRun && r.blockDryRun.mode === 'dry-run' && dryScore && dryScore.value === 0,
    { mode: r.blockDryRun && r.blockDryRun.mode, score: dryScore });
  push('wrong-typed scalar is rejected',
    typeof r.badScalar === 'string' && r.badScalar.includes('finite JSON number'), r.badScalar);
  push('malformed node ref is rejected',
    typeof r.badRef === 'string' && r.badRef.includes('reference envelope'), r.badRef);
  push('mixed batch with a wrong-typed property is rejected',
    typeof r.badMixed === 'string' && r.badMixed.includes('finite JSON number'), r.badMixed);
  const badPage = r.personPageAfterBad || {};
  const badBlock = r.personBlockAfterBad || {};
  const partialBlock = ((badPage.blocks) || []).some(b => b.title === 'mcp partial person block');
  push('rejected mixed batch preserves page metadata and creates no partial block',
    propText(entityVal(badPage.entity || {}, props, PROP_TITLES.description)) === 'Senior Researcher' &&
    topicSet(entityVal(badPage.entity || {}, props, PROP_TITLES.topics)).has(topicAlpha) &&
    topicSet(entityVal(badPage.entity || {}, props, PROP_TITLES.topics)).has(topicBeta) && !partialBlock,
    { partial: partialBlock, description: propText(entityVal(badPage.entity || {}, props, PROP_TITLES.description)),
      topics: Array.from(topicSet(entityVal(badPage.entity || {}, props, PROP_TITLES.topics))) });
  const badScore = entityVal(badBlock, props, PROP_TITLES.score);
  push('rejected scalar/ref writes preserve block metadata',
    badScore && badScore.value === 0 &&
    entityVal(badBlock, props, PROP_TITLES.enabled) === false &&
    topicSet(entityVal(badBlock, props, PROP_TITLES.topics)).has(topicAlpha) &&
    topicSet(entityVal(badBlock, props, PROP_TITLES.topics)).has(topicBeta),
    { score: badScore });

  // built-in closed-value status
  const statusWrite = r.statusWrite || {};
  const blockObserved = receiptGet(statusWrite, 'status') || null;
  const blockAfterStatus = r.blockAfterStatus || {};
  push('upsertNodes block closed-value write returns a verified receipt',
    statusWrite.mode === 'verified', { mode: statusWrite.mode });
  push('verified block receipt reports the observed closed-value ref',
    typeof blockObserved === 'string' && UUID_RE.test(blockObserved) && blockObserved !== 'Done', blockObserved);
  push('getBlock exposes the written closed value',
    !blockAfterStatus.error && blockAfterStatus.status && String(blockAfterStatus.status.uuid) === blockObserved,
    blockAfterStatus.status);
  push('upsertNodes page closed-value write returns a verified receipt',
    r.pageStatusWrite && r.pageStatusWrite.mode === 'verified', { mode: r.pageStatusWrite && r.pageStatusWrite.mode });
  const pageAfterStatus = r.pageAfterStatus || {};
  push('getPage exposes the page property on the entity',
    !pageAfterStatus.error && pageAfterStatus.entity && String(pageAfterStatus.entity.uuid) === pageUuid &&
    pageAfterStatus.entity.status, pageAfterStatus.entity && pageAfterStatus.entity.status);
  push('dry-run closed-value write reports dry-run and does not write',
    r.statusDryRun && r.statusDryRun.mode === 'dry-run' &&
    r.blockAfterStatusDry && r.blockAfterStatusDry.status &&
    String(r.blockAfterStatusDry.status.uuid) === blockObserved,
    { mode: r.statusDryRun && r.statusDryRun.mode });
  push('malformed closed-value type is rejected',
    typeof r.malformedType === 'string' && r.malformedType.includes('existing closed value'), r.malformedType);
  const mixedTitles = ((r.pageAfterMixed || {}).blocks || []).map(b => b.title);
  push('invalid mixed batch fails without partial writes',
    typeof r.mixedBatch === 'string' && r.mixedBatch.includes('existing property') &&
    !mixedTitles.includes('mcp partial block'), { error: r.mixedBatch, titles: mixedTitles });

  // scenario 3: recycle / restore
  const before = r.recycleBefore || {};
  const recycle = r.recycle || {};
  const hidden = r.getBlockWhileRecycled || {};
  const fetched = r.getRecycled || {};
  const noop = r.recycleNoop || {};
  const restore = r.restore || {};
  const visible = r.getBlockAfterRestore || {};
  const recycleUuids = recycle['affected-uuids'] || [];
  const restoredUuids = restore['affected-uuids'] || [];
  const subtree = fetched.subtree || [];
  push('getBlock before recycle is visible', before.uuid === rootUuid && before.title === 'mcp root', before);
  push('recycle reports recycled state and no-op false',
    recycle.state === 'recycled' && recycle['no-op'] === false, recycle);
  push('recycle root uuid matches', recycle['root-uuid'] === rootUuid, recycle['root-uuid']);
  push('recycle affected root-first root + child',
    recycle['affected-count'] === 2 && recycleUuids[0] === rootUuid &&
    new Set(recycleUuids).size === 2 && recycleUuids.includes(childUuid), recycleUuids);
  push('recycle stamps deleted-at', recycle['deleted-at'] != null, recycle['deleted-at']);
  push('recycled root is hidden from getBlock',
    !hidden.title && String(hidden.raw || JSON.stringify(hidden)).includes('getBlock'), hidden);
  push('getRecycledBlock returns recycled root-first subtree',
    fetched.state === 'recycled' && fetched['root-uuid'] === rootUuid &&
    fetched['subtree-count'] === 2 && subtree[0] === rootUuid &&
    new Set(subtree).size === 2 && subtree.includes(childUuid), fetched);
  push('getRecycledBlock retains original page location', fetched['original-page-uuid'] === pageUuid, fetched['original-page-uuid']);
  push('getRecycledBlock retains original parent', fetched['original-parent-uuid'] === pageUuid, fetched['original-parent-uuid']);
  push('re-recycle is a no-op preserving deleted-at',
    noop['no-op'] === true && noop.reason === 'already-recycled' && noop['deleted-at'] === recycle['deleted-at'], noop);
  push('restore reports active state and no-op false',
    restore.state === 'active' && restore['no-op'] === false, restore);
  push('restore returns to original page and root',
    restore['root-uuid'] === rootUuid && restore['page-uuid'] === pageUuid, restore);
  push('restore reuses original parent, position and order',
    restore['parent-uuid'] === pageUuid && restore.position === 'original' && restore.order === 'original',
    { parent: restore['parent-uuid'], position: restore.position, order: restore.order });
  push('restore affected root-first root + child',
    restore['affected-count'] === 2 && restoredUuids[0] === rootUuid &&
    new Set(restoredUuids).size === 2 && restoredUuids.includes(childUuid), restoredUuids);
  push('restored block is visible to getBlock again',
    visible.uuid === rootUuid && visible.title === 'mcp root', visible);
  push('malformed uuid is rejected by the SDK input schema',
    typeof r.schemaRejection === 'string' && r.schemaRejection.includes('-32602'), r.schemaRejection);

  return A;
}

function runSearchAssertions(s) {
  const A = [];
  const push = (name, ok, detail) => A.push({ name, ok: !!ok, detail });
  // `s.*` hold the parsed searchBlocks result object ({blocks:[...]}), not the array.
  const searchItems = r => (r && r.blocks) || (Array.isArray(r) ? r : []);
  const blockUuids = r => searchItems(r).map(b => String(b['block/uuid'] || b.uuid));
  push('Phase B: persisted graph reopen retains seeded blocks (real close/reopen)',
    s.persistedRoot && s.persistedRoot.title === 'mcp root' && s.persistedRoot.uuid === s.rootUuid, s.persistedRoot);
  push('Phase B: full search-index rebuild indexes seeded page + blocks',
    blockUuids(s.global).includes(s.pageUuid) && blockUuids(s.global).includes(s.rootUuid) &&
    blockUuids(s.global).includes(s.childUuid), blockUuids(s.global));
  push('Phase B: pageUuid scope returns page blocks',
    blockUuids(s.pageScoped).includes(s.rootUuid) && blockUuids(s.pageScoped).includes(s.childUuid),
    blockUuids(s.pageScoped));
  push('Phase B: blockUuid scope is exact and excludes descendants',
    blockUuids(s.blockScoped).length === 1 && blockUuids(s.blockScoped)[0] === s.rootUuid,
    blockUuids(s.blockScoped));
  push('Phase B: recycle makes the subtree invisible to search',
    !blockUuids(s.afterRecycle).includes(s.rootUuid) && !blockUuids(s.afterRecycle).includes(s.childUuid),
    blockUuids(s.afterRecycle));
  push('Phase B: restore returns the subtree to search results',
    blockUuids(s.afterRestore).includes(s.rootUuid) && blockUuids(s.afterRestore).includes(s.childUuid),
    blockUuids(s.afterRestore));
  return A;
}

function negativeControlFailures(r) {
  const c = JSON.parse(JSON.stringify(r));
  c.recycle.state = 'active';
  c.recycle['affected-count'] = 99;
  c.getRecycled = { state: 'active' };
  c.recycleNoop['no-op'] = false;
  c.restore.state = 'recycled';
  c.getBlockAfterRestore.title = 'wrong';
  c.schemaRejection = 'no code here';
  c.props = [];
  c.statusWrite.mode = 'unverified';
  c.blockAfterStatus = {};
  c.pageAfterStatus = { error: 'missing' };
  c.statusDryRun.mode = 'verified';
  c.malformedType = 'accepted';
  c.pageAfterMixed = { blocks: [{ title: 'mcp partial block' }] };
  if (c.personAdd.operations) c.personAdd.operations[0].entity.properties = {};
  c.personPageAfter = { entity: { topics: [] } };
  c.personBlockRead = {};
  c.personBlockAfterDry = {};
  c.personPageAfterBad = { entity: { description: 'Mutated' } };
  c.personBlockAfterBad = { enabled: true };
  c.badScalar = 'accepted';
  return runAssertions(c).filter(a => !a.ok).length;
}

// ---------------------------------------------------------------------------
// Phase A
// ---------------------------------------------------------------------------
async function phaseA(port, token) {
  const { app, win, client, graph, callText, callJson } = await launchAndConnect(port, token, 'A');
  const result = {};
  try {
    check('A: disposable graph opened from worktree-local graphs dir',
      !!(graph && graph.path && path.resolve(graph.path).startsWith(path.resolve(path.join(TMP, 'graphs')))), graph);

    const tools = await client.listTools();
    result.toolNames = tools.tools.map(t => t.name).sort();
    result.schemas = {};
    for (const t of tools.tools) result.schemas[t.name] = t.inputSchema || {};
    log('A tools=' + JSON.stringify(result.toolNames));

    result.statusPropUuid = (findProp((await callJson('listProperties', { expand: true })) || [], 'Status') || {}).uuid;
    log('A statusPropUuid=' + result.statusPropUuid);

    // seed base page + blocks (receipt)
    result.pageSeed = await callJson('upsertNodes', {
      receipt: true,
      operations: [{ operation: 'add', entityType: 'page', id: 'temp-mcp-page', data: { title: 'MCP IPC Page' } }],
    });
    result.pageUuid = result.pageSeed.operations[0].entity.uuid;
    result.seed = await callJson('upsertNodes', {
      receipt: true,
      operations: [
        { operation: 'add', entityType: 'block', id: 'temp-root', data: { 'page-id': result.pageUuid, title: 'mcp root' } },
        { operation: 'add', entityType: 'block', id: 'temp-child', data: { 'page-id': result.pageUuid, 'parent-id': 'temp-root', title: 'mcp child' } },
        { operation: 'add', entityType: 'block', id: 'temp-ordered', data: { 'page-id': result.pageUuid, title: 'ordered item', properties: { 'logseq.property/order-list-type': 'number' } } },
        { operation: 'add', entityType: 'block', id: 'temp-task', data: { 'page-id': result.pageUuid, title: 'task item', properties: { 'logseq.property/status': 'Done' } } },
      ],
    });
    result.rootUuid = result.seed.operations[0].entity.uuid;
    result.childUuid = result.seed.operations[1].entity.uuid;
    result.orderedUuid = result.seed.operations[2].entity.uuid;
    result.taskUuid = result.seed.operations[3].entity.uuid;
    result.pageTree = await callJson('getPage', { pageName: 'MCP IPC Page', includeChildren: true, maxBlocks: 50 });
    result.rootRead = await callJson('getBlock', { uuid: result.rootUuid });
    result.orderedRead = await callJson('getBlock', { uuid: result.orderedUuid });
    result.taskRead = await callJson('getBlock', { uuid: result.taskUuid });

    // create disposable user properties through the real MCP upsert path
    await callText('upsertNodes', {
      operations: [
        { operation: 'add', entityType: 'property', data: { title: PROP_TITLES.cv, 'property-type': 'url' } },
        { operation: 'add', entityType: 'property', data: { title: PROP_TITLES.orcid } },
        { operation: 'add', entityType: 'property', data: { title: PROP_TITLES.description } },
        { operation: 'add', entityType: 'property', data: { title: PROP_TITLES.topics, 'property-type': 'node', 'property-cardinality': 'many' } },
        { operation: 'add', entityType: 'property', data: { title: PROP_TITLES.score, 'property-type': 'number' } },
        { operation: 'add', entityType: 'property', data: { title: PROP_TITLES.enabled, 'property-type': 'checkbox' } },
      ],
    });
    result.props = await callJson('listProperties', { expand: true });
    result.propUuids = {};
    for (const key of Object.keys(PROP_TITLES)) {
      const p = findProp(result.props, PROP_TITLES[key]);
      result.propUuids[key] = p ? p.uuid : null;
    }
    log('A propUuids=' + JSON.stringify(result.propUuids));
    if (Object.values(result.propUuids).some(v => !v)) throw new Error('property discovery failed: ' + JSON.stringify(result.propUuids));
    const pu = result.propUuids;

    // topic pages
    const topicSeed = await callJson('upsertNodes', {
      receipt: true,
      operations: [
        { operation: 'add', entityType: 'page', id: 'temp-alpha', data: { title: 'Topic Alpha' } },
        { operation: 'add', entityType: 'page', id: 'temp-beta', data: { title: 'Topic Beta' } },
      ],
    });
    result.topicAlpha = topicSeed.operations[0].entity.uuid;
    result.topicBeta = topicSeed.operations[1].entity.uuid;

    // person page add with typed props
    result.personAdd = await callJson('upsertNodes', {
      receipt: true,
      operations: [{
        operation: 'add', entityType: 'page', id: 'temp-person',
        data: {
          title: 'Person Alpha',
          properties: {
            [pu.cv]: 'https://example.org/people/alpha',
            [pu.orcid]: '0000-0002-1825-0097',
            [pu.description]: 'Researcher',
            [pu.topics]: [{ uuid: result.topicAlpha }],
          },
        },
      }],
    });
    result.personUuid = result.personAdd.operations[0].entity.uuid;
    result.personPage = await callJson('getPage', { pageName: result.personUuid });

    result.personBlockAdd = await callJson('upsertNodes', {
      receipt: true,
      operations: [{
        operation: 'add', entityType: 'block', id: 'temp-person-block',
        data: {
          'page-id': result.personUuid, title: 'Person launch',
          properties: {
            [pu.score]: 0,
            [pu.enabled]: false,
            [pu.description]: 'Block level description',
            [pu.topics]: [{ uuid: result.topicAlpha }, { uuid: result.topicBeta }],
          },
        },
      }],
    });
    result.personBlockUuid = result.personBlockAdd.operations[0].entity.uuid;
    result.personBlockRead = await callJson('getBlock', { uuid: result.personBlockUuid });
    result.pageUnion = await callJson('upsertNodes', {
      receipt: true,
      operations: [{
        operation: 'edit', entityType: 'page', id: result.personUuid,
        data: { properties: { [pu.description]: 'Senior Researcher', [pu.topics]: [{ uuid: result.topicBeta }] } },
      }],
    });
    result.personPageAfter = await callJson('getPage', { pageName: result.personUuid });
    result.blockDryRun = await callJson('upsertNodes', {
      receipt: true, 'dry-run': true,
      operations: [{ operation: 'edit', entityType: 'block', id: result.personBlockUuid, data: { properties: { [pu.score]: 99 } } }],
    });
    result.personBlockAfterDry = await callJson('getBlock', { uuid: result.personBlockUuid });
    result.badScalar = await callText('upsertNodes', {
      operations: [{ operation: 'edit', entityType: 'block', id: result.personBlockUuid, data: { properties: { [pu.score]: 'not-a-number' } } }],
    });
    result.badRef = await callText('upsertNodes', {
      operations: [{ operation: 'edit', entityType: 'block', id: result.personBlockUuid, data: { properties: { [pu.topics]: ['not-a-ref-envelope'] } } }],
    });
    result.badMixed = await callText('upsertNodes', {
      operations: [
        { operation: 'add', entityType: 'block', data: { 'page-id': result.personUuid, title: 'mcp partial person block' } },
        { operation: 'edit', entityType: 'block', id: result.personBlockUuid, data: { properties: { [pu.score]: true } } },
      ],
    });
    result.personPageAfterBad = await callJson('getPage', { pageName: result.personUuid });
    result.personBlockAfterBad = await callJson('getBlock', { uuid: result.personBlockUuid });

    // built-in closed-value status on block (root) and page
    result.statusWrite = await callJson('upsertNodes', {
      receipt: true,
      operations: [{ operation: 'edit', entityType: 'block', id: result.rootUuid, data: { properties: { 'logseq.property/status': 'Done' } } }],
    });
    result.blockAfterStatus = await callJson('getBlock', { uuid: result.rootUuid });
    result.pageStatusWrite = await callJson('upsertNodes', {
      receipt: true,
      operations: [{ operation: 'edit', entityType: 'page', id: result.pageUuid, data: { properties: { 'logseq.property/status': 'Doing' } } }],
    });
    result.pageAfterStatus = await callJson('getPage', { pageName: result.pageUuid });
    result.statusDryRun = await callJson('upsertNodes', {
      receipt: true, 'dry-run': true,
      operations: [{ operation: 'edit', entityType: 'block', id: result.rootUuid, data: { properties: { 'logseq.property/status': 'Todo' } } }],
    });
    result.blockAfterStatusDry = await callJson('getBlock', { uuid: result.rootUuid });
    result.malformedType = await callText('upsertNodes', {
      operations: [{ operation: 'edit', entityType: 'block', id: result.rootUuid, data: { properties: { 'logseq.property/status': true } } }],
    });
    result.mixedBatch = await callText('upsertNodes', {
      operations: [
        { operation: 'add', entityType: 'block', id: 'temp-partial', data: { 'page-id': result.pageUuid, title: 'mcp partial block' } },
        { operation: 'edit', entityType: 'block', id: result.rootUuid, data: { properties: { 'user.property/does-not-exist': 'x' } } },
      ],
    });
    result.pageAfterMixed = await callJson('getPage', { pageName: result.pageUuid });

    // recycle / restore
    result.recycleBefore = await callJson('getBlock', { uuid: result.rootUuid });
    result.recycle = await callJson('recycleBlock', { blockUuid: result.rootUuid });
    result.getBlockWhileRecycled = await callJson('getBlock', { uuid: result.rootUuid });
    result.getRecycled = await callJson('getRecycledBlock', { blockUuid: result.rootUuid });
    result.recycleNoop = await callJson('recycleBlock', { blockUuid: result.rootUuid });
    result.restore = await callJson('restoreBlock', { blockUuid: result.rootUuid });
    result.getBlockAfterRestore = await callJson('getBlock', { uuid: result.rootUuid });
    result.schemaRejection = await callText('recycleBlock', { blockUuid: 'not-a-uuid' });

    const assertions = runAssertions(result);
    for (const a of assertions) check('A: ' + a.name, a.ok, a.detail);
    const neg = negativeControlFailures(result);
    check('A: corrupted-result negative control produces failures', neg > 0, { negativeControlFailures: neg });

    fs.writeFileSync(SESSION_PATH, JSON.stringify({
      pageUuid: result.pageUuid, rootUuid: result.rootUuid, childUuid: result.childUuid,
      statusPropUuid: result.statusPropUuid,
    }, null, 2));
  } finally {
    const proc = app.process();
    try { await client.close(); } catch (e) {}
    await app.close();
    // Wait for the Electron process (and its single-instance lock) to fully exit
    // before launching Phase B against the same userData dir.
    for (let i = 0; i < 30 && proc.exitCode === null; i++) await delay(1000);
    log('A teardown: electron exitCode=' + proc.exitCode);
    await delay(3000);
  }
  return result;
}

// ---------------------------------------------------------------------------
// Phase B
// ---------------------------------------------------------------------------
async function phaseB(port, token) {
  const session = JSON.parse(fs.readFileSync(SESSION_PATH, 'utf8'));
  const searchDir = path.join(TMP, 'graphs', 'Demo', 'search');
  fs.rmSync(searchDir, { recursive: true, force: true });
  log('B removed search dir, exists=' + fs.existsSync(searchDir));

  const { app, win, client, callJson } = await launchAndConnect(port, token, 'B');
  const s = { rootUuid: session.rootUuid, pageUuid: session.pageUuid, childUuid: session.childUuid };
  try {
    s.persistedRoot = await callJson('getBlock', { uuid: session.rootUuid });

    let global = null;
    for (let i = 0; i < 60; i++) {
      global = await callJson('searchBlocks', { searchTerm: 'mcp' });
      if ((global.blocks || []).length) break;
      await delay(2000);
    }
    s.global = global;
    s.pageScoped = await callJson('searchBlocks', { searchTerm: 'mcp', pageUuid: session.pageUuid });
    s.blockScoped = await callJson('searchBlocks', { searchTerm: 'mcp', blockUuid: session.rootUuid });

    await callJson('recycleBlock', { blockUuid: session.rootUuid });
    await delay(4000);
    s.afterRecycle = await callJson('searchBlocks', { searchTerm: 'mcp' });

    await callJson('restoreBlock', { blockUuid: session.rootUuid });
    await delay(4000);
    s.afterRestore = await callJson('searchBlocks', { searchTerm: 'mcp' });

    const assertions = runSearchAssertions(s);
    for (const a of assertions) check(a.name, a.ok, a.detail);
  } finally {
    try { await client.close(); } catch (e) {}
    await app.close();
  }
  return s;
}

async function main() {
  const wipe = process.argv.includes('--phase-b-only') ? false : true;
  let port;
  let token;
  if (wipe) {
    port = await freePort();
    token = crypto.randomBytes(24).toString('hex');
    wipeRuntime();
    writeConfigs(port, token);
    log('HARNESS port=' + port + ' token=REDACTED mode=two-phase');
  } else {
    // Reuse the persisted configs.edn written by the preceding full run.
    const t = fs.readFileSync(path.join(USER_DATA, 'configs.edn'), 'utf8');
    port = +t.match(/:server\/port\s+(\d+)/)[1];
    token = t.match(/:server\/tokens\s+\["([^"]+)"\]/)[1];
    log('HARNESS mode=phase-b-only port=' + port + ' token=REDACTED');
  }

  let exitCode = 1;
  try {
    if (wipe) await phaseA(port, token);
    if (!process.argv.includes('--phase-a-only')) await phaseB(port, token);
    const failures = checks.filter(c => !c.ok);
    log('DONE checks=' + checks.length + ' failures=' + failures.length);
    exitCode = failures.length ? 1 : 0;
  } catch (e) {
    log('HARNESS ERROR: ' + (e && e.stack || e));
    exitCode = 1;
  }
  fs.writeFileSync(path.join(TMP, 'harness-checks.json'), JSON.stringify(checks, null, 2));
  LOG.end();
  process.exit(exitCode);
}
main();
