import re, sys
patterns = [
    r'[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}',
    r'/Users/|/home/|C:[\\/]|D:[\\/]',
    r'zhang|@logseq|\.local|intranet|172\.16',
    r'password|token|secret|credential|api[_ ]?key',
    r'graph\s+(name|title)',
]
for f in ['issue_payload.md','comment4_payload.md','comment11_payload.md']:
    text = open(f, encoding='utf-8').read()
    hits = [(p, m) for p in patterns for m in set(re.findall(p, text, re.I))]
    print(f, '->', 'CLEAN' if not hits else f'HITS {hits}')
# confirm allowed identifiers present
issue = open('issue_payload.md', encoding='utf-8').read()
assert '37203268496' in issue and 'fb5eb4eb43cdb3be7a29d816b969d45c127d4861' in issue
c4 = open('comment4_payload.md', encoding='utf-8').read()
assert 'NEW_ISSUE_URL' in c4
c11 = open('comment11_payload.md', encoding='utf-8').read()
assert '37203268496' in c11 and 'fb5eb4eb43cdb3be7a29d816b969d45c127d4861' not in c11  # brief text for #11 has run only
print('placeholders confirmed')
