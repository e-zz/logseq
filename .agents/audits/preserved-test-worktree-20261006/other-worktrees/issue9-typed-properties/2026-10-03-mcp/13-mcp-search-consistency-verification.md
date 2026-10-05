# 13 Verification: MCP runtime writes now index into search (same-session)

Status: **PASS (fix verified end-to-end)** — the proven production integration
defect (MCP `upsertNodes` writes skipped incremental search indexing) is fixed and
verified same-session through the real HTTP → Electron IPC → renderer → node
db-worker chain, with no search-index deletion or forced rebuild. Full gate green
(clj-kondo 0/0; all suites 0 failures/0 errors; EXIT=0).

This report corrects the report12 "bypass" conclusion and records the actual
baseline RED, the fix, bulk-import compatibility, isolation proof, exact
commands/artifacts, and remaining limitations.

Written incrementally.

---

## 0. Critical correction: why the fix looked broken for several runs

Earlier interim runs (`13d`/`13e`) failed the same-session search checks even
though the marker existed in `static/db-worker-node.js`. That conclusion was
**wrong** and is retracted. The release loader does **not** load
`static/db-worker-node.js`; it loads `static/js/db-worker-node.js`:

- `src/main/logseq/cli/server.cljs:45-53` — in a release (`goog.DEBUG` false)
  build, `db-worker-release-script-path-from js/__dirname` resolves to
  `<static>/js/db-worker-node.js`.
- `scripts/prepare-desktop-runtime-js.mjs:33-38` copies `dist/db-worker-node.js`
  → `static/js/db-worker-node.js`.
- `scripts/build-db-worker-node-bundle.mjs:13` bundles the shadow output
  `static/db-worker-node.js` → `dist/db-worker-node.js` (vite/terser).

The failing runs loaded a **stale** `static/js/db-worker-node.js` built at
`2026-10-03T09:29:48Z` (confirmed by the worker's own
`db-worker-node-version` log line), i.e. **before the fix source was written**
(source edits are at 18:47/18:55 local = 10:47/10:55Z). That artifact contained
**zero** occurrences of `logseq.outliner.op/runtime-write`:

```
$ grep -c logseq.outliner.op/runtime-write static/js/db-worker-node.js   # stale -> 0
$ grep -c logseq.outliner.op/runtime-write static/db-worker-node.js      # my rebuild -> 1
```

So the earlier failures were against a genuinely **unfixed** worker. This mistake
also provides a legitimate integration baseline RED (see §2). The fix itself was
correct all along; only the artifact staging was wrong.

---

## 1. Result summary

| Evidence | Result |
|---|---|
| Focused unit regression `logseq.api.db-based.mcp-search-test` | PASS (1 test / 3 assertions) |
| Focused unit regression `frontend.worker.db-listener-test` | PASS |
| Full gate `bb dev:lint-and-test` | EXIT=0; clj-kondo errors 0 / warnings 0; all suites 0 failures, 0 errors |
| Real harness, same-session write→search (Phase A) | 60/60 checks, 0 failures |
| Real harness, full two-phase (A + B) | **65/65 checks, 0 failures** |
| Corrupted-result negative control | 31 induced failures (harness fails as designed) |
| Search-index deletion / forced rebuild | **none** (Phase B passed without it) |

Probe run (instrumented worker, Phase A only): `DONE checks=60 failures=0`.
Final post-gate run: `DONE checks=65 failures=0`.

Raw logs:
- Probe run (with runtime marker probe): `.tmp-electron-ipc/harness13f-probe.out`
- Probe worker log: `.tmp-electron-ipc/runs/2026-10-03T12-28-52-886Z-50188/graphs/Demo/db-worker-node-20261003.log`
- Final post-gate full run: `.tmp-electron-ipc/harness13-final-postgate.out`
- Full gate: `.tmp-electron-ipc/gate13-final.log`
- Baseline RED: `.tmp-electron-ipc/harness13d.out`, `.tmp-electron-ipc/harness13e.out`

---

## 2. Actual baseline RED

### 2a. Source-observed (root cause)

- `deps/outliner/src/logseq/outliner/op.cljs` `apply-ops!` (~line 452) sets
  `::sqlite-export/imported-data? true` for **any** `:batch-import-edn` op.
- `src/main/frontend/worker/db_listener.cljs` `skip-search-sync?`
  (lines 174-179) returned true for any tx carrying
  `:logseq.db.sqlite.export/imported-data?`, so the `:search` listener was
  skipped.
- MCP `upsertNodes` (`src/main/logseq/api/db_based/cli.cljs`) ran via
  `:batch-import-edn`, so same-session MCP writes never reached the search index
  until a full rebuild.

### 2b. Exec (integration, genuinely unfixed worker)

`harness13d` (checks=65, failures=6) and `harness13e` (checks=65, failures=7)
ran against the stale/pre-fix worker (build-time `09:29Z`, no marker):

```
FAIL :: A: same-session: MCP write is searchable immediately in the global index :: []
FAIL :: A: same-session: pageUuid scope yields root and child :: []
FAIL :: A: same-session: blockUuid scope is EXACT (root only, no descendants) :: []
FAIL :: A: same-session: existing block is searchable by its title before the edit :: []
FAIL :: A: same-session: edited title is searchable under the new text :: []
FAIL :: Phase B: search index survives normal reopen and still finds the page :: []
```

