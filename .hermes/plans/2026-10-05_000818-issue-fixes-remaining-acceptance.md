# Issue fixes: remaining acceptance plan

> For Hermes: execute bounded acceptance tasks, delegate mechanical evidence/publication work when useful, and independently verify every child result. Do not substitute source tests for packaged-runtime acceptance.

## Live progress update (supersedes stale snapshot statements below)
- #11 fresh ordinary block: PASS native read/search and user-observed GUI search, autocomplete and reference navigation; evidence `issue11-fresh-block-api.md`. Fresh page candidate visible is PASS; its separate page navigation remains unreported. Do not recreate the passing block case.
- #14 real page recycle: PASS native default exclusion, explicit recycled title/UUID reads, deleted-at marker and retained content. Same-title active/recycled coexistence is BLOCKED by independently published #21: add request silently reuses recycled UUID. Do not restore or write into recycled fixture to hide it.
- GUI recycled-search compatibility: NOT ACCEPTED. Separate implementation worker is executing `.hermes/plans/search-mcp-filter-upstream-compatibility.md`; keep GUI/upstream and MCP defaults distinct. Source worker results cannot certify the currently running package.
- #9 remaining gaps: PASS independent asset-reference/target readback after user-confirmed restart; PASS actual non-journal date batch rejection with valid first title edit not applied and complete before/after block equality. Evidence `.agents/audits/current-ui-acceptance-37203268496/issue9-remaining-gaps-results.md`. Exactly one upsertNodes call used for this follow-up.
- #12 read-only follow-up: actual packaged CLI reports isolated CLI-owned revision fb5eb4e. Five file contents and SHA256 hashes match historical saved values; actual Datascript count query reports exactly one entity for each of five paths. Evidence `issue12-readonly-followup.json`. No new restart or deletion was performed in that read-only substep. Subsequent user explicitly authorized removal of ONLY isolated `logseq/custom.css` file entity and normal isolated CLI worker stop/reopen. Actual missing-file acceptance FAIL: file remained absent (no file entity and get-file-content=nil), while other four full contents and existing UUID/title rows were preserved; see `issue12-missing-file-results.json`. The fixture remains partial intentionally; no automatic restoration. Legacy init-conn / Windows classpath remain NOT EXERCISED in package; this failed packaged db-worker case does not prove legacy init-conn failure.
- Public #20 and #21 published and independently read back in earlier turns; no closures authorized here. Latest #9/#11 fresh-block details are local evidence, not claimed newly published.
- #5 legitimate historical missing-order fixture remains unavailable. No DB damage to manufacture a case.

Historical snapshot below is retained for provenance; its pending #9/#11/recycle/publication statements are superseded by this update. Task 3 is now completed; Tasks 1/2/4 only their explicitly named residual gaps remain.

## Goal
Finish only the acceptance checks mapped to the integrated issue fixes. Separate passing functional cases, documented API limits, missing fixtures, and unrelated/pre-existing defects. Do not claim all issues resolved or release readiness.

## Current artifact and evidence provenance
- Dedicated worktree: D:/orca/workspaces/logseq/issues-human-test-20261003, test/issues-mcp-20261003.
- Current non-release Windows artifact: CI run 37203268496, commit fb5eb4eb43cdb3be7a29d816b969d45c127d4861.
- Earlier integrated-build API/GUI evidence includes run 37134185100. Those cases were not all rerun on the current artifact; retain the exact build attribution.
- Latest user observations: a new MCP-created page after the latest restart appears in [[...]] autocomplete; selecting it and navigation have not been reported. Dynamic numbering may duplicate/stale after insertion, does not recover by page navigation, and recovers after application restart. User reports earlier-version reproduction; no exact earlier identifier or bisect, frontend/backend cause unknown.
- Public numbering follow-up and #4/#11 comments are delegated under .agents/briefs/live-numbering-and-fresh-page-autocomplete-followup.md; publication and independent parent readback are not yet complete at this plan snapshot.
- Historical NEXT-TESTS.md and parent-ci-remaining-todo.md contain obsolete CLI-placeholder and GUI-pending statements. This plan supersedes those statements only; historical evidence remains intact.

