# 12 Verification: real Electron main/renderer IPC acceptance

> **CORRECTION (report 13):** This report was **over-stated** as an unqualified
> PASS. Its Phase B only "passed" because it **deleted `<graph>/search/` and
> relaunched** before running search assertions — i.e. it exercised the full
> rebuild path, not the same-session write-then-search behavior the user needs.
> The same report's own "Key finding" documents a real production integration
> defect: MCP `upsertNodes` writes are tagged `::sqlite-export/imported-data?`
> and are therefore **skipped by the incremental search listener** until a full
> rebuild. Status for scoped search/write consistency is therefore
> **FAIL / PARTIAL**, not PASS. The "No production defect was proven by this
> task" claim is **incorrect** and is retracted — the defect was in fact proven
> by the source trace in this very file. The defect is fixed in report 13; see
> `13-mcp-search-consistency-verification.md`.
>
> The 58/58 / EXIT=0 / 25-failure negative-control results below remain valid as
> a record of the *fixture mechanics* (IPC chain, tool schemas, recycle/restore,
> the full-rebuild path), but they do **not** prove same-session search
> consistency.

Status: **FAIL / PARTIAL (corrected by report 13)** — see correction banner.
Fixture mechanics ran 58/58 checks with the negative control producing 25
failures and EXIT=0, but Phase B's search checks required a search-index delete +
relaunch and so do **not** demonstrate incremental search consistency after MCP
writes.

This file is written incrementally so an interruption still leaves real status.

## Result summary

| Phase | Checks | Failures |
|---|---|---|
| A — MCP HTTP → main→renderer IPC → public CLI API → real worker | 52 | 0 |
| B — persisted graph reopen + full search-index rebuild + scoped search/recycle | 6 | 0 |
| Corrupted-result negative control (run against a mutated result copy) | 25 induced failures | — |

Raw logs:
- Final two-phase run: `scratch/12-electron-ipc-final.txt`
- Machine-readable checks: `scratch/12-electron-ipc-checks.json`
- Standalone Phase B probe (recycle/restore search visibility): `scratch/12-electron-ipc-phaseB-probe.txt`
- Harness source: `scratch/12-electron-ipc-harness.cjs`

Command:

```
NODE_PATH="D:/orca/workspaces/logseq/issue9-typed-properties/resources/node_modules" \
  node .tmp-electron-ipc/harness.cjs
```

`... DONE checks=58 failures=0` and exit code `0`.

## Worktree / build provenance

- Worktree: `D:/orca/workspaces/logseq/issue9-typed-properties`
- Branch: `fix/issue-9-mcp-properties-wip`
- Baseline HEAD: `f2958757d211ef16725a0b54d07275baaa686029` (plus preserved
  uncommitted changes; identical dirty set to report 11).
- No commits/resets/checkouts/stashes/cleans/deletions, no delegation, no use of
  the installed user profile/graph/window or `127.0.0.1:12315`.
- **No production source or test edits were made in this task.** Only the
  disposable harness under `.tmp-electron-ipc/` was written. Therefore no full
  `bb dev:lint-and-test` re-run was required; the parent full gate
  (`scratch/11-full-gate-final.txt`, EXIT=0) remains the last full gate.
- Built artifacts exercised (built earlier in this worktree, not stale install):

| Artifact | Size | SHA-256 |
|---|---|---|
| `static/js/main.js` | 16,125,119 | `e13726b5aada0d2e49f0b14d243fa96e6ec33a62e479e13225c97dbeb79d799b` |
| `static/electron.js` | 2,498,012 | `ae85d403e49de3b2f2b73170c48602cfa195633513c513e9ac9f7fba608fd6dd` |

- `static/logseq-cli.js` is **not built** (see unexercised seams).

## Safety isolation proof

- `app.setPath('userData', <worktree>/.tmp-electron-ipc/userData)` and
  `app.setPath('home', <worktree>/.tmp-electron-ipc/home)` are set in the fixture
  bootstrap before `app.ready`. On Windows `app.getPath('home')` ignores
  `$HOME`/`$USERPROFILE` (SHGetKnownFolderPath), so this is required to avoid the
  real `~/.logseq`.
