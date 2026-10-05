/* Actual U/B packaged Electron GUI comparison; no app/source patching. */
const fs=require('fs'),path=require('path'),net=require('net'),{spawn}=require('child_process');
const {chromium}=require('playwright'),WebSocket=require('ws');
const ROOT=path.resolve(process.env.TMPDIR,'ls-ub-20261005');
const FIX=JSON.parse(fs.readFileSync(path.join(ROOT,'fixture.json'),'utf8'));
const UP=JSON.parse(fs.readFileSync(path.join(__dirname,'upstream-package-37131548966/download-verification.json'),'utf8'));
const builds={U:{exe:UP.exe,sha:UP.source_sha},B:{exe:'C:/Users/zhang/Downloads/logseq-ci-37203268496-x64/Logseq-win-x64-2.0.2/Logseq.exe',sha:'fb5eb4eb43cdb3be7a29d816b969d45c127d4861'},C:{exe:'C:/Users/zhang/Downloads/logseq-ci-37259407681-x64/unpacked/Logseq.exe',sha:'7b1ae92ee1eb1679b8bb827d12d7e76e22d4d5c5'}};
const sleep=ms=>new Promise(r=>setTimeout(r,ms));
const report={scope:'Actual U vs B Electron Cmd-K GUI on initially byte-identical synthetic graph copies. No user graph.',limits:['Fresh-index fixture only; old-index migration not tested.','Only ordinary/nested block labels and navigation initially; recycled entities need separate controlled fixture.','Same-fixture equality is bounded compatibility evidence, not absence of every upstream regression.'],sides:{}};
function save(){fs.writeFileSync(path.join(ROOT,'gui-pair-recycle.json'),JSON.stringify(report,null,2));}
async function free(port){return new Promise((resolve,reject)=>{const s=net.createServer();s.once('error',reject);s.listen(port,'127.0.0.1',()=>s.close(resolve));});}
async function inspector(port){let target;for(let i=0;i<80;i++){try{const a=await(await fetch(`http://127.0.0.1:${port}/json/list`)).json();target=a[0];if(target)break;}catch{}await sleep(250);}if(!target)throw Error('Own inspector unavailable');const ws=new WebSocket(target.webSocketDebuggerUrl);await new Promise(r=>ws.once('open',r));let n=0,resolvePause;const pause=new Promise(r=>resolvePause=r),pending=new Map();ws.on('message',buf=>{const x=JSON.parse(buf);if(x.id&&pending.has(x.id)){const [res,rej]=pending.get(x.id);pending.delete(x.id);x.error?rej(Error(JSON.stringify(x.error))):res(x.result);}if(x.method==='Debugger.paused')resolvePause(x.params);});const send=(method,params={})=>new Promise((res,rej)=>{const id=++n;pending.set(id,[res,rej]);ws.send(JSON.stringify({id,method,params}));});return {ws,send,pause};}
async function side(name){
const cfg=builds[name],out=path.join(ROOT,name),home=path.join(out,'home'),ud=path.join(out,'userdata');
const inspectPort={U:19541,B:19551,C:19561}[name],cdpPort=inspectPort+1;
const s=report.sides[name]={sha:cfg.sha,exe:cfg.exe,queries:[],scope:'Synthetic graph only'};save();
for(const port of [inspectPort,cdpPort])await free(port);
fs.writeFileSync(path.join(ud,'configs.edn'),'{:server/autostart false :server/mcp-enabled? false}');
fs.writeFileSync(path.join(home,'.logseq/graphs.edn'),`[{:repo "logseq_db_${FIX.graph}" :graph-name "${FIX.graph}" :local-graph-id "${FIX.graph_id}" :graph-id "${FIX.graph_id}" :updated-at ${Date.now()}}]`);
const env={...process.env,HOME:home,USERPROFILE:home,APPDATA:path.join(out,'appdata'),LOCALAPPDATA:path.join(out,'appdata')};delete env.ELECTRON_RUN_AS_NODE;
const fd=fs.openSync(path.join(out,'electron.raw.log'),'a');
const child=spawn(cfg.exe,['--user-data-dir='+ud,`--inspect-brk=127.0.0.1:${inspectPort}`,'--remote-debugging-address=127.0.0.1',`--remote-debugging-port=${cdpPort}`],{env,stdio:['ignore',fd,fd]});
s.pid=child.pid;s.ports={inspectPort,cdpPort};save();let own,paused=false;
const exited=new Promise(r=>child.once('exit',(code,signal)=>r({code,signal})));
try {
 own=await inspector(inspectPort);
 await own.send('Debugger.enable');await own.send('Runtime.runIfWaitingForDebugger');
 const stop=await Promise.race([own.pause,sleep(20000).then(()=>{throw Error('No startup pause; refuse unsafe continuation')})]);paused=true;
 const code=`(()=>{const a=require('electron').app;const h=${JSON.stringify(home)},u=${JSON.stringify(ud)},o=${JSON.stringify(out)};a.setPath('home',h);a.setPath('userData',u);a.setPath('appData',o+'/appdata');a.setPath('logs',o+'/logs');require('os').homedir=()=>h;return {home:a.getPath('home'),userData:a.getPath('userData'),appData:a.getPath('appData'),logs:a.getPath('logs'),osHome:require('os').homedir(),appPath:a.getAppPath()}})()`;
 const x=await own.send('Debugger.evaluateOnCallFrame',{callFrameId:stop.callFrames[0].callFrameId,expression:code,returnByValue:true});
 if(x.exceptionDetails)throw Error(JSON.stringify(x.exceptionDetails));s.isolation=x.result.value;
 for(const k of ['home','userData','appData','logs','osHome'])if(!path.resolve(s.isolation[k]).startsWith(out+path.sep))throw Error('Filesystem isolation mismatch '+k);
 if(path.resolve(s.isolation.appPath)!==path.resolve(path.dirname(cfg.exe),'resources/app.asar'))throw Error('Wrong executable appPath');
 save();await own.send('Debugger.resume');paused=false;
 let browser;for(let i=0;i<100;i++){try{browser=await chromium.connectOverCDP(`http://127.0.0.1:${cdpPort}`);break;}catch{}await sleep(300);}if(!browser)throw Error('Own renderer not available');
 const pages=browser.contexts()[0].pages(),p=pages.find(x=>x.url().startsWith('file:'))||pages[0];
 p.on('pageerror',e=>{s.pageErrors??=[];s.pageErrors.push(e.message);save();});
 await p.waitForFunction(()=>typeof window.logseq?.api?.get_current_graph==='function',null,{timeout:25000});
 await p.waitForFunction(g=>JSON.stringify(window.logseq.api.get_current_graph()).includes(g),FIX.graph,{timeout:30000});
 s.graph=await p.evaluate(()=>window.logseq.api.get_current_graph());
 s.version=await p.evaluate(()=>({url:location.href,title:document.title,apiKeys:Object.keys(window.logseq.api)}));save();
 await p.locator('#search-button').waitFor({timeout:30000});
 // Allow the real worker to build fresh indexes; verify each precise result DOM item.
 for(const query of FIX.queries){
  const input=p.locator('.cp__cmdk-search-input');
  if(!(await input.isVisible().catch(()=>false)))await p.locator('#search-button').click();
  await input.waitFor({timeout:10000});await input.fill('');await sleep(500);await input.fill(query);
  const exact=p.locator('.cp__cmdk-item-main-text').filter({hasText:new RegExp('^'+query+'$')});
  await exact.first().waitFor({timeout:25000});await sleep(1000);
  const row=await exact.first().evaluate(e=>{const item=e.closest('[data-cmdk-item]');return {mainText:e.innerText,itemText:item.innerText,itemHTML:item.outerHTML,labels:[...item.querySelectorAll('.breadcrumb__label')].map(x=>({text:x.innerText,display:getComputedStyle(x).display,visibility:getComputedStyle(x).visibility,rect:{width:x.getBoundingClientRect().width,height:x.getBoundingClientRect().height}}))};});
  row.query=query;row.urlBefore=p.url();row.expectedPage=query==='compatotherpage45831'?FIX.page_two:FIX.page_one;
  row.hasVisibleExpectedPage=row.labels.some(x=>x.text===row.expectedPage&&x.display!=='none'&&x.visibility!=='hidden'&&x.rect.width>0&&x.rect.height>0);
  row.hasVisibleParent=query!=='compatnested45831'||row.labels.some(x=>x.text==='compatparent45831'&&x.rect.width>0);
  await p.screenshot({path:path.join(out,query+'.png')});
  // Click the actual result row via the GUI (not a direct redirect API).
  await exact.first().evaluate(e=>e.closest('[data-cmdk-item]').click());
  await p.waitForFunction(()=>!document.querySelector('.cp__cmdk-search-input'),null,{timeout:10000}).catch(()=>{});
  await sleep(700);row.urlAfter=p.url();row.navigation=await p.evaluate(async()=>({url:location.href,body:document.body.innerText.slice(0,10000),page:await window.logseq.api.get_current_page?.()}));
  row.navigationHasPage=JSON.stringify(row.navigation.page||'').toLowerCase().includes(row.expectedPage.toLowerCase());
  row.navigationHasQuery=row.navigation.body.includes(query);
  s.queries.push(row);save();console.log(name,query,JSON.stringify({labels:row.labels,pageLabel:row.hasVisibleExpectedPage,parent:row.hasVisibleParent,navPage:row.navigationHasPage,navQuery:row.navigationHasQuery}));
 }
 // Only this newly created ordinary page in the isolated synthetic graph is recycled, then restored.
 const target=await p.evaluate(async title=>await window.logseq.api.get_page(title),FIX.page_two);
 if(!target||!JSON.stringify(target).toLowerCase().includes(FIX.page_two.toLowerCase())||!target.uuid)throw Error('Exact synthetic ordinary page readback failed');
 s.recycle={target};save();
 const queryPage=async query=>{const i=p.locator('.cp__cmdk-search-input');if(!(await i.isVisible().catch(()=>false)))await p.locator('#search-button').click();await i.fill('');await sleep(500);await i.fill(query);await sleep(1200);const exact=p.locator('.cp__cmdk-item-main-text').filter({hasText:new RegExp('^'+query+'$')});return {query,count:await exact.count(),allMainTexts:await p.locator('.cp__cmdk-item-main-text').allTextContents(),rows:await p.locator('[data-cmdk-item]').evaluateAll(xs=>xs.map(x=>({text:x.innerText,labels:[...x.querySelectorAll('.breadcrumb__label')].map(e=>e.innerText)})))};};
 s.recycle.beforePage=await queryPage(FIX.page_two);s.recycle.beforeBlock=await queryPage('compatotherpage45831');save();
 const rawTarget=async()=>await p.evaluate(async uuid=>await window.logseq.api.datascript_query('[:find (pull ?e [:block/uuid :block/title :logseq.property/deleted-at {:block/parent [:block/title]}]) :where [?e :block/uuid #uuid "'+uuid+'"]]'),target.uuid);
 s.recycle.beforeRaw=await rawTarget();
 await p.evaluate(async title=>await window.logseq.api.delete_page(title),FIX.page_two);
 s.recycle.afterRaw=await rawTarget();save();
 s.recycle.afterPage=await queryPage(FIX.page_two);s.recycle.afterBlock=await queryPage('compatotherpage45831');
 await p.screenshot({path:path.join(out,'recycled-page-query.png')});save();
 await p.evaluate(async uuid=>await window.logseq.api.restore_page(uuid),target.uuid);
 s.recycle.restoredRaw=await rawTarget();s.recycle.restoredPage=await p.evaluate(async title=>await window.logseq.api.get_page(title),FIX.page_two);
 s.recycle.restoredQuery=await queryPage('compatotherpage45831');
 s.recycle.sameUuidRestored=s.recycle.restoredPage?.uuid===target.uuid;
 s.recycle.markerObserved=JSON.stringify(s.recycle.afterRaw).includes('deleted-at')&&JSON.stringify(s.recycle.afterRaw).includes('Recycle');
 save();console.log(name,'RECYCLE-CHECK',JSON.stringify({beforePage:s.recycle.beforePage.count,beforeBlock:s.recycle.beforeBlock.count,afterPage:s.recycle.afterPage.count,afterBlock:s.recycle.afterBlock.count,restored:s.recycle.restoredQuery.count,sameUuid:s.recycle.sameUuidRestored,raw:s.recycle.afterRaw}));
 s.status='EXERCISED';save();
} catch(e){s.status='BLOCKED_OR_FAILED';s.error=e.stack;save();console.error(name,e.stack);}
finally {
 if(own){
  // Own inspector and recorded PID only. Never kill a process or close other windows.
  const quit=paused?'Debugger.evaluateOnCallFrame':'Runtime.evaluate';
  try{if(!paused){await Promise.race([own.send(quit,{expression:"require('electron').app.quit()",returnByValue:true}),sleep(5000)]);s.normalQuitRequested=true;}}catch(e){s.quitTransportNote=e.message;}
  own.ws.close();const ended=await Promise.race([exited,sleep(15000).then(()=>null)]);s.normalExit=ended;s.leftRunning=ended===null;save();
 }
}
}
(async()=>{for(const name of ['U','B','C'])await side(name);const a=report.sides.U,b=report.sides.B;
report.comparison=FIX.queries.map(query=>{const u=a.queries.find(x=>x.query===query),v=b.queries.find(x=>x.query===query);return {query,U:u?{labels:u.labels.map(x=>x.text),visible:u.hasVisibleExpectedPage,parent:u.hasVisibleParent,navPage:u.navigationHasPage,navQuery:u.navigationHasQuery}:null,B:v?{labels:v.labels.map(x=>x.text),visible:v.hasVisibleExpectedPage,parent:v.hasVisibleParent,navPage:v.navigationHasPage,navQuery:v.navigationHasQuery}:null};});
report.comparisonC=report.sides.C.queries.map(r=>({query:r.query,labels:r.labels.map(x=>x.text),visible:r.hasVisibleExpectedPage,parent:r.hasVisibleParent,navPage:r.navigationHasPage,navQuery:r.navigationHasQuery}));report.status=Object.values(report.sides).every(s=>s.status==='EXERCISED')?'OBSERVED_COMPARISON':'INCOMPLETE';save();console.log(JSON.stringify({status:report.status,comparison:report.comparison,normalExit:{U:a.normalExit,B:b.normalExit}},null,2));process.exit(report.status==='OBSERVED_COMPARISON'?0:1);
})().catch(e=>{report.error=e.stack;save();console.error(e.stack);process.exit(1)});
