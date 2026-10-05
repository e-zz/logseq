# 09 Generic page/block properties implementation

Worktree: `D:/orca/workspaces/logseq/issue9-typed-properties` (branch `fix/issue-9-mcp-properties-wip`).
Evidence labels: **source-observed** (read from code), **executed** (command + output), **inference** (reasoned, unverified).

## Baseline before stage 1 (executed 2026-10-03)
- `node static/tests.js -n logseq.api.db-based.property-write-test` -> `Ran 12 tests containing 81 assertions. 0 failures, 0 errors.`
  (artifact `static/tests.js` mtime 12:09 newer than `tools.cljs` 10:08; still recompiled after edits).

## Stage 1: schema-driven typed user properties on block add/edit

### Contract: property key
- **accept** a property UUID string (the identity `listProperties` returns), or an exact qualified
  db ident (`logseq.property/status`, `user.property/foo`).
- **reject** a bare title/name, unknown uuid, non-property entity, hidden property
  (except the explicitly allow-listed built-in `order-list-type`), and property-value pseudochildren.

### Contract: value by real `:logseq.property/type` (never guessed)
| type | single | many (`:db.cardinality/many`) | encoding |
|------|--------|-------------------------------|----------|
| `:default` | string | set of strings | pvalue entity (`:block/title`) via importer |
| `:number` | finite JSON number | set of numbers | pvalue entity (`:logseq.property/value`) |
| `:url` | string passing `url?`/`macro-url?` | set of strings | pvalue entity (`:block/title`) |
| `:checkbox` | boolean | not allowed (not cardinality-capable) | direct boolean attr |
| `:datetime` | finite JSON number (ms) | not allowed | direct number attr |
| `:date` | `{"uuid": "..."}` envelope -> journal page | set of envelopes | `[:block/uuid uuid]` refs |
| `:node` | `{"uuid": "..."}` envelope -> titled block | set of envelopes | `[:block/uuid uuid]` refs |
| `:asset` | `{"uuid": "..."}` envelope -> Asset-class block | set of envelopes | `[:block/uuid uuid]` refs |

### Contract: closed-value properties (incl. built-in `status`, `priority`)
- selected by stable uuid, exact identity (`logseq.property/status.done`), or display value (`"Done"`),
  and **must be a member of the property's allowed (non-hidden, non-recycled) closed values**.
- encoded as `[:block/uuid uuid]` lookup ref. Many-valued closed-value properties rejected (no evidence yet).
- `order-list-type` keeps its special case: only the literal `"number"` is accepted.

### Contract: cardinality
- **many is additive (union)** on edit, because the importer merges a set/ref map and datascript
  adds cardinality-many values; it does NOT replace. Documented and tested explicitly.
- **single** rejects a JSON array; **many** requires a JSON array.
- `null`/removal is **rejected explicitly** (the import path cannot emit a retract here).

### Encoding note (source-observed)
- ref types return `[:block/uuid uuid]`; vector values skip pvalue construction in
  `build-property-map-for-pvalue-tx` (`deps/db/src/logseq/db/sqlite/build.cljs:121-126`).
- scalar value-ref types (`:default/:number/:url`) build a pvalue; scalar direct types
  (`:checkbox/:datetime`) are stored on the block/page directly.
- page property support uses the same `:build/properties` path in `build-page-tx`
  (`deps/db/src/logseq/db/sqlite/build.cljs:690-709`).

## Stage 1 measured results (executed 2026-10-03)
- `clojure -M:clj-kondo --lint src/main/logseq/api/db_based/tools.cljs src/test/logseq/api/db_based/property_write_test.cljs`
  -> `errors: 0, warnings: 0`.
- `pnpm cljs:test` -> `[:db-worker-node] Build completed. (441 files, 0 warnings)`;
  `[:test] Build completed. (1381 files, 0 warnings)`.
- `node static/tests.js -n logseq.api.db-based.property-write-test`
  -> `Ran 20 tests containing 140 assertions. 0 failures, 0 errors.`
  (12 pre-existing + 8 new stage-1 tests).
