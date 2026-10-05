# 08 Correct MCP verification + real worker/SQLite persistence — final report

Status: **dispatch items 1–7 exercised.** The false "real API→worker / end-to-end"
claims in reports 06/07 are corrected. The MCP harness is now a strict assertion
suite over the **production method resolution/dispatch**
(`electron.api-method`) with a real SDK + StreamableHTTP server and an explicit
negative control; it runs over the in-process local-db worker stub and is
labelled as such. A separate fixture drives the recycle/restore slice through a
**real `db-worker-node` process and on-disk `node:sqlite` `db.sqlite`**, including
normal close + worker restart + reopen readback. The **Electron-renderer IPC**
transport and **crash (unclean-kill) durability** remain explicitly uncovered.
No issue is closed.

Worktree `D:/orca/workspaces/logseq/issue9-typed-properties`, branch
`fix/issue-9-mcp-properties-wip`, baseline HEAD
`f2958757d211ef16725a0b54d07275baaa686029` (+ preserved uncommitted changes).
Runtime: Windows, Node (`node:sqlite`), shadow-cljs, DeepSeek v4.1 Flash.

Labels: **[edit]** source edit, **[exec]** executed evidence, **[design]**
design/inference not measured.

Dispatch: `08-real-worker-verification-dispatch.md`. Acceptance authority:
`06-recycle-restore-contract.md` + `06-recycle-restore-dispatch.md`.

---

## 1. Corrected false claims (06/07) **[edit]**

Source facts that triggered the correction:

- `src/dev-cljs/electron/mcp_verify.cljs` required `logseq.api.test-helper` and
  wrapped its scenario in `api-test/with-plugin-api`.
- `src/test/logseq/api/test_helper.cljs` docstring: *"Local-db worker stubs so
  plugin API tests can run without a db worker."* Its handler routes directly
  into DataScript in-process — **no worker thread, no IPC, no SQLite**.
- The old `-main` printed `MCP-VERIFY-RESULT` and `process.exit 0` for *any*
  resolved result, with no behavior assertions, and used a hand-written `case`
  dispatcher that duplicated `resolve-real-api-method`.

Corrections applied:

- `07-recycle-integration-verification.md`: header, §4 harness bullet, and
  "closes item 4" now scope the verification to **MCP protocol + renderer
  API/CLI/outliner semantics over the local-db worker stub**, explicitly *not*
  worker transport or durability. Coverage table and honest status rewritten;
  missing seam 2 (SQLite close/reopen) marked **CLOSED by 08**; seam 1
  (Electron IPC) still uncovered.
- `06-recycle-restore-implementation.md`: status changed from
  "ordinary-block slice complete: yes" to source-level + unit-tested + 07 MCP
  transport over worker stub; **gate 2 (SQLite close/reopen) closed by 08**;
  remaining gates reframed (renderer IPC + crash durability uncovered).
- `src/dev-cljs/electron/mcp_verify.cljs` docstring corrected to state the
  local-db stub boundary.
- Real transport evidence (previous `MCP-VERIFY-RESULT`, SDK lifecycle, tool
  registration) was preserved, not erased.

## 2. Production method resolution/dispatch **[edit]**

- New pure ns `src/electron/electron/api_method.cljs`:
  `type-proxy-api?`, `resolve-real-api-method`, `dispatch-real-api-method`
  (no electron/renderer deps).
- `src/electron/electron/server.cljs` now delegates to
  `api-method/resolve-real-api-method`; local copy + `camel-snake-kebab.core`
  removed.
- `src/main/electron/listener.cljs` `invokeLogseqAPI` now calls
  `api-method/dispatch-real-api-method`, preserving the `ret-fn!`/error shape.
- `mcp_verify.cljs` uses the same production resolver/dispatcher instead of the
  prior manual case dispatch. The `real-api` table mirrors the `^:export` CLI
  entries in `src/main/logseq/api.cljs` so the harness dispatches into the same
  fns the renderer exposes (not a re-implementation of behavior).

## 3. Asserted MCP harness + negative control **[exec]**

`src/dev-cljs/electron/mcp_verify.cljs`: real MCP client SDK → real
`electron.mcp-server` StreamableHTTP transport → production dispatcher →
renderer API/CLI → outliner recycle implementation (over the local-db stub).

- **21 `check` assertions** covering: all 10 required tool names; strict
  `blockUuid`/`uuid` input schemas; getBlock visibility before recycle; recycle
  `state "recycled"` + `no-op false` + `root-uuid` + root-first affected uuids +
  `deleted-at`; getBlock invisibility while recycled (error text, not nil-title
  success); `getRecycledBlock` `subtree`/`subtree-count`/original page+parent;
  re-recycle `no-op true` + `reason "already-recycled"` preserving `deleted-at`;
  restore `state "active"` + root/page/parent/position/order; post-restore
  getBlock visibility; malformed uuid rejected with SDK `-32602`.
