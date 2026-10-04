# 搜索：MCP 过滤隔离与 upstream 兼容性修补计划

> For Hermes: execute through one implementation worker, then independent parent review. User explicitly authorized BOTH plan creation and worker implementation; this is not a plan-only request.

## Goal
只改变 MCP 的搜索默认过滤策略，保持 GUI 搜索与对应 upstream 的默认行为一致。定位共享搜索/索引改动是否导致回收实体结果变化，以最小改动修补并提供真实测试证据。

## Workspace and baseline
- Worktree: `D:/orca/workspaces/logseq/issues-human-test-20261003`
- Branch: `test/issues-mcp-20261003`
- Initial HEAD: `fb5eb4eb43cdb3be7a29d816b969d45c127d4861`
- Integrated upstream anchor: `22a29b30dee3b3930cf49bba50454650c31d2a07`
- Model: OpenCode CLI, explicitly `baqis/deepseek-v4.1-flash`; never label qwen3.8 as this model.
- Existing untracked audit/brief/integration-input files belong to previous work; preserve them. No broad git add, reset, clean, checkout, stash, or deletion.

## Evidence status and uncertainty
源码核验：GUI and MCP share renderer handler/worker search paths. Handler, scope predicates, SQL scope and index-listener runtime-write behavior were changed against the anchor.
源码核验：the inspected diff does not add recycled visibility predicates; these already existed at the anchor. This does NOT exclude an indirect behavior change.
用户实测：previous GUI search could show recycled matching entities with recycle labels; current package could not find the recycled test page. Exact previous application SHA/search mode is not established.
推测：shared index changes may make existing visibility predicates effective sooner, or shared search options/state may leak between callers. Neither is a confirmed root cause.
No inference is a license to remove upstream hidden/private filtering. No source test is a packaged GUI acceptance result.

## Scope / safety
Allowed: targeted source/tests changes, local test compilation only when source changes require it, isolated synthetic fixtures, report and local targeted commits.
Forbidden: GUI automation, live MCP graph writes, existing graph/database edits, current application lifecycle operations, installation, packaged app rebuild, full Electron application launch, public comments/issues, push, modifying other worktrees/profiles, unrelated #21 importer/naming fix.
No new public includeRecycled API/schema is required by this task. Do not invent one unless necessary and justified in the report.
Search policies MUST NOT leak via shared default options, global state, or destructive shared-index exclusion.

## Task 1 — Trace and establish a differential reproduction
Read root and applicable directory AGENTS.md and relevant local repo skills before editing. Use current source and tests as authority.
Trace separately:
1. GUI Cmd-K/page/block search and recycled badge display.
2. MCP searchBlocks bridge/IPC/renderer handler call.
3. Full indexing, incremental indexing, query/result merge predicates, caller options and shared state writes.
Compare the same synthetic active page/block, recycled page and its descendant block against upstream anchor behavior and current implementation. Reproduce via the smallest actual functions used by production, not a replica of predicate logic. Do not modify upstream/default policy to force the remembered behavior.
Candidate files:
- `src/main/frontend/handler/search.cljs`
- `src/main/frontend/search.cljs`
- `src/main/frontend/worker/handler/search.cljs`
- `src/main/frontend/worker/search.cljs`
- `src/main/frontend/worker/db_listener.cljs`
- `src/electron/electron/mcp_search.cljs`
- MCP IPC registration/caller discovered from actual references
- `deps/db/src/logseq/db/frontend/entity_util.cljs`
Deliver an interim `.agents/reports/search-mcp-filter-investigation.md` EARLY with actual call chain, function/file anchors, ranked hypotheses and actual upstream/current predicate findings.
If exact upstream GUI runtime behavior cannot be established, mark it unverified. Do not assert legacy GUI compatibility based on unit tests alone.

