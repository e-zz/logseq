"""Build one new disposable seed graph, stop its CLI worker normally, copy to U/B."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess

BASE=Path(__file__).resolve().parent
OUT=Path(os.environ['TMPDIR'])/'ls-ub-20261005'
if OUT.exists():
    raise SystemExit('Refuse reuse of existing runtime-pair; inspect before retry')
OUT.mkdir()
EXE=Path('C:/Users/zhang/Downloads/logseq-ci-37203268496-x64/Logseq-win-x64-2.0.2/Logseq.exe')
CLI=EXE.parent/'resources/app.asar/js/logseq-cli.js'
SEED=OUT/'seed'; HOME=SEED/'home'; ROOT=HOME/'logseq'
for x in [HOME,SEED/'appdata',ROOT]: x.mkdir(parents=True,exist_ok=True)
ENV=dict(os.environ,ELECTRON_RUN_AS_NODE='1',HOME=str(HOME),USERPROFILE=str(HOME),APPDATA=str(SEED/'appdata'),LOCALAPPDATA=str(SEED/'appdata'))
GRAPH='compat-pair-fixture'
raw=[]
def cli(*args):
    p=subprocess.run([str(EXE),str(CLI),*args,'--root-dir',str(ROOT),'-g',GRAPH,'-o','json'],env=ENV,capture_output=True,text=True,encoding='utf8',errors='replace',timeout=90)
    raw.append({'args':list(args),'exit':p.returncode,'stdout':p.stdout,'stderr':p.stderr})
    (OUT/'fixture-cli.raw.json').write_text(json.dumps(raw,indent=2),encoding='utf8')
    print(args,'exit',p.returncode,p.stdout[:300],flush=True)
    if p.returncode: raise RuntimeError(p.stderr+p.stdout)
    v=json.loads(p.stdout)
    if v.get('status')!='ok': raise RuntimeError(str(v))
    return v['data']
try:
    cli('graph','create')
    cli('upsert','page','--page','Compat Page One')
    cli('upsert','block','--target-page','Compat Page One','--blocks','- compatordinary45831\n- compatparent45831\n  - compatnested45831')
    cli('upsert','page','--page','Compat Page Two')
    cli('upsert','block','--target-page','Compat Page Two','--content','compatotherpage45831')
    cli('graph','switch')
    info=cli('graph','info')
finally:
    cli('server','stop')
source=ROOT/'graphs'/GRAPH
if not source.is_dir(): raise RuntimeError('Seed graph path missing')
# The worker was stopped using its own scoped CLI server stop command. Do not copy leases.
for side in ['U','B']:
    dest=OUT/side/'home'/'logseq'/'graphs'/GRAPH
    shutil.copytree(source,dest,ignore=shutil.ignore_patterns('db-worker.lock','*.lease'))
    for d in [OUT/side/'userdata',OUT/side/'appdata',OUT/side/'logs',OUT/side/'home'/'.logseq']:
        d.mkdir(parents=True,exist_ok=True)
meta={'graph':GRAPH,'graph_id':info['kv']['logseq.kv/local-graph-uuid'],'page_one':'Compat Page One','page_two':'Compat Page Two','queries':['compatordinary45831','compatnested45831','compatotherpage45831'],'provenance':'New synthetic B CLI seed, normal scoped worker stop, identical copies to U/B. No existing user graph.'}
(OUT/'fixture.json').write_text(json.dumps(meta,indent=2),encoding='utf8')
paths={}
for side in ['U','B']:
    paths[side]={str(p.relative_to(OUT/side/'home'/'logseq'/'graphs'/GRAPH)):hashlib.file_digest(p.open('rb'),'sha256').hexdigest() for p in (OUT/side/'home'/'logseq'/'graphs'/GRAPH).rglob('*') if p.is_file()}
if paths['U']!=paths['B']: raise RuntimeError('Fixture copies differ')
(OUT/'fixture-hashes.json').write_text(json.dumps(paths,indent=2),encoding='utf8')
print('Prepared identical U/B copies:',json.dumps(meta),flush=True)
