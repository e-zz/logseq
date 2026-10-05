# Demo graph acceptance setup findings — 20261004

## API 实测
- 当前图识别：旧 abort page `TEST-BUILD-20261004-A` 不存在；目标新页 `TEST-DEMO-20261004-A` 写前不存在。
- 重建 builtin：
  - Task tag UUID: `00000002-1282-1814-5700-000000000000`
  - Status property UUID: `00000002-9072-1685-3000-000000000000`
  - Status Todo choice UUID: `00000002-1615-5853-7700-000000000000`
  - List type property UUID: `00000002-6078-1711-1000-000000000000`
- 一次真实写入成功（回执非证据，已独立回读）：`Added: {:page 1, :property 6, :block 8}.`
- 新页 UUID: `63bab91a-013d-44df-afb7-4c83026b03ef`
- 属性定义 UUID/type/cardinality:
  - `TEST-DEMO-CV`: `00000002-1190-2949-4400-000000000000`, url, one
  - `TEST-DEMO-ORCID`: `00000002-2061-0048-5900-000000000000`, default, one
  - `TEST-DEMO-Topics`: `00000002-4607-5057-9000-000000000000`, node, many
  - `TEST-DEMO-Description`: `00000002-2040-4008-4000-000000000000`, default, one
  - `TEST-DEMO-Number`: `00000002-1290-4969-3200-000000000000`, number, one
  - `TEST-DEMO-Checkbox`: `00000002-1028-8501-4900-000000000000`, checkbox, one
- 普通块 UUID:
  - 编号列表标题: `dddcda42-ca26-48c4-8bf5-ad5e939d27b3`
  - 编号项一: `69c8cc2b-dc3a-40e1-b133-a0781a131cbb`
  - 编号项二: `76a13258-d931-41c9-a21d-14fd6dbacaed`
  - 编号项三: `0c8fa0e5-fe1b-4d82-ac90-d5be7bd713f5`
  - 测试父块: `70ec2dc8-8fcc-4c97-b134-bdf2a24f3cda`
  - Task 块: `a4454633-532a-443f-8d3a-d06426f0a1d0`
  - metadata 块: `d8d5836c-5a3c-4fe8-8258-f0edd7d3c706`
  - keyword 对照块: `e6a5a455-812d-4672-ae8f-7c0a5e3633c0`
- 搜索对比：
  - page scope 返回 3 个含 `LQDEMO20261004NESTTOKEN` 的顶层块。
  - exact parent scope（`blockUuid=70ec2dc8`）只返回目标块自身。
- 负面验证：
  - 普通块 UUID 作为 `pageUuid`: 报错 `does not identify a page`。
  - property UUID 作为 `getBlock`: 报错 `is a page, tag, or property, not a block`。
  - active block 作为 `getRecycledBlock`: 报错 `is not recycled`。

## 未验 / 未完成
- 未写 nested child/grandchild：一次真实写批次内 block temp id 不能做 `page-id`；若用真实 UUID 需要第二次 upsert，超出本请求一次上限。
- 未写真正 numbered-list 子块结构：brief 禁止 fake textual prefixes，且单批限制阻止真实嵌套；现有“编号项”只是顶层普通块，不是 List type 子项。
- 未写 typed property values：只创建了属性定义；不得声称 definitions-only 为 typed-write 成功。
- 未写 Task status：Task tag 已挂，但 Status/Todo 值未写入；Task 类属性槽已发现（status/priority/deadline/scheduled）。
- UI 未验：computer_use 一次 capture 尝试失败，未安装、未改配置、未声称页面可见。

## 文件
- 原始证据: `D:/orca/workspaces/logseq/issues-human-test-20261003/.agents/audits/demo-graph-acceptance-20261004/setup-results.json`