- New stage-1 tests (source `src/test/logseq/api/db_based/property_write_test.cljs`):
  `typed-scalars-write-and-edit`, `typed-scalars-reject-wrong-shape`,
  `node-reference-write-and-reject`, `node-reference-recycled-target-rejected`,
  `date-reference-write-and-reject`, `asset-reference-write-and-reject`,
  `many-typed-values-are-additive`, `property-key-accepts-uuid-and-qualified-ident-only`.
- Readback note (source-observed): the importer stores values under the property's
  `:db/ident` (e.g. `:user.property/text`), not the property UUID. `get-page-blocks`
  returns refs as pulled `{:db/id ..}` maps, which the test helpers resolve to entities.
- Empty many array now rejects explicitly (`requires a non-empty JSON array`); the
  importer unions cardinality-many values, so removal is not claimed.
- `resolve-typed-properties` was extended to allow property writes on add/edit
  pages and pages are validated as existing, non-recycled before any write.

## Stage 2: content page add/edit and property-only page edits (executed 2026-10-03)
- `src/main/logseq/api/db_based/tools.cljs`:
  - `visible-page` (new) requires an existing non-recycled page for page edits.
  - `resolve-typed-properties` accepts `entityType` `"page"` for add/edit.
  - `ops->pages-and-blocks` attaches `:build/properties` to new-page adds.
  - `ops->existing-pages-and-blocks` attaches page-edit properties and includes
    page-only edit ids in its page set (no block add / page duplicate).
  - schema adds `["edit" "page"]` requiring `:properties` only (title/tags rejected,
    `:closed true`); tag/property edits remain unsupported.
  - page property edit requires non-empty properties.
- New stage-2 tests: `page-add-with-properties`,
  `page-property-only-edit-retains-outline`,
  `page-property-edit-rejects-empty-and-non-property-data`.
- Measured: `pnpm cljs:test` clean; `node static/tests.js -n logseq.api.db-based.property-write-test`
  -> `Ran 23 tests containing 155 assertions. 0 failures, 0 errors.`
- Observed: page property value entities live on the page (`:block/page` = page) and
  carry `:logseq.property/created-from-property`; outline reads must filter them.
  `get-page-blocks` includes them, so the test filters property-value blocks.

## Stage 3: real post-transaction property readback receipts (executed 2026-10-03)
- Removed the property receipt ban; `validate-receipt-operations!` now accepts both
  block and page add/edit operations (`"Receipt mode supports only add/edit block
  and page operations"`).
- `src/main/logseq/api/db_based/tools.cljs`:
  - `canonical-expected-property` reuses the write resolver so the receipt measures
    exactly what the importer stores (ref values -> uuid strings, many -> sets).
  - `observed-property-values` reads values back through the same readback shape the
    importer uses (property `:db/ident` key, refs resolved via `resolve-ref`).
  - `verify-requested-properties` verifies single values exactly and many values as an
    additive superset; returns `{:values {ident observed}}` or `{:error ..}`.
  - `read-upsert-blocks` rewritten to accept `:entity-type`, `:page-uuid`,
    `:parent-uuid`, `:title`, and `:properties`, and to verify observed properties;
    fixed a local `uuid`/`uuid-str` shadowing bug.
  - `ops->pages-and-blocks` new-page adds apply `(::receipt-uuid op)` + `:block/uuid`
    + `:build/keep-uuid? true`; `build-upsert-nodes-edn` assigns `::receipt-uuid` for
    page adds too.
- `src/main/logseq/api/db_based/cli.cljs`:
  - `receipt-uuids` extended to page adds; preflight `receipt-edit-readbacks` passes
    `:entity-type`; `receipt-expectations` carries `:entity-type`/`:properties` and
    omits page-uuid/parent-uuid for pages; `receipt-operation` emits observed
    `:properties` (block-only page-uuid/parent-uuid); `json-property-map` stringifies
    namespaced property keys at the JSON boundary.
