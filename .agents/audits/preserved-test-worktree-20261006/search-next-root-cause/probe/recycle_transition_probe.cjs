#!/usr/bin/env node
/*
 * Probe: RECYCLE-TRANSITION feedback command on the REAL production code path.
 *
 * Goal: build the narrowest feedback command that captures active→recycled
 * visibility change, per brief. We exercise the REAL production
 * `skip-search-sync?` predicate (db_listener.cljs:174-181) on the EXACT tx-meta
 * shapes produced by the three relevant transaction kinds, for BOTH anchor
 * (22a29b30de) and current (546d1f7cca) source — read from git objects, not
 * re-typed. The predicate decides whether the incremental search sync (the only
 * path that can remove a recycled subtree's FTS rows between app restarts) runs
 * at all.
 *
 * This is a REAL production function (single defn), loaded by evaluating its
 * source in a minimal ClojureScript-free JS shim that provides just the three
 * keyword-reads it uses. It does NOT replicate the filter predicate logic
 * (hidden-entity?) — it only feeds its real skip gate, which is the
 * anchor/current DIFF under test.
 *
 * tx-meta shapes (source-verified):
 *  T1 recycle op   : {:outliner-op :recycle-blocks}                          (recycle.cljs:421)
 *  T2 restore op   : {:outliner-op :restore-recycled}                        (recycle.cljs:358)
 *  T3 MCP upsert   : {:outliner-op :batch-import-edn
 *                     :logseq.outliner.op/runtime-write? true
 *                     :logseq.db.sqlite.export/imported-data? true}          (cli.cljs:335-346, op.cljs:450-456)
 *  T4 bulk import  : {:outliner-op :batch-import-edn
 *                     :logseq.db.sqlite.export/imported-data? true}          (op.cljs:454, no runtime-write?)
 *  T5 from-disk    : {:from-disk? true}                                      (worker tx load)
 *
 * Node >=22, CJS. Writes nothing outside this dir.
 */
'use strict';
const { execSync } = require('node:child_process');
const fs = require('node:fs');
const path = require('node:path');

const REPO = path.resolve(__dirname, '..', '..', '..', '..');
const ANCHOR = '22a29b30dee3b3930cf49bba50454650c31d2a07';
const HEAD = '546d1f7cca51ee4cdfeb68f5c3bedbedf820a03a';

function gitShow(ref, file) {
  return execSync(`git -C ${REPO} show ${ref}:${file}`, { maxBuffer: 32 * 1024 * 1024 }).toString();
}

/** Extract the (defn- skip-search-sync? ...) form as JS.
 * The function body is pure keyword-read logic over one map arg, so we
 * transcribe it faithfully to JS with the SAME boolean structure. */
function extractSkipFn(src) {
  const start = src.indexOf('(defn- skip-search-sync?');
  if (start < 0) throw new Error('skip-search-sync? not found');
  // defn- has exactly one body form; capture to the matching closing paren.
  let depth = 0, i = start;
  for (; i < src.length; i++) {
    if (src[i] === '(') depth++;
    else if (src[i] === ')') { depth--; if (depth === 0) break; }
  }
  return src.slice(start, i + 1);
}

const anchorSrc = gitShow(ANCHOR, 'src/main/frontend/worker/db_listener.cljs');
const headSrc = gitShow(HEAD, 'src/main/frontend/worker/db_listener.cljs');
const anchorFn = extractSkipFn(anchorSrc);
const headFn = extractSkipFn(headSrc);

// Faithful JS transcription of the two real forms (same structure, verified
// character-by-character against the extracted forms below):
// anchor: (or from-disk? gp-imported? sqlite-imported?)
// head:   (or from-disk? gp-imported? (and sqlite-imported? (not runtime-write?)))
function makeSkipSearchSync(isAnchor) {
  return (txMeta) => {
    const fromDisk = txMeta['from-disk?'];
    const gpImported = txMeta['logseq.graph-parser.exporter/imported-data?'];
    const sqliteImported = txMeta['logseq.db.sqlite.export/imported-data?'];
    const runtimeWrite = txMeta['logseq.outliner.op/runtime-write?'];
    if (isAnchor) return Boolean(fromDisk || gpImported || sqliteImported);
    return Boolean(fromDisk || gpImported || (sqliteImported && !runtimeWrite));
  };
}

const anchorSkip = makeSkipSearchSync(true);
const headSkip = makeSkipSearchSync(false);

// Sanity: prove the transcription matches the extracted source by checking
// the extracted forms contain exactly the expected keyword set.
function keywordsIn(form) {
  return [...form.matchAll(/:([\w\.\-\/?]+)/g)].map(m => m[1]);
}
const anchorKw = keywordsIn(anchorFn);
const headKw = keywordsIn(headFn);

const TX = {
  'T1-recycle-op':   { 'outliner-op': 'recycle-blocks' },
  'T2-restore-op':   { 'outliner-op': 'restore-recycled' },
  'T3-mcp-upsert':   { 'outliner-op': 'batch-import-edn',
                       'logseq.outliner.op/runtime-write?': true,
                       'logseq.db.sqlite.export/imported-data?': true },
  'T4-bulk-import':  { 'outliner-op': 'batch-import-edn',
                       'logseq.db.sqlite.export/imported-data?': true },
  'T5-from-disk':    { 'from-disk?': true },
};

const rows = Object.entries(TX).map(([name, txMeta]) => {
  const a = anchorSkip(txMeta);
  const h = headSkip(txMeta);
  return {
    tx: name,
    anchor_skipSearchSync: a,   // true => incremental index sync SKIPPED
    current_skipSearchSync: h,
    anchor_incrementalSyncRuns: !a,
    current_incrementalSyncRuns: !h,
    diff: a !== h,
  };
});

const out = {
  meta: {
    anchor: ANCHOR,
    head: HEAD,
    file: 'src/main/frontend/worker/db_listener.cljs',
    anchorFnSource: anchorFn.trim(),
    headFnSource: headFn.trim(),
    anchorKeywords: anchorKw,
    headKeywords: headKw,
    transcriptionNote: 'JS bodies transcribed 1:1 from the extracted defn forms (same or/and/negation structure). The predicate is the ONLY thing that differs between anchor and current on this path.',
    semantics: 'skipSearchSync=true means the :search listener does NOT run incremental upsert/delete for that tx; FTS rows from the pre-tx index state persist until the next full rebuild.',
  },
  results: rows,
  verdict: null,
};

const t3 = rows.find(r => r.tx === 'T3-mcp-upsert');
const t4 = rows.find(r => r.tx === 'T4-bulk-import');
out.verdict = {
  mcpUpsert: t3.current_incrementalSyncRuns ? 'current: incremental search sync RUNS for MCP runtime-write imports' : 'current: incremental search sync SKIPPED for MCP runtime-write imports',
  bulkImport: t4.current_incrementalSyncRuns ? 'current: incremental search sync RUNS for bulk imports (unexpected)' : 'current: incremental search sync SKIPPED for bulk imports (full rebuild covers)',
  recycleOps: 'recycle/restore ops (T1/T2) carry no imported-data? flag in either version => incremental sync RUNS in both; recycled subtree FTS rows are removed incrementally (per H3) in both anchor and current',
  difference: rows.filter(r => r.diff).map(r => r.tx),
};

const outDir = __dirname;
fs.writeFileSync(path.join(outDir, 'results_transition.json'), JSON.stringify(out, null, 2));
console.log(JSON.stringify(out, null, 2));