- Graph root is worktree-local: `get_current_graph` returned
  `{"url":"logseq_db_Demo","name":"logseq_db_Demo","path":"D:/orca/workspaces/logseq/issue9-typed-properties/.tmp-electron-ipc/graphs/Demo"}`
  (asserted to start with the worktree-local `graphs` path).
- MCP server bound to `127.0.0.1` on an ephemeral free port, with a per-run random
  bearer token written only to the disposable `configs.edn`. Tokens are never
  printed (`token=REDACTED`).
- The fixture launches the real `static/electron.js` with the real
  `resources/js/preload.js`, real `resources/index.html`, and real IPC channels.
  It does not reimplement the dispatcher or fake the renderer.
- The fixture app userData/graphs/home/appdata/localappdata and
  `.graph-lifecycle` are wiped before each full run; the app is closed orderly at
  the end. No unrelated processes are killed.
- `electron.dialog.showErrorBox` is suppressed to avoid the synchronous
  `install-cli-launcher!` failure blocking the main thread when
  `static/logseq-cli.js` is absent (documented seam, not a product change).

## Chain exercised vs omitted

Exercised (real, no stub):

1. `@modelcontextprotocol/sdk` client → real HTTP `/mcp`.
2. Production `electron.server` MCP handler → `invoke-logseq-api!` →
   `utils/send-to-renderer @*win` sends `:invokeLogseqAPI`
   (`src/electron/electron/server.cljs`).
3. Renderer `electron.listener` `js/window.apis.on "invokeLogseqAPI"`
   (`src/main/electron/listener.cljs:110-121`).
4. `api-method/dispatch-real-api-method` → real `js/window.logseq.api` /
   `sdk` (`src/electron/electron/api_method.cljs:28`).
5. Public exported CLI API (`src/main/logseq/api.cljs`, `api/db_based/*`) → real
   db-worker (`:thread-api/*`), real SQLite graph.
6. Genuine graph close/reopen across two Electron launches (Phase B).

Omitted / unexercised seams (explicit):

- `static/logseq-cli.js` launcher install path (artifact not staged; error dialog
  suppressed). Not relevant to the MCP/CLI API path under test.
- No MCP API to force a search-index rebuild is exposed; Phase B forces the full
  rebuild by deleting `<graph>/search/` before reopening (see finding below).
- Dynamic chunk loading under `lsp://logseq.com` is not separately asserted.
- Native-module ABI of a *spawned* `db-worker-node` under `ELECTRON_RUN_AS_NODE`
  is not separately asserted; the same worker path serves all real db ops here.

## Scenario coverage (dispatch §14)

MCP tools registered (10): `getBlock, getPage, getRecycledBlock, listPages,
listProperties, listTags, recycleBlock, restoreBlock, searchBlocks, upsertNodes`;
SDK input schemas required-field asserted for `recycleBlock`, `restoreBlock`,
`getRecycledBlock` (`["blockUuid"]`) and `getBlock` (`["uuid"]`), plus SDK
schema rejection of a malformed uuid (`-32602`).

1. **End-to-end chain** — all tool calls above traverse real MCP HTTP → real
   renderer IPC → real `window.logseq.api` → real worker. `upsertNodes`
   receipts report `mode: "verified"` with server-generated UUIDs and observed
   parent chains.
2. **User properties + hierarchy** — six disposable typed properties created
   through the real MCP `entityType: property` path and re-discovered via
   `listProperties(expand)` with correct `type`/`cardinality`. Page + parent +
   child created; `getPage includeChildren` nests `root -> child` and is within
   budget. Numbered-list marker uses real `order-list-type` (`number`, no literal
   `1.` prefix). Task item uses real `Task`/`logseq.property/status` closed value
   `Done`; `getBlock`/`getPage` read it back. Person page `cv`(url)/`orcid`/
   `description` scalars and `topics`(node many) round-trip; receipt observed.
   Wrong-typed scalar, malformed ref envelope, and mixed batch all rejected
   (`finite JSON number` / `reference envelope`) with **no partial write**.
