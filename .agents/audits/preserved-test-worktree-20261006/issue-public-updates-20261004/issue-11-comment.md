# Acceptance update — MCP-written block searchability (2026-10-04)

Measured against the Windows CI build [run 37203268496](https://github.com/e-zz/logseq/actions/runs/37203268496), commit `fb5eb4eb43cdb3be7a29d816b969d45c127d4861` (non-release test build), on a disposable DB graph via the built-in MCP server. No source changes.

## MCP-side: incremental indexing now works (verified)

The symptom in this issue — MCP-written blocks not found by `searchBlocks` — no longer reproduces on this build:

- **New block immediately searchable**: a block added via `upsertNodes` is found by `searchBlocks` in the same session, page-scoped, without waiting, index deletion, or rebuild. (Earlier acceptance runs on the previous CI build had the same-session add → search passing, but that was not re-measured on this exact run; it is measured now.)
- **Title edit → old text disappears, new text searchable**: editing a block's title removes the old title from search results and the new title appears, immediately, same session. This also covers the rename case from the issue.
- **Search results are not lost on normal reopen**: a full page read plus the same token search after a normal close/reopen returned the expected blocks with `hasMore?=false`.
- **Transport**: all of the above went through the built-in HTTP MCP endpoint of the packaged app (production transport), not a test harness or worker-only path.

Source-level, for maintainers: `upsertNodes` tags its import transaction as an incremental runtime write (`:logseq.outliner.op/runtime-write?` in `src/main/logseq/api/db_based/cli.cljs`), and the worker's search listener only skips index sync for genuine bulk imports (`skip-search-sync?` in `src/main/frontend/worker/db_listener.cljs`) — so MCP writes are indexed while file-graph import still uses its post-import rebuild path. This is the code path the original issue's diagnosis flagged as missing.

## Still not verified (not claimed)

- **GUI search box**: in-app search UI behavior for MCP-written blocks was not re-tested in this pass. MCP `searchBlocks` is a separate (worker-side) surface from the GUI search box; verified ≠ GUI-verified.
- **`[[...]]` page-reference autocomplete**: `[[...]]` candidates are page references, and ordinary blocks are not page-reference candidates — so absence there is expected behavior, not the search-index bug in this issue. This should not be counted as a failure of this fix.
- **`((...))` block-reference autocomplete**: not tested; needs a dedicated pass in the app UI.
- Recycled blocks are excluded from search and reappear after restore (verified via the MCP cycle) — listed here for completeness since the issue concerns search index freshness.

## Latency

No controlled per-operation timing measurements were taken in this pass.
