# Follow-up publication audit — live numbering bug + fresh-page autocomplete

Date: 2026-10-05 (UTC+8)
Repository: e-zz/logseq (verified public at run time: `{"private": false, "visibility": "public", "fork": false}`)
Auth: gh, account e-zz, token scopes incl. `repo`.

## Duplicate checks (before creation)

1. `gh issue list --repo e-zz/logseq --state all --limit 100` — full all-state listing (issues 2–14). No dynamic-numbering issue present.
2. Comments on #4 (before): 1 comment, id 5981225906 (acceptance update 2026-10-04) — no numbering follow-up.
3. Comments on #11 (before): 2 comments, ids 5904563059, 5981226419 — no fresh-page follow-up.
   (The existing #11 comment lists "fresh-page autocomplete not completed" as pending — the new comment answers that pending item, not a duplicate.)

## Privacy pre-check

Ran regex scan (precheck.py) over all three payloads: no UUIDs, no local paths (C:/, D:/, /Users/), no user/host identity, no credentials. Only public repo/build URLs (actions run 37203268496) and commit SHA fb5eb4eb43cdb3be7a29d816b969d45c127d4861. Result: CLEAN for all three. Comment #4's `NEW_ISSUE_URL` placeholder was replaced with the real issue URL only after issue creation.

## Published targets (all left OPEN)

| Target | Handle | URL | Readback |
|---|---|---|---|
| New issue | #20 | https://github.com/e-zz/logseq/issues/20 | title_match=true, body_match=true (byte-exact vs issue_payload.md), state=open |
| Comment on #4 | 5981927250 | https://github.com/e-zz/logseq/issues/4#issuecomment-5981927250 | found=true, body_match=true (byte-exact vs comment4_final.md) |
| Comment on #11 | 5981927917 | https://github.com/e-zz/logseq/issues/11#issuecomment-5981927917 | found=true, body_match=true (byte-exact vs comment11_payload.md) |

Verification: verify.py re-fetched `GET /repos/e-zz/logseq/issues/20` and the comment lists (per_page=100, paginated) and compared against the exact payloads. ALL_OK = true.

## Audit file map (this directory)

- issue_payload.md — exact issue body as published (#20)
- comment4_payload.md — comment #4 draft with NEW_ISSUE_URL placeholder (pre-substitution)
- comment4_final.md — comment #4 body as published (placeholder substituted with real URL)
- comment11_payload.md — comment #11 body as published
- precheck.py / verify.py — privacy scan + readback comparison scripts
- issue4_comments_before.json / issue11_comments_before.json — pre-publication comment state
- issue20_readback.json / issue4_comments_after.json / issue11_comments_after.json — post-publication readbacks
- handles.json — consolidated handles + readback status

## Non-claims

- No issue was closed; #4, #11, #20 all remain open.
- No screenshots, graph names, page titles, UUIDs, or local paths were published.
- No app restart, index rebuild, graph write, code change, or git push was performed for this task.
- The fresh-page result on #11 is scoped: it passes the fresh-page autocomplete visibility case only; no latency measurement, selection/navigation confirmation, or fresh-block claim is made in the comment.
- Earlier-version reproduction of the numbering defect: version identifier unspecified, no bisect; the issue explicitly avoids regression attribution to the MCP changes.
