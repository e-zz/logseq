# PDF Asset / Highlight Fixes — Port Report (merge-all-fixes → pdf-assets-port-priv)

Date: 2026-10-03
Branch: `pdf-assets-port-priv` (created from `priv` @ `073ec5b90f`)
Source: `merge-all-fixes` @ `548ee5a3fe` (local)
Authorization: port PDF fixes into a temporary feature branch; **not** a merge of
`merge-all-fixes`; **not** merged into `priv` (left for parent review).

## Scope

Files changed (tracked, task-owned only):

| File | Change |
|---|---|
| `src/main/frontend/extensions/pdf/assets.cljs` | Ported verbatim from `merge-all-fixes@548ee5a3fe` (byte-identical), with one adaptation: `editor-handler/db-based-save-assets!` → `editor-assets/db-based-save-assets!` (priv's #13368 refactor moved `db-based-save-assets!` into `frontend.handler.editor.assets`). All other imports resolve identically in priv (`page-handler/<create!`, `common-util/safe-decode-uri-component`, `assets-handler/get-file-checksum`, `db-async/<get-asset-with-checksum`, `editor-handler/move-blocks!`). |
| `src/main/frontend/extensions/pdf/core.cljs` | Seven port hunks: `complete-asset-creation-for-current-pdf!`; ctx-menu `pdf-current` prop + `add-highlight!`/`ensure-db-asset!` color-action flow (replaces `:asset/dialog-edit-external-url`); `<persist-new-area-highlight!` / `<persist-resized-area-highlight!` helpers; resize-handler rewrite with `restore!`/notification; `add-hl!` optional `current` arg + `effective-pdf-current`; ctx-menu render pass-through of `:pdf-current` + effect dep; `pdf-loader` passes `:pdf-current` into `pdf-highlights` ops. |
| `src/test/frontend/extensions/pdf/assets_test.cljs` | Ported from source (10 tests) + 1 new known-issue probe; removed `normalize-asset-resource-url-normalizes-file-protocols` (see Gaps); redefined `editor-handler/db-based-save-assets!` → `editor-assets/db-based-save-assets!` in 3 tests. |
| `src/test/frontend/extensions/pdf/core_test.cljs` | Ported from source (5 tests). |
| `src/resources/dicts/en.edn` | Added `:pdf/annotation-create-error`, `:pdf/area-image-save-error` (both used by ported UI text via `t`). |

Ported functionality (from source commits 711f373c47, f4414bdfdd, 2b15c6623c,
54b90672a2, d78dd0b542, 886afe501c, aadf4ed287, 60938ca9ee, 2db9460f2d,
886afe501c, d99d517760, 1336937d3e, f8346e71ab, 91da2bfad2, 3c02c34c4b,
194b676a60):

1. `ensure-db-asset!` — create the DB Asset record for an external PDF before
   annotating (per-PDF `hls__` page via `page-handler/<create!`), with
   in-flight dedup (`*asset-creation-in-flight`), Zotero import reuse
   (`<find-zotero-asset-by-source`), checksum duplicate reuse
   (`<save-or-reuse-asset!`), and fail-fast on missing source path.
2. `db-based-ensure-ref-block!` — fail-fast when no Asset block / no color /
   no text; verify the annotation block actually exists after insert.
3. Area image persistence — reject (not silently nil) when the page canvas or
   2D context is unavailable or `toBlob` yields no PNG; reuse existing area
   image assets by checksum; surface `:pdf/area-image-save-error` with
   rollback of optimistic highlight state (`<persist-new-area-highlight!`,
   `<persist-resized-area-highlight!`).
4. `copy-hl-ref!` explicit `pdf-current` arity + notification on failure
   (`:pdf/annotation-create-error`).
5. `resolve-external-pdf-url` / `zotero-protocol-url?` / canonicalized
   `get-zotero-local-pdf-path` (Electron `assets://` normalization protecting
   Windows drive letters).
6. Async-asset-completion guard: `complete-asset-creation-for-current-pdf!`
   refuses to reactivate a PDF the user has already left.

## Dependency completion (second commit)

- `frontend.handler.assets/normalize-asset-resource-url` `file://` normalization
  (merge-all-fixes 3c02c34c4b "canonicalize external asset resolution"):
  ported with adaptation. The source's port recursed into the relative branch
  for Windows drive paths, producing `assets:///C%3A/...` (encoded colon, not
  protected) and crashing on `file://C:/...` (nil `get-repo-dir`). The adapted
  port detects the Windows drive path after stripping and protects the colon
  directly via `protect-windows-drive-in-assets-path`, producing the correct
  `assets:///C/logseq__colon/...` form for both `file:///C:/...` and
  `file://C:/...`. POSIX `file:///tmp/...` recurses into the normal
  absolute-path branch.
- `inflate-asset` `file://` branch: adapted to pass the whole `file://` URI
  to `normalize-asset-resource-url` (the normalizer now handles the strip +
  drive protection), instead of pre-stripping `file://` and recursing.
- Restored the dropped source test
  `normalize-asset-resource-url-normalizes-file-protocols` and added
  `inflate-asset-normalizes-file-uri-on-windows` (both `file:///C:/` and
  `file://C:/` forms).
- Added targeted helper tests in `assets_test.cljs`:
  `normalize-asset-resource-url-electron-file-uri-windows-test`
  (both Windows URI forms + raw drive path) and
  `normalize-asset-resource-url-electron-file-uri-posix-test`.
- Fixed misleading comments: the `file://` strip branch is NOT a no-op (the
  raw-path helper transforms Windows drives; the Windows URI leading slash
  handling now has explicit tests). Fixed the known-issue probe commentary
  claiming `ensure-ref-block!` moves the PDF Asset (it only moves the area
  image asset, the PNG block under `:logseq.property.pdf/hl-image`).

## Not ported (deliberate)

- `core.cljs` load-error branch: source routes fetchable `https?://` external
  URLs to the browser via `util/open-url`; priv's newer fix 3751fa7108 already
  implements that (priv behavior kept, source hunk not applied).
- Everything else in `merge-all-fixes` (MCP write speedup, external-link
  protocol confirmation, query sort-by, etc.) — untouched.

## Known issue (preserved, demonstrated, not fixed)

`<create-db-asset!` passes `[:save-to-page hls-page]` to
`db-based-save-assets!` but not `:target-block`. The shared target priority is
`target-block > active edit-block > save-to-page > today`
(`frontend.handler.editor.assets/db-based-save-assets!`, lines 121–132): when a
block is actively being edited at highlight time
(`state/get-edit-block` ≠ nil, non-empty content or non-area save), the asset
block is inserted into/at that edit block instead of the `hls__` page
(`:sibling?` true / `:replace-empty-target?` true for an empty edit block).
The page still receives the asset on the subsequent `ensure-ref-block!`
`move-blocks!` — unless that move fails.

Executable demonstration: `frontend.extensions.pdf.assets-test/
known-issue-active-edit-block-overrides-save-to-page` exercises the **real**
`db-based-save-assets!` target-resolution path (only file-write, checksum and
outliner-op application are stubbed) and asserts the resolved target is the
active edit block, not the hls page, with `sibling? true`. It is named
`known-issue-*` and is a passing demonstration, not a hidden acceptance gate.
Separation: preserved-behavior evidence (this test) vs production cause
(unproven — see plan below).

## Test evidence

Commands (run in `D:/Action/logseq` on branch `pdf-assets-port-priv`):

```
pnpm cljs:test            # compile db-worker-node + test targets (shadow-cljs)
node static/tests.js -n frontend.handler.assets-test
node static/tests.js -n frontend.extensions.pdf.assets-test -n frontend.extensions.pdf.core-test
node static/tests.js -n frontend.handler.editor-test -n frontend.handler.editor-assets-test
bb lang:validate-translations
```

Results:

- `frontend.handler.assets-test` (dependency completion, 2nd commit):
  **Ran 17 tests containing 41 assertions. 0 failures, 0 errors.**
  (15 pre-existing tests + 2 new: `file-uri-windows-test` and
  `file-uri-posix-test`. Output: `test-assets-handler.log` in Hermes scratch.)
- `frontend.extensions.pdf.assets-test` + `frontend.extensions.pdf.core-test`
  (dependency completion, 2nd commit):
  **Ran 20 tests containing 45 assertions. 0 failures, 0 errors.**
  (17 pre-existing + 1 known-issue probe + 2 new:
  `normalize-asset-resource-url-normalizes-file-protocols` and
  `inflate-asset-normalizes-file-uri-on-windows`.
  Output: `test-pdf-assets.log` in Hermes scratch.)
- `frontend.handler.editor-test` + `frontend.handler.editor-assets-test`
  (prior commit, still passing):
  **Ran 105 tests containing 286 assertions. 0 failures, 0 errors.**
  (confirms the `db-based-save-assets!` redefinition adaptation is sound and
  no editor behavior regressed)
- `bb lang:validate-translations`: all keys defined/used, placeholders and
  render contracts preserved (the 5 parse warnings on `en.edn` lines 364/389
  pre-date this change — verified by stashing and re-running).
- Full `bb dev:lint-and-test` not run (whole-suite node runner hits
  pre-existing DOM-dependent failures unrelated to this port, e.g.
  `electron.embedding_server_test` path-separator mismatches and
  `document is not defined` in block tests). The targeted namespaces above are
  the affected surface.

## Commit

See `git log --oneline -2` on `pdf-assets-port-priv` (single scoped commit on
top of `073ec5b90f`). Untracked `.agents/`, `.hermes/`, `docs/superpowers/`
left untouched; no pushes; `priv` untouched.

## Runtime reproduction plan (active-edit override → parentless insert)

Goal: confirm at runtime whether the Oct 3 00:15:58 CST failure
(`:block/parent nil`, `:block/order "a0"`, `:sibling? true`, target = Sep 18
journal page `57693`, external-url PDF asset) was produced by the active-edit
override in `db-based-save-assets!`. No production instrumentation (per
authorization); capture is done by reading existing worker-log structure plus
a one-off dev-graph REPL capture.

Capture (correlate by asset UUID `6abfd8be-…` / operation / running build):

1. **PDF Asset UUID** — the Asset block
   `:block/uuid` in the emitted insert op (`:blocks` in the
   `:thread-api/apply-outliner-ops` payload already carries it in
   `db-worker-node-*.log`).
2. **Edit-block UUID / type / parent** — in the renderer REPL
   (`bb dev:repl` / desktop-app-repl `:app`):
   `(state/get-edit-block)` and `(state/get-edit-content)` immediately before
   and after a highlight action. Record `:block/uuid`, `:block/page`,
   `:block/parent`, `:block/title`.
3. **Explicit save-to-page** — the `hls__<key>` page UUID:
   `(state/get-state :pdf/current)` → `:key` → `hls__` page via
   `(db-async/<get-page-by-name …)` after `ensure-db-asset!` resolves.
4. **Raw op** — from `db-worker-node-<date>.log`: the full
   `:thread-api/apply-outliner-ops` map (`:outliner-op`, `:insert-blocks`,
   `:sibling?`, `:replace-empty-target?`, `:keep-uuid?`, `:bottom?`, `:target`,
   `:blocks`).
5. **Resolved target/sibling** — derive: `:target` vs the `hls__` page UUID
   from (3). If `:target` == the edit block (2) and `:sibling?` true →
   override confirmed for that operation.
6. **Resulting parent** — post-failure, query the Asset block's
   `:block/parent` (should be nil in the recorded incident) via
   `(db-async/<get-block "repo" <asset-uuid> …)`.

Reproduction steps (dev graph, this build):

1. Build the branch (`bb dev:electron-start` or packaged dev build) and open a
   graph.
2. Create/leave an **empty block** (or a block with text) in edit mode on a
   journal page (e.g. today's page) — keep focus in that block.
3. Open an **external PDF** (file:// or assets:// URL, no existing Asset).
4. Trigger a **text highlight** (color) or **area highlight**.
5. Observe: does the Asset block land on the `hls__` page or as a child of the
   actively edited block? Area highlight: does the resulting PNG/ref block
   transaction carry `:block/parent nil` when the edit block is page-top-level?
6. Repeat with **no active edit block** (focus elsewhere) as the control.

Interpretation:

- Step 5 with active edit → asset in edit block (unit-level already proven by
  the known-issue probe); area-image ref insert under a top-level parentless
  target reproducing `:block/parent nil` + `:sibling? true` would match the
  incident's op shape and support the override as a contributing cause.
- It does **not** by itself prove the Oct 3 incident used this path (the
  recorded op's target was the Sep 18 journal page, and why the editor target
  was that page remains unknown). MCP/fork involvement is **not** ruled out.
- The `move-blocks!` in `ensure-ref-block!` only relocates the area image
  asset (the PNG block under `:logseq.property.pdf/hl-image`), not the
  external PDF Asset block. If the PDF Asset was inserted into the active
  edit block instead of the `hls__` page, no later step moves it to the
  `hls__` page — the asset stays in the edit block (or parentless if the
  edit block's parent is nil).

Correlation rule: an operation is attributed to this build + asset when the
asset UUID in `:blocks` matches (1), the worker log timestamp falls inside the
reproduction window, and the running build hash matches
`git rev-parse HEAD` of this branch (stamp visible in the packaged build's
`logseq.common.version.REVISION`).

## Gaps

- `normalize-asset-resource-url` `file://` normalization is now ported
  (dependency completion, 2nd commit) with adaptation for Windows drive
  protection. The source's port had a bug: it recursed into the relative
  branch for Windows drives, producing `assets:///C%3A/...` (encoded colon,
  not protected) and crashing on `file://C:/...`. The adapted port fixes this.
- Full-suite `bb dev:lint-and-test` not green end-to-end due to pre-existing
  unrelated node-test failures (DOM/path-separator), so lint coverage for the
  changed files is limited to shadow-cljs compile warnings (0) + the targeted
  namespaces above. `bb dev:lint` (clj-kondo) on the changed CLJS files
  was not run separately.
- Runtime reproduction (plan above) not executed here — requires the desktop
  app on a dev graph; recorded as parent follow-up.