## Task 2 — Write and exercise red-capable tests before fixing
Use existing fixture/test helpers and async lifecycle rules; execute real production functions.
Likely tests (confirm actual namespaces/runner before invocation):
- `src/test/frontend/handler/search_test.cljs`
- `src/test/frontend/worker/handler/search_test.cljs`
- `src/test/frontend/worker/search_test.cljs`
- `src/test/logseq/api/db_based/mcp_search_test.cljs`
- existing Electron MCP search argument tests, located by references
Required matrix:
- GUI/default shared request vs MCP request on the SAME fixture; default GUI behavior compared to actual anchor, not guessed expectations.
- active match retained for both; MCP recycled page and recycled-page descendants excluded.
- recycled ordinary block excluded for MCP; unrelated hidden/private entities stay excluded.
- MCP call followed by GUI call and reverse order produce caller-appropriate results without global option/state leakage.
- full-index and incremental-update behavior, including runtime upsert newly searchable (#11) and recycle transitions.
- scoped page/block searches, scope mismatch rejection, namespace OR grouping and limits remain correct (#8).
- Filtering and limits: excluded recycled entries must not crowd out eligible active results, and no newly misleading count/has-more assertion.
Record the exact red command/output and failure reason. If no defect is reproduced, do not manufacture a red result: document what was exercised and narrow the conclusion.

## Task 3 — Minimal repair, conditional on diagnosed cause
Prefer caller-specific MCP policy/options at its adapter/API boundary; preserve shared defaults and upstream GUI behavior. If a shared primitive must accept an explicit option, preserve its original default, and ensure MCP is the explicit caller.
Do not globally remove `hidden?` or recycled predicates. Do not disable #11 runtime indexing to hide the symptom. Separate recycled visibility from genuinely hidden/private restrictions if needed, without widening GUI relative to the anchor.
Avoid invoking GUI result publication for an MCP-only query if confirmed to contaminate GUI state; reuse the existing lower-level query function rather than duplicating SQL/search engines.
If upstream itself excludes these entities and no fork-only regression reproduces, report the conflict explicitly. Keep upstream behavior, implement only a justified MCP isolation fix; no speculative global repair.

## Task 4 — Execute focused tests and assess regressions
Root AGENTS documented commands:
- `bb dev:test -v frontend.handler.search-test`
- `bb dev:test -v frontend.worker.handler.search-test`
- `bb dev:test -v frontend.worker.search-test`
- `bb dev:test -v logseq.api.db-based.mcp-search-test`
Inspect repo task definitions to confirm these commands in this worktree. Existing compiled artifacts may be stale after edits; compile test artifacts as necessary, NOT application/CI package.
Verify named namespaces actually ran; 0 tests is not success. Save exact commands, runner output, counts, exit codes, any baseline failures/blockers. Run targeted lint and `git diff --check`.
If environment blocks execution, give the concrete error and non-destructive alternative attempted. Never substitute source inspection or made-up test counts for execution.

## Task 5 — Report and targeted commit
Write `.agents/reports/search-mcp-filter-worker.md` with:
- root cause status (confirmed / inferred / still unknown), evidence and what cannot be proved;
- changed files and exact behavior boundary;
- test matrix with real commands/counts/results and raw evidence paths;
- upstream compatibility evidence and remaining GUI packaged acceptance;
- explicit statement current application/graphs were not touched;
- exact source commit SHA if committed.
Commit only this plan and worker-owned source/test/report files; force-add a named plan/report only if ignored. No unrelated files, no agent/model names in commit metadata, no push. Before commit inspect staged diff and secret/privacy risk. Leave pre-existing audit material untouched.

## Task 6 — Parent acceptance (not delegated)
Parent independently inspect worker diff, rerun focused tests, check default/caller policies and no state leakage. Worker passing tests does not close an issue or certify current package. A new CI package and GUI differential acceptance are separate later steps, not authorized application installation now.

## Completion criteria
Plan saved, worker actually launched with verified model. Implementation acceptance additionally requires reviewed minimal diff plus real executed tests, with packaged GUI gaps clearly marked. Preserve uncertainty rather than redefining upstream to satisfy a guess.
