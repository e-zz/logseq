import hashlib, json, os, subprocess, urllib.request
from pathlib import Path

EXE = 'C:/Users/zhang/Downloads/logseq-ci-37203268496-x64/Logseq-win-x64-2.0.2/Logseq.exe'
CLI = EXE.rsplit('/', 1)[0] + '/resources/app.asar/js/logseq-cli.js'
ROOT = 'C:/Users/zhang/AppData/Local/hermes/cache/scratch/logseq-issue12-ci37203268496'
GRAPH = 'issue12-preservation'
OUT = Path(__file__).with_name('issue12-missing-file-results.json')
PATHS = ['logseq/config.edn', 'logseq/custom.css', 'logseq/custom.js', 'logseq/publish.css', 'logseq/publish.js']
TARGET = 'logseq/custom.css'
env = dict(os.environ, ELECTRON_RUN_AS_NODE='1')
report = {'authorization': 'User explicitly allowed only custom.css file entity removal and normal isolated worker stop/reopen via clarify.', 'root': ROOT, 'graph': GRAPH, 'package': EXE, 'target': TARGET, 'log': [], 'result': 'INCOMPLETE'}

def save():
    OUT.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')

def cli(*args):
    r = subprocess.run([EXE, CLI, '--root-dir', ROOT, '--output', 'json', *args], env=env, capture_output=True, text=True, timeout=90)
    report['log'].append({'args': args, 'exit': r.returncode, 'stdout': r.stdout, 'stderr': r.stderr})
    save()
    assert r.returncode == 0, r.stdout + r.stderr
    data = json.loads(r.stdout)
    assert data['status'] == 'ok', data
    return data['data']

def server():
    s = next(x for x in cli('server', 'list')['servers'] if x['graph'] == GRAPH)
    assert Path(s['root-dir']).resolve() == Path(ROOT).resolve(), s
    assert s['owner-source'] == 'cli' and s['revision'] == 'fb5eb4e', s
    return s

def invoke(s, method, args):
    req = urllib.request.Request(s['base-url'] + '/v1/invoke', data=json.dumps({'method': method, 'argsTransit': json.dumps(args)}).encode(), headers={'Content-Type': 'application/json'})
    with urllib.request.urlopen(req, timeout=45) as response:
        result = json.load(response)
    assert result['ok'], result
    value = json.loads(result['resultTransit'])
    if isinstance(value, list) and len(value) == 2 and value[0] == "~#'":
        value = value[1]
    return value

def contents(s):
    return {p: invoke(s, 'thread-api/get-file-content', [s['repo'], p]) for p in PATHS}

def counts():
    return cli('query', '--graph', GRAPH, '--query', '[:find ?path (count ?e) :where [?e :file/path ?path]]')['result']

def nodes():
    rows = cli('query', '--graph', GRAPH, '--query', '[:find ?e ?uuid ?title :where [?e :block/uuid ?uuid] [?e :block/title ?title]]')['result']
    return sorted(rows, key=lambda x: json.dumps(x, sort_keys=True))

try:
    s = server()
    before = contents(s)
    assert all(isinstance(v, str) for v in before.values()), before
    before_counts = counts()
    assert dict(before_counts).get(TARGET) == 1, before_counts
    before_nodes = nodes()
    report.update(before=before, before_counts=before_counts, before_nodes=before_nodes, target_before_sha256=hashlib.sha256(before[TARGET].encode()).hexdigest())
    save()  # complete backup and exact target verification BEFORE authorized removal
    invoke(s, 'thread-api/transact', [s['repo'], [['~:db/retractEntity', ['~:file/path', TARGET]]], {}, None])
    report['after_removal'] = contents(s)
    report['after_removal_counts'] = counts()
    save()
    assert TARGET not in dict(report['after_removal_counts']), 'Target was not removed'
    assert all(report['after_removal'][p] == before[p] for p in PATHS if p != TARGET), 'Removal changed another file'
    cli('server', 'stop', '--graph', GRAPH)
    cli('graph', 'info', '--graph', GRAPH)
    s = server()
    after = contents(s)
    after_counts = counts()
    after_nodes = nodes()
    report.update(after_reopen=after, after_reopen_counts=after_counts, other_four_preserved=all(after[p] == before[p] for p in PATHS if p != TARGET), existing_uuid_title_rows_preserved=before_nodes == after_nodes, missing_default_recreated=after[TARGET] == '' and dict(after_counts).get(TARGET) == 1)
    report['result'] = 'PASS' if all(report[k] for k in ['other_four_preserved', 'existing_uuid_title_rows_preserved', 'missing_default_recreated']) else 'FAIL'
except Exception as error:
    report['error'] = repr(error)
    report['result'] = 'FAIL/INCOMPLETE'
finally:
    save()
    print(json.dumps({k: report[k] for k in ['result', 'error', 'other_four_preserved', 'existing_uuid_title_rows_preserved', 'missing_default_recreated'] if k in report}, ensure_ascii=False))

# CLI subprocess success is not acceptance success.
raise SystemExit(0 if report['result'] == 'PASS' else 1)
