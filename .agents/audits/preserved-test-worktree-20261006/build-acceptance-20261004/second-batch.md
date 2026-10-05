# First populated test page — measured MCP results

Page: TEST-BUILD-20261004-A
Page UUID: 1dfb23bf-acbb-4276-abf4-01c6d108fc2e

## 实测

- One upsertNodes call with receipt=true added nine ordinary test blocks to existing page UUID. All operation receipts reported verified. Independent full getPage readback confirmed added structures and values.
- Temporary block ids resolved three numbered items below list-section and a parent/child/grandchild chain.
- List-section: 1ce74fec-357b-465e-822c-93f631e6a453.
- Numbered items in sibling order: ece0343e-5468-486e-bd70-46700fc9b102; 2765567c-0eb7-4890-afaa-3d62d357eb78; a4e244f5-32f6-49df-8c1c-c60479ec24f7. Each reads order-list-type title number. Titles have no forged numerical prefix. Independent getBlock on first item agrees.
- Parent: 121b5d9b-7abf-4e02-8725-579ad1b14d59. Child: 48d3eecf-0175-4a9e-9ada-e4d37f049179. Grandchild: 0b6cea08-6b18-4f7e-b925-486918634aea. Complete page readback confirms chain.
- Task: 946d7f36-cc7b-4f15-9392-37daa78fa564. getPage shows actual Task tag and Todo status UUID 00000002-1615-5853-7700-000000000000.
- Metadata block: b705b8fb-8f12-4b04-aa60-34c19fc85620. getPage and independent getBlock agree on cv=https://example.org/test-build-cv, orcid=TEST-ORCID-NOT-A-REAL-ID, topics=TEST-TOPIC, description=本轮独立测试描述. Existing properties are default/ref types; this does not test typed URL or cardinality-many references.
- Without restarting or rebuilding index, the next page-scoped search for LQTEST20261004NESTTOKEN returned exactly the three parent/child/grandchild UUIDs, hasMore? false. Exact parent-block search returned only parent, hasMore? false. Exact task-block search returned empty results, hasMore? false.
- Full getPage with maxBlocks=1 rejected: This page requires 17 blocks, exceeding maxBlocks=1. No silent truncation. Tree currently includes an extra empty block and property-value pseudochildren; nine requested ordinary blocks must not be confused with 17 returned tree nodes. Origin of empty block not established.

## 未验/限制

- UI numbering, Task view and properties display require user observation. API values alone do not prove correct rendering.
- getPage exposes property-value pseudochildren under children. Record as API shape observation, not automatically a defect; distinguish from ordinary nesting in consumers.
- No property edits, negative write/atomicity, typed scalar or many tests performed in this request.
- No recycle/restore, restart persistence, CLI preservation, sync or PDF tests performed.
- Receipt same-batch new-page restriction remains separately documented in first-batch.json.

Next: user visually verifies page. Subsequent new request may edit task/keywords/properties in one batch. Do not repeat upsertNodes within this request. No cleanup or deletion authorized.
