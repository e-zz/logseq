# Parent independent validation evidence

Evidence: directly executed by parent in dedicated worktrees. Not worker self-report.

Baseline D:/orca/workspaces/logseq/issue9-baseline-f2958757:
- git rev-parse HEAD: f2958757d211ef16725a0b54d07275baaa686029; git status --short empty.
- pnpm cljs:test exit 0. db-worker-node 441 files/440 compiled/0 warnings, test 1376 files/1375 compiled/0 warnings. Artifacts built from isolated baseline source, not current bundle.
- node static/tests.js -n frontend.worker.search-test exit 1: 65 tests/203 assertions, 3 failures/1 error.
- ERROR sync-search-indice-reindexes-holders-when-property-is-deleted: Cannot store nil as a value at [:db/add 193 :block/refs nil].
- FAIL search-indexes-hide-by-default-properties: hide? keywords nil; indexed-titles excludes literal keywords and author, contains generated keywords-rp5XaGoi and author-P0WV9VDF.
- Same failed test vars/assertions/error as current worktree 73 tests/235 assertions, 3 failures/1 error. Established pre-existing on selected baseline; does not prove all other current paths correct.

Current D:/orca/workspaces/logseq/issue9-typed-properties:
- pnpm cljs:test exit 0; db-worker-node 441 files/0 compiled/0 warnings; test 1378 files/6 compiled/0 warnings.
- node static/tests.js -n logseq.api.db-based.property-write-test exit 0: 12 tests/81 assertions, 0 failures/0 errors.
- clojure -M:clj-kondo --parallel --lint src --cache false exit 0: errors 0/warnings 0.
- git diff --check exit 0 earlier same revision edits; only CRLF normalization notice.

Scope: source remains uncommitted on fix/issue-9-mcp-properties-wip at f2958757d2. HTTP/UI/normal SQLite reopen not verified. Full suite has additional Windows path/document failures; baseline provenance for those NOT independently proven here. No issue completed.