### 2c. Exec (unit)

`src/test/logseq/api/db_based/mcp_search_test.cljs` attaches a `d/listen!` to the
live conn, runs the real `cli-api/upsert-nodes` path, and asserts the captured
`:batch-import-edn` tx-meta has
`(true? (:logseq.outliner.op/runtime-write? tx-meta))`. Without the fix this
assertion fails; with the fix it passes (GREEN in the gate log:
`Testing logseq.api.db-based.mcp-search-test`).

---

## 3. Selected fix and bulk-import compatibility

Minimal distinction between a **runtime MCP write** and a **bulk import**, reusing
the existing import op and a single new tx-meta marker:

| File | Change |
|---|---|
| `deps/outliner/src/logseq/outliner/op.cljs` | add `::runtime-write?` to `defkeywords` (shared keyword def) |
| `src/main/logseq/api/db_based/cli.cljs` (`upsert-nodes`) | tx opts add `:logseq.outliner.op/runtime-write? true` |
| `src/main/frontend/worker/db_listener.cljs` (`skip-search-sync?`) | `(and (:logseq.db.sqlite.export/imported-data? tx-meta) (not (:logseq.outliner.op/runtime-write? tx-meta)))` |

Bulk-import paths still skip, because they do not set `::runtime-write?`:

- Full-graph import: `src/main/frontend/handler/db_based/import.cljs` passes
  `{:tx-meta {:import-db? true}}`.
- CLI `import-edn`: passes `{}`.
- Both still carry `::sqlite-export/imported-data?` from `apply-ops!`, and the
  marker is absent → `skip-search-sync?` true → skipped. The focused
  `db-listener-test` and the full gate cover the listener predicate.

`::sqlite-export/imported-data?` is auto-namespaced and preserved; the new marker
is a fully-qualified `logseq.outliner.op/runtime-write?` keyword that survives
transit (`frontend.persist_db.remote/invoke!` `:argsTransit` → node daemon → worker
`read-transit-str`). A temporary `log/info :debug/search-skip-probe` in the
worker `:search` listener confirmed the mechanism in the real worker
(`runs/2026-10-03T12-28-52-886Z-50188/.../db-worker-node-20261003.log`):

```
{:debug/search-skip-probe {:outliner-op :batch-import-edn, :batch-import? true,
                           :runtime-write? true, :skip? false}}   x10
```

All 10 real MCP `:batch-import-edn` writes arrived with
`:runtime-write? true` and `:skip? false`. The probe was then reverted before the
final build (`grep -c search-skip-probe` = 0 in all final artifacts).

No new public "refresh index" API was added; the harness polls only until the real
incremental index update is observable, with no forced rebuild.

---

## 4. Exact commands and build artifacts

Build order matters: `pnpm cljs:test` (invoked by the gate) overwrites
`static/db-worker-node.js` with a small test build, so the runtime bundle must be
produced by the `:release:bundle` script (which re-runs the release compile first):

```
# 1. full source gate
bb dev:lint-and-test                                   # EXIT=0

# 2. real worker release + vite bundle (recompiles shadow release, then bundles)
pnpm db-worker-node:release:bundle                     # -> static/db-worker-node.js + dist/db-worker-node.js

# 3. stage the release runtime where the loader looks (equiv. of prepare-desktop-runtime-js
#    for the worker; the full script also stages static/logseq-cli.js, absent here and unused)
cp dist/db-worker-node.js static/js/db-worker-node.js

# 4. safe disposable two-phase acceptance
NODE_PATH="D:/orca/workspaces/logseq/issue9-typed-properties/resources/node_modules" \
  node .tmp-electron-ipc/harness.cjs                   # DONE checks=65 failures=0
```

Final artifacts (post-gate, build-time `2026-10-03T12:37:16Z` for the worker):

| Artifact | Size | SHA-256 |
|---|---|---|
| `static/js/main.js` | 16,125,578 | `395f39bd4338edb18eb910256ab17b60be1aa78f2a094c627432927a46ffff88` |
| `static/electron.js` | 2,498,012 | `ae85d403e49de3b2f2b73170c48602cfa195633513c513e9ac9f7fba608fd6dd` |
| `static/db-worker-node.js` | 2,780,815 | `9d6cd3031c6b21de7276bdabcf50b7cfcea672ae7e39d878087751573c62dfcc` |
| `dist/db-worker-node.js` | 3,909,576 | `5b5ce3e2087a43ca879ae6d045950e89b3482b59b8da5243a4a3054f237b9b7d` |
| `static/js/db-worker-node.js` (loaded by loader) | 3,909,576 | `5b5ce3e2087a43ca879ae6d045950e89b3482b59b8da5243a4a3054f237b9b7d` |
| `static/js/db-worker.js` (browser worker) | 2,789,436 | `de2c160eddd713583acf06e8101055ccdf86a6944d823388f49a9eb2f40ab14f` |