- New stage-3 tests: `block-add-property-receipt-verifies-observed-value`,
  `page-add-property-receipt-verifies-planned-uuid`,
  `page-property-edit-receipt-verifies-observed-value`,
  `many-property-receipt-accepts-observed-superset` (property write test);
  `read-upsert-blocks-verifies-observed-property-values` (`tools_test.cljs`).
- Measured: `clojure -M:clj-kondo --lint` clean on `tools.cljs` + `cli.cljs`;
  `pnpm cljs:test` clean; suites run separately:
  - `logseq.api.db-based.property-write-test` -> `Ran 27 tests containing 170
    assertions. 0 failures, 0 errors.`
  - `logseq.api.db-based.issue6-test` -> `Ran 10 tests containing 41 assertions.
    0 failures, 0 errors.`
  - `logseq.api.db-based.tools-test` -> `Ran 37 tests containing 192 assertions.
    0 failures, 0 errors.`
  - `logseq.api.db-based.cli-test` -> `Ran 22 tests containing 107 assertions.
    0 failures, 0 errors.`
  - `electron.mcp-server-test` -> `Ran 4 tests containing 8 assertions.
    0 failures, 0 errors.`
- Obsolete tests updated: `property-dry-run-and-receipt-boundary` now asserts a
  verified property receipt with an observed value; the page-edit `:title` case in
  `build-upsert-nodes-edn-rejects-invalid-operations` now asserts the schema
  rejection; `upsert-nodes-receipt-rejects-mixed-operations-before-writing` uses a
  still-unsupported `tag` add and the current rejection message.

## Stage 4: parent-review gaps + HTTP/persistence acceptance (executed 2026-10-03)
- Gap 1 (`mcp_server.cljs`, `mcp_upsert.cljs`): upsertNodes description now states
  the actual block+page contract (UUID or exact qualified ident keys, typed
  `{uuid: ...}` refs, additive many, null/empty-array rejection, closed choices,
  opt-in observed receipts); example is a valid page-property edit with
  `:entityType` (was `:entity`); receipt schema is page/block opt-in. Boundary
  test `upsert-nodes-description-matches-supported-property-contract` added.
- Gap 2 (`tools.cljs`): `resolve-closed-choice`/`closed-choice-matches` support
  ident-less UUID closed values without `name`/`subs` nil errors and fail visibly
  on duplicate display matches; many closed values reported unsupported.
- Gap 3 (`tools.cljs`): `ref-target` rejects hidden targets and inherited
  recycled/hidden ancestry; `assert-allowed-target-class!` uses the shared
  inheritance-aware `db-db/class-instance?` identity check instead of `db/ident`.
- Gap 4 (`tools.cljs` `get-page-data`): default (top-level) projection now uses
  `entity->serializable` so reference-valued page properties (e.g.
  `logseq.property/status`, user node/many refs) carry stable title/uuid/ident
  over the real worker transit boundary, while still omitting `:block/tags`,
  `:block/refs`, internal `:db/id` and page-own pvalue pseudochildren. Regression
  asserts added in `property_write_test.cljs`.
- Gap 5: receipt verifier negative tests reject deliberately wrong observed
  values (`false`/`0`, missing ref/closed value, many subset mismatch) without
  rebuilding expectations from changed metadata.
- HTTP harness (`src/dev-cljs/electron/mcp_verify.cljs`): registered-tool run now
  covers real `listProperties` discovery plus `upsertNodes` page+block
  metadata/refs/receipt, public `getPage`/`getBlock`, invalid mixed batch with no
  partial writes, dry-run, malformed type, and property negative-control
  corruption. All recycle checks preserved.
- Real worker (`recycle_persistence_test.cljs`): new
  `typed-property-persists-across-worker-restart` writes built-in closed-value
  `status` to a block and page through the production
  `api-build-upsert-nodes-edn` -> `apply-outliner-ops :batch-import-edn` path,
  closes/reopens on-disk SQLite via `frontend.worker.db-worker-node`, and verifies
  stable observed closed-value uuids via `api-read-upsert-blocks` plus
  `api-get-block`/`api-get-page-data`. No direct SQLite writes.

