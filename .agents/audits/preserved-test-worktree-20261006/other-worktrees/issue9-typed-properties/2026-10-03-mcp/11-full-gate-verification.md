# 11 Verification: full gate portability + namespace contamination

Status: **FINAL**. Both measured gate failures fixed; full
`bb dev:lint-and-test` now completes **EXIT=0**.

This file is written incrementally so an interruption still leaves real status.

## Build / worktree provenance

- Worktree: `D:/orca/workspaces/logseq/issue9-typed-properties`
- Branch: `fix/issue-9-mcp-properties-wip`
- Baseline HEAD: `f2958757d211ef16725a0b54d07275baaa686029` (plus preserved uncommitted changes)
- No resets, checkouts, stashes, cleans, commits, deletions or delegation.
- Parent failing log: `scratch/11-parent-full-gate.txt`
- Final full-gate raw log: `scratch/11-full-gate-final.txt`

## Task 1 — Windows path portability in `logseq.api.plugin-test`

File: `src/test/logseq/api/plugin_test.cljs`

Reproduced on Windows: POSIX-literal expectations were compared against
host-native `util/node-path.join` output. Actual values were
`\tmp\plugins\tmp\scratch.txt` vs expected `/tmp/plugins/tmp/scratch.txt`
(previously 11 failures, e.g. assertions at lines 98, 211, 212, 213).

Fix (test-only; no production plugin code changed, no assertion removed or
skipped):

- Added `["path" :as node-path]` and a local helper
  `(defn- join-path [& parts] (apply node-path/join parts))`.
- Built fixture keys and expectations with `join-path`, mirroring the
  production `util/node-path.join` / `.normalize` calls.
- Fake-FS keys were made native so read/write/list/clear still enforce all
  contents (`plugin-package-and-dotdir-file-io`, `plugin-storage-file-round-trip`,
  `list-and-clear-plugin-storage-files`) and the `starts-with?` root guards now
  match native separators.
- Traversal/security cases remain real: `sub-path?` still rejects
  `/etc/passwd` and `root/../secrets.txt`; `assert-storage-path!` still throws
  `"write file denied"`. On Windows these resolve through `path.relative` to a
  parent-dir segment, so the guard is genuinely exercised, not bypassed.

Measured (isolated):

```
LOGSEQ_STABLE_IDENTS=1 node static/tests.js -n logseq.api.plugin-test -e long -e fix-me
Ran 22 tests containing 67 assertions.
0 failures, 0 errors.
```

Note: isolated `npm cljs:test` in the parent (`f2958757`) was not re-run here
per dispatch (parent logs cited); this same class was already fixed for
`embedding_server_test.cljs` in report 10, Deliverable C.

## Task 2 — grouped `logseq.api.db-test` contamination

`logseq.api.db-test` is GREEN isolated (6 tests / 9 assertions) and was also
green in the parent as a standalone `node static/tests.js -n logseq.api.db-test`
(EXIT=0). The grouped failure was therefore **not** pre-existing and **not** a
jsdom/environment gap (correcting report 10's overclaim).

Measured minimal repro: the contamination only appears when
`frontend.handler.editor-context-popup-test` runs before `logseq.api.db-test`
(node runs namespaces in alphabetical order). A probe namespace sorting before
`logseq.*` reproduced it; one sorting after did not.

Root cause (confirmed by stack): `shortcut-cut-and-delete-close-context-popup-test`
wrapped `editor/shortcut-cut` / `editor/delete-selection` in a **synchronous**
`with-redefs`. Rule body in `src/main/frontend/handler/editor.cljs:3375-3382`:

```clojure
(let [cut-p (cut-selection-blocks copy?)]
  (hide-block-context-popup!)
  (p/do! cut-p (clear-selection!)))
```

`(clear-selection!)` is evaluated by `p/do!` only **after** `cut-p` settles, so
it runs on a later microtask, after `with-redefs` had already restored the real
`editor/clear-selection!` -> `state/clear-selection!` ->
`state/dom-clear-selection!` -> `js/document` (undefined in node). The leaked
unhandled rejection then cascaded into `logseq.api.db-test` as misattributed
`ReferenceError: document is not defined` and
`Unhandled test worker api: :thread-api/query-dsl-query` failures.

Fix (preferred: fix the leaked fixture/restoration, not isolate the victim):

File: `src/test/frontend/handler/editor_context_popup_test.cljs`

- Test is now `(async done ...)`.
- Uses `promesa.core/with-redefs` (`p/with-redefs`) so redefs stay installed
  until the returned promise settles.
- `p/let` awaits the promises returned by `editor/shortcut-cut` and
  `editor/delete-selection` before asserting and calling `done`.

This keeps both original assertions and expected call order intact; it does not
add `logseq.api.db-test` to `isolated-test-namespaces`, does not serialize all
tests, and does not add compatibility handlers to `test_helper.cljs`.

Measured pairwise (the previously failing order):

```
LOGSEQ_STABLE_IDENTS=1 node static/tests.js \
  -n frontend.handler.editor-context-popup-test -n logseq.api.db-test -e long -e fix-me
Ran 8 tests containing 15 assertions.
0 failures, 0 errors.
```

All 6 DB tests still execute (6 tests / 9 assertions) and are present in the
final full-gate log (`Testing logseq.api.db-test`).

## Task 3 — fresh build / lint / tests / full gate

- Fresh `pnpm cljs:test` (`clojure -M:test compile db-worker-node test`) ran as
  the first stage of the full gate (`11-full-gate-final.txt:4-17`): both builds
  completed, 0 warnings.
- clj-kondo: `errors: 0, warnings: 0` (`11-full-gate-final.txt:20`); only an
  informational redundant-boolean-coercion note in an unrelated Electron test.
- `bb lint:carve` ran (`11-full-gate-final.txt:21`).
- Full gate command (raw exit captured):

```
bb dev:lint-and-test > .agents/audits/2026-10-03-mcp/scratch/11-full-gate-final.txt 2>&1; echo EXIT=$?
EXIT=0
```

Verification of the raw log: 40 test-run summary blocks, **all** report
`0 failures, 0 errors`. No `Unhandled test worker api` remains. The only
remaining `document is not defined` occurrences (3) are inside
`frontend.handler.paste`'s own expected-error logging path, not
`logseq.api.db-test`.

Test-file diffstat:

```
 src/test/frontend/handler/editor_context_popup_test.cljs | 70 +++++++++-----------
 src/test/logseq/api/plugin_test.cljs                     | 49 ++++++++-------
 2 files changed, 64 insertions(+), 55 deletions(-)
```

## Task 4 — downstream docs

- Report 10 corrected: status no longer claims the full gate is blocked by two
  pre-existing groups; `plugin-test` portability was proven pre-existing
  (parent baseline isolated failure), while the `db-test` grouped failure is
  attributed to the `editor-context-popup` leak now fixed.
- Remaining feature limitations and the unverified Electron IPC path from
  report 10 are unchanged and still accurate.

## Update log

- (initial) Task 1 fixed and measured green isolated.
- (initial) Task 2 root-caused to the async `clear-selection!` leak; fixed in
  `editor_context_popup_test.cljs`; pair measured green.
- (final) Fresh `cljs:test`, kondo/lint:carve, and FULL `bb dev:lint-and-test`
  executed with EXIT=0; raw log saved; report 10 corrected.
