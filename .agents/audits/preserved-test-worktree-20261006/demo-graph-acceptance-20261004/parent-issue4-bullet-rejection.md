# #4 bullet拒绝及批次原子性：实测通过（不是bullet功能通过）

在当前运行的CI Logseq实例上直接使用native MCP；未编译、未启动另一CLI/应用、未恢复disposable graph。目标编号项一69c8cc2b-dc3a-40e1-b133-a0781a131cbb，页面63bab91a-013d-44df-afb7-4c83026b03ef。

本请求唯一一次upsertNodes receipt=true：先合法修改标题为ISSUE4-BULLET-REJECT-SHOULD-NOT-COMMIT，再同块写List type UUID 00000002-6078-1711-1000-000000000000="bullet"。native实际返回：API Error: List-type value must be the string "number" (a numbered/ordered list). "bullet" requires removal, which the import path does not support yet.

native getBlock写前、写后完整内容相同：原标题编号项一；order-list-type number UUID6ac1b25e-2986-4a8c-a736-bdc69e13ce48；updated-at1791079006228；parent/page/order b1c均保持。page-scope失败标题搜索blocks=[]，hasMore?=false。

独立只读HTTP在写前和写后获取真实对象并保存，程序assert before.response==after.response，搜索为空且hasMore? false，PASS。完整JSON证据：parent-issue4-bullet-before.json、parent-issue4-bullet-input.json、parent-issue4-bullet-response.json、parent-issue4-bullet-after.json、parent-issue4-bullet-search.json（response为native返回逐字转录，before/after/search为实际只读HTTP结果）。

判读：实测仅证明当前number-only契约下，bullet错误值明确拒绝、前置合法编辑未落地。不能把该负向用例通过描述成支持bullet写入/编号转bullet，更不能据此关闭整个#4。当前MCP属性删除仍不支持，是已识别能力限制。

下一顺序：#6自引用/循环父级负向（待独立新写调用），再#9受限Node错误目标。每用户请求一次upsert限制仍适用，不能用子代理/HTTP绕过。