- `-main` **exits 0 only if all 21 checks pass AND the negative control fails
  > 0**; otherwise it prints `MCP-VERIFY-FAIL` and `process.exit 1`. Parse
  failure is treated as a check failure (`parse-text` returns `{:raw text}` so
  assertion details show the raw text), never as success.
- **Negative control** (`negative-control-failures`, harness-local only, no
  production behavior change): corrupts 7 fields (`recycle.state`,
  `recycle.affected-count`, `get-recycled`, `recycle-noop.no-op`,
  `restore.state`, post-restore title, `schema-rejection`) and re-runs the same
  assertion set; the count of newly failing checks must be positive or `-main`
  fails.

Executed (`scratch/mcp-http-verify-08.txt`):

```
NODE_PATH=D:/orca/workspaces/logseq/issue9-typed-properties/resources/node_modules \
  node static/mcp-http-verify.js
MCP-VERIFY-CHECKS [ ... 21 entries, all :ok? true ... ]
MCP-VERIFY-NEGATIVE-CONTROL 9
MCP-VERIFY-OK
EXIT 0
```

Build (`scratch/compile-mcp-verify-08.txt`):

```
clojure -A:cljs compile mcp-http-verify
[:mcp-http-verify] Build completed. (558 files, 4 compiled, 17 warnings, 7.70s)
EXIT 0
```

(The 17 warnings are `:infer-warning`s on Node HTTP shim interop; the pre-08
harness had the same class of warnings. No new functional warning.)

**[design]** Property discover/write/read and scoped search are covered by their
own real tests (`property_write_test`, `search_test`,
`frontend.worker.handler.search-test`) rather than by extra MCP tool calls; no
unrelated property-type expansion was added.

## 4. Real worker + on-disk SQLite recycle/restore **[exec]**

`src/test/frontend/worker/recycle_persistence_test.cljs` (new) starts the real
`frontend.worker.db-worker-node` daemon and talks to it over HTTP `/v1/invoke`
(`{:method :argsTransit (ldb/write-transit-str args)}`), with an on-disk graph
created via `logseq.cli.root-dir` + `@logseq/graph-lifecycle`
(`resolveStorage`/`createGraph`). No stub store, no synthesized rows: the worker
applies the real outliner ops and the real `platform/node.cljs` `node:sqlite`
`DatabaseSync` persists them.

Two `deftest`s:

1. `recycle-restore-roundtrip-through-real-worker` — transacts a page + root
   block (`order "a0"`) + nested child + sibling (`order "b0"`), then
   `:thread-api/apply-outliner-ops [[:recycle-blocks [root {}]]]` →
   `:thread-api/api-get-recycled-block` →
   `[[:restore-recycled [root]]]` → `:thread-api/api-get-block`. Asserts the
   full result contract (recycle `state`/`affected-count` root-first,
   `getRecycledBlock` subtree/original page+parent, restore
   `position`/`order`/`parent`/`page`, post-restore `:block/parent`,
   `:block/page`, `:block/order`).
2. `recycled-state-persists-across-worker-restart` — recycles, then
   `thread-api/close-db`, asserts `db.sqlite` **exists on disk and has positive
   size**, stops the worker, starts a **new** worker against the same root/repo,
   reopens, and re-reads recycled state (`state`, `subtree-count`,
   `original-page-uuid`, subtree set) **before** restoring and asserting the
   restored location.

Result (`scratch/focused-recycle-08.txt`):

```
LOGSEQ_STABLE_IDENTS=1 node static/tests.js -n frontend.worker.recycle-persistence-test
Ran 2 tests containing 65 assertions.
0 failures, 0 errors.
EXIT 0
```

This closes 06 gate 2 (SQLite close/reopen persistence) **for the recycle/restore
slice** and closes 07 missing seam 2. It does **not** test crash (unclean-kill)
durability: the fixture uses normal DB close + worker stop/restart only.

## 5. Coverage boundary

| Layer | Exercised? | Evidence |
| --- | --- | --- |
| Real MCP client SDK + StreamableHTTP transport | **yes** | `mcp_http_verify` exit 0; real init/tools-list/call lifecycle |
| Production method resolution/dispatch (`electron.api-method`) | **yes** | harness + `server.cljs`/`listener.cljs` delegation |
| Real renderer API/CLI/outliner functions | **yes** | harness (10 tools), 21 assertions |
| DataScript semantics (in-process) | **yes** | harness over local-db worker stub |
| Electron-renderer IPC (`ipcMain`/preload bridge) | **no** | harness uses in-process API stub, no live renderer window |
| Actual worker-thread transport (`db-worker-node` `/v1/invoke`) | **yes** | `recycle_persistence_test` 2 tests / 65 assertions |
| On-disk SQLite persistence (`node:sqlite` `db.sqlite`) | **yes** | restart test asserts file exists + positive size + reloaded state |
| Normal close/reopen durability | **yes** | restart test |
| Crash (unclean-kill) durability | **no** | not tested by design |

