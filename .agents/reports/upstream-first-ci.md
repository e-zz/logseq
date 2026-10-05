# Upstream-first synchronization + integrated manual-test CI — progress ledger

Upstream anchor (immutable): `22a29b30dee3b3930cf49bba50454650c31d2a07` (upstream/master)
Final integration worktree: `issues-human-test-20261003` (branch `test/issues-mcp-20261003`, start `44b64d8f3e`)
Git identity for commits: `e-zz <e-zz@users.noreply.github.com>`
No secrets recorded here.

## Branch ledger

| worktree | branch | start HEAD | upstream merged | merge commit | notes |
|---|---|---|---|---|---|
| issue5-child-order | fix/issue-5-render-missing-order-wip | dd62f90d80 | DONE | ff8254274d | conflict resolved to upstream semantics; focused test GREEN |
| issue6-parent-wip | fix/issue-6-mcp-parent-wip | 76c7a22327 | DONE | 79e608b0bb | clean auto-merge, no conflicts |
| issue8-scoped-search | fix/issue-8-scoped-search-wip | 0ded4dbf1b | DONE | 565f91ee27 | clean auto-merge, no conflicts |
| issue9-typed-properties | fix/issue-9-mcp-properties-wip | 2b8b4403b9 | DONE | a3f075728b | 3 conflicts resolved; focused search+popup tests GREEN |
| issue11-search-index | fix/issue-11-search-index-wip | 57a04cdfc0 | DONE | e5f943dfb6 | clean auto-merge; superseded by issue9 marker |
| issue12-cli-open-safety | fix/issue-12-cli-open-wip | a940516fdd | DONE | 2255fa7974 | clean auto-merge; untracked .agents/briefs preserved |

All six merge commits verified to have the anchor as an ancestor (`git merge-base --is-ancestor`).

## Issue #5 resolution (anchor merge, done)

Conflict only in `src/main/frontend/worker/handler/block.cljs` at direct-child order handling.

- Local `dd62f90d80` introduced a `log/warn` fallback: a direct child missing `:block/order` was accepted, rendered **last** via custom `direct-child-render-cmp`.
- Upstream anchor includes `2be6bfc156` (allow missing direct-child order; nil sorts **first** matching `ldb/sort-by-order`) and `68a68263b7` (repair nested-page missing order at migration/validate + deterministic assignment).
- Decision: adopt upstream semantics (nil legal, `sort-by :block/order`, nil first; non-string still fail-fast). Local warn/sort-last is duplicative OLD behavior and contradicts upstream tests.
- Resolved `block.cljs` and `block_test.cljs` now byte-match the anchor (`git diff <anchor> -- <file>` empty).
- Verification: `bb dev:test -v frontend.worker.handler.block-test` — `Ran 37 tests containing 212 assertions. 0 failures, 0 errors.`
- Note: issue5 branch-local-only files (graph-parser exporter, electron external protocols/window, render_resource_test) auto-merged and remain; assess for redundancy at integration.

## Issue #9 resolution (anchor merge, done)

Three conflicts:
1. `deps/outliner/src/logseq/outliner/recycle.cljs` — `restore-target`/`restore-tx-data`. Kept local occupied-order-preserving two-arg `restore-order`, `original-page-valid?`, and receipt `:position` fields; adopted upstream `parent-pending?`, `awaiting-original-parent?`, `relink-waiting-children-tx`, conditional `clear-meta`, and `relink-tx` for page restore. Local receipt `restore-result`/`restore!` retained.
2. `src/test/frontend/handler/editor_context_popup_test.cljs` — took upstream `[async deftest is]` require order and the upstream `p/do!`/`p/catch`/`p/finally` test form (current file equals anchor). Tab-menu tests present via merge union.
3. `src/test/frontend/worker/search_test.cljs` — union of requires (`recycle` + `promesa.core :as p`); kept both the local scoped-search/recycle-search tests and the upstream FTS rowid migration tests/hëlpers.
- Verification: `bb dev:test -v frontend.worker.search-test` — `Ran 78 tests containing 290 assertions. 0 failures, 0 errors.` (includes `recycled-subtree-excluded-from-global-page-and-block-search`, which exercises merged `recycle/recycle!` and `recycle/restore!`). `frontend.handler.editor-context-popup-test` — `Ran 4 tests containing 8 assertions. 0 failures, 0 errors.`
- Build: shadow-cljs `:test` compiled with `0 warnings`.