`dist/db-worker-node.js` and `static/js/db-worker-node.js` are byte-identical
(copy), and both contain exactly 1 `logseq.outliner.op/runtime-write` occurrence
and 0 `search-skip-probe` occurrences. `static/js/main.js` contains the renderer
`upsert-nodes` tx-meta literal with the marker.

Worktree/branch: `D:/orca/workspaces/logseq/issue9-typed-properties`,
`fix/issue-9-mcp-properties-wip`, HEAD `f2958757d211ef16725a0b54d07275baaa686029`
plus preserved dirty set. No commits/push/reset/checkout/stash/clean.

---

## 5. Safety isolation proof (no deletions)

- Harness creates a brand-new unique root
  `.tmp-electron-ipc/runs/<ISO>-<pid>` each run and aborts if it already exists
  (`resolveRunRoot`).
- `wipeRuntime()` is a **no-op**; there are **no** `fs.rmSync`/`fs.rm`/`unlink`
  calls in `.tmp-electron-ipc/harness.cjs` or `app/bootstrap.cjs` (grep-verified).
- Phase B performs a normal close/reopen of the **same** run graph and asserts
  search durability; it does **not** delete `<graph>/search/` or force a full
  rebuild (harness comment line 761; no delete calls).
- `app.setPath('userData'|'home')` are set from `LOGSEQ_E2E_RUN_ROOT` in the
  bootstrap before the real `static/electron.js` loads, so the installed
  `~/.logseq` and user graphs are never touched. MCP binds `127.0.0.1` on an
  ephemeral port with a per-run token that is never printed.
- The real `static/electron.js`, real preload/index, and real IPC channels are
  used; no stub worker or reimplemented dispatcher.
- Newest run roots: `.../runs/2026-10-03T12-38-08-303Z-45188` (final),
  `.../runs/2026-10-03T12-28-52-886Z-50188` (probe).

---

## 6. Scenarios exercised (final run, 65 checks)

- 10 MCP tools registered; SDK input schemas asserted (incl. malformed-uuid
  rejection, `-32602`).
- Typed properties: url/default/node-many/checkbox/number, closed values
  (Task `Done`, status `Doing`), list marker; verified receipts with real UUIDs
  and parent chains; wrong-typed / malformed-ref / mixed-batch all rejected with
  **no partial write**; dry-run does not mutate.
- Recycle/restore: root-first affected set, hidden from `getBlock`,
  `getRecycledBlock` retains page/parent/order, idempotent re-recycle, restore to
  original location.
- **Same-session search consistency (the defect):**
  - MCP write searchable immediately in the global index — PASS
  - `pageUuid` scope yields root + child — PASS
  - `blockUuid` scope is exactly the root (descendants excluded) — PASS
  - edited title searchable under new text, old text no longer matches — PASS
  - recycle removes subtree from search immediately — PASS
  - restore returns subtree to search immediately — PASS
- Normal close/reopen (Phase B, no rebuild): persisted blocks/search/page/scope
  checks all PASS.
- Corrupted-result negative control: 31 induced failures.

---

## 7. Remaining limitations / not addressed

- **Full-build snapshot/truncate race (pre-existing, unchanged):**
  `src/main/frontend/worker/handler/search.cljs` `<build-blocks-index!` snapshots
  `blocks`, then `truncate-table!`, then upserts in batches; a write committing
  between snapshot and truncate can be lost from the index. This fix only changes
  the *incremental* skip decision for MCP writes and does not claim to close that
  separate race.
- **Search-index build trigger:** the full build is scheduled on
  `:graph/restored` and re-schedules until idle; no new API was added.
- **Out of scope (unchanged by this task):** property removal semantics, many
  closed-value refs, list-bullet edge cases, and legacy default `getPage` block
  UUID shape called out in the dispatch remain as-is.
- `static/logseq-cli.js` is not staged in this build; `prepare-desktop-runtime-js`
  and its CLI-launcher path are unexercised (unrelated to the worker).
- Build caveat for maintainers: `pnpm cljs:test` clobbers
  `static/db-worker-node.js`; always produce the shipped worker via
  `pnpm db-worker-node:release:bundle` (which recompiles release first), then
  stage `dist/db-worker-node.js` to `static/js/db-worker-node.js`.

---

## 8. Classification of claims

- **Source-observed:** loader path `server.cljs:45-53`; staging
  `prepare-desktop-runtime-js.mjs:33-38`; bundling
  `build-db-worker-node-bundle.mjs:13`; root cause in `op.cljs`/`db_listener.cljs`;
  the three fix edits.
- **Executed:** gate (`bb dev:lint-and-test`, EXIT=0); worker probe log (10×
  `:runtime-write? true, :skip? false`); harness `13f` 60/0 and final 65/0;
  baseline RED `13d` 6 failures / `13e` 7 failures; artifact hashes; grep proofs
  of marker/probe absence.
- **Inference:** the earlier "fix not working" conclusion was an artifact-staging
  error, not a code defect (consistent with the pre-fix artifact containing no
  marker and the post-fix artifact passing). The full-build race is inferred from
  source ordering, not reproduced to failure here.

No issue closures are implied by this report.
