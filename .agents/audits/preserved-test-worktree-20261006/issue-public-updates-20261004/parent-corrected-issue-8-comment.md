# Acceptance update — read/verify-side gaps (2026-10-04)

**Parent review correction:** the earlier version incorrectly attributed earlier acceptance evidence to run 37203268496. The live evidence below comes from earlier integrated-build acceptance, including [run 37134185100](https://github.com/e-zz/logseq/actions/runs/37134185100); it was not all re-run on [run 37203268496](https://github.com/e-zz/logseq/actions/runs/37203268496). Source-level contracts are not additional live test results. No controlled performance benchmark was collected. No source changes.

## §1 — `getPage` tree (verified working)

`getPage` with `includeChildren=true` and a positive integer `maxBlocks` returns the complete nested block tree with correct `children` arrays at each level (verified on a three-level parent → child → grandchild structure). The budget is enforced: a `maxBlocks` smaller than the full tree is rejected with an error naming the required count — no silent partial tree.

Note for agents: the **default** read (no `includeChildren`) still returns top-level blocks only, with `tree-has-more?`/`tree-omitted-count` set when the page has descendants — same semantics as the earlier comment, now with a full-tree option.

## §2 — `searchBlocks` page/block scope (verified working)

- **Page scope**: `pageUuid` limits results to that page.
- **Exact-block scope**: `blockUuid` limits results to that one block.
- The two must identify the same page or the call is rejected (`pageUuid and blockUuid must identify the same page`) — no silent global fallback.
- **Exact-block scope excludes descendants**: with `blockUuid` set to a parent block, a token that also matches its children returns only the parent itself. Verified with an actual nested structure.
- A title edit changes search results immediately within the same session (old token → no hits, new token → exact hit), without any index rebuild or restart.

## §3 — receipt readback (verified working; scope clarified)

With `receipt=true`, the response is `mode=verified` only if, **after the transaction**, a readback from the worker's in-memory graph state matches the requested UUID, page, parent (for blocks), title (when present), and requested property values. A mismatch rejects the call (the transaction itself is already complete; the receipt error surfaces it).

Two clarifications on what the receipt does and does not cover:

- The live read path (`getPage`/`getBlock`) and the receipt readback both operate on the **worker's current graph state** (the running DataScript connection, per `worker/handler/cli.cljs` — all `thread-api` read handlers resolve `worker-state/get-datascript-conn`), not a freshly opened SQLite database. SQLite persists that state; the search index is a separate store updated by a worker listener.
- The receipt verifies **post-transaction in-graph state**. It does **not** verify: filesystem flush/disk durability (crash scenario), search-index completion, or any read taken before the transaction commits. So a verified receipt is a real state readback, but it is not a crash-durability guarantee and not a search-completion fence.

## §4 — single-block lookup (verified working)

`getBlock` returns exactly one visible block by UUID (no descendants in the payload). It rejects: non-UUID input, page/tag/property entities (`is a page, tag, or property, not a block`), property-value pseudochildren, and recycled blocks by default (with an `includeRecycled` opt-in that carries the page's deleted-at marker). Delete operations remain out of scope — soft recycling exists via `recycleBlock`/`restoreBlock` (agent-facing tools), not a hard delete.

## Search and recycled content

- Recycled subtrees are excluded from both page-scoped and global search; after restore they reappear. Verified with a real recycle → search → restore → search cycle on a three-block subtree.
- `getRecycledBlock` returns the recycled root's deleted-at, original page/parent/order, and its retained subtree.

## Not yet verified (not claimed)

- GUI-side behavior (search box, autocomplete) — MCP `searchBlocks` is verified; the in-app GUI surfaces were not re-checked in this pass.
- No controlled per-operation latency/timing measurements were taken in this pass; timestamps in evidence are event times, not server-latency figures.