## Open blocker / uncertainty

- `deps/outliner` nbb test runner (`pnpm test` from `deps/outliner`, CI job `deps-outliner.yml`) currently fails to load `logseq.outliner.core`: `Unable to resolve symbol: ldb/some-parent` at `core.cljs:92`. Both `core.cljs` (reference) and `deps/db/src/logseq/db.cljs` (`(def some-parent entity-util/some-parent)`) are the untouched anchor versions, and the same code compiles with 0 warnings under shadow-cljs, so this looks like a local nbb/node_modules environment mismatch rather than a merge error. Not part of the `bb dev:lint-and-test` gate; the recycle behaviour it would cover is instead exercised by the passing search-test `recycled-subtree-*` test. Recheck against a clean nbb install before finalizing.

## Final integration (`test/issues-mcp-20261003`, start 44b64d8f3e)

| commit | content |
|---|---|
| a3f075728b | issue9 upstream merge (typed props, recycle/receipt, scoped search, incremental marker) |
| 463df59b3f | merge issue9 into test branch (clean, 40 files) |
| 2255fa7974 | issue12 upstream merge (CLI open-safety) |
| c771bf6242 | merge issue12 into test branch (clean, 4 files) |

### Branch inclusion decisions (step 4)

- **issue9 (`a3f075728b`)** — MERGED. Brings #6 nested writes, #4/#7/#9 typed props, #8 scoped search, outliner recycle/receipt, newer incremental-search marker; also issue12 CLI tests and mcp_verify harness.
- **issue12 (`2255fa7974`)** — MERGED. CLI graph config/CSS/JS open-safety.
- **issue6 (`79e608b0bb`)** — SUPERSEDED / already contained. Original tip `76c7a22327` is an ancestor of the test branch (`git merge-base --is-ancestor` YES), so its content is present; the anchor-merge commit itself is stale relative to the integration tree and was not layered.
- **issue8 (`565f91ee27`)** — SUPERSEDED. Core `src/main/frontend/worker/search.cljs` is byte-identical between issue8 and the integration tree (scoped search already present via issue9). Remaining issue8 diffs are OLDER and would regress newer code (e.g. it lacks `modules/outliner/op.cljs/recycle-blocks!`, lacks the `db_listener` `runtime-write?` import exemption, lacks newer handler/search require order). Not merged.
- **issue11 (`e5f943dfb6`)** — SUPERSEDED. Its alternate incremental-index fix is a duplicate of issue9's latest marker solution; integration tree search files are strictly newer. Not layered, per brief.
- **issue5 (`ff8254274d`)** — NOT MERGED. The missing-order rendering fix is fully superseded by upstream `2be6bfc156`/`68a68263b7` (`block.cljs`/`block_test.cljs` now byte-identical to anchor). Other issue5 tree deltas are OLDER than the integration base and would REGRESS shipped behavior: `issue5` lacks the `{{zotero-linked-file}}` standalone-macro asset handling present in the integration tree (graph-parser `exporter.cljs`), and its `render_resource_test.cljs` targets code not in the integration tree. Recorded covered semantics; no code merged.

- Net integration delta from priv (`44b64d8f3e` → `c771bf6242`): 44 files, +7783/−400.

## Gate results (integration tree, HEAD b5aa479adb)

