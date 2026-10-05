"""Fetch exact upstream CI artifact. No app launch or existing files overwritten."""
import hashlib
import json
from pathlib import Path
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parent / 'upstream-package-37131548966'
ROOT.mkdir(parents=True, exist_ok=True)
RUN = 37131548966
JOB = 111227498724
ARTIFACT = 11277537465
SHA = '22a29b30dee3b3930cf49bba50454650c31d2a07'
DIGEST = '503ad6252dbf2f123c83ac74d55e6d7201e6eca69d2ed3e9b45bc8b99f8a3c97'
archive = ROOT / 'artifact.zip'
if archive.exists():
    raise SystemExit('Refuse overwrite; inspect existing download before retry')

def gh_json(endpoint):
    p = subprocess.run(['gh', 'api', endpoint], capture_output=True, check=True)
    return json.loads(p.stdout)

metadata = gh_json(f'repos/logseq/logseq/actions/runs/{RUN}')
if metadata['head_sha'] != SHA or metadata['conclusion'] != 'success':
    raise SystemExit('Run identity mismatch')
artifact = gh_json(f'repos/logseq/logseq/actions/artifacts/{ARTIFACT}')
if artifact['expired'] or artifact['name'] != 'logseq-win-x64-builds':
    raise SystemExit('Artifact unavailable/mismatch')
if artifact['digest'] != 'sha256:' + DIGEST:
    raise SystemExit('Remote digest mismatch')
(ROOT / 'run-metadata.json').write_text(json.dumps(metadata, indent=2), encoding='utf8')
(ROOT / 'artifact-metadata.json').write_text(json.dumps(artifact, indent=2), encoding='utf8')
with (ROOT / 'compile-checkout.raw.log').open('wb') as log:
    subprocess.run(['gh', 'run', 'view', str(RUN), '--repo', 'logseq/logseq', '--job', str(JOB), '--log'], stdout=log, check=True)
logtext = (ROOT / 'compile-checkout.raw.log').read_text(encoding='utf8', errors='replace')
checkout_lines = [line for line in logtext.splitlines() if SHA in line]
if not checkout_lines:
    raise SystemExit('Compile log does not corroborate SHA; no download or execution')
print('Compile log contains exact SHA:', '\n'.join(checkout_lines), flush=True)
with archive.open('xb') as out:
    subprocess.run(['gh', 'api', f'repos/logseq/logseq/actions/artifacts/{ARTIFACT}/zip'], stdout=out, check=True)
hashval = hashlib.file_digest(archive.open('rb'), 'sha256').hexdigest()
if hashval != DIGEST:
    raise SystemExit(f'Download digest mismatch {hashval}; do not extract or execute')
outer = ROOT / 'artifact-content'
with zipfile.ZipFile(archive) as z:
    z.extractall(outer)
nested = list(outer.rglob('Logseq-win-x64-*.zip'))
if len(nested) != 1:
    raise SystemExit(f'Expected exactly one portable ZIP, found {len(nested)}')
portable = ROOT / 'portable'
with zipfile.ZipFile(nested[0]) as z:
    z.extractall(portable)
exes = list(portable.rglob('Logseq.exe'))
if len(exes) != 1:
    raise SystemExit(f'Expected one executable, found {len(exes)}')
result = {'status': 'DOWNLOADED_NOT_EXECUTED', 'source_sha': SHA, 'source_evidence': 'compile log exact SHA lines (inspect checkout context before running)', 'run': RUN, 'artifact': ARTIFACT, 'zip_sha256': hashval, 'expected_sha256': DIGEST, 'exe': str(exes[0]), 'compile_sha_lines': checkout_lines}
(ROOT / 'download-verification.json').write_text(json.dumps(result, indent=2), encoding='utf8')
print(json.dumps(result, indent=2), flush=True)
