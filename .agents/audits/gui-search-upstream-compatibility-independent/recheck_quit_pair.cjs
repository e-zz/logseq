/* Recheck async current-page navigation; normal quit only own recorded inspectors. */
const fs=require('fs'),path=require('path'),WebSocket=require('ws');const {chromium}=require('playwright');
const ROOT='C:/Users/zhang/AppData/Local/Temp/ls-ub-20261005';
const report=JSON.parse(fs.readFileSync(path.join(ROOT,'gui-pair.json'),'utf8'));
const sleep=ms=>new Promise(r=>setTimeout(r,ms));
async function connection(port){const list=await(await fetch(`http://127.0.0.1:${port}/json/list`)).json();const ws=new WebSocket(list[0].webSocketDebuggerUrl);await new Promise(r=>ws.once('open',r));let n=0;const pending=new Map();ws.on('message',buf=>{const x=JSON.parse(buf);if(x.id&&pending.has(x.id)){const [res,rej]=pending.get(x.id);pending.delete(x.id);x.error?rej(Error(JSON.stringify(x.error))):res(x.result);}});const send=(expression)=>new Promise((res,rej)=>{const id=++n;pending.set(id,[res,rej]);ws.send(JSON.stringify({id,method:'Runtime.evaluate',params:{expression,returnByValue:true,awaitPromise:true}}));});return {ws,send};}
(async()=>{
for(const [side,s] of Object.entries(report.sides)){
 const ins=await connection(s.ports.inspectPort);
 const probe=await ins.send("({pid:process.pid,requireType:typeof require,mainModuleType:typeof process.mainModule,mainRequireType:typeof process.mainModule?.require})");
 s.quitProbe=probe;console.log(side,'OWN-PROBE',JSON.stringify(probe));
 if(probe.exceptionDetails||probe.result.value.pid!==s.pid)throw Error('Inspector process mismatch');
 const browser=await chromium.connectOverCDP(`http://127.0.0.1:${s.ports.cdpPort}`);const p=browser.contexts()[0].pages()[0];
 if(!JSON.stringify(await p.evaluate(()=>window.logseq.api.get_current_graph())).includes(path.join(side,'home').replace(/\\/g,'/')))throw Error('Wrong graph home');
 for(const row of s.queries){
  const input=p.locator('.cp__cmdk-search-input');if(!(await input.isVisible().catch(()=>false)))await p.locator('#search-button').click();await input.fill('');await sleep(500);await input.fill(row.query);
  const exact=p.locator('.cp__cmdk-item-main-text').filter({hasText:new RegExp('^'+row.query+'$')});await exact.first().waitFor({timeout:15000});await exact.first().evaluate(e=>e.closest('[data-cmdk-item]').click());await sleep(700);
  const nav=await p.evaluate(async()=>({url:location.href,page:await window.logseq.api.get_current_page(),body:document.body.innerText}));
  row.navigationAsyncReadback=nav;row.navigationHasPage=JSON.stringify(nav.page).toLowerCase().includes(row.expectedPage.toLowerCase());row.navigationHasQuery=nav.body.includes(row.query);
  console.log(side,row.query,'ASYNC-NAV',JSON.stringify({page:nav.page,hasPage:row.navigationHasPage,url:nav.url}));
 }
 fs.writeFileSync(path.join(ROOT,'gui-pair.json'),JSON.stringify(report,null,2));
 // require is not always a global in Electron inspector; mainModule owns the real module require.
 const action=await Promise.race([ins.send("(()=>{const r=typeof require==='function'?require:process.mainModule.require.bind(process.mainModule);const a=r('electron').app;return {requested:(a.quit(),true)}})()"),sleep(4000).then(()=>({transportClosedOrTimedOut:true}))]);
 s.normalQuitReadback=action;console.log(side,'NORMAL-QUIT',JSON.stringify(action));
 if(action.exceptionDetails){ins.ws.close();throw Error('Normal quit evaluation failed');}
 let closed=false;for(let i=0;i<40;i++){await sleep(250);try{await fetch(`http://127.0.0.1:${s.ports.inspectPort}/json/list`);}catch{closed=true;break;}}
 s.inspectorClosed=closed;s.leftRunning=!closed;ins.ws.close();fs.writeFileSync(path.join(ROOT,'gui-pair.json'),JSON.stringify(report,null,2));
}
report.comparison=report.comparison.map(x=>{for(const side of ['U','B']){const r=report.sides[side].queries.find(r=>r.query===x.query);x[side].navPage=r.navigationHasPage;x[side].navQuery=r.navigationHasQuery;}return x;});fs.writeFileSync(path.join(ROOT,'gui-pair.json'),JSON.stringify(report,null,2));console.log(JSON.stringify(report.comparison,null,2));
})().catch(e=>{console.error(e.stack);fs.writeFileSync(path.join(ROOT,'gui-pair.json'),JSON.stringify(report,null,2));process.exit(1)});
