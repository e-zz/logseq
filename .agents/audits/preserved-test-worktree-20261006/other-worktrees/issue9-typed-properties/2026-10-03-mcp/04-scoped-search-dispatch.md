# Next slice: SCOPE-8 on the dedicated MCP worktree

User authorizes advancing in worktrees. Work ONLY D:/orca/workspaces/logseq/issue9-typed-properties on fix/issue-9-mcp-properties-wip. Preserve uncommitted property implementation and numeric/parent/receipt/read work. No commit/push/merge/cherry-pick/reset/checkout/clean/delete. Do NOT write to main checkout. You are the worker; do not delegate or inspect agent/process event logs. Read applicable AGENTS.md, repo-local skills and prompts/review.md. No web research needed.

Model is explicitly baqis/deepseek-v4.1-flash. Main checkout and other worktrees are READ-ONLY source references, not destinations. Read .agents/audits/2026-10-03-mcp/02-read-reconcile-map.md. Source-level audit is not runtime verification.

Parent independent evidence on existing compiled bundle: property-write 11 tests/75 assertions; issue6 10/41; tools 35/183; cli 18/67; electron.mcp-server 1/3; all zero failures/errors. These prove focused bundle results, not freshness or HTTP/persistence. git diff --check fails property_write_test.cljs:410 blank line EOF. Previous worker report 03-list4-task7-implementation.md is MISSING. Do not claim all done.

Task: implement scoped-search missing wiring/runtime segments on THIS baseline, not wholesale salvage. Acceptance: searchBlocks accepts optional pageUuid, exact blockUuid and limit; invalid/hidden/recycled/broken-chain scope never falls back globally; page scope matches current membership, not stale index membership; exact block is NOT subtree; combined scopes must agree; deterministic bounded results and actual MCP registered schema/adapter coverage.

Verified reference commits: scope branch 0ded4dbf1b contains electron/mcp_search.cljs, frontend/handler/search.cljs, frontend/worker/handler/search.cljs, frontend/worker/search.cljs and handler search tests. Salvage af19a45591 has useful mcp_server.cljs tool schema/wiring and tests BUT requires files absent in its tree. DO NOT apply whole salvage. Current baseline f2958757d2 includes real mcp_upsert.cljs, #17 tree/lifecycle/receipt/parent, #18 numeric and new status/list changes. Preserve all.

Method:
1. Audit current paths, read narrowly git show/diff reference segments. Define change list before writes. Root mcp_server currently may contain only searchTerm; inspect current file rather than trust stale line numbers.
2. Add target behavior RED tests first (schema/adapter and scope runtime); a compilation/fixture failure is not RED. Record genuine behavior failure.
3. Port/reconcile only needed scope segments, preserving current property tool description/discovery and parent/receipt APIs. Invalid scope fail-fast. No compatibility hacks.
4. Fresh compile pnpm cljs:test then node static/tests.js -n <namespace>, each target separately; confirm actual Testing lines/nonzero count. Exercise electron.mcp-server, scoped adapter tests, handler/worker search tests and existing property/parent/read suites. Do not reuse stale bundle after edits.
5. Run git diff --check; fix only incidental new EOF whitespace, no broad reformat. Run bb dev:lint-and-test. If clj-kondo unavailable, investigate documented tooling/path first; distinguish missing dependency from code failure. Do not claim full gate passed when it hasn't. No unapproved global config changes.
6. If full registered HTTP MCP can be tested safely via existing harness, do it on synthetic disposable graph. Do not open/modify user graph. Built CLI graph-open remains blocked by unresolved SAFE-12; do not bypass that safety gate for persistence tests. Record exact blocker rather than fabricate HTTP success.
7. Write .agents/audits/2026-10-03-mcp/04-scoped-search-implementation.md with exact source/change list, RED/GREEN commands and actual results, tests run, HTTP and persistence status, remaining gaps. Also write a short 03-list4-task7-implementation.md inventory explicitly citing parent verified focused results and unverified UI/HTTP/reopen/list-removal/receipt gaps; do not claim prior runtime yourself.

One source issue to record (do not derail scope): current list-properties starts with into {} e; when filtered closed-values is empty it never dissocs original :property/closed-values, potentially retaining hidden/recycled raw refs. Add a focused test/fix if cheap, otherwise state as pending follow-up. Do not report generic property or LIST/TASK issue as complete. Keep report labels source-observed vs executed vs inference explicit.

Finish this concrete slice with compiling source, focused test output and on-disk report, not a plan. No issue closing. Preserve dedicated worktree isolation throughout.
