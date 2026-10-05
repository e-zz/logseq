# #9 asset引用前置检查：尚未获得真实asset fixture

在当前运行CI应用执行native MCP只读检查，没有upsertNodes、应用关闭、DB修改、编译或其他应用测试。

实测：listProperties(expand=true)返回定义中没有type=asset的测试属性；名为Asset的内置属性UUID00000002-8768-5679-0000-000000000000实际type=entity、hide?=true，不能作为可写asset属性替代。另一个Asset UUID00000002-7975-0297-0000-000000000000是内置Tag类定义，不是资产实例。getPage名称Asset歧义，按返回的两个UUID分别读回确认以上区别，两者blocks=[]。

实测：searchBlocks(searchTerm=assets/.png/.pdf,limit=100)三次均返回blocks=[]、files=[]、hasMore?=false。只能说明这些文本搜索未找到资产，不能据此断言全图没有asset；也不能拿类定义UUID当真实asset目标。

独立HTTP全页扫描未执行：execute_code持久内核已重置，readonly helper不存在，调用NameError，没有请求发出。没有把此失败当全图扫描结果。

状态：asset引用值写入未验。最小解阻方式是在当前disposable测试页通过应用原生导入一个小图片，取得真实asset实例，另建asset/one属性后再按独立读回验收。正常重开持久性也仍需用户正常重开当前CI应用，不能强杀或自行关闭。
