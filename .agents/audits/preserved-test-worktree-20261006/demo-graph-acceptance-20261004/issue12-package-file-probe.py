import json, os, subprocess, urllib.request, hashlib
from pathlib import Path
EXE='C:/Users/zhang/Downloads/logseq-ci-37203268496-x64/Logseq-win-x64-2.0.2/Logseq.exe'
CLI=EXE.rsplit('/',1)[0]+'/resources/app.asar/js/logseq-cli.js'
ROOT='C:/Users/zhang/AppData/Local/hermes/cache/scratch/logseq-issue12-ci37203268496'
GRAPH='issue12-preservation'
OUT=Path(__file__).with_name('parent-issue12-package-file-probe.json')
env=dict(os.environ,ELECTRON_RUN_AS_NODE='1')
log=[]
def cli(*args):
    p=subprocess.run([EXE,CLI,'--root-dir',ROOT,'--output','json',*args],env=env,capture_output=True,text=True,timeout=90)
    log.append({'args':args,'code':p.returncode,'stdout':p.stdout,'stderr':p.stderr})
    assert p.returncode==0,p.stdout+p.stderr
    x=json.loads(p.stdout); assert x['status']=='ok',x
    return x['data']
def server():
    ss=cli('server','list')['servers']
    s=next(s for s in ss if s['graph']==GRAPH)
    assert Path(s['root-dir']).resolve()==Path(ROOT).resolve(),s
    assert s['owner-source']=='cli' and s['revision']=='fb5eb4e',s
    return s
def invoke(s,method,args):
    data=json.dumps({'method':method,'argsTransit':json.dumps(args)}).encode()
    req=urllib.request.Request(s['base-url']+'/v1/invoke',data=data,headers={'Content-Type':'application/json'})
    with urllib.request.urlopen(req,timeout=45) as r: x=json.load(r)
    assert x['ok'],x
    value=json.loads(x['resultTransit'])
    if isinstance(value,list) and len(value)==2 and value[0]=="~#'":
        value=value[1]
    return value
paths=['logseq/config.edn','logseq/custom.css','logseq/custom.js','logseq/publish.css','logseq/publish.js']
def contents(s):
    return {p:invoke(s,'thread-api/get-file-content',[s['repo'],p]) for p in paths}
def digest(c):
    return {p:hashlib.sha256(v.encode()).hexdigest() if isinstance(v,str) else None for p,v in c.items()}
report={'scope':ROOT,'package':EXE,'log':log}
try:
    cli('graph','info','--graph',GRAPH)
    s=server(); before=contents(s); report['before']=before
    assert all(isinstance(v,str) for v in before.values()),before
    changed={p:v+'\n'+(';; ' if p.endswith('.edn') else '// ' if p.endswith('.js') else '/* ')+ 'ISSUE12-CI37203268496-'+p+(' */' if p.endswith('.css') else '')+'\n' for p,v in before.items()}
    tx=[{'~:db/id':['~:file/path',p],'~:file/content':v} for p,v in changed.items()]
    invoke(s,'thread-api/transact',[s['repo'],tx,{},None])
    saved=contents(s); assert saved==changed,'Save readback differs'
    report['saved']=saved; report['saved_sha256']=digest(saved)
    OUT.write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
    for i in range(2):
        cli('server','stop','--graph',GRAPH)
        cli('graph','info','--graph',GRAPH)
        s=server(); readback=contents(s)
        report['reopen_'+str(i+1)]={'contents':readback,'sha256':digest(readback),'equal':readback==saved}
        assert readback==saved,'Contents overwritten after CLI reopen'
    report['result']='PASS: five file contents preserved through two packaged CLI server stop/open cycles'
except Exception as e:
    report['error']=repr(e); report['result']='FAIL/INCOMPLETE'; raise
finally:
    OUT.write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps({k:v for k,v in report.items() if k in ['result','error','scope']},ensure_ascii=False))
