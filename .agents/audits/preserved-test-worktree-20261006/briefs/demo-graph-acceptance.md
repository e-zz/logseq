# Demo graph acceptance setup — single child, parent independently verifies

User switched desktop Logseq to another disposable DEMO graph and authorized tests. Prior graph UUIDs MUST NOT be reused. Parent just read listProperties on new graph: builtin schema only, created-at 1784649034084 etc; Status builtin choices include Todo/Doing/Done, list type UUID 00000002-6078-1711-1000-000000000000. Desktop MCP http://127.0.0.1:12315/mcp serves current app graph; native mcp__logseq tools are available. Use those preferred, or existing C:/Users/zhang/AppData/Local/hermes/scripts/lsq.py only against desktop HTTP, never CLI -g or direct SQLite.

Load logseq-mcp skill. Parent has made NO upsertNodes call for this user request; YOU may call upsertNodes EXACTLY ONCE total, including errors/dry-run. Don't retry, don't evade restriction by changing transport. Receipt mode only allows page/block and block adds require existing page UUID. Since setup includes property definitions and a new page, use receipt=false explicitly and independent readback.

Scope: setup and read-only validation; no source code edits, config changes, git operations, install, deletion, recycle/restore, app shutdown or graph switching. Do not modify existing notes or property definitions. Abort if prior page TEST-BUILD-20261004-A exists in current graph (graph identity uncertain). Read listTags/listProperties to rediscover builtins. Check EACH intended page/property name via getPage before add. Use unique page TEST-DEMO-20261004-A; six property names TEST-DEMO-CV, TEST-DEMO-ORCID, TEST-DEMO-Topics, TEST-DEMO-Description, TEST-DEMO-Number, TEST-DEMO-Checkbox. If collision, choose suffix and check; don't edit existing.

One setup batch, receipt=false:
- Add page using temporary id demo-page.
- Add six property definitions respectively url/one, default/one, node/many, default/one, number/one, checkbox/one. Explicit property-type and property-cardinality.
- Add numbered-list heading and three numbered child items with builtin list type number, no fake textual number prefixes.
- Add test parent/child/grandchild with temporary parent IDs and unique shared keyword LQDEMO20261004NESTTOKEN.
- Add Task-tagged Todo test block. Discover actual Task UUID and status property choices rather than guessing.
- Add plain metadata block titled 专用类型属性测试：等待赋值. Don't assign new custom-property keys in same batch unless documented resolution proven; default plan leaves them unassigned. Do not claim typed value tests pass for definitions alone.
- Add another plain matching keyword block at same page root to permit page/exact-block search contrast.

Independent readbacks: getPage for every created definition (UUID/type/cardinality), getPage(includeChildren=true,maxBlocks=50) and getBlock on ordinary targets, searchBlocks shared token page scope vs exact parent scope. Invalid pageUuid=ordinary block must error not fallback; getBlock(property uuid) must error; getRecycledBlock(active test root) must error. No upsert negative write in this request.

Record ordinary nodes separately from property-value pseudochildren. Save all RAW responses/payload in .agents/audits/demo-graph-acceptance-20261004/setup-results.json and short findings in setup.md. Append after each read/write so failures survive. Return exact file paths, page UUID, property UUID mapping and ordinary block UUIDs for parent verification. Structured child output_schema NOT needed. Mark API 实测 / UI观察 / 未验 separately. Do not call definitions-only roundtrip typed-write success.

Snapshot: computer_use previously failed dependency availability; may try capture once, no install or reconfigure. A capture alone is read-only. If unavailable, save exact blocker and request user screenshot via parent, do not claim UI passed. If snapshot can be obtained, save actual screenshot path, describe visible page identity; don't navigate by guessed hotkeys or claim current page is new test page without observation.

Finish without waiting for further interaction. Respond concise Chinese summary; parent performs exact target readbacks, then gives next stage with honest per-request upsert limit. No background poll loops or workers spawned.
