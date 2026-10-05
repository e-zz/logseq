# 10 Verification: generic HTTP writes and real worker persistence

Status: **FINAL**. Deliverables A, B, C measured-pass. Feature lint
(`bb lint:large-vars`) and targeted kondo measured-pass. Full
`bb dev:lint-and-test` completes with **EXIT=0** (see "Full gate" below and
report `11-full-gate-verification.md`); the earlier EXIT=1 was two test groups
since fixed. Every feature-relevant suite is green.
This file is written incrementally so an interruption still leaves real status.

## Build / worktree provenance

- Worktree: `D:/orca/workspaces/logseq/issue9-typed-properties`
- Branch: `fix/issue-9-mcp-properties-wip`
- Baseline HEAD: `f2958757d211ef16725a0b54d07275baaa686029` (plus preserved uncommitted changes)
- `git status --porcelain` touched the same working set as prior runs; no resets,
  checkouts, stashes, cleans, commits or deletions were performed.
- HTTP harness build: `clojure -M:cljs compile mcp-http-verify`
- HTTP harness run: `NODE_PATH=.../resources/node_modules node static/mcp-http-verify.js`
- Worker tests build: `clojure -M:test compile db-worker-node test` (via `pnpm cljs:test`)

## Deliverable A — real user-property metadata through the HTTP MCP path

Measured PASS. Final evidence log: `scratch/10-http-final.txt`
(`MCP-VERIFY-OK`, `MCP-VERIFY-NEGATIVE-CONTROL 23`, EXIT=0). Same result also
in `scratch/10-http-debug.txt` after locating the harness regression via a
temporary stack trace (since reverted).

- `MCP-VERIFY-OK`
- `MCP-VERIFY-NEGATIVE-CONTROL 23` (7 of those corrupt Stage 5 results)
- 43 total checks, 0 failures; 13 are new Stage 5 user-property checks:
  - discovery of real metadata for all six typed user properties
  - page add receipt with typed user properties
  - getPage typed values on the entity
  - block add receipt for typed user properties
  - getBlock typed description / number / checkbox / many node refs
  - many node refs are additive (editing one retains existing refs)
  - property-only page edit unions refs and retains entity title
  - dry-run typed edit reports dry-run and does not mutate
  - wrong-typed scalar is rejected
  - malformed node ref is rejected
  - mixed batch with a wrong-typed property is rejected
  - rejected mixed batch preserves page metadata / creates no partial block
  - rejected scalar/ref writes preserve block metadata

The built-in `logseq.property/status` closed-value/recycle tests were retained
alongside; generic user properties were **not** substituted with Status.

Root-cause fix kept (not test-only): `validate-local-db!` on the
`:validate-scope :tx` import/MCP path now passes `:entity-fn` into
`malli_schema/datoms->entities` so a many user-property's single ref value
validates as a ref instead of a scalar. All temporary debug instrumentation was
reverted and verified absent by grep.

## Deliverable B — real `db-worker-node` on-disk SQLite persistence

Measured PASS. Evidence logs: `scratch/b-compile-02.txt` (build clean, 0
warnings), `scratch/10-worker-persistence-final.txt` and `scratch/b-worker-02.txt`
(`LOGSEQ_STABLE_IDENTS=1 node static/tests.js -n
frontend.worker.recycle-persistence-test` -> `Ran 4 tests containing 162
assertions. 0 failures, 0 errors.`, EXIT=0). Re-verified after the function
refactor below.

Implementation added to `src/test/frontend/worker/recycle_persistence_test.cljs`
(new `deftest user-property-persists-across-worker-restart`). Genuine production
paths only, no fake worker / direct SQLite writes:

- Seed the six user-property types + two topic pages through the existing
  `thread-api/import-edn` (`sqlite-export/build-import` -> `validate-import-txs`
  -> `sqlite-build/build-blocks-tx`), asserting `:error` nil and `:tx-count`
  positive.
- Discover created UUIDs via `api-list-properties` (`:db/ident` filter) and `q`;
  never made-up property names/UUIDs. Person page/block UUIDs are supplied as
  deterministic receipt UUIDs.
- Write person page + block and a property-only page union edit through
  `api-build-upsert-nodes-edn` -> `:batch-import-edn`, verifying receipts with
  `api-read-upsert-blocks`.
- Assert description/cv/orcid, score=0, enabled=false, both many topics, stable
  block UUID, and unchanged outline BEFORE normal `close-db`.
- Normal close -> assert `db.sqlite` exists with positive size -> stop worker ->
  start a new worker on the same graph -> reopen -> re-assert every value.
- Labeled normal-close durability, not crash durability.

Measured projection shapes (worker CLJS map after transit): text/url property
values project under `:block/title`; `:number` projects under
`:logseq.property/value`; `:checkbox` is a bare boolean; node-many projects as a
vector of `{:block/title ... :block/uuid ...}`.

Template pattern: `src/test/frontend/worker/recycle_persistence_test.cljs`.

## Deliverable C — Windows path portability in the full gate

Measured PASS. Evidence log: `scratch/c-embedding-test.txt`.

- Baseline (parent, clean `f2958757`): `electron.embedding-server-test`
  9 tests / 33 assertions, 12 failures / 0 errors, all POSIX-literal fixtures
  against native `path.join` output.
- After fix (`src/test/electron/embedding_server_test.cljs` uses
  `node-path/join` for fixture construction):
  `Ran 9 tests containing 33 assertions. 0 failures, 0 errors.`
- Production `embedding_server.cljs` behavior was not altered; only test fixture
  construction was made platform-native.

## Typed-property support / reject table (remaining gaps)

