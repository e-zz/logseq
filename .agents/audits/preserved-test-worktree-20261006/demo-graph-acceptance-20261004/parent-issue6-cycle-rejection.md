# #6 两块循环父级拒绝及批次原子性：实测通过

验收载体：当前运行的集成CI Logseq，native MCP；独立只读HTTP保存完整读回并程序比较。未编译、未启动其他应用/CLI、未恢复图。本请求仅一次upsertNodes。
页面：63bab91a-013d-44df-afb7-4c83026b03ef。

实际批次：前置edit现有a855d7ad-20b0-413c-9652-96c33176a2d9标题为CI-ISSUE6-CYCLE-PREEDIT-SHOULD-NOT-COMMIT；随后add ci-cycle-a(parent-id=ci-cycle-b,title=CI-ISSUE6-CYCLE-A)，add ci-cycle-b(parent-id=ci-cycle-a,title=CI-ISSUE6-CYCLE-B)，两个新增块page-id均为当前页面，receipt=true。真实输入见parent-issue6-cycle-input.json。

native真实错误：API Error: Block parent-id references form a cycle。逐字转录保存parent-issue6-cycle-response.json。

拒绝后native getBlock：原parent标题CI-FORWARD-REF-PARENT、updated-at1791111639712、order b1w均保持；page-scope搜索CI-ISSUE6-CYCLE返回blocks=[]，hasMore?=false。

写前和拒绝后独立HTTP getPage完整响应分别保存在parent-issue6-cycle-before.json和parent-issue6-cycle-after.json；程序assert两response完全相同，PASS。独立HTTP搜索真实结果存parent-issue6-cycle-search.json，assert blocks为空、hasMore? false，PASS。

结论：实测证明双节点循环父级拒绝，前置合法标题编辑与新增块均未落地，当前完整测试页面响应保持。本结果不等同全数据库所有内部状态不变，也不覆盖直接self-parent单节点路径；不据此关闭整个#6。
