# 07 Recycle/restore verification gaps — integration report

Status: **all six dispatch items exercised; the ordinary-block recycle/restore
slice is verified through the real registered MCP HTTP server (real SDK +
StreamableHTTP listener) against the real renderer API/CLI/outliner functions,
but over an in-process local-db worker stub (`logseq.api.test-helper` /
`with-plugin-api`), not a real worker thread/IPC or SQLite.** This is **not**
"end-to-end through a real worker": the 07 harness bypasses worker transport and
durability. Two seams were originally reported uncovered; the SQLite
close/reopen durability seam has since been closed by 08
(`08-real-worker-persistence-verification.md`) using the real `db-worker-node` +
on-disk SQLite. The Electron-renderer IPC transport remains the one explicitly
uncovered seam. No issue is closed.

Worktree `D:/orca/workspaces/logseq/issue9-typed-properties`, branch
`fix/issue-9-mcp-properties-wip`, baseline HEAD
`f2958757d211ef16725a0b54d07275baaa686029`. All uncommitted changes preserved.

Labels used below: **[edit]** source-level edit, **[exec]** executed evidence,
**[design]** design/inference not measured.

Dispatch: `07-recycle-verification-dispatch.md`. Acceptance authority remains
`06-recycle-restore-contract.md` + `06-recycle-restore-dispatch.md`.

---

## 1. Shadow warning + 06 report corrections **[exec]**

- Fixed clj-kondo warning at `src/test/logseq/api/db_based/cli_test.cljs:543`:
  the `let` binding `second` shadowed `cljs.core/second`; renamed to
  `second-result`. Repo lint is now **0 errors / 0 warnings** (see §5).
- Corrected `06-recycle-restore-implementation.md`:
  - "ordinary-block slice complete: yes" contradicted the missing HTTP/
    persistence gates; status now states *source-level + unit-tested, not
    end-to-end verified* (and this 07 report supersedes that as of the HTTP
    run below).
  - CLI count `22/105` → `22/107` (was a transcription typo).
  - Stated explicitly that `electron.mcp-server-test` adapter tests only prove
    `blockUuid` forwarding; they do **not** exercise `electron.mcp-server` SDK
    registration. That gap is closed by the real HTTP run in §4.

## 2. Genuine recycle exclusion / retrieval / reappearance tests **[exec]**

Added to `src/test/frontend/worker/search_test.cljs` using the **real
worker/search path over a synthetic better-sqlite3 FTS DB**, not a stub
callback. The production sqlite wrapper (`platform/node.cljs` `exec-sql`, line
138) accepts the `#js {:sql :bind :rowMode}` object form, so the tests wrap the
raw better-sqlite3 handle's `.exec`/`.transaction` via a new
`better-sqlite-exec` helper + `create-search-sqlite!` (~line 1990). Without this
wrap, raw better-sqlite3 throws on the object form.

- `recycled-subtree-excluded-from-global-page-and-block-search` (line 2032):
  recycled root + descendant are absent from global block search, page-scoped
  search, and exact-block-scoped search; they reappear after restore.
- `recycled-blocks-are-absent-from-index-build-inputs` (line 2099): recycled
  blocks are not fed into index construction, so an incremental index does not
  resurrect them.
- `src/test/frontend/handler/search_test.cljs`:
  `search-blocks-by-page-uuid-rejects-recycled-page` (line 217) preserves the
  scoped-search contract that a recycled page uuid is rejected.
- `src/test/frontend/worker/handler/search_test.cljs` (new):
  `resolve-active-visible-page-uuid-rejects-recycled-and-hidden-ancestors`
  (line 111).
- `src/test/logseq/api/db_based/tools_test.cljs`:
  `get-recycled-block-distinguishes-retained-and-collected` (line 848) proves
  explicit `getRecycledBlock` retrieval vs a collected row, and the existing
  active `getBlock`/`getPage`/`list`-hiding tests remain green.

These assert behavior, not argument forwarding. **[design]** Cross-page
`list` exclusion beyond the page-uuid handler path is not separately asserted.

## 3. Backend subtree / preflight / order / eligibility / retention **[exec]**

