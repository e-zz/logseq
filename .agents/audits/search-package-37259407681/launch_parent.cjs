const fs=require('fs'),path=require('path'),net=require('net'),{spawn}=require('child_process'),WebSocket=require('ws');
const {chromium}=require('playwright');
const OUT=path.resolve(__dirname,'parent-runtime');
const HOME=path.join(OUT,'home'),UD=path.join(OUT,'userdata');
const EXE='C:/Users/zhang/Downloads/logseq-ci-37259407681-x64/unpacked/Logseq.exe';
const sleep=ms=>new Promise(r=>setTimeout(r,ms));
async function free(port){await new Promise((res,rej)=>{const s=net.createServer();s.once('error',rej);s.listen(port,'127.0.0.1',()=>s.close(res));});}
(async()=>{
for(const p of [19431,19432,19433])await free(p);
for(const d of [HOME,UD,path.join(HOME,'.logseq'),path.join(OUT,'logs'),path.join(OUT,'appdata')])fs.mkdirSync(d,{recursive:true});
fs.writeFileSync(path.join(UD,'configs.edn'),'{:server/autostart true :server/mcp-enabled? true :server/port 19433}');
const raw=JSON.parse(fs.readFileSync(path.join(OUT,'fixture-cli.raw.json'),'utf8'));
const info=JSON.parse(raw.find(x=>x.args.join(' ')==='graph info').stdout).data;
const gid=info.kv['logseq.kv/local-graph-uuid'];if(!gid)throw Error('missing actual synthetic graph identity');
fs.writeFileSync(path.join(HOME,'.logseq','graphs.edn'),`[{:repo "logseq_db_search-package-fixture" :graph-name "search-package-fixture" :local-graph-id "${gid}" :graph-id "${gid}" :updated-at ${Date.now()}}]`);
const env={...process.env,HOME,USERPROFILE:HOME,APPDATA:path.join(OUT,'appdata'),LOCALAPPDATA:path.join(OUT,'appdata')};delete env.ELECTRON_RUN_AS_NODE;
const log=fs.openSync(path.join(OUT,'electron.raw.log'),'a');
const child=spawn(EXE,['--user-data-dir='+UD,'--inspect-brk=127.0.0.1:19431','--remote-debugging-address=127.0.0.1','--remote-debugging-port=19432'],{env,stdio:['ignore',log,log]});
fs.writeFileSync(path.join(OUT,'pid.json'),JSON.stringify({pid:child.pid,exe:EXE,home:HOME,userData:UD,inspectorPort:19431,cdpPort:19432,apiPort:19433},null,2));
let targets;for(let i=0;i<40;i++){try{targets=await(await fetch('http://127.0.0.1:19431/json/list')).json();break;}catch{await sleep(500)}}
if(!targets?.[0])throw Error('Inspector unavailable; own instance left paused, no forced kill');
const ws=new WebSocket(targets[0].webSocketDebuggerUrl);await new Promise(r=>ws.once('open',r));let id=0;const waiting=new Map();let pauseResolve;const paused=new Promise(r=>pauseResolve=r);
ws.on('message',b=>{const m=JSON.parse(b);if(m.id){const h=waiting.get(m.id);if(h){waiting.delete(m.id);m.error?h[1](Error(JSON.stringify(m.error))):h[0](m.result)}}if(m.method==='Debugger.paused')pauseResolve(m.params)});
const send=(method,params={})=>new Promise((res,rej)=>{const n=++id;waiting.set(n,[res,rej]);ws.send(JSON.stringify({id:n,method,params}))});
await send('Debugger.enable');await send('Runtime.runIfWaitingForDebugger');
const stop=await Promise.race([paused,sleep(15000).then(()=>{throw Error('No startup pause; do not continue')})]);
const expr=`(()=>{const a=require('electron').app; const fs=require('fs'); const home=${JSON.stringify(HOME)},ud=${JSON.stringify(UD)},out=${JSON.stringify(OUT)};a.setPath('home',home);a.setPath('userData',ud);a.setPath('appData',out+'/appdata');a.setPath('logs',out+'/logs');require('os').homedir=()=>home;return {home:a.getPath('home'),userData:a.getPath('userData'),appData:a.getPath('appData'),logs:a.getPath('logs'),osHome:require('os').homedir(),appPath:a.getAppPath()}})()`;
const checked=await send('Debugger.evaluateOnCallFrame',{callFrameId:stop.callFrames[0].callFrameId,expression:expr,returnByValue:true});
if(checked.exceptionDetails)throw Error(JSON.stringify(checked.exceptionDetails));const values=checked.result.value;
for(const k of ['home','userData','appData','logs','osHome'])if(!path.resolve(values[k]).startsWith(OUT))throw Error('isolation check failed:'+k);
fs.writeFileSync(path.join(OUT,'startup-isolation.json'),JSON.stringify({measured:values,scope:'Inspector adjusts only filesystem paths before production app startup; search code unchanged.'},null,2));
await send('Debugger.resume');ws.close();
let browser;for(let i=0;i<100;i++){try{browser=await chromium.connectOverCDP('http://127.0.0.1:19432');break;}catch{await sleep(500)}}if(!browser)throw Error('CDP not available');
const pages=browser.contexts()[0].pages();const page=pages.find(p=>p.url().startsWith('file:'))||pages[0];
await sleep(5000);
const state=await page.evaluate(()=>({url:location.href,title:document.title,text:document.body.innerText.slice(0,12000),apiKeys:Object.keys(window.logseq?.api||{}),storage:{...localStorage}}));
fs.writeFileSync(path.join(OUT,'initial-renderer.json'),JSON.stringify(state,null,2));console.log(JSON.stringify({pid:child.pid,isolation:values,url:state.url,text:state.text.slice(0,5000),apiKeys:state.apiKeys}));
child.unref();console.log('Own isolated instance intentionally kept running. No app closed or killed.');setInterval(()=>{},30000);
})().catch(e=>{console.error(e.stack);process.exit(1)});
