# #11 fresh ordinary block after user-confirmed application restart

Current acceptance track: non-release CI 37203268496. No restart or index rebuild performed by agent during this test. Native desktop MCP used; no independent CLI or SQLite mirror.

## Directly observed API results
- Existing dedicated page ISSUE11-FRESH-PAGE-A was independently read: UUID a7c5ae06-585c-4729-ac52-5f1fe6d5cb88, visible blocks [].
- Before write, global searchBlocks("ISSUE11-FRESH-BLOCK-A", limit=10) returned blocks=[], files=[], hasMore?=false.
- Exactly one upsertNodes call for this user request: receipt=true, add ordinary block with page-id a7c5ae06-585c-4729-ac52-5f1fe6d5cb88 and title ISSUE11-FRESH-BLOCK-A; no task tags/list/property decorations.
- Receipt: mode=verified; operation index=0, status=verified; UUID 5267678c-ff56-4c0d-af51-247e51676a51; page-uuid and parent-uuid both a7c5ae06-585c-4729-ac52-5f1fe6d5cb88.
- Independent getBlock returned exact title/UUID, parent and page matching that page, order=a4, created-at=updated-at=1791130462930.
- Subsequent page-scoped searchBlocks on the page with the same query returned exactly that UUID, title/fullTitle/content all ISSUE11-FRESH-BLOCK-A; hasMore?=false.

## Interpretation
PASS: native API ordinary-block write and independent lookup; indexed page-scoped search finds the fresh ordinary block without an agent restart or manual index rebuild. This is not a latency benchmark, and receipt alone does not establish search completion (actual search was independently executed).
PASS (user-observed GUI): user reports GUI search found the fresh block; [[...]] autocomplete offered the existing block; selecting it and clicking the resulting reference navigated normally. User reply verbatim: "有；有；正常". These are human-observed functional results, not an agent timing measurement. Combined with the independent native API write/read/search evidence, this completes this fresh ordinary-block case. No issue closure authorized.

Private fixture names/UUIDs are for local evidence only; redact from public comments.
