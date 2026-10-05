# Parent recycle test evidence and runner

Repo deps/outliner/package.json script: pnpm exec nbb-logseq -cp test -m nextjournal.test-runner.
Runner -n/--namespace selects namespace, -v selects fully qualified var; -H/--test-help for help. No need to inspect user .gitlibs cache.

Parent directly executed in D:/orca/workspaces/logseq/issue9-typed-properties/deps/outliner:
pnpm exec nbb-logseq -cp test -m nextjournal.test-runner -n logseq.outliner.recycle-test
Exit 1. Ran 19 tests containing 78 assertions. 5 failures, 0 errors.

Failures:
- restore-block-regenerates-malformed-original-order, 3 assertions: "", "!!!", "a b" must not be reused; actual predicate false.
- restore-top-level-page-preserves-order-shape: expected before-order nil, actual "b1y".
- apply-ops-restore-recycled-page-preserves-order-shape: expected before-order nil, actual "b20".

This is actual test failure against current working tree, not source inference. Shared page restore changes must be repaired, not assertions weakened. Existing collision test apparently passed, but baseline collision failure is not proven by this run. Model writes before RED earlier; do not claim historical TDD order compliance.

Keep all tooling workdir within dedicated worktree. Do NOT read or invoke tools inside .gitlibs or external baseline directories. Parent can supply required external information. Nbb dependencies now resolve; simply run documented command above. No need to reinstall or locate cache runner sources.
