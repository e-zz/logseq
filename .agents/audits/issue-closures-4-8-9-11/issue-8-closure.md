## Resolution: MCP tree reads, scoped lookup and verified receipts

Closing as completed with maintainer approval for the accepted read/verify contract. The evidence and limits below distinguish transaction-state verification from disk durability.

### Verified acceptance (measured)
- `getPage(includeChildren=true, maxBlocks=...)` returns the tested complete multilevel tree. An insufficient budget errors rather than silently truncating it. Default shallow reads expose completeness metadata.
- Page UUID and exact-block UUID search scopes work; mismatched scopes are rejected. Exact-block search does not silently include descendants or fall back to global search.
- `receipt=true` returns verified operation identities/state only after transaction readback, rather than merely counting the input operations.
- `getBlock` provides single-block UUID lookup with tested entity-type and visibility rejection.
- `recycleBlock`, `getRecycledBlock` and `restoreBlock` support the tested reversible subtree lifecycle. Ordinary search excludes the recycled subtree and returns it after restoration.

### Build attribution
The core live acceptance is recorded in the earlier parent-reviewed evidence under [run 37134185100](https://github.com/e-zz/logseq/actions/runs/37134185100), not as a comprehensive rerun on [run 37203268496](https://github.com/e-zz/logseq/actions/runs/37203268496). Later search tests are separate evidence; neither CI success nor GUI search tests substitute for these read/verify checks.

### Contract and limits retained after closure
- Receipt verifies the worker's post-transaction DataScript graph state. It does not prove filesystem flush/crash durability or search-index completion.
- A receipt mismatch reports an already-completed transaction's verification failure; it is not a promise to roll back that transaction.
- Reversible recycling is the accepted removal mechanism. Permanent deletion and general re-parenting are not claimed.
- Recycled-page name/generation resolution remains separately tracked by #14; the write-time recycled-title collision remains #21.
- No latency benchmark, exhaustive fault-injection matrix or full application no-regression certification is claimed.

No additional production change or live graph mutation was performed for this closure.