## 6. Gates **[exec]**

| Gate | Command | Result |
| --- | --- | --- |
| clj-kondo (touched files) | `clojure -M:clj-kondo --lint src/dev-cljs/electron/mcp_verify.cljs src/electron/electron/api_method.cljs src/electron/electron/server.cljs src/main/electron/listener.cljs src/test/frontend/worker/recycle_persistence_test.cljs` | **0 errors / 0 warnings** |
| Whitespace | `git diff --check` | EXIT 0 (only CRLF notice on `property_write_test.cljs`) |
| MCP harness compile | `clojure -A:cljs compile mcp-http-verify` | EXIT 0; 558 files, 4 compiled, 17 infer warnings |
| MCP harness run | `node static/mcp-http-verify.js` (NODE_PATH resources) | EXIT 0; 21 checks pass; negative control 9 |
| Real worker + SQLite | `node static/tests.js -n frontend.worker.recycle-persistence-test` | **2 tests / 65 assertions, 0 failures, 0 errors** |
| CLJS CLI/tools | `node static/tests.js -n logseq.api.db-based.cli-test -n logseq.api.db-based.tools-test` | **58 tests / 297 assertions, 0 failures, 0 errors** |
| CLJS property/pipeline/search/parent | `node static/tests.js -n logseq.api.db-based.property-write-test -n frontend.worker.pipeline-test -n frontend.handler.search-test -n frontend.worker.search-test -n frontend.worker.handler.search-test` | **146 tests / 591 assertions, 0 failures, 0 errors** |
| Backend outliner (nbb) | `pnpm exec nbb-logseq -cp test -m nextjournal.test-runner` (cwd `deps/outliner`) | **152 tests / 754 assertions, 0 failures, 0 errors** |
| Full gate | `bb dev:lint-and-test` | **EXIT 1** — 12 failures / 0 errors, all in `electron.upload`→`electron.embedding-server-test` Windows path separators |

### Full-gate honest status (no baseline overclaim)

The 12 remaining full-gate failures are all in
`electron/embedding_server_test.cljs` (e.g. expected
`"/users/me/logseq/embedding-server"` vs actual
`"\\users\\me\\logseq\\embedding-server"`) — i.e. Windows path-separator
assertions in a namespace untouched by this work (`git status` shows no changes
to `src/electron/electron/embedding*` or its test). They also appear with the
identical test cases/line numbers in the pre-08 run
`scratch/lint-and-test-07b.txt`. I did **not** run the baseline commit in a
second worktree (dispatch forbids external-worktree use and
commit/checkout/reset), so "pre-existing" here rests on the untouched-namespace +
identical-prior-run evidence, **not** a strict baseline checkout. The gate is
recorded as **failing** and is not claimed green. This matches the prior 07
observation of ~50 pre-existing failures (07 also counted DOM/`document is not
defined` failures); the 08 run captured only the embedding-server group within
the tail-captured log, and the full log
(`scratch/lint-and-test-08-full.txt`) confirms 12 failures / 0 errors across the
run.

## 7. Remaining gates

1. **Electron-renderer IPC transport** — not traversed by any fixture. The MCP
   harness uses an in-process API stub; the persistence fixture talks directly
   to `db-worker-node`. A disposable renderer fixture was not attempted (no safe
   documented live-renderer harness available in this slice), so this remains
   explicitly unverified.
2. **Crash durability** — only normal close + restart is tested; unclean-kill
   recovery is not.
3. **Full `bb dev:lint-and-test`** — still red on the pre-existing, unrelated
   Windows-path embedding-server failures above.

No issue-completion claim is made (no RECYCLE-8 / RESTORE-8 / issue-8 closure).

## 8. Evidence index (`scratch/`)

- `mcp-http-verify-08.txt` — real MCP lifecycle, 21 checks, negative control 9,
  `MCP-VERIFY-OK`.
- `compile-mcp-verify-08.txt` — `:mcp-http-verify` build log.
- `focused-recycle-08.txt` — real-worker SQLite tests (2/65).
- `focused-cli-tools-08.txt` — CLI/tools (58/297).
- `focused-other-08.txt` — property/pipeline/search/parent (146/591).
- `outliner-test-08.txt` — backend nbb suite (152/754).
- `lint-and-test-08-full.txt` + `lint-and-test-08.txt` — full-gate runs
  (EXIT 1; 12 embedding-server Windows-path failures).
