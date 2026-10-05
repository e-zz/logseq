# #9 受限Node错误目标类型拒绝与批次原子性：实测通过

直接测试当前运行的集成CI Logseq native MCP。未编译、未启动CLI/另一应用、未恢复图。本请求唯一一次upsertNodes。

只读前置确认：Assignee UUID00000002-2190-1503-4000-000000000000，type=node，cardinality=many，classes=[logseq.class/Page]。引用目标70ec2dc8-8fcc-4c97-b134-bdf2a24f3cda为现有普通父块，非Page标签实体；不是伪造或不存在UUID。测试写入对象2834bd67-ba5d-4737-acf1-5ddb4913cee7为已有Done Task。

实际批次receipt=true：先edit该Task标题为CI-ISSUE9-WRONG-CLASS-SHOULD-NOT-COMMIT；再同块写Assignee=[{"uuid":"70ec2dc8-8fcc-4c97-b134-bdf2a24f3cda"}]。完整输入存parent-issue9-class-input.json。

native真实拒绝：API Error: Reference property :00000002-2190-1503-4000-000000000000 requires a target tagged with one of [:logseq.class/Page]。逐字转录存parent-issue9-class-response.json。

native写后读回原标题CI-ISSUE7-TASK-STATUS-ON-ADD、Done状态、deadline1791091897093、updated-at1791111639712、Task标签、引用和层级/order均保持；未新增Assignee。page-scope失败标题搜索blocks=[]、hasMore? false。

写前/写后独立只读HTTP完整响应分别存parent-issue9-class-before.json和parent-issue9-class-after.json。程序assert两response完全相同；独立搜索assert blocks空且hasMore? false；属性定义classes限定为Page也再次assert，均PASS。额外证据：parent-issue9-class-search.json和parent-issue9-class-definition.json。

判读：实测只覆盖这个Page-only Node目标约束路径及该批次无部分落地，不泛称全部Node限制/回收引用/asset/date覆盖。本轮没有真正数据修改，也没有回收/恢复。
