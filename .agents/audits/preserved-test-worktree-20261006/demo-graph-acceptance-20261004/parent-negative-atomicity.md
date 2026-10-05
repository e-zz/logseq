# Negative Number / batch atomicity — parent 实测

Target demo page 63bab91a-013d-44df-afb7-4c83026b03ef, metadata block d8d5836c-5a3c-4fe8-8258-f0edd7d3c706.

Before: getBlock title 专用类型属性测试：等待赋值; TEST-DEMO-Number 42.5; updated-at 1791079006228; other typed properties present.

Exactly one upsertNodes call receipt=true, two edit operations on same test block:
1. valid title edit to ATOMICITY-TEST-SHOULD-NOT-COMMIT
2. Number property UUID 00000002-1290-4969-3200-000000000000 assigned string not-a-number.

Actual response: API Error: Numeric property :00000002-1290-4969-3200-000000000000 value must be a finite JSON number.

Independent post-read getBlock returns original title, number 42.5, identical updated-at and original other values. Page-scoped search for ATOMICITY-TEST-SHOULD-NOT-COMMIT returns blocks=[], hasMore? false. This case passes Number wrong-type rejection and no partial commit of preceding valid operation. Does not prove all error paths atomic or URL/checkbox-specific wrong-value rejection.

Additional read-only 实测:
- Full getPage maxBlocks=1 rejects: requires 10 blocks, exceeding maxBlocks=1; no silent partial tree.
- getBlock existing parent yields exactly ordinary root, page/parent UUIDs and no descendants.
- searchBlocks pageUuid=TEST-DEMO-CV property page and blockUuid=parent from test page rejects: pageUuid and blockUuid must identify the same page. No global fallback.

No recycling, app close/restart, config changes or second upsert performed. Remaining: dedicated URL/checkbox invalid shapes, many append/dedupe, bullet switch, task transitions, recycle/restore (pre-recycle confirmation), normal close/reopen and current demo UI observation.
