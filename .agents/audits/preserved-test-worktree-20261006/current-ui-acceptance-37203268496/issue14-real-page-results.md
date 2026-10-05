# #14 real page recycle: measured results and fixture-creation blocker

Current acceptance artifact track: non-release Windows CI 37203268496. Native desktop MCP. User manually deleted only dedicated ISSUE14-RECYCLE-PAGE-A in the app and reported that search no longer shows a recycled-labelled result. Agent did not recycle, restore, permanently delete, restart or rebuild index.

## Passing directly observed read/search/list cases
Fixture page UUID 7e46503d-b21f-424c-a94b-ad191560c600; original canary UUID af8ca48b-78a6-46d0-a455-239f06857b65.
- getPage(name), without includeRecycled: explicit recycle-bin error, instructing opt-in; original content not returned.
- getPage(original UUID), without includeRecycled: Page not found; original content not returned. UUID rejection message differs from name error; do not claim both paths carry same error text.
- getPage(original UUID, includeRecycled=true): original page identity/title, deleted-at=1791130990434, and retained original canary title/UUID/parent/order returned.
- getPage(name, includeRecycled=true), before any same-name active generation exists: same recycled identity, deletion marker and canary returned.
- listPages default result omits target UUID/title. listPages(expand=true, includeRecycled=true) includes target UUID with the same deleted-at.
- searchBlocks for both recycled page title and original canary title returned blocks=[], files=[], hasMore?=false.
Interpretation: recycled fixture retained, default page reads and ordinary indexed search exclude it; explicit opt-in reads/list include it with deletion marker. Ordinary GUI search need not display a recycle label because this search surface excludes recycled content. Source of deleted-at is real runtime readback, not inferred from user word 'delete'.

## Attempted active/recycled same-name fixture
Exactly one upsertNodes call on this user request:
1. add page temporary id=issue14-active-generation-b, title=ISSUE14-RECYCLE-PAGE-A.
2. add ordinary block under that temporary page reference, title=ISSUE14-ACTIVE-CANARY-B.
No receipt requested (new page + block batch).
Reported response: Added: {:page 1, :block 1}.

Independent actual readback contradicts interpreting this as a fresh active page:
- Default getPage(name) STILL gives recycle-bin error.
- Both getPage(name, includeRecycled=true) and explicit original UUID opt-in read return the SAME old recycled UUID, SAME deleted-at, retained original canary, and the newly added canary.
- New canary UUID a42a21b9-66a7-459a-880d-e6ab5b2ae2a9, parent id=355 (original recycled page), order=a6, created-at=updated-at=1791131158816.
- Search for new canary and old canary both empty.
Measured conclusion: this MCP add-page/add-block attempt reused the recycled page and attached the new block to it; it did not produce the expected fresh active same-name fixture. The page-level reported add count is not independent evidence of a new entity.

Do not label this as failure of #14's active-first resolver: no active same-name generation has been demonstrated. It is a fixture-creation blocker and an observed recycled-target write behavior requiring separate scope/diagnosis. No root cause assertion without source/runtime tracing. Search exclusion of the new canary on the recycled page is expected, not a regression in #11.

## Remaining
- Actual active/recycled same-name coexistence and active-first name resolution NOT TESTED.
- Multiple active same-name ambiguity NOT TESTED.
- Need supported GUI/API method to construct these conditions. Do not mutate raw SQLite or silently restore the recycled page as substitute.
- No further writes to this recycled fixture, no cleanup/restoration unless requested.
- #14 remains OPEN; closure not authorized. Keep public claims limited to the passing cases and explicitly report the collision fixture gap.

Private fixture names, UUIDs and user graph details are local-only. Redact before public publication.
