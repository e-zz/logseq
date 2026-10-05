# Status update — trashed-page visibility in `getPage` (2026-10-04)

Source-verified against the integration tree (commit `fb5eb4eb43cdb3be7a29d816b969d45c127d4861`) plus live behavior of the built-in MCP server on a disposable DB graph. No source changes.

## What the current source does (`src/main/logseq/api/db_based/tools.cljs`, `resolve-page-for-read` / `get-page-data`)

- **Active page wins on name lookup.** If a name has exactly one active generation, `getPage` returns it regardless of how many trashed generations exist. Trashed generations are only considered when **no active generation** has that name.
- **Explicit opt-in for trashed pages.** `getPage(name, {includeRecycled: true})` returns a trashed generation; the payload carries the page's `deleted-at` marker (source: `get-page-data` docstring "Recycled pages are only returned when `:include-recycled?` is true").
- **Ambiguity is reported, not silently resolved.** Multiple active generations sharing a name → error listing candidate UUIDs. Multiple trashed generations with no active one → same, for the trashed set.
- **UUID lookup** works the same way: trashed page UUIDs are rejected by default and accepted with `includeRecycled: true`.
- **`getBlock` mirrors this**: a block on a trashed page is rejected by default with a pointer to `includeRecycled`.

So the two surfaces now agree in the reported collision case: `listPages` hides trashed generations, and `getPage` by default resolves to the active generation or errors — instead of returning a trashed generation's block list without any indicator.

## Measured live (disposable DB graph, packaged build)

- `getRecycledBlock` on a recycled **block** root returns deleted-at, original page/parent/order, and the retained subtree; ordinary `getBlock` on the same UUID rejects it as hidden.
- Recycled subtrees are excluded from page-scoped and global search, and reappear after `restoreBlock`; the restored subtree lands at its original parent/order with no duplicates.

## Honest gap: no live recycled-PAGE / collision acceptance yet

The live tests above exercised the **ordinary-block** recycle path (`recycleBlock`). The specific scenario in this issue — a **recycled page generation** colliding with an active page of the same name, read through `getPage` on a real app instance — has **not** been end-to-end measured yet. The behavior above is verified from source only for that case. Needed:

1. A fixture: a page that has an active generation and at least one recycled generation with the same name (e.g. created, renamed, or rewritten; the page entity must carry `deleted-at`).
2. Live checks:
   - `getPage(name)` without options → returns the active generation (or the documented error if none active);
   - `getPage(name, {includeRecycled: true})` → returns the recycled generation with `deleted-at` present;
   - multiple recycled generations → ambiguity error listing candidate UUIDs;
   - `getBlock` on a block of the recycled page → rejected by default, readable with `includeRecycled`.
3. Confirm `listPages` (default) excludes the recycled generation while `includeRecycled: true` lists it.

Until that fixture passes live, this issue should be read as: **source contract implemented and verified by code; end-to-end page-collision acceptance pending.**
