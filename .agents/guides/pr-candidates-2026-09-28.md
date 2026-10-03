# PR Candidates — 2026-09-28

Status of the fork's own commits on `merge-all-fixes`, recorded so work can pause
here safely and resume without re-deriving context.

## Why a PR cannot be opened from `merge-all-fixes`

`merge-all-fixes` is not a feature branch. It has merged `upstream/master`
(`be800f1711`, 2026-09-08) plus later upstream merges, so its history contains
upstream's own commits.

- `git log be800f1711..HEAD` reports 530 commits across 15 authors
- Of those, ~498 belong to upstream (Tienson Qin 249, Cursor Agent 112, others)
- The fork's own commits number **32** (author `e-zz <e-zz@users.noreply.github.com>`)

Any PR from this branch would carry upstream's commits back to upstream. It has
to be cut into topic branches first.

Note: use a fixed SHA as the diff base, never the `upstream/master` ref name.
That ref moves; on 2026-09-28 it advanced to `794fb4de29`, which silently
widened `upstream/master..HEAD` from 31 to 530 commits and produced a false audit
result.

## Sync status

- Merge base with upstream: `be800f1711` (2026-09-08)
- `upstream/master` as of 2026-09-28: `794fb4de29` (2026-09-21)
- Behind upstream by **103 commits / 13 days**
- No `main` branch exists locally; the working line is `merge-all-fixes`

## Candidate 1 — external link confirmation

Local marker branch: `pr/candidate-external-link` -> `13be83e2a8`

Commit: `fix(electron): stop confirming external links for known protocols`
Base: `3bbdfdefda`
Files (2 files, +34 -3):

- `src/electron/electron/js/external-protocols.js` (new, 24 lines, module.exports
  of the six allowed protocols)
- `src/electron/electron/window.cljs` (protocol gate in `open-default-app!`)

Verification state:
- `shadow-cljs compile electron` 0 warnings (measured)
- `clojure -M:test compile test` 0 warnings (measured)
- clj-kondo 0/0 (measured)
- Single-test runner crashes on a pre-existing `document is not defined` (measured,
  not caused by this commit)

Open questions before this could be proposed upstream:
- Whether `https:` still prompts is NOT measured. Two click paths exist:
  `setWindowOpenHandler` (changed here) and `will-navigate` at
  `window.cljs:167`, which calls `open-default-app!` unconditionally. This is an
  inference, not a verified fact.
- `resources/js/preload.js:9` still lists 5 protocols and omits `logseq:`, so
  `logseq:` passes the main process but throws `illegal protocol` in the renderer.
  The two lists disagree.

Route: this should go through the FR, not a PR. Published as
https://github.com/e-zz/logseq/issues/13 (label enhancement).
Upstream issue #6291 is the reason the prompt exists at all — it asked for an
`openExternal` allowlist after an XSS-to-RCE report. Any proposal must argue
that it is more secure, not less, or it will be closed against #6291.

## Candidate 2 — PDF external asset resolution

Local marker branch: `pr/candidate-pdf-assets` -> `3c02c34c4b`

Not ready to propose. The 19 commits between `711f373c47` (2026-09-16) and
`3c02c34c4b` (2026-09-20) all address one cluster:

- file:// URL to assets:// conversion on Electron
- Windows drive-letter protection in Zotero PDF paths
- external PDF asset reuse and deduplication
- zotero-link reference resolution
- annotation creation ordering and failure surfacing

That count is a rework signal: the same area was patched repeatedly across five
days, and two of the commits are labelled `refactor:` rather than `fix:`. The
design had not converged when the patches were written. Proposing this upstream
as-is would likely draw a request to redo it.

Path forward, if this is ever proposed: squash the cluster into 1-2 logical
commits, re-run compile/lint/tests after the squash, and state the root cause in
the commit body.

Longer context: PDF work is a standing thread on this fork. `e-zz` also authored
PDF fixes during the upstream era (2023-10-28 bounding coordinates, 2023-12-21
area highlight blink, 2024-03-19 Linux shift+mouse area highlight).

## Not candidates

The remaining fork commits (MCP write speedup, import cleanup, fork CI workflow)
depend on upstream changes that are not merged, so they cannot be proposed
independently.

## Local state at time of writing

- Branch `merge-all-fixes`, HEAD `13be83e2a8`, pushed as `origin/merge-all-fixes`
  = `bb70c73d29` (remote is stale; the identity rewrite was never pushed)
- Backup ref for the identity rewrite: `backup/pre-identity-fix-20260928-085140`
  -> `65b00df37d`
- The rewrite fixed 5 commits authored as `jz <@>` (invalid email, no GitHub
  account link); tree hash verified identical to the backup, so only metadata
  changed
- Force-push to `origin/merge-all-fixes` is still pending and NOT done. Pushing
  would require `--force-with-lease`, and the old merged SHAs are still
  referenced by other local branches (`fix/pdf-external-asset-refactor`,
  `fix/external-pdf-annotation-state`, `debug/pdf-annotation-runtime`)

Uncommitted in the working tree (not authored by this session):
- `AGENTS.md` modified
- `.agents/guides/` untracked (includes this file)
- `.agents/plans/`, `.hermes/`, `docs/superpowers/` untracked and not covered by
  `.gitignore`; `git add -A` would pick them up
