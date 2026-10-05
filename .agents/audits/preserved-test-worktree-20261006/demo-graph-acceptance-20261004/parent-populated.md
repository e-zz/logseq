# Parent-run populated demo acceptance — 实测

Target page TEST-DEMO-20261004-A UUID 63bab91a-013d-44df-afb7-4c83026b03ef. Parent re-read full page before writing to confirm current graph/targets. Exactly one upsertNodes call, receipt=true: seven edits plus two block adds. Receipt nine operations verified; independent getPage complete tree and getBlock(metadata) agree.

## Typed definitions + values

Definitions independently verified in parent-review.md. Metadata block d8d5836c-5a3c-4fe8-8258-f0edd7d3c706 now carries:
- URL 00000002-1190-2949-4400-000000000000 => https://example.org/demo-cv.
- Text ORCID 00000002-2061-0048-5900-000000000000 => TEST-ORCID-NOT-A-REAL-ID.
- Node/many Topics 00000002-4607-5057-9000-000000000000 => exact UUIDs 70ec2dc8-8fcc-4c97-b134-bdf2a24f3cda and e6a5a455-812d-4672-ae8f-7c0a5e3633c0.
- Text Description 00000002-2040-4008-4000-000000000000 => 专用类型属性真实赋值测试.
- Number 00000002-1290-4969-3200-000000000000 => numeric 42.5.
- Checkbox 00000002-1028-8501-4900-000000000000 => boolean true.
Metadata title unchanged (retains 等待赋值 intentionally to verify property-only edit preserves title).
Page carries same URL definition => https://example.org/demo-page, Number=>7, Checkbox=>false; false explicitly present in receipt and independent getPage entity, not omitted. Page title unchanged.

## Numbering and Task

Ordinary numbered item UUIDs 69c8cc2b-dc3a-40e1-b133-a0781a131cbb, 76a13258-d931-41c9-a21d-14fd6dbacaed, 0c8fa0e5-fe1b-4d82-ac90-d5be7bd713f5 now each have real order-list-type number. Items remain page-root siblings, not nested under heading. UI numbering not yet observed in this graph.
Task a4454633-532a-443f-8d3a-d06426f0a1d0 now actual Todo status plus Task tag; misleading TODO prefix removed, title 专用类型属性任务测试. API verifies genuine status, UI Task view/filter not observed.

## Nested temporary parent id proves worker limitation claim wrong

Parent 70ec2dc8-8fcc-4c97-b134-bdf2a24f3cda -> new child d00c2214-fa31-44af-bbea-7f5f9a7c45b9 -> new grandchild 61859851-aec0-4973-9543-8c4d5bf2c802. Child parent-id used real parent UUID; grandchild parent-id used temporary demo-child id, both page-id=current real page UUID. Complete readback levels 1/2/3 and receipt parent UUIDs agree. Single-call temporary parent nesting works.

## Search without restart/rebuild

Contrast block e6a5a455-812d-4672-ae8f-7c0a5e3633c0 edited from NESTTOKEN to EDITTOKEN. Next exact-block search for LQDEMO20261004NESTTOKEN returns empty, new LQDEMO20261004EDITTOKEN returns exact contrast block. Parent page-scope old token returns parent/child/grandchild/heading UUIDs, hasMore? false; exact-parent scope returns only parent, excluding true matching descendants, hasMore? false. Unlike worker preparation phase, actual descendant exclusion now tested.

## Still NOT_RUN

UI in new demo graph; typed invalid-value rejection and atomicity; many append/dedupe; list-to-bullet removal; task Done/reverse UI edits; recycle/restore; normal reopen persistence; crash durability; CLI safety; PDF/sync. One-call-per-user-request upsert constraint remains: no second write/dry-run in this request. No deletion, recycling, restart, installation or config modification performed.
