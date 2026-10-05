# 正常重开后读回与asset fixture

重开动作来源：用户在被请求导入图片并正常关闭/重开当前CI应用后回复“好了”。agent未自行关闭或强杀应用。重开事件本身为用户确认；下列重开后的数据为native MCP直接实测。未编译或运行另一应用/CLI。

## 重开后实测保留

getBlock(2834bd67-ba5d-4737-acf1-5ddb4913cee7)：date字段CI-ISSUE9-Date-Reference-EhgalqcB精确引用journal UUID00000001-2026-1004-0000-000000000000/title Oct 4th, 2026；Done身份logseq.property/status.done；Task标签；deadline1791091897093；原title/parent/page/order b1z保留。
getBlock(d8d5836c-5a3c-4fe8-8258-f0edd7d3c706)：Number value=0，Checkbox=false，CV=https://example.org/demo-cv-updated，ORCID/Description保留，Topics仍为父70ec2dc8-8fcc-4c97-b134-bdf2a24f3cda、control e6a5a455-812d-4672-ae8f-7c0a5e3633c0、子d00c2214-fa31-44af-bbea-7f5f9a7c45b9三个引用。
getPage(current page,includeChildren=true,maxBlocks=50)完整读回：页面URL/Number7/false保留；原标题Todo/Scheduled1791091897093；编号一/三number保留，编号二保持用户移除后的无marker状态。两套父子孙均按原UUID/三级children/order读回。旧三块回收恢复子树也仍在。
searchBlocks(CI-FORWARD-REF,pageUuid=current page,limit=100)：返回父a855d7ad-20b0-413c-9652-96c33176a2d9、子561d588a-1a7d-4b28-9cf2-7808a5e113ec、孙ea42cc33-227d-4efb-a924-00d72e35722f，hasMore?=false。
结论：在用户确认正常重开的前提下，上述本次写入数据及结构/搜索读回通过。不证明crash/强杀持久性，也不是所有图或全部属性全覆盖。

## 单次超时及恢复

首个getPage(includeChildren=true,maxBlocks=1000)调用120秒超时；并行getBlock均成功。随后顶层getPage成功；再完整树maxBlocks=50成功。没有把一次超时归因为应用挂死，也没有重建索引或修改DB。

## 真实asset确认及属性定义准备

用户导入图片已在测试页：UUID6ac2457e-d17f-4148-b1ec-69d957a588c1，title2026-10-04-20-24-31，type=png，size=44733，width=1453，height=436，tags含logseq.class/Asset，parent/page=current page，order=b20。native getBlock独立确认它是实例，非Asset类定义。

getPage(CI-ISSUE9-Asset-Reference)先确认不存在。本请求唯一一次upsertNodes新增property，title=CI-ISSUE9-Asset-Reference，property-type=asset，property-cardinality=one，receipt=false。真实响应Added: {:property 1}。独立getPage读回type=asset，cardinality=one，UUID00000002-2000-6258-5000-000000000000，ident=CI-ISSUE9-Asset-Reference-FWh0Sz8T。

asset引用值写入仍未执行。下一独立请求先确认目标Task/asset/定义，再写Task properties={"00000002-2000-6258-5000-000000000000":{"uuid":"6ac2457e-d17f-4148-b1ec-69d957a588c1"}}，receipt=true，独立读回检查精确asset UUID和原Task/date/deadline保留。不得把fixture准备算成asset写入通过。
