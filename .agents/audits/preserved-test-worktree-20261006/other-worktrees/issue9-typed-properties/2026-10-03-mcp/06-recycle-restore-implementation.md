# 06 Agent-only block recycle/restore — implementation report

Status: **implemented at source level, unit/backend tested.** The 07 pass
exercised the real registered MCP HTTP transport (real SDK + StreamableHTTP
listener) against the real renderer API/CLI/outliner functions, but over an
**in-process local-db worker stub** (`logseq.api.test-helper` /
`with-plugin-api`), i.e. **not** a real worker-thread/IPC transport and **not**
SQLite persistence; see `07-recycle-integration-verification.md` and the
corrected labels there. A later pass (`08-real-worker-persistence-verification.md`)
adds a genuine `db-worker-node` + on-disk SQLite recycle/restore/close-reopen
fixture. Every claim below is labelled as *source-level edit*, *executed
evidence*, or *design/inference*. The label applies to the specific layer it
names; do not read a *source-level edit* as an *executed* result. Nothing here
claims RECYCLE-8 / RESTORE-8 full completion. Still **uncovered** after 08: the
Electron-renderer IPC transport (see "Remaining gates" and 08's coverage
boundary table). The executed unit/backend namespaces below exercise the
in-process worker/CLI path with a test DB; the 07 harness exercises the real
registered HTTP transport but not the renderer IPC bridge; 08 exercises the real
node worker + on-disk SQLite through its HTTP invoke endpoint.

Branch: `fix/issue-9-mcp-properties-wip`, baseline HEAD
`f2958757d211ef16725a0b54d07275baaa686029` (uncommitted combined MCP work
preserved; pre-existing property/search/read/parent/receipt edits untouched).

## Scope delivered

Agent-only ordinary-block root + subtree soft recycle and restore:

- MCP tools: `recycleBlock`, `restoreBlock`, `getRecycledBlock`.
- Chain: `electron.mcp-recycle` adapter → `logseq.cli.<camel>` →
  `logseq.api` `^:export` snake_case → `logseq.api.db-based.cli/*` →
  `:thread-api/...` worker call → `logseq.api.db-based.tools/*` and
  `logseq.outliner.recycle/*`.
- Backend op: semantic `:recycle-blocks`; restore reuses `:restore-recycled`.

No permanent-delete API, no GUI normal-delete change, no new
schema/class/property, no raw SQLite/DataScript writes from Electron.

## Source-level changes

Backend (`deps/outliner/`):

- `src/logseq/outliner/recycle.cljs`:
  - `block-subtree` rewritten as root-first ordered DFS (group-by
    `:block/parent` + `ldb/sort-by-order`, pseudochildren included via
    `get-block-full-children-ids`); public `^:api subtree-uuids`.
  - `restore-order` now `[target-parent original-order]`: reuses a valid,
    non-occupied `original-order`, else regenerates via `next-child-order`;
    nil target yields nil order (page-root path).
  - `valid-order-key?` + `order-key-re` guard malformed stored keys.
  - `restore-target` computes `:position` (`:original-parent` /
    `:original-page-fallback` / `:page-root`).
  - `restore-result` maps the contract shape and derives
    `:position`/`:order` from the actual readback vs stored original value.
  - `restore!` returns a map (was `true`); errors for not-recycled / no target /
    no tx / missing block.
  - `ineligible-recycle-reason`, `recycle-result`, `^:api recycle!` with
    eligibility + whole-subtree preflight (page / tag / property / built-in /
    hidden / pseudochild / nested-page-subtree rejected before any write;
    already-recycled is an explicit no-op).
- `src/logseq/outliner/op.cljs`: ops-schema entry
  `[:recycle-blocks [:catn [:op :keyword] [:args [:tuple ::uuid ::option]]]]`
  and `apply-op!` case `(reset! *result (outliner-recycle/recycle! ...))`.
- `src/logseq/outliner/op/construct.cljc`: added `:recycle-blocks` to
  `semantic-outliner-ops`, canonicalize case, and stale-numeric case.

Renderer / worker / API:

- `src/main/frontend/modules/outliner/op.cljs`: `recycle-blocks!` builder
  (uses `current-user-delete-opts`).
- `src/main/frontend/worker/handler/cli.cljs`: `:thread-api/api-get-recycled-block`.
- `src/main/logseq/api/db_based/tools.cljs`: `get-recycled-block` with
  `:classify?` (`:state "active"` for an active ordinary block) and a
  `:page-uuid` (current Recycle location) field.
- `src/main/logseq/api/db_based/cli.cljs`: `recycle-block`, `restore-block`,
  `get-recycled-block` (+ private `get-recycled-block*`). Recycle preflight
  classifies then either no-ops, errors, or transacts + worker readback
  verifies. Restore preflight errors on active roots; recycled roots transact
  then read back page/parent from `api-get-block`.
- `src/main/logseq/api.cljs`: `^:export recycle_block`, `restore_block`,
  `get_recycled_block`.
- `src/electron/electron/mcp_recycle.cljs` (new): three thin adapters.
- `src/electron/electron/mcp_server.cljs`: registered `:recycleBlock`,
  `:restoreBlock`, `:getRecycledBlock` with strict `blockUuid` uuid schemas and
  documented result shape.

Tests:

- `deps/outliner/test/logseq/outliner/recycle_test.cljs`: focused regressions
  (malformed order, collision, top-level page order shape, GUI semantic page
  restore, recycle subtree/no-op/eligibility, restore result shape,
  apply-ops round trip).
