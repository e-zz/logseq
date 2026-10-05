"""Exact ASAR package inspection. No app startup or graph writes."""
import argparse, hashlib, json, struct
from pathlib import Path

def inspect(root):
    asar = Path(root) / 'resources' / 'app.asar'
    with asar.open('rb') as f:
        prefix = f.read(16)
        if len(prefix) != 16:
            raise ValueError('truncated ASAR')
        header_size = struct.unpack_from('<I', prefix, 4)[0]
        json_size = struct.unpack_from('<I', prefix, 12)[0]
        if not 0 < json_size < header_size < asar.stat().st_size:
            raise ValueError('invalid ASAR header sizes')
        header = json.loads(f.read(json_size))
        body_offset = 8 + header_size
        def extract(name):
            node = header
            for part in name.split('/'):
                node = node['files'][part]
            if node.get('unpacked'):
                return Path(str(asar) + '.unpacked', name).read_bytes()
            f.seek(body_offset + int(node['offset']))
            data = f.read(node['size'])
            if len(data) != node['size']:
                raise ValueError('truncated entry: ' + name)
            return data
        package = json.loads(extract('package.json'))
        main_name = package['main']
        entries = {}
        for name in [main_name, 'js/main.js', 'js/logseq-cli.js']:
            data = extract(name)
            entries[name] = {'bytes': len(data), 'sha256': hashlib.sha256(data).hexdigest(),
                             'publish_result_key_present': b'publish-result?' in data}
    with asar.open('rb') as f:
        digest = hashlib.file_digest(f, 'sha256').hexdigest()
    return {'path': str(asar), 'asar_sha256': digest,
            'package_name': package.get('name'), 'package_version': package.get('version'),
            'package_main': main_name, 'entries': entries,
            'claim_limit': 'Exact file metadata and fingerprint only, not GUI/MCP behavior proof.'}

if __name__ == '__main__':
    p = argparse.ArgumentParser()
    p.add_argument('package_root')
    p.add_argument('--old')
    p.add_argument('--out', required=True)
    a = p.parse_args()
    result = {'new': inspect(a.package_root)}
    if a.old:
        result['old'] = inspect(a.old)
        result['main_fingerprint_discriminates'] = (
            result['new']['entries']['js/main.js']['publish_result_key_present']
            and not result['old']['entries']['js/main.js']['publish_result_key_present'])
    Path(a.out).parent.mkdir(parents=True, exist_ok=True)
    Path(a.out).write_text(json.dumps(result, indent=2), encoding='utf-8')
    print(json.dumps(result, indent=2))