- `bb dev:lint-and-test`:
  - clj-kondo: `errors: 0, warnings: 0` after removing the orphaned `frontend.worker.search-test/process-cpu-time-ms` helper (upstream rewrote the benchmark to deterministic work-count assertions; helper was dead local code). Committed `b5aa479adb`.
  - shadow-cljs `:test` compiled `0 warnings`.
  - All test batches green except ONE: `frontend.worker.sync.restart-test/start-reconnects-closed-ws-with-stale-open-state-test` (`restart_test.cljs:58`, `expected (= 1 @connect-calls)`, `actual 0`).
- Baseline vs integration classification for that failure: `restart_test.cljs`, `db_worker_test.cljs`, `frontend/worker/sync.cljs`, `frontend/worker/db_worker.cljs`, `frontend/worker/sync/client_op.cljs` are all byte-identical between the provided base `44b64d8f3e` and integration HEAD (`git diff --stat` empty). The only integration change in the db-worker load path is `frontend/worker/db_listener.cljs` (search-index `runtime-write?` exemption), unrelated to websocket connect.
  - Isolation evidence: `restart-test` alone → PASS; after `macro-test` → PASS; after `search-test` → PASS; after `db-worker-test` → FAIL (`connect-calls` 0). So it is a pre-existing test-isolation race in the base (db-worker-test leaves async sync state that makes `sync/start!` resolve before the reconnect websocket connect is invoked), NOT introduced by the integration. Left the upstream test unmodified (not weakened/asserted away).
- Full-suite summary: 20+ batches, all `0 failures, 0 errors` except the single baseline restart-test failure above. Scratch log: `.agents/scratch/gate-full.log` (untracked, not committed).

## Push and CI dispatch

- Pushed integration branch (no force): `test/issues-mcp-20261003` → `origin` (e-zz/logseq). Head `eb69c1bd17`.
- Workflow change (test branch only): added `windows-only` dispatch boolean to `build-desktop-release.yml`, gating `build-linux-x64`, `build-linux-arm64`, `build-macos-x64`, `build-macos-arm64`, `build-android` so fork manual-test builds spend Windows runners only. YAML validated with PyYAML. Commit `eb69c1bd17`.
- Dispatched non-release Windows build:
  - workflow: `Build-Desktop-Release`, repo `e-zz/logseq`, ref `test/issues-mcp-20261003`
  - inputs: `build-target=non-release`, `git-ref=test/issues-mcp-20261003`, `enable-file-sync-production=true`, `enable-plugins=true`, `build-android=false`, `publish-linux-stores=false`, `windows-only=true`
  - run: https://github.com/e-zz/logseq/actions/runs/37134185100
  - initial job state: `compile-cljs` in_progress, `build-android` skipped (gate works).
- CI RESULT: run **success** (completed). Windows artifacts produced:
  - `logseq-win-x64-builds` (~347 MB) — `Logseq-win-x64-<version>-nsis.exe`, `.zip`, blockmap, `latest-x64.yml`.
  - `logseq-win-arm64-builds` (~340 MB).
  - Artifact page: https://github.com/e-zz/logseq/actions/runs/37134185100
  - Job outcomes: `compile-cljs` success; `build-windows-x64` success; `build-windows-arm64` success; `build-linux-*`, `build-macos-*`, `build-android`, `release`, `nightly-release`, publish jobs skipped (non-release + windows-only gate). Fork gating confirmed: OCaml/CLI steps and Sentry skipped.
  - Download via `gh run download 37134185100 --repo e-zz/logseq -n logseq-win-x64-builds`.

## Final status
- DONE: anchor merged into all six issue worktrees; issue9+issue12 integrated into `test/issues-mcp-20261003` (HEAD `eb69c1bd17`); gate run with only one documented pre-existing baseline failure; docs written; branch pushed; non-release Windows CI dispatched and green.
- Manual testing can proceed from the x64 (or arm64) artifact above. Follow `docs/testing/issues-20261003.md` on a NEW/COPY graph in an isolated environment.

## Deterministic uncertainty



- `sort-by :block/order` relies on cljs `compare` ordering nil before strings; upstream test asserts this.
- Duplicate upstream/local regression tests auto-merged side by side; local contradictory ones removed, not merely to pass.
