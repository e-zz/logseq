# #7 未知任务状态拒绝与批次原子性：实测通过

验收载体：用户正在运行的集成 CI 应用，通过 native Logseq MCP。未编译、未启动 CLI、未修改源码或环境。本请求仅一次 upsertNodes。
目标页面 63bab91a-013d-44df-afb7-4c83026b03ef；Task a4454633-532a-443f-8d3a-d06426f0a1d0。

## 实际输入

{"operations":[{"data":{"title":"ISSUE7-INVALID-STATUS-SHOULD-NOT-COMMIT"},"entityType":"block","id":"a4454633-532a-443f-8d3a-d06426f0a1d0","operation":"edit"},{"data":{"properties":{"00000002-9072-1685-3000-000000000000":"logseq.property/status.invalid_acceptance_value"}},"entityType":"block","id":"a4454633-532a-443f-8d3a-d06426f0a1d0","operation":"edit"}],"receipt":true}

## 实际拒绝响应

API Error: Status value must be an existing closed value: its stable uuid, its identity (e.g. "logseq.property/status.done"), or its display value (e.g. "Done")

## 实际前后 getBlock 读回

父代理在写前与拒绝后各执行一次 getBlock，完整返回内容相同，以下是两次均返回的对象（逐字转录，非模拟结果）：

{"uuid":"a4454633-532a-443f-8d3a-d06426f0a1d0","updated-at":1791091897093,"status":{"title":"Done","uuid":"00000002-1827-5820-8200-000000000000","ident":"logseq.property/status.done"},"refs":[{"title":"Tags","uuid":"00000002-1814-9483-4000-000000000000","ident":"block/tags"},{"title":"Status","uuid":"00000002-9072-1685-3000-000000000000","ident":"logseq.property/status"},{"title":"Task","uuid":"00000002-1282-1814-5700-000000000000","ident":"logseq.class/Task"}],"created-at":1791078193103,"tags":[{"title":"Task","uuid":"00000002-1282-1814-5700-000000000000","ident":"logseq.class/Task"}],"title":"专用类型属性任务测试","parent":"63bab91a-013d-44df-afb7-4c83026b03ef","order":"b1g","page":"63bab91a-013d-44df-afb7-4c83026b03ef"}

标题、Done 状态、更新时间、标签、引用与层级/order 均保持。page-scoped searchBlocks 实际输入：{"limit":100,"pageUuid":"63bab91a-013d-44df-afb7-4c83026b03ef","searchTerm":"ISSUE7-INVALID-STATUS-SHOULD-NOT-COMMIT"}；实际结果：{"blocks":[],"hasMore?":false}。

结论：实测证明该未知状态错误路径拒绝整批写入，前置合法标题编辑没有落地，没有失败标题搜索命中。仅覆盖本错误路径，不泛化到所有错误或全数据库不变。无清理或恢复要求。
