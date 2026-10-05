# 04 Scoped Search — Implementation Report

Worktree: `D:/orca/workspaces/logseq/issue9-typed-properties`
Branch: `fix/issue-9-mcp-properties-wip`
HEAD at start: `f2958757d2 wip: support numeric MCP property writes`
Status: source + tests implemented; focused tests pass. Broad gate pending/partial.

## Scope implemented

Target behavior for `searchBlocks` MCP tool:
- optional `pageUuid` (active visible page scope)
- optional `blockUuid` (exact visible block scope; NOT subtree)
- optional `limit` (1..100)
- invalid/hidden/recycled/broken-chain scope never falls back to global search
- page scope uses current DB membership, not stale index membership
- combined `pageUuid`+`blockUuid` must agree
- deterministic bounded results
- exact-block scope excludes semantic/vector results

## Source changes

### New
- `src/electron/electron/mcp_search.cljs` — `search-call-args` builds JS options
  (`:enable-snippet? false`, optional `page-uuid`, `block-uuid`, `limit`) and
  returns `[searchTerm options]`.

### Modified
- `src/electron/electron/mcp_server.cljs`
  - require `[electron.mcp-search :as mcp-search]`
  - `api-search-blocks` now routes through `mcp-search/search-call-args`
  - `:searchBlocks` schema expanded with `pageUuid`, `blockUuid`, `limit`
    (`.uuid` / `.int .positive .max 100`) and updated description.
  - Preserved all current property/numeric/parent/receipt descriptions.
- `src/main/frontend/handler/search.cljs`
  - `<resolve-page-uuid` via `:thread-api/search-page-uuid`
  - `validate-search-limit!` (explicit limit must be integer 1..100)
  - UUID format + mutually-exclusive `page-db-id`/`page-uuid`/`block-uuid` checks
  - blank query with a scope still validates scope; blank unscoped query returns nil
  - threaded `:page` (page scope) / `:block` (exact block) options
  - file search suppressed when scoped
- `src/main/frontend/worker/handler/search.cljs`
  - require `logseq.api.db-based.tools` + `logseq.db`
  - `resolve-active-visible-page-uuid` (valid UUID, is a page, not hidden)
  - `resolve-active-visible-block` via `api-tools/get-block` (reuses eligibility:
    pages/pseudochildren/hidden/recycled/broken/cycle rejected) then validates its page
  - thread APIs `:thread-api/search-block-uuid`, `:thread-api/search-page-uuid`
  - semantic embedding skipped when `:block` set
- `src/main/frontend/worker/search.cljs`
  - `build-search-bind` / `search-blocks-aux` thread `block`
  - `search-blocks-exact-title-aux`, `search-blocks-fuzzy-aux` constraint to `page`/`id = ?`
  - `search-block-in-page?` current-membership filter; `include-search-block?` applies
    `page`/`block` filters (rename `block`->`entity`)
  - main `search-blocks` threads `block`; `scope-sql` added to FTS/non-match SQL;
    namespace match wrapped in parens when scoped; vector results disabled for `:block`
  - docstring updated with `:block`

### Incidental
- `src/test/logseq/api/db_based/property_write_test.cljs`: removed trailing blank
  line at EOF so `git diff --check` passes (no content change).

## Tests added

- `src/test/frontend/handler/search_test.cljs` (+11 tests): invalid limits, default
  limit, unscoped shape, malformed page uuid, exact-block scope, mismatched
  page+block, blank-query scope validation, stable page scope, invalid/recycled page.
- `src/test/frontend/worker/search_test.cljs` (+8 tests, appended only):
  page+block bind like-before-limit, page scope before limit, namespace alternatives
  scoped before limit, stale page membership rejected, block-only fuzzy candidate
  scope, exact block before candidate limit, namespace alternatives in block scope,
  vector skipped for exact block.