- `src/test/logseq/api/db_based/cli_test.cljs`: recycle→getRecycled→restore
  round trip; repeated recycle no-op preserving metadata; ineligible-root and
  active-restore errors; get-recycled rejection of non-recycled/missing.
- `src/test/electron/mcp_server_test.cljs`: adapters forward `blockUuid` to the
  camelCase CLI method. These are adapter-forwarding tests; they do **not** load
  or exercise `electron.mcp-server`'s SDK tool registration. Registration
  (`:recycleBlock`/`:restoreBlock`/`:getRecycledBlock` schemas) was *source-level*
  until 07 installed the SDK and ran the real server; see 07 §4 for the executed
  registration + strict-schema evidence.
- Corrected count: the CLI namespace run is **22 tests / 107 assertions** (the
  earlier "22/105" was a transcription typo).
- `src/test/logseq/api/test_helper.cljs`: `api-get-recycled-block` worker stub.

## Executed evidence

| Command | Result |
| --- | --- |
| `pnpm exec nbb-logseq -cp test -m nextjournal.test-runner -n logseq.outliner.recycle-test` (workdir `deps/outliner`) | **26 tests / 120 assertions, 0 failures, 0 errors** |
| `bb dev:test -n logseq.api.db-based.cli-test` | **22 tests / 107 assertions, 0 failures, 0 errors** |
| `bb dev:test -n electron.mcp-server-test` | **4 tests / 8 assertions, 0 failures, 0 errors** |
| `clojure -M:cljs compile electron` | build completed, 0 warnings |
| `git diff --check` | no whitespace errors |

RED→GREEN history actually observed:

- The parent RED run (19 tests / 78 assertions, 5 failures: malformed `""` /
  `"!!!"` / `"a b"`, top-level page order invented, GUI page restore invented
  order) was reproduced by the parent; the backend fix made those pass.
- During CLI wiring the `:recycle-blocks` op initially failed `ops-validator`
  (`["<uuid-string>" {}]` vs `::uuid uuid?`); root cause was passing a string
  where the op schema requires a `uuid`; fixed with `(uuid uuid-string)` and
  re-run GREEN.

## Design/inference (NOT measured)

- **Order-collision defect is not independently reproduced.** The original
  algorithm was read as reusing `original-order` without checking occupancy;
  the fixed path's collision regression passes, but no test proves a duplicate
  sibling key existed before the fix. Treat as design expectation.
- **Read/search invisibility is partially tested.** The recycled root is
  unreadable through `getBlock` (asserted), and the recycled subtree disappears
  from the original page's `getPageData` tree and reappears after restore
  (asserted in the round-trip test). Cross-page `list`/`search` exclusion is
  **not** asserted here.

## Remaining gates (partial, not fabricated as pass)

1. HTTP synthetic-graph test through the real registered MCP server: **CLOSED by
   07 for the MCP protocol + API-semantics layer only** — SDK
   `@modelcontextprotocol/sdk@1.29.0` was installed from the declared manifest
   with `pnpm install --ignore-workspace --frozen-lockfile` (cwd `resources`),
   and the real server was exercised over `StreamableHTTPServerTransport`
   (`MCP-VERIFY-RESULT` in `07-recycle-integration-verification.md` §4). The API
   layer underneath, however, runs on the `logseq.api.test-helper` local-db
   worker stub (DataScript in-process), **not** a real worker thread/IPC. 08 adds
   a REAL `db-worker-node` + on-disk SQLite recycle/restore + close/reopen run.
   Adapter-only forwarding tests still exist separately.
2. SQLite close/reopen persistence of recycled rows: **CLOSED by 08** —
   `frontend.worker.recycle-persistence-test` starts the real `db-worker-node`,
   recycles + restores over the real `/v1/invoke` transport, closes the DB,
   stops the worker, restarts it against the same on-disk `db.sqlite`, and
   re-asserts recycled state and post-restore location. Earlier claim of "no
   safe seam found" was wrong; the documented `db_worker_node_test` fixture
   pattern was already available. Crash durability (unclean kill) still not
   tested.
3. Cross-page `list`/`search` invisibility + reappearance: **CLOSED by 07** for
   the real worker/search + FTS path (global, page-scoped, exact-block-scoped);
   see 07 §2 and `tools_test.cljs` retrieval/hiding tests.
4. Full `bb dev:lint-and-test`: **run by 07, EXIT 1** on 50 pre-existing,
   unrelated Windows/DOM failures (dev-lint itself 0 errors/0 warnings); see 07
   §5. The slice's focused namespaces are green and the CLJS build is
   warning-free.

## Honest status summary

- Source-level: backend op, eligibility, return maps, renderer/worker/CLI/API
  exports, MCP tools, and tests all edited on disk.
- Executed (in-process test DB / compiled output only): backend 26/120, CLI
  22/107, MCP adapter forwarding 4/8, electron compile — all green.
- Inferred/design: historical collision reproduction; Electron-renderer IPC
  transport.
- Complete: ordinary-block slice (source + in-process unit/backend tests), the
  registered-MCP HTTP transport path (07), and real node worker + on-disk SQLite
  close/reopen persistence (08). Still **uncovered**: renderer IPC transport
  (and crash, i.e. unclean-kill, durability). See 07/08 acceptance matrices.