New/extended `deps/outliner/test/logseq/outliner/recycle_test.cljs`:

- `restore-leaves-independently-recycled-descendant-recycled` (line 574):
  restoring a root does not clear `deleted-at` on a descendant that was
  independently recycled.
- `recycle-rejects-subtree-containing-page-before-any-write` (line 528):
  full-subtree preflight rejects a page nested in the subtree, and asserts **no
  partial write** (datoms unchanged; validate-before-transact).
- `restore-occupied-order-legacy-rule-would-have-collided` (line 330),
  `restore-block-regenerates-malformed-original-order` (line 351),
  `restore-top-level-page-preserves-order-shape` (line 371),
  `restore-without-valid-target-errors-and-leaves-recycled-state` (line 554):
  order collision, malformed base-62 key, page-root nil-target, and
  invalid-target-with-unchanged-DB.
- `gc-retention-boundary-is-deterministic` (line 600): deterministic
  retention/GC boundary (30-day window), documenting collected-not-found vs
  retained recovery. No infinite-undo claim.
- `restore-block-returns-contract-shaped-result` (line 481) + strict
  `get-recycled-block-rejects-non-recycled-and-pseudochild-input`
  (`cli_test.cljs:590`) pin the contract shape and eligibility.

**Restore return-shape regression (real bug found and fixed) [exec].** Because
`restore!` intentionally changed its return from boolean to a map, the broader
semantic-outliner regression
`frontend.worker.pipeline-test/recycle-ops-return-apply-result-test` (line 670)
failed. It asserted the old boolean. Updated it to assert the map
(`(= "restore" (:operation ...))`, `(= "active" (:state ...))`). Confirmed no
production caller depends on the boolean (`apply_txs.cljs`,
`replay_sync_sqlite.cljs`, `page.cljs` only transact the tx-data). Result:
`frontend.worker.pipeline-test` → **42 tests / 194 assertions, 0 failures/errors**
(`scratch/pipeline-test-07.txt`). No GUI ordinary-delete routing change.

## 4. Declared SDK install + real registered MCP HTTP verification **[exec]**

### Dependency install

`resources/package.json` declares `@modelcontextprotocol/sdk ^1.27.1`;
`deps/db-sync/package.json` declares `^1.29.0`; both were declared but not
installed. Installed the declared deps with the official frozen-lock workspace
command from the correct package root:

```
cwd: resources
pnpm install --ignore-workspace --frozen-lockfile
```

Result: `@modelcontextprotocol/sdk@1.29.0` + `zod@4.3.6` installed under
`resources/node_modules/.pnpm/`. No root dependency added, no lockfile change
(`git status --short resources/` is empty), no global change. `require.resolve`
verified for `server/mcp.js`, `server/streamableHttp.js`, `zod/v3` from
`resources`.

### Harness (real server, not a mock)

- `src/dev-cljs/electron/mcp_verify.cljs` (new): starts the **real** registered
  `electron.mcp-server` over `StreamableHTTPServerTransport`, drives a real MCP
  client, and runs `recycleBlock` → `getRecycledBlock` → `restoreBlock` against
  a synthetic graph through the true
  `client → HTTP → mcp-server → electron.mcp-recycle → logseq.cli.* → logseq.api
  → renderer API/CLI/outliner` chain. **Correction:** the API layer is backed by
  the `logseq.api.test-helper` local-db worker stub, which routes into DataScript
  in-process; it is **not** a real worker thread, IPC transport, or SQLite. So
  this proves MCP protocol + API semantics, **not** worker transport or
  durability. (08 covers the real worker + disk.) It is not transport-only in the
  sense that the MCP HTTP/SDK layer is real, but calling it an API→worker path is
  wrong.
- `shadow-cljs.edn`: opt-in `:mcp-http-verify` build (`:node-script`, output
  `static/mcp-http-verify.js`, main `electron.mcp-verify/-main`,
  `:closure-defines NODETEST true`, `:static-fns false`), explicitly **not** part
  of `:test` and not discovered by the `-test$` regex.
