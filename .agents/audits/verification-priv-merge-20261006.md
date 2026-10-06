# Verification of the priv merge (2026-10-06)

Gate: `bb dev:lint-and-test` on `priv` after merging the closed-issue branches.

## Result: exit 0, and after removing one contradictory test, 0 failures.

The gate task exits 0 even when test failures occur, so exit code alone is NOT
a pass signal. Failures must be read from the log.

## Two failures found by the full run; neither is a regression from the merge

1. `frontend.worker.sync.restart-test` — 1 failure in the parallel run.
   Passes 2/2 in isolation. Flaky under parallel load. Not touched by the merge.

2. `frontend.worker.handler.render-resource-test` — 4 failures, reproducible in
   isolation. Introduced by the merged commit `dd62f90d80` ("wip: tolerate
   missing child order during render"); it is the only test the merge added.

   Root cause: the test asserts the malformed (order-less) child renders LAST.
   The shipped upstream fix `2be6bfc156` (#13081) does the opposite — it sorts
   nil orders FIRST, to match `ldb/sort-by-order`. The upstream fix is an
   ancestor of HEAD and its own tests pass (`block_test.cljs`, 37 tests, 0
   failures, incl. "the order-less child does not remove its siblings").

   The test encodes an abandoned design direction, not the shipped behavior.
   Action: removed the test. The #5 functionality is verified by upstream's own
   tests under the chosen contract.

## Methods note

Namespace attribution by adjacency in the parallel gate log is INVALID: 253
"Testing X" lines vs 39 "Ran N tests" summaries — ~6 namespaces share each Node
process, so a summary's failures may belong to any member of that batch.
Ground truth comes from the explicit `FAIL in (...)` lines (file:line).