## Status by issue
|Issue|Verified scope|Remaining gap / boundary|GitHub state read this turn|
|---|---|---|---|
|#4|Real number-list property write/readback, static UI display, invalid bullet value batch rejection|MCP bullet/removal unsupported; dynamic numbering is separately tracked pre-existing defect, not a new regression attribution. No full feature-completion claim.|OPEN|
|#5|Integration documentation states upstream coverage; local missing-order implementation not layered|No qualified historical sample for current-package GUI regression. Do not damage DB to manufacture it.|OPEN|
|#6|Temporary-parent multi-level nesting including child-before-parent; before/after relation and order; two-node cycle batch rejection|Direct self-parent live case not covered, not a reason to repeat passing core cases or reopen user-closed issue.|CLOSED by explicit user instruction|
|#7|Task status on add and ordinary block; unknown status atomic rejection; UI/API status correspondence|No general task-view testing required.|CLOSED by explicit user instruction|
|#8|Full tree with budget, single-block lookup, scoped search, real receipt state readback, ordinary subtree recycle/restore with search exclusion|Receipt is current DataScript readback, not crash durability or index-completion fence. Functional checks covered; no controlled latency benchmark.|OPEN|
|#9|Typed scalar/property-only/node-many/date/asset/status writes with independent reads; false/0; additive dedup; tested error atomicity; earlier date/task/metadata normal reopen|Non-journal date rejection and latest asset-reference persistence not independently rechecked after latest restart. Null/empty-many/removal and many closed values unsupported, not hidden features.|OPEN|
|#11|Current-package GUI search for existing fixture; block [[...]] candidate/UUID selection after earlier restart; latest genuinely fresh MCP page candidate confirmed by user|Latest post-restart fresh ordinary-block write -> autocomplete/search still needed; page selection/navigation not reported; no timing benchmark.|OPEN|
|#12|Real packaged CLI now shipped; five DB file contents/hashes survive two normal isolated CLI-worker stop/open cycles; defaults on fresh graph|Original init-conn path not established by newer worker lifecycle; missing-file repair on existing graph and explicit semicolon-separated Windows classpath not verified.|OPEN|
|#14|Source contract reviewed; public comments corrected for active-first naming|Real recycled-page, same-name active/recycled, recycled UUID reads and ambiguous active names not live-tested.|OPEN|

## Ordered remaining tasks

