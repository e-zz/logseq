const fs=require('fs'),path=require('path');const {chromium}=require('playwright');
const OUT=path.join(__dirname,'parent-runtime'), report={evidence:'Actual new package renderer and production Electron HTTP MCP; synthetic isolated DB.',tests:[],limits:['Sampled state stability detects the known persistent overwrite regression, NOT proof of zero transient/same-value writes.','Inspector startup adjusted filesystem isolation only; package files unmodified.']};
const sleep=ms=>new Promise(r=>setTimeout(r,ms));
function check(name,ok,detail){report.tests.push({name,status:ok?'PASS':'FAIL',detail});console.log(name,ok?'PASS':'FAIL',JSON.stringify(detail).slice(0,800));if(!ok)throw Error(name)}
(async()=>{
const b=await chromium.connectOverCDP('http://127.0.0.1:19432');const p=b.contexts()[0].pages()[0];
const graph=await p.evaluate(()=>window.logseq.api.get_current_graph());check('exact-isolated-graph',JSON.stringify(graph).includes('search-package-fixture'),graph);
const state=()=>p.evaluate(()=>window.logseq.api.get_state_from_store('search/result'));
const search=q=>p.evaluate(q=>window.logseq.api.search(q,{'enable-snippet?':false}),q);
const active='parentactivecanary3785', control='parentcontrolcanary9432';
let r=await search(active);report.rawRendererSearch=r;check('renderer-default-search-result',JSON.stringify(r).includes(active),r);
let s=await state();check('renderer-default-publishes-state',JSON.stringify(s).includes(active),s);
const sentinel={blocks:[],pages:[],testMarker:'parent-optout-sentinel'};
await p.evaluate(v=>window.logseq.api.set_state_from_store('search/result',v),sentinel);const before=await state();
let sid,n=0;
async function rpc(method,params,notification=false){
 const headers={'Content-Type':'application/json','Accept':'application/json, text/event-stream','Authorization':'Bearer '+require('crypto').randomBytes(24).toString('hex')};if(sid)headers['mcp-session-id']=sid;
 const payload={jsonrpc:'2.0',method,params};if(!notification)payload.id=++n;
 const response=await fetch('http://127.0.0.1:19433/mcp',{method:'POST',headers,body:JSON.stringify(payload)});const text=await response.text();if(response.headers.get('mcp-session-id'))sid=response.headers.get('mcp-session-id');
 if(!response.ok)throw Error('MCP HTTP '+response.status+' '+text.slice(0,300));if(!text)return null;
 const messages=text.startsWith('event:')||text.startsWith('data:')?text.split('\n').filter(l=>l.startsWith('data:')).map(l=>JSON.parse(l.slice(5))):[JSON.parse(text)];const m=messages.find(m=>m.id===payload.id)||messages.at(-1);if(m?.error)throw Error(JSON.stringify(m.error));return m;
}
await rpc('initialize',{protocolVersion:'2025-03-26',capabilities:{},clientInfo:{name:'parent-package-verification',version:'1.0'}});await rpc('notifications/initialized',{},true);
async function tool(name,args){const x=await rpc('tools/call',{name,arguments:args});if(x?.result?.isError)throw Error(JSON.stringify(x));return x}
const toolList=await rpc('tools/list',{});report.toolNames=toolList.result.tools.map(t=>t.name);
let sampled=[];for(const q of [active,control,active]){const x=await tool('searchBlocks',{searchTerm:q,limit:5});check('mcp-real-search-'+q,JSON.stringify(x).includes(q),x);sampled.push(await state());await sleep(300);sampled.push(await state());}
check('mcp-does-not-persistently-overwrite-sentinel',sampled.every(x=>JSON.stringify(x)===JSON.stringify(before)),{before,sampled,limit:report.limits[0]});
// Separate Cmd-K DOM evidence; do not demand shared renderer state publishing from this direct path.
const input=p.locator('.cp__cmdk-search-input');if(!(await input.isVisible().catch(()=>false)))await p.locator('#search-button').click();await input.waitFor({timeout:10000});await input.fill('');await sleep(500);await input.fill(active);
const exactNode=p.locator('.cp__cmdk-item-main-text').filter({hasText:new RegExp('^'+active+'$')});await exactNode.waitFor({timeout:15000});
check('cmdk-direct-DOM-search',await exactNode.count()>0,{query:active,exactNodeText:await exactNode.allTextContents(),dom:await p.locator('.cp__cmdk-item-main-text').allTextContents()});
await p.keyboard.press('Escape');
// Read exact synthetic block target from production API before recycling.
const tree=await p.evaluate(()=>window.logseq.api.get_page_blocks_tree('Search Fixture'));report.fixtureTree=tree;
function find(x){if(Array.isArray(x)){for(const a of x){const y=find(a);if(y)return y}}else if(x&&typeof x==='object'){if(JSON.stringify(x.content||x.title||x['block/title']||'').includes(active))return x;for(const v of Object.values(x)){const y=find(v);if(y)return y}}return null}
const block=find(tree);check('exact-synthetic-recycle-target',!!block,block);const uuid=block.uuid||block['block/uuid'];check('target-uuid-present',!!uuid,{uuid});
const recycled=await tool('recycleBlock',{blockUuid:uuid});report.recycle=recycled;
async function guiQueryVisible(want){const i=p.locator('.cp__cmdk-search-input');if(!(await i.isVisible().catch(()=>false)))await p.locator('#search-button').click();await i.fill('');await sleep(500);await i.fill(active);const node=p.locator('.cp__cmdk-item-main-text').filter({hasText:new RegExp('^'+active+'$')});if(want)await node.waitFor({timeout:15000});else{await sleep(1200);await node.waitFor({state:'hidden',timeout:15000});}return {count:await node.count(),texts:await p.locator('.cp__cmdk-item-main-text').allTextContents()};}
let hidden;for(let i=0;i<20;i++){hidden=await tool('searchBlocks',{searchTerm:active,limit:5});if(!JSON.stringify(hidden).includes(active))break;await sleep(300)}check('recycled-excluded-MCP',!JSON.stringify(hidden).includes(active),hidden);
const uiHidden=await guiQueryVisible(false);check('recycled-excluded-CmdK',uiHidden.count===0,uiHidden);
const restored=await tool('restoreBlock',{blockUuid:uuid});report.restore=restored;
let shown;for(let i=0;i<20;i++){shown=await tool('searchBlocks',{searchTerm:active,limit:5});if(JSON.stringify(shown).includes(active))break;await sleep(300)}check('restored-visible-MCP',JSON.stringify(shown).includes(active),shown);
const uiRestored=await guiQueryVisible(true);check('restored-visible-CmdK',uiRestored.count===1,uiRestored);
report.status='PASS_WITH_OBSERVATION_LIMIT';
})().catch(e=>{report.status='FAIL_OR_BLOCKED';report.error=e.stack;console.error(e.stack)}).finally(()=>{fs.writeFileSync(path.join(OUT,'behavior-parent.json'),JSON.stringify(report,null,2));console.log('REPORT',report.status);process.exit(report.status==='PASS_WITH_OBSERVATION_LIMIT'?0:1)});
