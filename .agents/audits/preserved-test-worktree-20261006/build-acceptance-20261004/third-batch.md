# Third acceptance batch — 实测 / pending

Only one upsertNodes call in user request, receipt=false because verified receipt supports only page/block operations, not property definitions. Six property definitions created and three existing test blocks edited. All writes independently read back.

## Dedicated definitions verified by exact getPage readback

|Title|UUID|Type|Cardinality|
|---|---|---|---|
|测试-CV|00000002-4222-3848-2000-000000000000|url|one|
|测试-ORCID|00000002-1801-2743-2700-000000000000|default|one|
|测试-Topics|00000002-6788-4006-4000-000000000000|node|many|
|测试-Description|00000002-1919-8505-2000-000000000000|default|one|
|测试-Number|00000002-1123-0590-9900-000000000000|number|one|
|测试-Checkbox|00000002-1407-2160-8400-000000000000|checkbox|one|

Dedicated definitions have not yet been assigned values. They cannot be marked value-roundtrip passed.

## Actual typed value tests using existing property definitions on test block only

Target block b705b8fb-8f12-4b04-aa60-34c19fc85620, on TEST-BUILD-20261004-A. No existing property definitions altered.
- number, last-read UUID 6a731af2-d902-4b2e-aa41-32be1937139a: getBlock value wrapper contains numeric 42.5, not a string.
- URL, home-page UUID 6a731ba2-79df-4198-99c4-e3d1b2f43c83: getBlock title wrapper contains https://example.org/typed-url-test.
- checkbox, excalidraw-plugin UUID 6a731bc1-10fb-4e09-8a58-5e3a6f3353ea: getBlock raw boolean true. This label is a reused existing property solely on the disposable test block; not a change to application config. UI/plugin side effects not assessed, prefer dedicated test checkbox next.
- node/many, related UUID 6a731bc9-23a8-45d1-be02-f713e4694e47: getBlock returns both test node UUIDs 121b5d9b-7abf-4e02-8725-579ad1b14d59 and 48d3eecf-0175-4a9e-9ada-e4d37f049179.
- Metadata title remained 属性测试：标题保持不变, original cv/orcid/topics/description values also remain. This is a property-only edit, not a full replacement.

## Other 实测

- Task 946d7f36-cc7b-4f15-9392-37daa78fa564 changed Todo→Doing. Independent getBlock confirms Doing UUID 00000002-1840-1229-0800-000000000000 and Task tag. UI task filtering pending.
- Parent 121b5d9b-7abf-4e02-8725-579ad1b14d59 title changed to 嵌套父块 LQTEST20261004EDITTOKEN. Next exact-block search for old LQTEST20261004NESTTOKEN returned blocks=[], hasMore? false; new token returned only target parent, hasMore? false. No restart or index rebuild.
- Default shallow getPage explicitly reports tree-has-more? true, tree-omitted-count 12. Do not mistake this for complete tree.
- getRecycledBlock on active test parent rejected: Block uuid ... is not recycled. Read-only negative case; no recycle done.
- searchBlocks with ordinary test block UUID supplied as pageUuid rejected: UUID ... does not identify a page. Does not silently fall back to global search.
- getBlock on dedicated property UUID rejected: Entity uuid ... is a page, tag, or property, not a block.

## Remaining

Dedicated-property value assignment, invalid typed values and batch atomicity, many append dedupe, bullet removal/API support, Done/UI reverse edits, recycle/restore (needs explicit pre-recycle confirmation), normal close/reopen, PDF/sync/CLI. No UI result inferred from API. All current test data left intact.
