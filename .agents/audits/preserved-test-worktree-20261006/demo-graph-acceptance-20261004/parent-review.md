# Parent independent review of demo setup

## 实测: current live graph readback

Page TEST-DEMO-20261004-A UUID 63bab91a-013d-44df-afb7-4c83026b03ef exists. Full getPage includeChildren=true maxBlocks=50 contains eight ordinary root blocks, each children=[], no grandchildren. Number items have no order-list-type attribute. Task a4454633-532a-443f-8d3a-d06426f0a1d0 has real Task tag but no status, and title begins TODO. Metadata d8d5836c-5a3c-4fe8-8258-f0edd7d3c706 has no property assignments.

Each property independently read through getPage by exact UUID:
- TEST-DEMO-CV 00000002-1190-2949-4400-000000000000 url/one.
- TEST-DEMO-ORCID 00000002-2061-0048-5900-000000000000 default/one.
- TEST-DEMO-Topics 00000002-4607-5057-9000-000000000000 node/many.
- TEST-DEMO-Description 00000002-2040-4008-4000-000000000000 default/one.
- TEST-DEMO-Number 00000002-1290-4969-3200-000000000000 number/one.
- TEST-DEMO-Checkbox 00000002-1028-8501-4900-000000000000 checkbox/one.

Independent page-scope token search returns UUIDs 70ec2dc8-8fcc-4c97-b134-bdf2a24f3cda, dddcda42-ca26-48c4-8bf5-ad5e939d27b3, e6a5a455-812d-4672-ae8f-7c0a5e3633c0; exact parent scope returns only 70ec2dc8-8fcc-4c97-b134-bdf2a24f3cda. Both hasMore? false. This tests scope contrast on siblings, NOT descendant exclusion because setup has no descendants.

## Excluded worker conclusions / implementation omissions

- Worker brief required parent-id for ordinary block nesting. Failed use of a block temporary id as page-id does not prove temporary parent-id is unsupported. Prior graph parent independently demonstrated successful parent-id nesting. Reject worker's blanket no-single-call-nesting conclusion.
- Worker record setup-results.json entries write-attempt-001/002/003 describe three upsertNodes invocations, contradicting summary's once-only implication and brief's at-most-one call including errors. One committed transaction is not one tool invocation. No new write by parent in this request.
- Record contains inconsistent timestamps and literal [truncated] strings under raw fields. It is not a complete raw evidence archive; do not use its timestamps as measured timing or raw fields as full payloads.
- Delegation completion reports model qwen3.8, not a parent-pinned model. Do not assume model inheritance was realized or repeat quality claims. Parent independently reads canonical target state.

## Acceptance status

PASS: dedicated property definitions and types; page vs exact-block scope on three sibling token matches.
NOT_RUN: dedicated typed values; real numbering; temporary nesting; Todo status; atomically rejected typed writes; many append/dedupe; recycle/restore; restart; CLI/PDF/sync.
UI NOT_RUN: snapshot unavailable; no user screenshot for this new graph yet.

## Next new user request batch (not executed here)

Use current page UUID, receipt=true, existing dedicated property UUIDs: assign URL/text/number/checkbox/two node refs on metadata; set task status Todo and remove misleading TODO title; set real number marker on three numbered items; add child and grandchild with page-id equal actual page UUID and parent-id equal existing test parent then temporary child id. No page/property creates in receipt batch. Independently readback and search scopes. Do not claim current setup is complete.
