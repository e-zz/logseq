"""Correct acceptance attribution and unsupported claims; retain exact comment IDs."""
import json
import re
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parent
REPO = 'repos/e-zz/logseq'

def api(path, body=None):
    args = ['gh', 'api', path]
    if body is not None:
        args += ['--method', 'PATCH', '--input', '-']
    return json.loads(subprocess.check_output(args, input=None if body is None else json.dumps({'body': body}), encoding='utf-8'))

repo = api(REPO)
print('REPOSITORY VISIBILITY:', repo['visibility'])
rows = json.loads((ROOT / 'parent-live-comment-readback.json').read_text(encoding='utf-8'))
assert len(rows) == 5
old_header = 'Measured against the Windows CI build [run 37203268496](https://github.com/e-zz/logseq/actions/runs/37203268496), commit `fb5eb4eb43cdb3be7a29d816b969d45c127d4861` (non-release test build), on a disposable DB graph via the built-in MCP server.'
new_header = ('**Parent review correction:** the earlier version incorrectly attributed earlier acceptance evidence to run 37203268496. '
              'The live evidence below comes from earlier integrated-build acceptance, including [run 37134185100](https://github.com/e-zz/logseq/actions/runs/37134185100); it was not all re-run on [run 37203268496](https://github.com/e-zz/logseq/actions/runs/37203268496). '
              'Source-level contracts are not additional live test results. No controlled performance benchmark was collected.')
result = []
for row in rows:
    issue = int(row['issue_url'].rsplit('/', 1)[-1])
    body = row['body'].replace('\r\n', '\n')
    if issue in (4, 8, 9):
        assert old_header in body
        body = body.replace(old_header, new_header)
    if issue == 4:
        body = body.replace('A block\'s numbered state can currently only be removed through the UI (right-click → "Toggle number list").', 'Removing the numbered-list attribute requires a different path; this MCP import path does not provide it. UI conversion itself remains part of the pending interactive acceptance.')
        body += '\n\nCurrent-package follow-up: a new three-item fixture was written and independently read back, but its static rendering and dynamic renumbering were not completed in the GUI pass. Earlier static-rendering evidence must not be read as a new-package rerun.\n'
    elif issue == 8:
        pass
    elif issue == 9:
        body = body.replace('All of the above survived a normal app close/reopen with values intact (measured, not crash durability).', 'Normal app reopen was verified for earlier date/task/metadata writes. It was not verified for the latest asset reference; the list above is not a claim that every case was reopened.')
        body = body.replace('- Many-valued property given a non-array, or an empty array', '- Many-valued input validation is also implemented in source (array required; empty array rejected), but is not claimed here as an additional paired-title-edit live atomicity test.')
        body = body.replace('## Cardinality-many semantics (measured) — additive, not replacement', '## Cardinality-many semantics — source contract, with live deduplication evidence')
        body = body.replace('- Matching is by **reference UUID, not display title** — the same target under a different title is still recognized as the same value.', '- Source contract: references are matched by node identity/UUID, not display title. A target-renaming experiment is not claimed here.')
        body = body.replace('- Example shape: existing `[A, B]` + incoming `[B, C, C]` → exactly `{A, B, C}` (A remains, B not duplicated, C added once).', '- Illustrative semantics (not an additional benchmark or exact live fixture): existing `[A, B]` + incoming `[B, C, C]` yields `{A, B, C}`; existing values remain and duplicate references collapse.')
    elif issue == 11:
        body = '''# Acceptance update — MCP-written block searchability (parent-reviewed correction)

The earlier version incorrectly attributed earlier MCP acceptance to the latest CI run and claimed a latest-build rerun that the evidence does not establish. The corrected split is:

## Earlier packaged-build live evidence

Earlier integrated-build acceptance, including [run 37134185100](https://github.com/e-zz/logseq/actions/runs/37134185100), verified same-session MCP add/search, title-edit/search freshness, and retained search results after normal reopen. These results are not being presented as reruns on the newer package.

## Current-package GUI search — measured passing

On the Windows non-release package from [run 37203268496](https://github.com/e-zz/logseq/actions/runs/37203268496), commit `fb5eb4eb43cdb3be7a29d816b969d45c127d4861`, a new fixture page and blocks were added via the built-in MCP server and independently read back. A subsequent app search for the exact unique block title showed the expected block under its fixture page, with `Nodes 1`. The parent reviewed the screenshot and input/readback evidence. No manual index rebuild was performed.

This verifies that this newly MCP-written block became visible in the GUI search. It does not measure indexing latency: the interval contained unrelated automation/navigation work. It also does not establish a latest-package title-edit/search rerun or crash durability.

## Pending / blocked

- `((...))` block-reference autocomplete was not completed.
- `[[...]]` page-reference autocomplete for the new page was not completed. Ordinary blocks are not page-reference candidates.
- GUI editing/keyboard routing blocked those checks; they are not recorded as functional failures.
- The test window disappeared after automation issued clicks at the window-close control. The dispatch effect was unverified; an independent application crash is not established. Post-event graph integrity has not been checked.

No controlled per-operation latency benchmark was collected. This issue remains open.
'''
    elif issue == 14:
        body = body.replace('Source-verified against the integration tree (commit `fb5eb4eb43cdb3be7a29d816b969d45c127d4861`) plus live behavior of the built-in MCP server on a disposable DB graph. No source changes.', 'Source-verified against the integration tree (commit `fb5eb4eb43cdb3be7a29d816b969d45c127d4861`). The ordinary-block recycle evidence below was collected during earlier integrated-build acceptance and is not a latest-package page-collision rerun. No source changes.')
        body = body.replace('`getPage(name, {includeRecycled: true})` returns a trashed generation;', '`getPage(name, {includeRecycled: true})` can return a trashed generation only when no active generation has that name;')
        body = body.replace('`getPage(name, {includeRecycled: true})` → returns the recycled generation with `deleted-at` present;', '`getPage(name, {includeRecycled: true})` with an active same-name page → still returns the active generation; to read the recycled generation, use its UUID with `includeRecycled: true` and verify `deleted-at`;')
    else:
        raise AssertionError(issue)
    for pattern in (r'[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}', r'[CD]:[\\/]', r'ISSUE4-11-UI-', r'sk-[A-Za-z0-9]{10,}'):
        assert not re.search(pattern, body, re.I), (issue, pattern)
    (ROOT / f'parent-corrected-issue-{issue}-comment.md').write_text(body, encoding='utf-8')
    live_before = api(f'{REPO}/issues/comments/{row["id"]}')
    live_body = live_before['body'].replace('\r\n', '\n')
    assert live_body in (row['body'].replace('\r\n', '\n'), body), 'Concurrent comment edit; stop'
    if live_body != body:
        api(f'{REPO}/issues/comments/{row["id"]}', body)
    live_after = api(f'{REPO}/issues/comments/{row["id"]}')
    assert live_after['body'].replace('\r\n', '\n') == body
    state = api(f'{REPO}/issues/{issue}')['state']
    assert state == 'open'
    result.append({'issue': issue, 'id': row['id'], 'url': live_after['html_url'], 'exact_body_verified': True, 'state': state})
    (ROOT / 'parent-correction-verification.json').write_text(json.dumps(result, indent=2), encoding='utf-8')
    print('CORRECTED AND READ BACK:', issue, row['id'], state)
assert len(result) == 5
for issue in (6, 7):
    state = api(f'{REPO}/issues/{issue}')['state']
    assert state == 'closed'
    print('CLOSED VERIFIED:', issue)
