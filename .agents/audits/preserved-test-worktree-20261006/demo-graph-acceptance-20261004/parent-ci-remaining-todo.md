# 当前集成 CI 实例：待测 TODO 与本批实测

> **SUPERSEDED（2026-10-06 整理）** — 本文件是 2026-10-04 的 worker 续接/待测清单，其中的"当前未验"等状态已过时：
> #4/#8/#9/#11 已于 2026-10-05 关闭；搜索修补已验收（119/431 全绿、clj-kondo 0/0、C 包 14 项）；#6/#7 早前已关。
> 当前状态见 `.hermes/plans/2026-10-05_000818-issue-fixes-remaining-acceptance.md` 与
> `.hermes/plans/search-mcp-filter-upstream-compatibility.md`。**保留为历史证据，勿据此执行。**

仅在用户当前运行的 Windows CI 实例通过 MCP 验收。无编译、无源码/环境变更、无另一套 Logseq、无测试图还原。目标页 TEST-DEMO-20261004-A / 63bab91a-013d-44df-afb7-4c83026b03ef。已通过旧用例不重复；本表不是所有issues整体结论。

## TODO 状态

|ID|Issue与具体用例|本批状态/依据|
|---|---|---|
|live6forward|#6 孙/子先于父的临时parent-id前向引用|实测通过：receipt关联、三级树、UUID各一次、order存在|
|live7addplain|#7 新增Task同时写Done；现有普通块写Todo|实测通过：新增有Task标签+Done，普通块Todo且未自动加Task标签|
|live9datetime|#9 datetime值写入/读回|实测通过：Deadline/Scheduled均精确1791091897093；仅此内置属性路径|
|live4reject|#4 bullet字符串拒绝及原子性|实测通过：明确number-only错误拒绝，前后完整对象相同、失败标题无搜索命中；见parent-issue4-bullet-rejection.md。不是bullet功能支持通过|
|live6cycle|#6 双节点循环父级拒绝|实测通过：明确cycle错误，完整page前后响应程序比较相同，失败标题/循环块搜索为空。直接self-parent路径未覆盖。见parent-issue6-cycle-rejection.md|
|live9class|#9 Page-only Node目标类型限制|实测通过：Assignee明确拒绝现有普通块UUID，完整getBlock前后相同，前置标题编辑未落地、失败标题搜索为空；见parent-issue9-class-rejection.md|
|live9date|#9 date引用|实测通过：date/one属性写入真实journal引用对象，receipt verified；独立读回精确journal UUID/title，原Task状态/标签/deadline/位置保留。见parent-issue9-date-value.md；正常重开后已读回保留，见parent-reopen-and-asset-fixture.md；非journal拒绝未验|
|live9asset|#9 asset引用|实测通过：asset/one写入真实png引用，receipt verified；独立getBlock读回精确asset UUID，原Task/date/deadline/位置及图片节点保留。见parent-issue9-asset-value.md；此次新引用再次重开未验|
|live9reopen|#9 本次写入正常重开持久性|用户确认正常重开后实测读回通过：date/Task/metadata false与0/页面属性/两套三级结构/搜索保留；不证明crash持久性。见parent-reopen-and-asset-fixture.md|
|live12artifact|#12 CLI文件保护|阻塞：正在运行CI包的两个CLI入口实测都是not-built stub；不编译、不用旧CLI替代|
|live5sample|#5 缺order历史样本回归|当前完整demo页块均有order，无合格历史样本；不直接损坏DB造样本|

## 本批实际单次写输入

receipt=true，按以下顺序：
1. add block id=ci-forward-grandchild，page-id=current page，parent-id=ci-forward-child，title=CI-FORWARD-REF-GRANDCHILD。
2. add block id=ci-forward-child，page-id=current page，parent-id=ci-forward-parent，title=CI-FORWARD-REF-CHILD。
3. add block id=ci-forward-parent，page-id=current page，title=CI-FORWARD-REF-PARENT。
4. add block id=ci-new-task-status，page-id=current page，title=CI-ISSUE7-TASK-STATUS-ON-ADD，tags=[00000002-1282-1814-5700-000000000000]，properties={00000002-9072-1685-3000-000000000000:Done,00000002-1685-9016-0400-000000000000:1791091897093}。
5. edit普通块 dddcda42-ca26-48c4-8bf5-ad5e939d27b3，仅properties={00000002-9072-1685-3000-000000000000:Todo,00000002-1644-5209-4300-000000000000:1791091897093}，不改标题。

实际receipt mode=verified，所有五操作status=verified。新UUID：parent=a855d7ad-20b0-413c-9652-96c33176a2d9，child=561d588a-1a7d-4b28-9cf2-7808a5e113ec，grandchild=ea42cc33-227d-4efb-a924-00d72e35722f，newTask=2834bd67-ba5d-4737-acf1-5ddb4913cee7。

## 独立验证与原始证据

native getPage/getBlock/searchBlocks 独立读回，与receipt一致。随后只读HTTP fresh-session getPage/getBlock再次取得真实JSON，程序断言PASS：level=[1,2,3]；parent/child.children关联一致；三个UUID各出现一次；order均非空；Task status.done且有Task标签；普通块status.todo且无Task标签；deadline/scheduled精确等于输入。写后page-scoped searchBlocks(CI-FORWARD-REF)返回对应三个UUID，hasMore?=false。

保存的未截断真实只读响应：
- parent-forward-ref-page.json：完整页面及调用参数。
- parent-task-on-add.json：新增Task及调用参数。
- parent-plain-status-datetime.json：现有普通块及调用参数。

## #12 包内入口检查

运行进程exe实际路径 C:/Users/zhang/Downloads/logseq-win-x64-builds/Logseq-win-x64-2.0.2/Logseq.exe。直接只读解析其resources/app.asar archive header；logseq-cli.js和js/logseq-cli.js均90字节，sha256均0581285715b905026ee101c42ffabeecb6a6b14b2d116f4d5aa0201ec7cd80d6，内容为：
#!/usr/bin/env node
console.error("logseq-cli: not built (fork build)");
process.exit(1);

完整路径、两个入口尺寸/hash/内容前缀存 parent-ci-cli-artifact-inspection.json。这是实际包内容证据；没有运行CLI roundtrip，不冒充#12通过。Downloads里的带(1) ZIP是2.0.1安装包，不能拿它代替当前运行的2.0.2目录。

## 执行限制

本用户请求仅一次upsertNodes已使用，不能通过HTTP/子代理/dry-run绕过；继续负向用例须分别独立新写调用，不能首错短路后冒称全部通过。未对图做回收、恢复、永久删除或应用关闭；所有测试结果按用户授权留在disposable graph。