- Runtime `NODE_PATH=<repo>/resources/node_modules` is required because
  `static/tests.js`/scripts run from the repo root and cannot resolve the SDK
  otherwise. Eager shadow `SHADOW_IMPORT` of discovered test nses is why the
  harness ns is *not* named `*-test`.
- Harness bugs fixed during bring-up: server self-reference before binding
  (used a holder atom); `parse-text` now falls back to `{:raw text}` on JSON
  parse failure; `(aget args 0)` on a CLJS vector returns nil → `(nth args i nil)`.

### Executed HTTP result

```
NODE_PATH=<repo>/resources/node_modules node static/mcp-http-verify.js
```

Full output saved to `scratch/mcp-http-verify-result.txt`; `MCP-VERIFY-RESULT`:

- `tool-names` (10) includes `getBlock`, `getPage`, `getRecycledBlock`,
  `listPages`, `listProperties`, `listTags`, `recycleBlock`, `restoreBlock`,
  `searchBlocks`, `upsertNodes`; `has-required-block-uuid? true`.
- **recycle**: `{state "recycled", no-op false, affected-count 2, deleted-at,
  page-uuid = Recycle built-in}`, 2 affected uuids.
- **get-recycled**: subtree 2, original-page/parent/order + current page uuid.
- **restore**: `{state "active", operation "restore", position "original",
  order "original", parent-uuid = original page uuid}`, 2 affected.
- **schema-rejection**: `recycleBlock` with `blockUuid "not-a-uuid"` is rejected
  by the SDK/zod strict schema with JSON-RPC **-32602** `Input validation error:
  Invalid arguments ... Invalid uuid`.

This closes dispatch item 4 and the previously-unverified "registered-MCP HTTP
transport" gate from 06, **for the MCP protocol + API-semantics layers**. It does
**not** close worker transport or persistence; those are addressed by 08.

## 5. Gates **[exec]**

| Gate | Command | Result |
| --- | --- | --- |
| Fresh CLJS compile | `pnpm cljs:test` | EXIT 0; `:db-worker-node` and `:test` build with **0 warnings** (`scratch/cljs-test-07.txt`) |
| Focused namespaces | `pnpm cljs:run-test -n logseq.api.db-based.cli-test -n logseq.api.db-based.tools-test -n frontend.worker.search-test -n frontend.handler.search-test -n electron.mcp-server-test` | **148 tests / 602 assertions, 0 failures, 0 errors** (`scratch/run-test-07.txt`) |
| Backend nbb suite | `pnpm exec nbb-logseq -cp test -m nextjournal.test-runner -n logseq.outliner.recycle-test` (cwd `deps/outliner`) | **30 tests / 142 assertions, 0 failures, 0 errors** (`scratch/nbb-recycle-07.txt`) |
| Pipeline regression | `bb dev:test -n frontend.worker.pipeline-test` | **42 tests / 194 assertions, 0 failures, 0 errors** (`scratch/pipeline-test-07.txt`) |
| Repo clj-kondo | `clojure -M:clj-kondo --parallel --lint src --cache false` | **0 errors / 0 warnings** |
| Whitespace | `git diff --check` | EXIT 0 (only CRLF notice on `property_write_test.cljs`) |
| Full gate | `bb dev:lint-and-test` | **EXIT 1** — dev-lint 0/0; **50 pre-existing/unrelated failures, 0 errors** (`scratch/lint-and-test-07.txt`, `07b`) |

Full-gate failures, all unrelated to this slice and reproducing without the
recycle changes: `electron.embedding-server-test` (12, Windows path
`\users\...`), DOM `document is not defined` / `db/db?` asserts in
`logseq.api.editor-test`, `logseq.api.plugin-test`, and component/UI namespaces
(27 + 11 = 38), plus the Windows-path platform assertion group. The
`recycle-ops-return-apply-result-test` failure no longer appears. Per dispatch,
the full gate is **explicitly recorded as failing** and is not claimed green.

## 6. What is real vs what remains uncovered

**Real / executed coverage:**

- SDK install from declared manifests via frozen-lock workspace install.
- SDK tool registration, strict zod schemas, and JSON-RPC validation over a
  **real registered HTTP MCP server**.
