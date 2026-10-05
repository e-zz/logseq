# 05 Validation Repair — Final

Worktree: `D:/orca/workspaces/logseq/issue9-typed-properties`
Branch: `fix/issue-9-mcp-properties-wip`
HEAD: `f2958757d2 wip: support numeric MCP property writes`
Status: focused suites green; full gate has documented blockers; source uncommitted.

## Baseline provenance

Parent independently built and ran an isolated detached worktree at `f2958757d2`
(`issue9-baseline-f2958757`, clean status) and recorded it in
`05-parent-verification.md`. I did not call tools in that external directory.

- Baseline `pnpm cljs:test` exit 0 (artifacts from isolated baseline source).
- Baseline `node static/tests.js -n frontend.worker.search-test` exit 1:
  65 tests / 203 assertions, **3 failures / 1 error** — same test vars as the
  current worktree pre-repair (current pre-repair: 73 tests / 235 assertions,
  3 failures / 1 error).
- Therefore the two disputed search failures are established **pre-existing on the
  selected baseline**. This does not prove all other current paths correct.

## Search fixture repairs (DONE)

Two failing tests were fixture identity bugs, not production bugs:

- `search-indexes-hide-by-default-properties`
- `sync-search-indice-reindexes-holders-when-property-is-deleted`

Root cause: `db-test/create-conn-with-blocks` routes `:properties` keys through
`sqlite-build/create-all-idents`. An **unqualified** key
(e.g. `:keywords`, `:foo`) goes through
`db-property/create-user-property-ident-from-name`, which appends a random suffix
(observed idents `keywords-rp5XaGoi`, `author-P0WV9VDF`). The tests then looked up
the deterministic `:user.property/keywords` / `:user.property/foo`, got `nil`, and
either asserted against a nil entity or transacted `[:db/add e :block/refs nil]`
(the reported `Cannot store nil ... [:db/add 193 :block/refs nil]`). A **qualified**
key instead goes through `db-ident/create-db-ident-from-name` and is deterministic.

Fix (narrow, fixtures only): qualify the two fixtures to
`:user.property/keywords`, `:user.property/author`, `:user.property/foo` in both
`:properties` and `:build/properties`, matching the established pattern
(`frontend.worker.markdown-mirror-test`, etc.). No assertion weakened, no skip, no
production behavior changed.

Evidence after repair (fresh compile):
`node static/tests.js -n frontend.worker.search-test`
-> **73 tests / 246 assertions, 0 failures, 0 errors** (was 3 failures / 1 error).
Assertion count rose because the earlier error aborted later assertions.

## Closed-value discovery investigation

The dispatch claimed `(into {} e)` keeps raw `:property/closed-values` when the
filtered allowed set is empty.

Finding: not reproducible. `:property/closed-values` is a **virtual entity-plus
attribute**, derived in `deps/db/src/logseq/db/common/entity_plus.cljc:207` from
`:block/_closed-value-property`; the stored schema attribute is
`:block/closed-value-property` (`deps/db/src/logseq/db/frontend/schema.cljs:100`).
`Entity -seq` (`entity_plus.cljc:267`) yields only touched forward attrs plus the
kv map, so `(into {} e)` cannot contain it. A debug run of the new test with the
**unfixed** code already showed `:choices-count 0` and no hidden uuids, i.e. no RED.

Action: per dispatch, added an explicit sanitization guard to `list-properties`
in `src/main/logseq/api/db_based/tools.cljs` (dissoc `:property/closed-values`
before conditionally re-assoc'ing the filtered, JSON-safe choices) and kept the
behavior test `list-properties-omits-hidden-closed-values-and-write-agrees`, which
locks the invariant: a property whose choices are all hidden/recycled exposes no
choices and the writer rejects with the same allowed set. No genuine RED was
captured for this item; the guard is belt-and-suspenders at the external API
boundary rather than a reproduced-defect fix.

## lint / diff

- `clojure -M:clj-kondo --parallel --lint src --cache false` -> **errors: 0, warnings: 0**.
- `git diff --check` -> exit 0 (only a CRLF normalization notice on
  `property_write_test.cljs`).

## Focused suites (fresh compile, all green)

| namespace | tests | assertions | failures | errors |
|---|---|---|---|---|
| `logseq.api.db-based.property-write-test` | 12 | 81 | 0 | 0 |
| `logseq.api.db-based.issue6-test` (parent) | 10 | 41 | 0 | 0 |
| `logseq.api.db-based.cli-test` | 18 | 67 | 0 | 0 |
| `logseq.api.db-based.tools-test` | 35 | 183 | 0 | 0 |
| `electron.mcp-server-test` | 3 | 7 | 0 | 0 |
| `frontend.worker.search-test` | 73 | 246 | 0 | 0 |
| `frontend.handler.search-test` | 11 | 29 | 0 | 0 |
| `frontend.worker.handler.search-test` | 6 | 19 | 0 | 0 |

## Full gate `bb dev:lint-and-test`

Outcome: **exit 1** (no green fiction).

- clj-kondo step: errors 0 / warnings 0.
- `lint:large-vars` **FAILS**:
  `upsert-nodes` is 119 lines (max 100) in
  `src/main/logseq/api/db_based/cli.cljs`. This file is not modified in the working
  tree; the var is committed in `f2958757d2` and is therefore present at baseline
  too. `run-parallel!` throws on this task error (`scripts/src/logseq/tasks/dev.clj:83`,
  `:165`) and aborts the parallel run.
- `electron.embedding-server-test` reports **12 failures**, all Windows
  path-separator mismatches (`/users/me/...` expected vs `\users\me\...` actual).
  Isolated as a Windows-only environmental blocker; out of this slice's scope.
- Raw output kept at
  `.agents/audits/2026-10-03-mcp/scratch/lint-and-test.txt`.

## Remaining gate blockers / scope boundaries

- `lint:large-vars`: `logseq.api.db-based.cli/upsert-nodes` (pre-existing at
  baseline; needs a narrow refactor, not done here to keep scope closed).
- `electron.embedding-server-test`: Windows path-separator assumptions.
- HTTP/persistence-level MCP integration and normal SQLite reopen remain unverified;
  adapter-level tests only.
- Plugin/editor/mobile Windows/document failures per dispatch are not independently
  re-proven here; treated as remaining gate blockers.
- No user graphs or GUI mutated; synthetic fixtures only. No commits made. No issue
  closed.
