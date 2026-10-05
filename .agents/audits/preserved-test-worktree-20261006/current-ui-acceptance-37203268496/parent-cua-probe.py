import json
import subprocess
import sys
from pathlib import Path
from datetime import datetime

ROOT = Path(__file__).resolve().parent / 'parent-reopen'
ROOT.mkdir(exist_ok=True)
label, tool, encoded = sys.argv[1:4]
args = json.loads(encoded)
target = ROOT / (label + '.json')
if target.exists():
    raise RuntimeError('Do not overwrite evidence')
if tool in ('click', 'double_click', 'right_click'):
    if args.get('y', 1000) < 50 and args.get('x', 0) > 1250:
        raise RuntimeError('Window-control clicks prohibited')
    if args.get('element_index') in (107, 108, 109):
        raise RuntimeError('Window-control indices prohibited')
start = datetime.now().astimezone().isoformat()
p = subprocess.run(['C:/Users/zhang/AppData/Local/Programs/Cua/cua-driver/bin/cua-driver.exe', 'call', tool, json.dumps(args)], capture_output=True, encoding='utf-8', errors='replace', timeout=40)
target.write_text(json.dumps({'input': args, 'tool': tool, 'started_at': start, 'stdout': p.stdout, 'stderr': p.stderr, 'exit_code': p.returncode}, ensure_ascii=False, indent=2), encoding='utf-8')
try:
    data = json.loads(p.stdout)
except ValueError:
    print(p.stdout, p.stderr)
    sys.exit(p.returncode)
if tool == 'get_window_state':
    print(json.dumps({k: data.get(k) for k in ('snapshot_id', 'pid', 'window_id', 'screenshot_file_path', 'screenshot_width', 'screenshot_height', 'element_count')}, ensure_ascii=False))
    for e in data.get('elements', []):
        if e.get('role') in ('Edit', 'MenuItem') or any(s in (e.get('label') or '') for s in ('ISSUE4', 'control-', 'Nodes', 'Demo', 'What are you')):
            print(json.dumps({k: e.get(k) for k in ('element_index', 'element_token', 'role', 'label', 'value', 'frame')}, ensure_ascii=False))
else:
    print(json.dumps(data, ensure_ascii=False))
sys.exit(p.returncode)
