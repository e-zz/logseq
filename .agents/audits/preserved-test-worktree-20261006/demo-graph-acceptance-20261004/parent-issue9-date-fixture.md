# #9 date引用：测试属性准备完成，值写入未验

直接在运行中CI Logseq实例native MCP准备；无编译或CLI、源码/环境变更。本请求唯一一次upsertNodes用于新建属性定义；不把fixture准备标成date值写入通过。

先getPage确认CI-ISSUE9-Date-Reference不存在。真实journal已确认：Oct 4th, 2026，UUID00000001-2026-1004-0000-000000000000，journal-day=20261004。测试Task2834bd67-ba5d-4737-acf1-5ddb4913cee7仍在当前demo页面63bab91a-013d-44df-afb7-4c83026b03ef。

实际单次写：add property，id=ci-issue9-date-reference-definition，title=CI-ISSUE9-Date-Reference，property-type=date，property-cardinality=one，receipt=false（属性定义新增不使用receipt）。真实响应："Added: {:property 1}."

native getPage和独立只读HTTP均证实实际定义：type=date，cardinality=one，uuid=00000002-5930-4674-3000-000000000000，ident=CI-ISSUE9-Date-Reference-EhgalqcB。程序assert属性类型/基数/UUID及journal-day通过。完整证据parent-issue9-date-definition-input.json、parent-issue9-date-definition.json、parent-issue9-date-journal.json。

下一独立新请求的实际用例：对Task2834bd67-ba5d-4737-acf1-5ddb4913cee7写properties={"00000002-5930-4674-3000-000000000000":{"uuid":"00000001-2026-1004-0000-000000000000"}}，receipt=true；date值采用明确的journal引用对象，不是datetime epoch。源码核验tools.cljs:585-590调用ref-target再检查entity-util/journal?，因此不能按接口描述简写误用裸UUID字符串。写前须再确认current graph和定义。读回验证该date字段引用精确journal UUID、原Task/status/其他属性保留；如果实际拒绝则保存FAIL，不切换payload绕过本请求一次写限制。不推断未执行的通过。