| Contract | Status | Evidence |
|---|---|---|
| add/page with `:url`, `:default`, `:node` many | supported | A Stage 5 |
| add/block with `:number`, `:checkbox`, `:default` | supported | A Stage 5 |
| many node refs additive (union, no removal) | supported | A Stage 5, B `user-property-persists-across-worker-restart` |
| property-only page edit retains title/outline | supported | A Stage 5 |
| wrong-typed scalar rejected | supported | A Stage 5 |
| malformed node ref rejected | supported | A Stage 5 |
| mixed batch rejected, no partial write | supported | A Stage 5 |
| null value / property removal | **not supported / not tested** | gap |
| many closed-value property write | **not supported** | gap |
| list bullet (`:logseq.property/order-list-type`) via generic path | **gap** | separate contract |
| node on-disk persistence across worker restart | supported | B `user-property-persists-across-worker-restart` |
| Electron IPC path | **unverified** | only HTTP + worker paths exercised |

Honest limitation: the legacy `getPage` `:blocks` projection still leaks opaque
ref UUID objects; typed `:entity` and `getBlock` ref values carry clean string
UUIDs. This is reported, not silently normalized.

## Function refactor (required by `lint:large-vars`)

Four vars exceeded the 100-line `lint:large-vars` limit. Refactored coherently
into helpers without changing assertions, ordering, error/finally cleanup, or the
legacy/receipt return contract:

- `src/dev-cljs/electron/mcp_verify.cljs`: extracted `list-tools-info`,
  `run-recycle-stage`, `run-status-property-stage`, `run-user-property-stage`
  (slims `run-scenario!` to merges) and split `user-property-assertions` into
  `user-property-context` + discovery/write/union/rejection assertion groups.
- `src/test/frontend/worker/recycle_persistence_test.cljs`: extracted
  `seed-user-property-metadata!`, `write-person-properties!`,
  `assert-user-property-reads!`.
- `src/main/logseq/api/db_based/cli.cljs`: extracted `receipt-uuids-for`,
  `preflight-edit-readbacks`, `receipt-add-id-map`, `receipt-expectations-for`,
  `verify-receipt-readbacks!`.

Two real defects were introduced by the mechanical refactor and fixed (not
test-only):

1. `run-recycle-stage` passed the unresolved SDK promise straight into
   `parse-text` (`Promise` has no `.content`). Added `call-tool-parsed` so the
   call is awaited before parsing.
2. `preflight-edit-readbacks` used `let` instead of `p/let`, so it iterated the
   unresolved `state/<invoke-db-worker` promise (`[object Object] is not
   ISeqable`), which broke every block/page property edit receipt. Switched to
   `p/let`.

Post-fix measured green:
- `bb lint:large-vars` -> `All vars are below the max size!`, EXIT=0
  (`scratch/10-large-vars-after-refactor.txt`).
- `clojure -M:clj-kondo --lint` (3 refactored files) -> errors 0, warnings 0,
  EXIT=0 (`scratch/10-kondo-after-refactor.txt`).
- HTTP harness -> 43 checks / 0 failures, negative control 23, EXIT=0
  (`scratch/10-http-final.txt`).
- `frontend.worker.recycle-persistence-test` -> 4 tests / 162 assertions,
  0 failures (`scratch/10-worker-persistence-final.txt`).
- Feature suites (`scratch/10-suite-final.txt`):
  `logseq.api.db-based.cli-test` 22/107, `property-write-test` 34/208,
  `tools-test` 37/192, `issue6-test` 10/41, all 0 failures / 0 errors.

## Full gate

Command (raw exit captured, no masking pipeline):
`bb dev:lint-and-test > scratch/11-full-gate-final.txt 2>&1; echo EXIT=$?`
-> **EXIT=0** (`scratch/11-full-gate-final.txt`). 40 test-run summary blocks,
all `0 failures, 0 errors`; clj-kondo errors 0 / warnings 0.

At the time this report was first written the gate was EXIT=1 with two test
groups failing. Both have since been fixed under task 11; corrected diagnosis:

- `logseq.api.plugin-test`: 11 failures, all POSIX-literal fixtures
  (`/tmp/plugins/...`, `storages/demo-plugin`) compared against native Windows
  `path.join` output. This **is** a proven pre-existing portability issue
  (parent baseline `f2958757` isolated run failed the same 11). Fixed in
  `src/test/logseq/api/plugin_test.cljs` by constructing fixtures/expectations
  with `node-path/join` (same class as the Deliverable C embedding fix).
- `logseq.api.db-test`: 5 failures, `ReferenceError: document is not defined`
  plus `Unhandled test worker api: :thread-api/query-dsl-query`. The claim in
  the first draft that these were a pre-existing jsdom/test-harness
  environment gap was **wrong**: isolated db-test is green (6 tests / 9
  assertions). The failures were batch contamination from
  `frontend.handler.editor-context-popup-test`, whose synchronous `with-redefs`
  let the real `editor/clear-selection!` run on a microtask and touch
  `js/document`. Fixed by making that test `async` and using
  `promesa.core/with-redefs` + `p/let`.

Both files are now modified (test-only); all feature-relevant suites and the
HTTP harness remain green. See `11-full-gate-verification.md` for the exact
commands, counts, and stack evidence.

## Update log

- (initial) A and C recorded as measured-pass; B recorded as implemented/pending.
- (final) Function refactor completed and two refactor bugs fixed; A/B/C
  re-verified; full gate run with EXIT=1.
- (task 11) Corrected the full-gate diagnosis: `plugin-test` portability was
  genuinely pre-existing, while the `db-test` grouped failures were
  contamination from `editor-context-popup-test` (not a jsdom gap). Both fixed;
  FULL `bb dev:lint-and-test` now EXIT=0. Details in
  `11-full-gate-verification.md`.