### 1. Complete #11 fresh ordinary-block case
Prerequisite: app still on disposable graph; do not restart or rebuild index between write and query. Read current fixture and properties first. Use at most one upsertNodes per active user request to create one distinct ordinary block on an existing dedicated test page. Native desktop MCP only: do not open a second live CLI/SQLite mirror.
Acceptance: getBlock/getPage confirms title+UUID; GUI search finds that exact block; typing [[distinct-prefix in a different test block produces its existing-block candidate; selecting it stores reference to the correct UUID, not a newly created page. Save input, readbacks, screenshot, and user report separately. Earlier pre-restart fixtures do not satisfy this fresh case. Ask user only for precise GUI steps that automation cannot reliably do. Page reference selection/navigation can be confirmed in the same manual batch but is distinct from candidate appearance.

### 2. Run #14 real page-recycle cases
Create new dedicated disposable fixtures, not existing notes. Get explicit fixture-specific recycling confirmation before any page recycling. Existing three-block recycle authorization is not page-deletion authorization.
Acceptance:
- Recycled page absent from listPages/default getPage; explicit includeRecycled read carries deleted-at marker.
- With an active page having the same name, name lookup resolves the active page even with includeRecycled=true.
- Explicit UUID resolves the recycled generation when includeRecycled=true.
- If feasible using supported fixture creation, multiple active same-name candidates produce actionable UUID ambiguity instead of arbitrary selection; if the app prevents creating this condition, record fixture limitation, do not edit SQLite.
User can perform only the fixture-page recycle GUI operation; parent performs exact readback. No permanent deletion and no automatic test-graph cleanup.

### 3. Close focused #9 evidence gaps
Use independent read-only getBlock to inspect the previously written asset reference after the user-confirmed normal restart; do not rewrite it. Validate target existence and exact UUID, not merely a display label.
For non-journal date rejection, make one bounded negative write paired with a valid title edit only in a fresh fixture, record complete before/after equality and explicit rejection. Do not conflate source rejection with live atomicity. Respect one upsertNodes per user request; do not bypass via HTTP or delegation.
API limits remain explicit: removal unsupported, many inputs additive union, many closed values unsupported. Completing tests does not silently expand delivered semantics.

### 4. Complete #12 isolated runtime-path acceptance
Reuse only the dedicated disposable CLI graph and exact current packaged CLI; current GUI graph must not be opened by a second mirror.
- Establish whether the package exposes a supported route that actually executes original init-conn. Trace with source guidance but require runtime evidence; if absent, mark that packaged path NOT EXERCISED, not PASS.
- Use supported graph/file transactions on the isolated graph to remove only an owned test file entity, then reopen and verify missing-file defaults restored without changing the other four full contents. State the consequence and get required fixture-specific deletion authorization first; no raw DB edits.
- Test explicit two-directory Windows classpath separated by semicolon using the relevant supported entry point and discoverable sentinel/resource; distinguish exact resolution from merely successful generic startup.
- Check duplicate file-entity counts and unrelated canary preservation while reading this isolated graph.
No rebuild needed to repeat an existing runtime case. Rebuild only if a reproduced defect requires source changes and user scope permits.

### 5. Mark #5 sample-limited and finalize evidence
Find a legitimate historical disposable missing-order sample if already available. Without one, record runtime coverage gap; do not manufacture malformed current graph data.
Read back child-created public issue/comment targets and compare exact payloads before reporting publication complete. Assemble final acceptance table with build SHA, case, evidence type, PASS/FAIL/NOT TESTED/UNSUPPORTED, and reason. Recommend closure per issue only when its delivered boundary is explicit; do not close without user instruction.

## Excluded work
Do not test unrelated PDF/Zotero/sync/external-link features (#2/#10/#13 not covered by this batch). Do not undertake dynamic-numbering implementation as part of this acceptance batch: independent issue first, later dedicated diagnosis. No controlled performance benchmark exists; timing tool calls/events does not create one. Crash durability, arbitrary UI editing, and broad full-app QA are not added acceptance gates.

## Integration-test caveat
An earlier source suite had a sync.restart-test/connect-calls failure. The previous baseline-race attribution was a worker claim not independently confirmed by parent. Retain as unresolved source-test attribution, separate from packaged-runtime passes; CI build success does not prove the full test suite green. Do not rerun/recompile source solely to replace current-package acceptance.

## Evidence pointers
- docs/testing/issues-20261003.md: implementation/test mapping, some statements historical rather than current acceptance authority.
- .agents/audits/current-ui-acceptance-37203268496/parent-reopen/: direct GUI evidence.
- .agents/audits/current-ui-acceptance-37203268496/parent-review.md: historical parent review, superseded UI-pending lines by latest evidence.
- .agents/audits/demo-graph-acceptance-20261004/parent-issue12-package-file-preservation.md and parent-issue12-package-file-probe.json: isolated runtime preservation evidence.
- Corrected public #8/#9/#12 comment bodies read back this turn establish existing documented scope, not new tests.

## Completion definition
Each named case has independently reviewed evidence or an explicit supported limitation/fixture blocker. No fabricated aggregate pass percentage. No blanket current-build attribution for old-build cases. No issue closure or release declaration implied by finishing this plan.
