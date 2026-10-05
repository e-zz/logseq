# Public issue follow-up

## Scope
Repository e-zz/logseq, verified public by parent. Publish only the three payloads below. No source edits, graph mutations, app input/restarts, issue closure, upstream publication, or git push. Existing issue titles were checked; no separate dynamic-numbering issue appeared in the all-state listing. Recheck duplicate before creating. Use gh. Read github-issues skill and its publishing-privacy reference first.

## Evidence and limits
Reporter confirms: inserting ordered-list items may leave existing displayed numbers unchanged and may produce duplicate numbers. Navigating away and returning does not refresh numbering; restarting the app corrects it. Reporter reproduced the same defect in an earlier version; its identifier is unspecified and no bisect was performed. Frontend/backend cause unknown. Agent previously observed repeated numbering in screenshots; do not publish those screenshots (private graph metadata).
Reporter confirms: a fresh page created by MCP after the latest restart appears in [[...]] autocomplete. Parent checked that the name did not exist before creating one page through upsertNodes and verified the exact new page by getPage afterward. No app restart or index rebuild was performed by parent between creation and reporter confirmation. Selection/navigation not confirmed, no latency measurement, and this new-page case is not a fresh-block case.
Current non-release CI: https://github.com/e-zz/logseq/actions/runs/37203268496 ; commit fb5eb4eb43cdb3be7a29d816b969d45c127d4861. Never generalize these results to all issues.

## New issue
Title: Bug: live ordered-list numbering remains stale until application restart
Body:
## Observed behavior (user-tested)
In a DB graph, inserting an item into an ordered list does not reliably update the displayed numbers of existing items. The inserted item may display a number already used by another item.

Navigating to another page and returning does not correct the numbering. Restarting the application restores correct numbering.

## Reproduction
1. Open a page with several ordered-list items.
2. Insert an item between existing items.
3. Observe the displayed numbering after exiting edit mode.
4. Navigate to another page, then return: the incorrect numbering remains.
5. Restart the application and reopen the page: the numbering is correct.

## Expected behavior
Displayed ordered-list numbering updates after insertion without navigating away or restarting the application.

## Scope and attribution
This is separate from #4, which requests MCP access to actual ordered-list properties. The reporter reproduced this numbering defect in an earlier version too; the earlier version identifier is not yet recorded and no regression bisect was performed. We are therefore tracking it as a pre-existing defect rather than attributing it to the current MCP changes.

Root cause is unconfirmed. Restart recovery alone does not establish whether the defect is in frontend rendering, backend state, or their update propagation.

## Current validation environment
Windows 11, Logseq DB graph, non-release Windows CI build: https://github.com/e-zz/logseq/actions/runs/37203268496 (commit fb5eb4eb43cdb3be7a29d816b969d45c127d4861).

## Comment on #4
Append a new comment (do not replace existing body):
User-tested follow-up: dynamic ordered-list numbering is defective after insertion: existing displayed numbers do not reliably advance and the new item may duplicate a number. Navigating away and returning does not fix it; restarting the application does. The reporter also reproduced this in an earlier version (exact version unspecified; no bisect). This is now tracked separately in NEW_ISSUE_URL, rather than treating it as a regression introduced by the MCP list-property changes. Frontend/backend attribution remains unconfirmed. This comment does not close #4 or claim all of its requested behavior is complete.

## Comment on #11
Append a new comment:
Additional user-tested validation on the current non-release Windows CI build (https://github.com/e-zz/logseq/actions/runs/37203268496): after the latest application restart, a distinct new test page was created through the built-in MCP upsertNodes tool. A prior getPage lookup confirmed that the name did not exist; post-write getPage confirmed the new page. The user then confirmed that the fresh page appears as an existing-page candidate in [[...]] autocomplete. No further application restart or manual index rebuild was performed by the agent between creation and confirmation.

This passes the fresh-page autocomplete visibility case. It does not establish a measured indexing latency, selection/navigation correctness, or a newly created ordinary-block visibility case after this latest restart. DB graph node references use [[...]]; the editor warns against using ((...)) for this purpose. The earlier ((...)) test expectation was incorrect. Leaving #11 open; no blanket completion claim.

## Publication and result handling
Before publishing scan each body: no graph name, test page title, UUID, local path, user/host identity, credentials or personal notes. Only public repo/build URLs and commit SHA are allowed identifiers. Check comments for existing matching follow-ups to avoid duplicates. Keep all targets OPEN. After each write read back exact target and compare title/body (new issue) or body (comments). Save drafts, handles and exact readback results under .agents/audits/live-numbering-fresh-page-followup/. Return new issue number+URL, both comment IDs+URLs, exact local payload paths and readback status. Parent will independently verify all three public targets. Do not claim UI tests you did not execute.
