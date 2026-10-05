import json, sys

aud = 'D:/orca/workspaces/logseq/issues-human-test-20261003/.agents/audits/live-numbering-fresh-page-followup/'
def load(f):
    with open(aud + f, encoding='utf-8') as fh:
        return json.load(fh)

results = {}

# 1. New issue readback
issue = load('issue20_readback.json')
title_ok = issue['title'] == 'Bug: live ordered-list numbering remains stale until application restart'
body_expected = open(aud + 'issue_payload.md', encoding='utf-8').read()
body_ok = issue['body'].strip() == body_expected.strip()
state_ok = issue['state'] == 'open'
results['issue20'] = {
    'number': issue['number'],
    'url': issue['html_url'],
    'state': issue['state'],
    'title_match': title_ok,
    'body_match': body_ok,
    'state_open': state_ok,
}

# 2. Comment on #4
expected4 = open(aud + 'comment4_final.md', encoding='utf-8').read()
comments4 = load('issue4_comments_after.json')
c4 = [c for c in comments4 if c['id'] == 5981927250]
results['comment4'] = {
    'id': 5981927250,
    'url': c4[0]['html_url'] if c4 else None,
    'found': bool(c4),
    'body_match': bool(c4) and c4[0]['body'].strip() == expected4.strip(),
}

# 3. Comment on #11
expected11 = open(aud + 'comment11_payload.md', encoding='utf-8').read()
comments11 = load('issue11_comments_after.json')
c11 = [c for c in comments11 if c['id'] == 5981927917]
results['comment11'] = {
    'id': 5981927917,
    'url': c11[0]['html_url'] if c11 else None,
    'found': bool(c11),
    'body_match': bool(c11) and c11[0]['body'].strip() == expected11.strip(),
}

all_ok = (results['issue20']['title_match'] and results['issue20']['body_match']
          and results['issue20']['state_open'] and results['comment4']['found']
          and results['comment4']['body_match'] and results['comment11']['found']
          and results['comment11']['body_match'])
results['ALL_OK'] = all_ok
print(json.dumps(results, indent=2))
sys.exit(0 if all_ok else 1)
