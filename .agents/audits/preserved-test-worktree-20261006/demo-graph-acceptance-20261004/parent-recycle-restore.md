# Many append / false-zero / recycle-restore — parent 实测

Page TEST-DEMO-20261004-A 63bab91a-013d-44df-afb7-4c83026b03ef. One upsertNodes receipt=true with two edits. Independent getBlock and final raw HTTP getPage verification:
- Metadata d8d5836c-5a3c-4fe8-8258-f0edd7d3c706 checkbox true→false (key retained), Number 42.5→0 (numeric value retained), URL changed to https://example.org/demo-cv-updated, unchanged title.
- Node/many before [parent, contrast]; payload [parent, child, child]; after exactly [parent, contrast, child]. Existing contrast preserved, existing parent not duplicated, duplicate new child deduped. Python asserted exact UUID set and length three on final full-page read.
- Task a4454633-532a-443f-8d3a-d06426f0a1d0 Todo→Done, Task tag/title unchanged, independent getBlock confirms status UUID 00000002-1827-5820-8200-000000000000. UI state/filter not observed.

## Recycle safety confirmation and actual execution

Parent full-read inspected three-block subtree. clarify explicitly requested move only test parent→child→grandchild to recycle bin then restore, no permanent delete/other data. User selected 允许，仅回收并恢复这三个测试块.

Root 70ec2dc8-8fcc-4c97-b134-bdf2a24f3cda; child d00c2214-fa31-44af-bbea-7f5f9a7c45b9; grandchild 61859851-aec0-4973-9543-8c4d5bf2c802.
- recycleBlock affected-count=3 and affected UUID list exactly above. deleted-at=1791081044741, current recycle page UUID 00000004-7238-1304-3000-000000000000.
- getRecycledBlock independently reports original page/parent 63bab91a-013d-44df-afb7-4c83026b03ef, original order b1f, preserved subtree UUIDs/count three.
- ordinary getBlock(root) rejects hidden root. Original getPage full tree omits entire test subtree.
- page/global search NESTTOKEN both only return existing heading; no recycled root/child/grandchild results, hasMore? false. Metadata node references still exist pointing at recycled nodes; not interpreted as search leakage, and no claim that references are removed.
- restoreBlock affected-count=3, exact original UUIDs, page/parent restored, position/order original.
- Independent getBlock each confirms UUID and parent chain, exact original orders root b1f/child b1l/grandchild b1m.
- Search returns restored root/child/grandchild plus heading, hasMore? false; getRecycledBlock(root) rejects not recycled.
- Final full-page raw HTTP snapshot post-restore-snapshot.json parsed by Python: each restored subtree UUID occurs exactly once, no duplicate tree entries. Snapshot preserves typed values and Task Done.

## Remaining NOT_RUN

URL/checkbox-specific invalid-value rejection; bullet removal/API support; UI current graph, UI Task filters and reverse edits; normal close/reopen persistence; CLI file safety/PDF/sync; crash durability. No second upsert in request. No app close/restart or permanent deletion. Recycling authorized target fully restored.