- Real MCP protocol + renderer API/CLI/outliner recycle/restore/get-recycled
  semantics on a synthetic graph **over the in-process local-db worker stub**
  (DataScript), with readback verification for recycle, get-recycled, and
  restore. (Worker-thread transport and SQLite durability are not exercised here;
  see 08.)
- Real worker/search + FTS sqlite path for recycled exclusion/reappearance.
- Backend subtree/preflight/order/eligibility/retention and the restore
  return-map regression.

**Missing seams (reported, not fabricated):**

1. **Electron-renderer IPC transport.** The HTTP harness exercises
   `StreamableHTTPServerTransport` against the real tool registration and the
   real renderer API functions; it does **not** traverse the Electron
   `ipcMain`/preload renderer bridge. The safe harness uses an in-process
   `api-test/with-plugin-api` worker, not a live renderer window. Still
   uncovered after 08.
2. **SQLite close/reopen durability through `db-worker-node`.** Originally
   uncovered here. **Now CLOSED by 08**: a real `db-worker-node` fixture creates
   an on-disk graph, runs recycle/restore over the real `/v1/invoke` transport,
   closes the DB, stops the worker, restarts against the same `db.sqlite`, and
   re-asserts recycled state and post-restore location. Crash (unclean-kill)
   durability remains untested.

## Remaining acceptance matrix

| Contract clause | Status |
| --- | --- |
| Strict one-root tools, uuid `blockUuid`, MCP-only | **met** — real HTTP schema rejection -32602 |
| recycle/restore/get-recycled contract result shapes | **met** — real HTTP + API/CLI/backend tests |
| Validate-before-transact, no partial write | **met** — backend preflight test |
| Order collision / malformed key / page-root / invalid target | **met** — backend tests |
| Active-restore errors, recycle no-op idempotent | **met** — CLI tests |
| Independent descendant stays recycled | **met** — backend test |
| Retention/GC deterministic, retained vs collected | **met** — backend + tools tests |
| getBlock/getPage/list hiding; search exclusion/reappearance | **met** — worker/tools/handler tests |
| Real SDK registration + HTTP transport | **met** — `mcp-http-verify.js` |
| Production dispatcher (`electron.api-method`) for MCP API calls | **met** — harness uses `resolve-real-api-method`/`dispatch-real-api-method`; correction to prior manual case dispatch |
| Real renderer API/CLI/outliner semantics | **met** — `mcp-http-verify.js` (over local-db worker stub) |
| Actual worker-thread transport + on-disk SQLite persistence | **met** — 08 `frontend.worker.recycle-persistence-test` (recycle/restore + close/reopen) |
| Electron-renderer IPC transport | **uncovered** — missing seam 1 |
| Crash (unclean-kill) durability | **uncovered** — 08 tests normal close only |
| No RECYCLE-8 / RESTORE-8 / issue8 completion claim | honored |

## Honest status summary

The ordinary-block recycle/restore slice is implemented at source level, its
unit/backend/integration tests are green, and MCP-protocol + renderer
API/CLI/outliner semantics are verified through the real registered MCP HTTP
server. **Correction:** that API layer runs over an in-process local-db worker
stub, so 07 did **not** prove worker-thread transport or durability; report 08
adds a real `db-worker-node` + on-disk SQLite close/reopen fixture for the
recycle/restore slice. The slice is **not** verified through the Electron
renderer IPC bridge, and crash durability is untested. The full
`bb dev:lint-and-test` gate still fails on pre-existing, unrelated Windows/DOM
failures. No issue is closed.

## Evidence index (`scratch/`)

- `mcp-http-verify-result.txt` — real MCP HTTP lifecycle + schema result.
- `mcp-http-smoke.js` — pure-JS SDK+HTTP smoke (pre-harness sanity).
- `lint-and-test-07.txt`, `lint-and-test-07b.txt` — full-gate runs (tracking).
- `cljs-test-07.txt`, `run-test-07.txt`, `nbb-recycle-07.txt`,
  `pipeline-test-07.txt` — focused gate logs.
