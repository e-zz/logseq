# #14 real recycled-page acceptance: fixture baseline

Native desktop MCP on current disposable graph. Before creating anything, existing #11 fresh page and its fresh block were read and matched their expected UUIDs. getPage("ISSUE14-RECYCLE-PAGE-A") returned Page not found.

Exactly one upsertNodes call on this user request added a dedicated page and one canary ordinary block using a temporary page reference, without receipt (new page + block batch). Result Added: {:page 1, :block 1}.

Independent getPage readback:
- page title ISSUE14-RECYCLE-PAGE-A; UUID 7e46503d-b21f-424c-a94b-ad191560c600; created-at=updated-at=1791130769484.
- canary title ISSUE14-RECYCLE-CANARY-A; UUID af8ca48b-78a6-46d0-a455-239f06857b65; parent id=355; order=a5; level=1.

State: ACTIVE fixture baseline verified. No page recycling/restoration or permanent deletion performed by agent. Request user to move only this dedicated page to the recycle bin using normal UI, leaving it recycled while parent verifies #14 read/list/search behavior. Warn that the page and its canary will disappear from active pages but remain recoverable. Do not automatically restore or clean it. Do not recycle existing user pages.

Pending after user recycling: default name/UUID getPage rejection; includeRecycled=true read with deleted-at and retained canary; listPages exclusion/inclusion; recycled generation UUID identity; later active same-name fixture selection. Name collisions must be tested through supported operations only, no raw DB editing.

Private names/UUIDs retained locally, not for public comments.
