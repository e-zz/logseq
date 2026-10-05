# #9 asset引用写入与独立读回：实测通过

直接测试当前运行CI实例、disposable页面63bab91a-013d-44df-afb7-4c83026b03ef。本请求仅一次upsertNodes，未编译/启动其他CLI/关闭应用/改DB。

前置native读取确认：属性00000002-2000-6258-5000-000000000000为asset/one；真实图片6ac2457e-d17f-4148-b1ec-69d957a588c1为png且带logseq.class/Asset；目标Task2834bd67-ba5d-4737-acf1-5ddb4913cee7属于当前页，Done/date/deadline保留。

实际payload：operations=[{operation:edit,entityType:block,id:2834bd67-ba5d-4737-acf1-5ddb4913cee7,data:{properties:{"00000002-2000-6258-5000-000000000000":{"uuid":"6ac2457e-d17f-4148-b1ec-69d957a588c1"}}}}]，receipt=true。
真实响应mode=verified、操作status=verified，receipt属性user.property/CI-ISSUE9-Asset-Reference-FWh0Sz8T值为6ac2457e-d17f-4148-b1ec-69d957a588c1。

独立native getBlock目标Task读回CI-ISSUE9-Asset-Reference-FWh0Sz8T={title:"2026-10-04-20-24-31",uuid:"6ac2457e-d17f-4148-b1ec-69d957a588c1"}。对照写前输出：原标题CI-ISSUE7-TASK-STATUS-ON-ADD、Done身份/UUID、Task标签、deadline1791091897093、date字段journal00000001-2026-1004-0000-000000000000、created-at1791111639712、parent/page当前页、order b1z均保留。refs新增真实图片与属性引用。这里为native输出逐字段复核，不声称本轮进行了独立HTTP或自动比较。

独立getBlock真实图片：uuid/type/title/size44733/width1453/height436/checksum/created-at/updated-at/parent/page/order b20与写前输出一致，未改图片节点。

结论边界：实测通过此asset/one真实图片引用写入与读回，不证明错误类型拒绝、图片渲染、图片文件字节读取、或本次新asset引用的再次正常重开持久性。此前normal reopen验证发生于这次asset属性写入之前。