### Stage 4 measured results
- `clojure -M:clj-kondo --lint` clean (0 errors/0 warnings) on `tools.cljs`,
  `property_write_test.cljs`, `recycle_persistence_test.cljs`, `mcp_verify.cljs`.
- `pnpm cljs:test` clean; suites run separately:
  - `logseq.api.db-based.property-write-test` -> `Ran 34 tests containing 208
    assertions. 0 failures, 0 errors.`
  - `logseq.api.db-based.tools-test` -> `Ran 37 tests containing 192 assertions.
    0 failures, 0 errors.`
  - `logseq.api.db-based.issue6-test` -> `Ran 10 tests containing 41 assertions.
    0 failures, 0 errors.`
  - `logseq.api.db-based.cli-test` -> `Ran 22 tests containing 107 assertions.
    0 failures, 0 errors.`
  - `electron.mcp-server-test` -> `Ran 5 tests containing 22 assertions.
    0 failures, 0 errors.`
  - `frontend.worker.recycle-persistence-test` -> `Ran 3 tests containing 94
    assertions. 0 failures, 0 errors.`
- HTTP harness: `clojure -A:cljs compile mcp-http-verify` -> `Build completed.
  (558 files, 6 compiled, 17 warnings)` (17 = pre-existing baseline); run
  `NODE_PATH=.../resources/node_modules node static/mcp-http-verify.js` ->
  `MCP-VERIFY-NEGATIVE-CONTROL 14`, `MCP-VERIFY-OK`, EXIT 0.

## Stage 5: lint gate close-out and function refactor (executed 2026-10-03)

The earlier "Remaining gates" note was stale. Its claim that the full gate's only
failures were `electron/embedding_server_test.cljs` path separators was written
before that file was fixed (Deliverable C) and before the gate got past
`lint:large-vars`. Corrected, measured status:

- `bb lint:large-vars` initially failed on four >100-line vars:
  `run-scenario!` (184) and `user-property-assertions` (128) in `mcp_verify.cljs`,
  `user-property-persists-across-worker-restart` (148) in
  `recycle_persistence_test.cljs`, and `upsert-nodes` (128) in `cli.cljs`.
  Refactored into cohesive helpers (see 10-…-verification.md "Function refactor");
  `bb lint:large-vars` now passes.
- Corrected measured support/reject accounting is maintained in
  `10-generic-http-persistence-verification.md` (final A/B/C results plus the
  support/reject table and the unverified Electron IPC / legacy `getPage` block
  UUID-projection limitation).
- Requirement status after this stage: status-only and typed user properties are
  accepted on the HTTP and worker paths; the Electron IPC path remains
  **unverified**; the legacy `getPage` `:blocks` projection still leaks opaque ref
  UUID objects (reported, not normalized).

## Remaining gates
- Electron IPC property write/readback still unverified (HTTP harness uses the
  local-db worker stub only; no Electron IPC).
- Full `bb dev:lint-and-test` now gets past `lint:large-vars` (passes) and
  clj-kondo (0 errors, 0 warnings) and reaches the CLJS suites. It finishes with
  **EXIT=1** due only to two pre-existing, unrelated Windows-environment groups in
  unmodified namespaces:
  - `logseq.api.plugin-test`: 11 failures, POSIX-literal fixtures vs native
    Windows `path.join` output.
  - `logseq.api.db-test`: 5 failures, `document is not defined` (no jsdom) and
    `Unhandled test worker api: :thread-api/query-dsl-query`.
  The former `electron/embedding_server_test.cljs` failures were fixed in
  Deliverable C. All property/recycle/API suites are green; see
  `scratch/10-full-gate-final.txt` and `scratch/10-suite-final.txt`.
- `git diff --check` clean (only a CRLF-normalization notice for an existing
  test file).
