# Worker report — MCP search filter isolation / upstream compatibility

Status: PARTIAL for the original search-compatibility goal; result-publication isolation source increment completed.
"Commands and results" preserves worker-reported build/RED summaries; parent independently executed source-test logs are under `.agents/audits/search-mcp-filter-parent/`.
Authoritative closure review: `.agents/reports/search-mcp-filter-parent-review.md`.

Worktree: `D:/orca/workspaces/logseq/issues-human-test-20261003`
Branch: `test/issues-mcp-20261003`
Integrated upstream anchor: `22a29b30dee3b3930cf49bba50454650c31d2a07`
Plan HEAD at resume: `c835800f29ed22a158b31af79af106fe274cf0b6`
(Branch HEAD had advanced to `50ff825597` with unrelated parent acceptance
evidence before this worker's commit.)

## Diagnosed boundary

Two concerns are separate; only result-state isolation is repaired here.

1. **Recycled-entity visibility — original GUI discrepancy remains UNRESOLVED.**
   源码核验：the upstream anchor already includes recycled predicates in full/incremental indexing and result filtering. The new test executes GUI-like and MCP-like option sets against the SAME CURRENT worker implementation; it does not run the anchor implementation or a prior application. Therefore it proves current-fixture behavior, not absence of a fork regression. No packaged GUI comparison or index-listener differential experiment establishes why the earlier GUI returned recycled entities. Runtime/index side effects remain unexcluded. Shared upstream predicates were preserved, not removed.

2. **MCP result-state publication — existing shared behavior, now isolated.**
   源码核验：the upstream anchor's `frontend.handler.search/search` already publishes `:search/result` / `:search/more-result` unconditionally. Calling it through MCP also publishes those keys; this is NOT established as a fork-introduced defect.
   MCP `searchBlocks` reaches the handler via `electron.mcp-server/api-search-blocks` -> `logseq.app.search` -> renderer API `search`. The patch adds `:publish-result?` default true; the MCP adapter explicitly passes false and the handler strips that option before worker queries. Parent/worker handler tests exercise suppressed publication and unchanged returned results at the mocked worker boundary, not a live packaged MCP request.

源码核验：the inspected Cmd-K path calls `frontend.search/block-search` directly rather than the renderer handler. No Cmd-K consumer of `:search/result` was identified. The patch is justified as MCP state isolation; it does NOT establish a user-visible Cmd-K repair or explain recycled visibility. No causal exclusion of other GUI/shared-index paths is claimed.

## Changes (implementation-owned)

- `src/main/frontend/handler/search.cljs`
  - add `:publish-result?` (default `true`) to the opts destructuring;
  - wrap the `state/swap-state!` publication in `(when publish-result? ...)`;
  - strip `:publish-result?` from the options forwarded to the worker engine.
- `src/electron/electron/mcp_search.cljs`
  - `search-call-args` emits `:publish-result? false` next to
    `:enable-snippet? false`.
- `src/test/frontend/handler/search_test.cljs`
  - `search-publishes-result-state-only-when-requested`: MCP options must not
    publish `:search/result`; the default caller must; the returned result must
    be identical.
  - `search-does-not-leak-scope-options-across-callers`: two sequential searches
    reach the worker with each caller's own options (no MCP-only publication
    flag, no inherited scope) and the caller's opts map is not mutated.
- `src/test/electron/mcp_server_test.cljs`
  - the two exact-option-map assertions now include `:publish-result? false`.
- `src/test/frontend/worker/search_test.cljs`
  - `recycled-exclusion-is-identical-for-gui-and-mcp-option-sets`: on one
    fixture, GUI (`:built-in?`/`:include-matched-count?`) and MCP
    (`:enable-snippet? false`) option sets both exclude a *stale* recycled index
    row (via search-time `hidden-entity?`) and both keep the eligible active
    match under `:limit 1` (no crowding).

## Commands and results

Build (from repo root, `D:/orca/workspaces/logseq/issues-human-test-20261003`):

```
pnpm cljs:test
```

Result (final): `[:test] Build completed. (1393 files, 225 compiled, 0 warnings)`
and `[:db-worker-node] Build completed. (441 files, 0 compiled, 0 warnings)`.

Focused suites, run as
`bb dev:run-test-namespaces -r "^(?!logseq.db-sync.).*" -v <ns>`
against the freshly compiled `static/tests.js`:

| namespace | result (final, green) |
| --- | --- |
| `frontend.handler.search-test` | Ran 13 tests, 34 assertions, 0 failures, 0 errors |
| `frontend.worker.search-test` | Ran 79 tests, 300 assertions, 0 failures, 0 errors |
| `frontend.worker.handler.search-test` | Ran 6 tests, 19 assertions, 0 failures, 0 errors |
| `logseq.api.db-based.mcp-search-test` | Ran 1 test, 3 assertions, 0 failures, 0 errors |
| `electron.mcp-server-test` | Ran 5 tests, 22 assertions, 0 failures, 0 errors |
| `frontend.worker.db-listener-test` | Ran 15 tests, 53 assertions, 0 failures, 0 errors |

RED evidence (produced by temporarily reverting only the two production edits,
recompiling, and running, then restoring them):

- `frontend.handler.search-test`:
  `FAIL in (search-publishes-result-state-only-when-requested)` — before the fix
  both the suppressed MCP call and the default call published `:search/result`
  (2 entries instead of 1). Ran 13 tests, 1 failure.
- `electron.mcp-server-test`:
  `FAIL in (search-blocks-forwards-page-block-uuids-and-limit)` and
  `FAIL in (search-blocks-forwards-existing-page-scope)` — actual options lacked
  `:publish-result?`. Ran 5 tests, 2 failures.

After restoring the edits and recompiling, all suites above are green.

Lint: `bb lint:kondo-git-changes` could not run — the environment has no
`clj-kondo` binary on `PATH` (`Cannot resolve program: clj-kondo`). The
shadow-cljs build reported 0 warnings for the changed namespaces; no other lint
task was run because it is also clj-kondo-backed.

## Root-cause status

- Original GUI recycled-search discrepancy: UNRESOLVED. Source predicates match the anchor, but only the current worker was tested; anchor/old-package runtime comparison and index side effects are unverified.
- MCP state publication: repaired as a caller-boundary isolation improvement. Upstream already published these keys, so no fork-introduction claim.
- Parent independently reran all six named source namespaces: 119 tests / 431 assertions, no failures/errors. Logs: `.agents/audits/search-mcp-filter-parent/`. These counts are focused source coverage, not all application tests or packaged GUI acceptance.

## Remaining uncertainty (not in scope here)

- Exact previous application SHA / GUI search mode behind the remembered
  "old GUI showed recycled" is still unestablished.
- No packaged-GUI or live-graph acceptance was performed (not authorized);
  current-package GUI acceptance stays separate/unverified.

## Commit

Implementation commit: `5c1b5e55740ff0e0979e153e24cb84a5f174f175` on branch `test/issues-mcp-20261003`. Subsequent documentation/evidence commits do not change that source revision or imply a new CI package.