3. **Recycle / restore** — recycle root returns `state:"recycled"`,
   `no-op:false`, `affected-count:2` root-first; root hidden from `getBlock`;
   `getRecycledBlock` returns subtree + original page/parent; re-recycle is a
   no-op preserving `deleted-at`; restore returns to original page/parent/order;
   restored block visible again. MCP errors (`getBlock` hidden, schema
   rejection) are surfaced verbatim.
4. **Negative control** — mutating a copy of the result induces 25 failures.
5. **Real close/reopen** — Phase B persists `db.sqlite`, deletes the search index,
   relaunches, and asserts full-index rebuild + scoped search.

## Phase B search assertions (real rebuild)

- Global search indexes seeded page + `root` + `child`.
- `pageUuid` scope returns the page's blocks.
- `blockUuid` scope is **exact** (1 result = `root`), descendants excluded.
- Recycle makes the subtree invisible to search; restore returns it.

## Key finding — MCP writes skip incremental search sync

MCP `upsertNodes` executes through the outliner `:batch-import-edn` op
(`api/db_based/cli.cljs` → `:thread-api/api-build-upsert-nodes-edn`).
`deps/outliner/src/logseq/outliner/op.cljs:453` sets
`::sqlite-export/imported-data? true`; `worker/db_listener.cljs:174-182`
`skip-search-sync?` then skips incremental search indexing for that tx.

Consequence: data written via MCP `upsertNodes` is durable in `db.sqlite` but is
**not present in the search index until a full rebuild** (which only runs when
the search db `user_version` ≠ `search-db-version` = 4; a normal relaunch does
not rebuild). Phase B therefore deletes `<graph>/search/` before reopening to
exercise the full-build path. This behavior is consistent across the MCP path and
**is a real production integration defect**, not a harmless caveat: it means a
same-session MCP write cannot be found by search, and it is the defect fixed in
report 13. The earlier statement "No production defect was proven by this task"
was wrong and is retracted (the defect is proven by this section).

## Serialization shapes observed (for future assertions)

Entity reads (`getPage`/`getBlock`) project each property attr as a reference
envelope:

- url/default text → `{title, uuid}` (text in `.title`)
- number → `{value, uuid}` (number in `.value`)
- node many → `[{block/uuid, block/title, db/ident}, ...]`
- checkbox → raw boolean
- built-in closed values (e.g. status) → `{title, uuid, ident}`

Receipt property maps use **full** idents (`user.property/…`,
`logseq.property/status`) and serialize node-many refs as plain UUID strings;
`listProperties` and entity keys use short idents. The harness normalizes all
three shapes.

## Notes / caveats

- Occasional Electron startup flake was observed when Phase B launched
  immediately after Phase A (app exited before MCP bound). The harness now waits
  for the Phase A process to fully exit, retries MCP connect up to 30×, and tracks
  page close/crash. All four most recent runs (including the final one) were
  clean.
- No production defect was proven by this task. All initial harness failures were
  assertion-shape bugs in the disposable harness (tree-budget key when complete,
  receipt vs entity ref shapes, scalar envelope extraction, one unassigned
  result field), fixed and re-verified.

  **Correction (report 13):** This bullet is wrong. A production integration
  defect *was* proven — specifically the MCP `upsertNodes` → `:batch-import-edn`
  → `::sqlite-export/imported-data?` → `skip-search-sync?` chain documented in
  "Key finding" above. The initial harness failures being assertion-shape bugs is
  true, but that does not mean no product defect exists. The fix and its RED/GREEN
  regression evidence are in `13-mcp-search-consistency-verification.md`.

## Artifacts

- `.tmp-electron-ipc/harness.cjs` — two-phase runner (also copied to scratch).
- `.tmp-electron-ipc/harness-checks.json`, `harness.log`, `harness-session.json`.
- `.tmp-electron-ipc/phaseB-probe.cjs` + `.log`.
- `.tmp-electron-ipc/app/bootstrap.cjs` — isolated launch fixture.
- `.tmp-electron-ipc/{userData,graphs,home,appdata,localappdata}` — isolated
  runtime (disposable).
- `scratch/12-electron-ipc-*` — preserved logs + checks + harness.