- `src/test/frontend/worker/handler/search_test.cljs` (new, 6 tests): get-block
  eligibility reuse (page/pseudochild/hidden/recycled/broken/cycle), worker thread
  route, semantic decline, page target validation, recycled/hidden-ancestor rejection.
- `src/test/electron/mcp_server_test.cljs` (+2 tests): adapter forwards
  page/block/limit and page/limit.

## Verification (actual runs)

Fresh compile:
`pnpm cljs:test` -> `[:db-worker-node]` 441 files 0 warnings; `[:test]` 1378 files,
253 compiled, **0 warnings**.

Focused runs (node static/tests.js):
- `frontend.handler.search-test` -> 11 tests / 29 assertions, **0 failures, 0 errors**
- `frontend.worker.handler.search-test` -> 6 tests / 19 assertions, **0 failures, 0 errors**
- `electron.mcp-server-test` -> 3 tests / 7 assertions, **0 failures, 0 errors**
- each of the 8 appended `frontend.worker.search-test` scope tests -> **0 failures, 0 errors**
  (incl. `search-blocks-scopes-exact-block-before-every-candidate-limit` 9 assertions,
  `search-blocks-rejects-stale-current-page-membership` 3 assertions)

Full `frontend.worker.search-test` namespace reported **3 failures + 1 error** in tests
outside this diff's appended lines:
`search-indexes-hide-by-default-properties` (fixture `:user.property/keywords` nil) and
`sync-search-indice-reindexes-holders-when-property-is-deleted`
(`Cannot store nil ... [:db/add 193 :block/refs nil]`).

**CORRECTION (superseded by 05-validation-repair.md):** calling these "baseline
WIP-branch fixture failures" was an **inference**, not a measurement. It was based on
reading the diff and test paths, not on running the unmodified baseline. This report
captured **no baseline run**. The 05 report measured the baseline in an isolated
detached worktree at `f2958757d2` and records the real provenance.

**RESOLVED (05-validation-repair.md):** these were fixture identity bugs, not
production bugs. `db-test/create-conn-with-blocks` gives an unqualified `:properties`
key a random-suffixed user-property ident (`keywords-rp5XaGoi`, `foo-...`), so the
tests' deterministic `:user.property/keywords` / `:user.property/foo` lookups were
nil. The two fixtures were repaired to use qualified keys in both `:properties` and
`:build/properties`, with no assertion weakened. Full namespace is now
**73 tests / 246 assertions, 0 failures, 0 errors**. Baseline independently confirmed
the same 3 failures / 1 error pre-existing at `f2958757d2`.

## Broad gate (`bb dev:lint-and-test`)

**CORRECTION (superseded by 05-validation-repair.md):** the claim that the lint step
aborts and therefore "the parallel test phase is not reached" was wrong. `lint-and-test`
runs lint and tests via `run-parallel!`; the tests do run even when the lint task fails.
Also the warning count was reported as 16; the measured count is **15**.

Ran. Outcome: exit 1. clj-kondo reports **errors: 0, warnings: 0** after the 05
repair pass (the 15 warnings from our new property tests were fixed). The remaining
exit-1 cause is `lint:large-vars`: `upsert-nodes` is 119 lines (max 100) in
`src/main/logseq/api/db_based/cli.cljs` (committed at `f2958757d2`, present at
baseline); `run-parallel!` throws on it and aborts the parallel run. Full-suite
`electron.embedding-server-test` additionally reports 12 Windows path-separator
failures. Both are documented as remaining gate blockers in 05-validation-repair.md.

Test phase (focused namespaces), run directly against the fresh compile:
handler 11/29, worker handler 6/19, mcp-server 3/7, plus the 8 worker scope tests —
all 0 failures / 0 errors.

## Not done / caveats

- No genuine pre-implementation RED transcript was captured for the scoped-search work:
  session interruptions led to source being written in the same pass as tests; those
  tests were instead validated green. This remains an explicit gap.
- HTTP/persistence-level MCP integration not exercised; adapter-level tests only.
