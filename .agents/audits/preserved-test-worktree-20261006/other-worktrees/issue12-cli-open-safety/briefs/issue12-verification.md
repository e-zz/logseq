# Issue #12 independent verification — CLI reopen file safety

Date: 2026-09-30 (verification run). Worker: independent GLM verification pass.
Worktree: C:/Users/zhang/orca/workspaces/logseq/issue12-cli-open-safety
Branch: e-zz/issue12-cli-open-safety. Nothing committed or pushed.

## Scope
Missing-tests item for #12: partial-existing-graph regression (some default file
entities absent), explicit policy for missing defaults, caller trace of
create-graph helpers, and the separate KV/schema-version reopen question.

## Files changed
- src/test/logseq/outliner/cli_issue12_test.cljs (untracked, extended): added
  `init-conn-partial-graph-preserves-existing-and-recreates-missing-defaults`.
  The two tracked source changes (deps/db/src/logseq/db/sqlite/create_graph.cljs,
  deps/outliner/src/logseq/outliner/cli.cljs) were preserved untouched.

## New regression test (partial graph)
Disposable DB graph: fresh `init-conn` install -> retract `logseq/custom.css`
and `logseq/custom.js` entities -> sentinel the remaining three files
(config.edn, publish.css, publish.js) with non-default strings -> add an
unrelated block -> reopen through real `outliner.cli/init-conn` twice.
Asserted: the three sentinels unchanged across both reopens; unrelated block
survives; the two missing defaults are recreated with creation-time defaults
(empty string); a further reopen keeps the completed set intact.

Policy made explicit (documented in the test comment): on CLI reopen, existing
file entities are never rewritten; default files absent from the graph are
recreated with creation-time defaults (config template content for
logseq/config.edn, "" for custom.css/custom.js/publish.css/publish.js).

## RED evidence
Temporary evidence namespace (deleted after the run) redefined
`logseq.db.sqlite.create-graph/build-db-initial-data` to strip the
`:existing-file-paths` option, simulating pre-fix behavior, then reopened a
sentineled graph. Observed failures (2):

- FAIL: config.edn expected "sentinel-config-red", actual "{}"
- FAIL: publish.css expected "sentinel-publish-red", actual ""

With the fix intact the same path passes (GREEN), matching issue #12's
"unpatched 2nd open" column.

## Test commands and results (all on the compiled test build; `pnpm cljs:test`
recompiled before running; `bb dev:run-test-namespaces` to execute)
- `-n logseq.outliner.cli-issue12-test`: 3 tests / 13 assertions, 0 failures,
  0 errors (was 2/7; +1 test / +6 assertions = the new partial-graph test)
- `-n logseq.db.sqlite.create-graph-issue12-test`: 1 test / 5 assertions,
  0 failures, 0 errors
- `-n logseq.api.db-based.cli-test`: 6 tests / 17 assertions, 0 failures,
  0 errors
- `git diff --check`: clean

## Caller trace of the create-graph helper (item 3)
- `logseq.outliner.cli/setup-init-data` (CLI create + open paths): supplies
  `:existing-file-paths`; covered by the tests above.
- `logseq.db.sqlite.export/create-conn` (export.cljs:1435-1441, also used by
  `validate-export`): builds a FRESH in-memory conn and transacts
  `build-db-initial-data "{}"` with no option. Correct by construction: there
  are no pre-existing file entities, so all five defaults install. Unaffected
  by the fix; not exercised at runtime (static trace only).
- `frontend.worker.db-core` (:783): transacts initial data only when the graph
  is empty (`when-not (or initial-data-exists? (seq datoms)
  sync-download-graph?)`) — creation-time only, not a reopen path.
- `frontend.worker.db.migrate/ensure-built-in-data-exists!` (:180): explicitly
  drops all `:logseq.kv/*` datoms from the initial tx ("should not be
  overwritten") and merges preserved content for existing entities.
- Script callers `deps/db/script/create_graph.cljs` and
  `deps/graph-parser/script/db_import.cljs` route through `init-conn`, so both
  inherit the guard. Not exercised at runtime in this pass.

## KV/schema/version re-transaction on reopen (item 2 — separate safety question,
NOT fixed here, outside the five-file scope)
Evidence (temporary namespace, disposable graph, real CLI reopen):

- `:logseq.kv/graph-created-at` is reset to the reopen time on every
  `init-conn` open (observed 1790785188071 -> 1790785188789).
- `:logseq.kv/local-graph-uuid` is REGENERATED with a fresh random UUID on
  every reopen (00000000-e3f1-... -> 00000000-8b2b-...). This is a graph
  identity change caused by merely opening the graph through the CLI.
- `:logseq.kv/schema-version` and `:logseq.kv/graph-initial-schema-version`
  are re-asserted to the current `db-schema/version` (65.33 in this tree). On
  a current graph the value does not change, but an older graph opened through
  the CLI would have both bumped to the compiled version without any migration
  running, and `graph-initial-schema-version` no longer records the version
  the graph was actually created with.
- `:logseq.kv/graph-git-sha` and `:logseq.kv/import-type` are re-asserted with
  unchanged values in this run.

All of these ride in the same `setup-init-data` transaction that the five-file
guard now partially shields; the kv datoms are not covered by any guard on the
CLI path. Reported separately per the brief; no code change made.

## Residual risks
1. The kv re-transaction above (identity regeneration + timestamp reset +
   unconditional schema-version assertion) remains open and predates the fix.
2. The partial-graph test asserts `:file/content` strings via Datascript, not
   raw SQLite byte-level equivalence (same limitation as the existing tests).
3. Export/temporary-graph and script callers were traced and reasoned about,
   not exercised at runtime (fresh-in-memory construction makes the export
   path correct by construction).
4. The new untracked test file and the two tracked source changes remain
   uncommitted; parent review/integration still required.
