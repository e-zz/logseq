# Issue closures: #4, #8, #9, #11

Date: 2026-10-05 (Asia/Shanghai). Repository: e-zz/logseq (public).
Authorization: the user replied `ok` immediately after the recommendation to close exactly these four
issues and to keep the actual test build plus the retained limits in each closing comment.

## What was done
- Posted one reviewed acceptance/limits comment per issue, then closed as `completed`.
- No product code, workflow, graph, or index was changed for these closures. Nothing was pushed.
- Each comment body was published verbatim from the reviewed file and read back afterwards.

## Verified effect (read back from the API, not self-reported)
| Issue | Closed at (UTC) | Comment | State readback |
|---|---|---|---|
| #4 | 2026-10-05T15:31:02Z | issues/4#issuecomment-5997654250 | closed / completed |
| #8 | 2026-10-05T15:31:07Z | issues/8#issuecomment-5997655714 | closed / completed |
| #9 | 2026-10-05T15:31:12Z | issues/9#issuecomment-5997657210 | closed / completed |
| #11 | 2026-10-05T15:31:18Z | issues/11#issuecomment-5997658668 | closed / completed |

`verification.json` records `body_match=true` for every published comment, and
`non_target_state_changes` is empty: #5, #12, #14, #20, #21 and the already-closed #6/#7 were untouched.

## Build attribution used in the comments
- Earlier live/static acceptance: CI run 37134185100 (not a comprehensive rerun).
- Later API/search acceptance track: CI run 37203268496, source fb5eb4eb43cdb3be7a29d816b969d45c127d4861.
- Test expectations were not re-run against newer packages solely to close these issues.

## Retained limits (also written into the public comments)
- #4: no property removal or number->bullet conversion; live renumbering handled separately as #20.
- #8: receipt verifies post-transaction DataScript state, not disk/crash durability or index completion.
- #9: no property removal, no many-value clear/replace, no many-valued closed-value writes.
- #11: fresh-page candidate selection/navigation unconfirmed; limited compatibility evidence elsewhere.

## Still open after this action
#5, #12, #14, #20, #21 remain OPEN and were not modified.

## Files
- `payloads.json`, `issue-*-closure.md`: the exact reviewed and published bodies.
- `close_verified.py`: the idempotent post-close-readback script that produced the JSON records.
- `states-before.json` / `states-after.json`: full issue-state snapshots around the action.

## Provenance note (measured vs. caution)
This record was written after the closures executed; its content is reconstructed from the archived
artifacts and API readbacks, not a contemporaneous transcription. The public comments are the primary
evidence for what was published.
