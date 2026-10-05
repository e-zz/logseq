# #9 date journal引用写入及独立读回：实测通过

载体：用户当前运行的集成CI应用，当前disposable demo页面63bab91a-013d-44df-afb7-4c83026b03ef。未编译，未使用另一应用或CLI。此请求仅一次upsertNodes。

前置native getPage/getBlock确认date/one属性00000002-5930-4674-3000-000000000000、journal00000001-2026-1004-0000-000000000000（Oct 4th, 2026 / journal-day=20261004）、目标Task2834bd67-ba5d-4737-acf1-5ddb4913cee7属于当前页面。

实际写入：edit目标Task，properties={"00000002-5930-4674-3000-000000000000":{"uuid":"00000001-2026-1004-0000-000000000000"}}，receipt=true。响应mode=verified、operation status=verified，receipt属性user.property/CI-ISSUE9-Date-Reference-EhgalqcB值为精确journal UUID。

独立native getBlock及只读HTTP getBlock均读回CI-ISSUE9-Date-Reference-EhgalqcB={title:"Oct 4th, 2026",uuid:"00000001-2026-1004-0000-000000000000"}。程序assert通过：精确UUID/title；uuid/title/page/parent/order/created-at/status/tags/deadline全部与写前一致，旧refs全部保留。

原始证据：parent-issue9-date-value-before.json、parent-issue9-date-value-input.json、parent-issue9-date-value-after.json。fixture证据另见parent-issue9-date-fixture.md。

结论边界：实测证明此自定义date/one属性通过journal引用对象写入和读回；不是datetime epoch写入，不证明非journal引用拒绝、正常重开持久性、asset路径或所有date输入形式。
