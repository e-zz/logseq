# Findings — public acceptance comments for #4/#8/#9/#11/#14 (2026-10-04)

## Posted (all verified by read-back, body == draft, no duplicates, privacy-clean)

| Issue | Comment ID | URL | Verified |
|---|---|---|---|
| #4 | 5981225906 | https://github.com/e-zz/logseq/issues/4#issuecomment-5981225906 | yes |
| #8 | 5981226056 | https://github.com/e-zz/logseq/issues/8#issuecomment-5981226056 | yes |
| #9 | 5981226274 | https://github.com/e-zz/logseq/issues/9#issuecomment-5981226274 | yes |
| #11 | 5981226419 | https://github.com/e-zz/logseq/issues/11#issuecomment-5981226419 | yes |
| #14 | 5981226568 | https://github.com/e-zz/logseq/issues/14#issuecomment-5981226568 | yes |

Repo is PUBLIC (verified via `gh api repos/e-zz/logseq`); privacy scan run on drafts before posting and on live bodies after posting — no UUIDs, graph names, local paths, host identifiers, or personal content. No issue was closed/reopened; no code, graph, config, or UI changes; no benchmark writes.

## Evidence mapping (claim → source)

- Build identity: run 37203268496 / SHA fb5eb4eb43cdb3be7a29d816b969d45c127d4861 — verified live via GitHub Actions API (conclusion=success, head_sha match, workflow_dispatch). All comments attribute MCP acceptance to this build only.
- #4: parent-issue4-bullet-rejection.md (verbatim API error, before/after identity, search-empty), worker-number-toggle-in-progress.md (UIA number→bullet removal + un-restored state), worker-ui-display-20261004.md (static 1/2/3 screenshot evidence), parent-populated.md (number writes + readback), parent-negative-atomicity.md (batch atomicity pattern). Source: tools.cljs:679-685 (number-only contract + removal-unsupported message).
- #8: parent-populated.md (tree readback, scopes, descendant exclusion), parent-recycle-restore.md (recycle/restore + search isolation + no duplicates), parent-ci-remaining-todo.md (fresh-session HTTP readback), worker-ui-display-20261004.md (UI confirmation). Source: cli.cljs:33-71 (worker-state conn for all read thread-apis), db_based/cli.cljs:278-360 (receipt verified via worker DB readback; docstring explicit about no crash durability / no search fence), search.cljs sync path (recycled exclusion via deleted-at datoms).
- #9: parent-populated.md, parent-recycle-restore.md (many union/dedupe shape [parent,contrast]+[parent,child,child]→{parent,contrast,child}, false/0 retention), parent-negative-atomicity.md, worker-url-negative.md, worker-checkbox-negative.md, parent-issue7-invalid-status.md, parent-issue9-class-rejection.md, parent-issue9-date-value.md, parent-issue9-asset-value.md, parent-ci-remaining-todo.md (deadline/scheduled epoch), parent-reopen-and-asset-fixture.md (normal reopen persistence). Source: tools.cljs:693-715 (null/empty-array rejection; many=union with comment at 713-715), 687-696 (many closed values unsupported).
- #11: parent-ci-remaining-todo.md (page-scoped search on new blocks, hasMore?=false), parent-populated.md (title edit → old token gone, new token exact), parent-reopen-and-asset-fixture.md (search after normal reopen), 14-parent-acceptance-status.md (production transport + same-session add/search on prior run). Source: db_based/cli.cljs:333-335 (runtime-write? tag), db_listener.cljs:174-179 (skip only for bulk imports), op.cljs:18-20 (keyword docstring). Comment explicitly says same-session add→search was re-measured on this build via the measured search cases, and that GUI box/((...)) autocomplete remain unverified.
- #14: parent-recycle-restore.md (ordinary-block recycle/restore + search), setup.md (includeRecycled-style errors on block paths). Source: tools.cljs:233-269 (resolve-page-for-read: active-first, includeRecycled opt-in, ambiguity errors), :319-358 (getBlock recycled-page marker), recycle.cljs:176-191 (page recycle tx). Comment clearly separates source-verified behavior from the not-yet-run live recycled-PAGE collision fixture, and lists the needed fixtures/checks.

## Honest epistemic categories used in comments

- MEASURED (this build, live MCP): everything under "verified" sections above.
- SOURCE-VERIFIED ONLY: #14 page-collision resolution semantics; #11 incremental-index code path.
- PRIOR-RUN MEASURED (different CI run/build, NOT re-claimed on this build): none asserted; the only cross-run reference is #11's parenthetical noting earlier acceptance on the prior run was not re-measured there but is now measured on this build.
- NOT VERIFIED (explicitly listed, not claimed): #4 dynamic renumbering; #8 GUI surfaces + no timing matrix; #9 non-journal date rejection + reopen of the specific asset batch; #11 GUI search box + ((...)) autocomplete; #14 live recycled-page fixture.

## Residual gaps / not done by this worker

1. #14 live recycled-PAGE + same-name collision fixture not yet run (GUI/UI write owner is a different worker; needs its own authorized write).
2. GUI-side search/autocomplete re-verification pending GUI worker.
3. No controlled per-operation performance matrix exists for #6–#9 on this build; comments state this explicitly where relevant rather than quoting tool durations or historic timestamps.
4. parent-ci-remaining-todo.md's #12 stub status (BLOCKED on not-built CLI) is stale; parent-issue12-package-file-preservation.md (superseding) covers the later CLI build, and #12 already has its own posted update (issue12-public-validation-update.md) — left untouched here.
