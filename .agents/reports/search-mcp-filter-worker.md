# Worker report — MCP search filter isolation / upstream compatibility

Status: complete. Source repair plus tests landed; all focused suites green.
See "Commands and results" for raw output; "Diagnosed boundary" for the
GUI-vs-MCP split.

Worktree: `D:/orca/workspaces/logseq/issues-human-test-20261003`
Branch: `test/issues-mcp-20261003`
Integrated upstream anchor: `22a29b30dee3b3930cf49bba50454650c31d2a07`
Plan HEAD at resume: `c835800f29ed22a158b31af79af106fe274cf0b6`
(Branch HEAD had advanced to `50ff825597` with unrelated parent acceptance
evidence before this worker's commit.)

## Diagnosed boundary

Two independent concerns, only one of which is a real fork defect:

1. **Recycled-entity visibility — NO fork GUI regression reproduces.**
   Recycled exclusion is already in the upstream anchor and is *not* part of the
   fork diff. `frontend.worker.search/hidden-entity?` (`worker/search.cljs:608`)
   → `hidden-search-node?` (`:600`) → `logseq.db/hidden?` →
   `entity_util.cljs:69` treats any entity (or ancestor) carrying
   `:logseq.property/deleted-at` as hidden; `combine-results` (`:845`) drops
   hidden rows. `git diff 22a29b30..HEAD -- src/main/frontend/worker/search.cljs`
   does not touch `hidden-entity?`, `hidden-search-node?`, `combine-results`,
   `get-affected-blocks`, or the `(remove hidden-entity?)` call sites.
   The remembered "old GUI showed recycled" therefore predates this default or
   used a different build/configuration; it is *not* reproducible here and per
   the plan safety boundary the upstream default is preserved, not disabled.

2. **GUI/MCP result-state publication leak — real, fork-introduced, fixed.**
   The fork made `frontend.handler.search/search` publish
   `:search/result` / `:search/more-result` unconditionally, for **every**
   caller. MCP `searchBlocks` reaches this exact handler
   (`electron.mcp-server.cljs:130` → `logseq.app.search` →
   `api.cljs:187` → `handler/search.cljs:31`), so an MCP-only query wrote the
   GUI-designated result key. Fix: a caller-boundary option
   `:publish-result?` (default `true`, so GUI/plugin callers are unchanged);
   the MCP adapter passes `false`, and the option is stripped before the shared
   worker engine is called.

The GUI Cmd-K path is unaffected either way: it calls
`frontend.search/block-search` directly from
`components/cmdk/core.cljs` with `cmdk-state/cmdk-block-search-options`, not
`handler/search`, and no `:search/result` reader exists in this tree (only
`components/query.cljs` reads the distinct `:search/result-count`). The leak is
therefore a state-isolation defect, not the cause of a user-visible Cmd-K
regression.

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

- Keyed to the plan's conditional: no GUI regression reproduces on the current
  tree/anchor, so no shared hidden/recycled filter was touched and the upstream
  default is preserved. Reported as a justified no-change on the recycled axis.
- The separate, independently reproduced MCP→GUI result-state leak is fixed with
  the minimal caller-boundary option above.

## Remaining uncertainty (not in scope here)

- Exact previous application SHA / GUI search mode behind the remembered
  "old GUI showed recycled" is still unestablished.
- No packaged-GUI or live-graph acceptance was performed (not authorized);
  current-package GUI acceptance stays separate/unverified.

## Commit

Implementation commit: see the commit that added this file on branch
`test/issues-mcp-20261003`.
