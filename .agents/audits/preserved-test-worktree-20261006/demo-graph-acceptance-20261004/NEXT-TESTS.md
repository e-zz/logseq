# 新 worker 测试续接入口

> **SUPERSEDED（2026-10-06 整理）** — 本文件是 2026-10-04 的 worker 续接/待测清单，其中的"当前未验"等状态已过时：
> #4/#8/#9/#11 已于 2026-10-05 关闭；搜索修补已验收（119/431 全绿、clj-kondo 0/0、C 包 14 项）；#6/#7 早前已关。
> 当前状态见 `.hermes/plans/2026-10-05_000818-issue-fixes-remaining-acceptance.md` 与
> `.hermes/plans/search-mcp-filter-upstream-compatibility.md`。**保留为历史证据，勿据此执行。**

## 最新逐项 TODO 与本批完成项

先读同目录 parent-ci-remaining-todo.md，作为当前未验清单；旧未验建议仅作历史，不照搬。新增实测：#6 孙/子先于父前向临时引用（三级树及receipt）；#7 新增Task写Done与普通块写Todo；#9 Deadline/Scheduled epoch milliseconds精确读回，均已独立程序验证。当前普通标题块 dddcda42-ca26-48c4-8bf5-ad5e939d27b3 现有Todo/Scheduled测试值，不恢复；新增节点UUID与原始JSON路径在该报告。

#12 当前实际运行2.0.2包的两个logseq-cli.js入口均为90字节not-built stub（已直接解析app.asar并计算hash）；因此明确BLOCKED，不编译、不用旧CLI代替。#4只支持number，bullet写入应明确拒绝，属性移除尚未实现，不能将bullet切换列成已交付能力。余下独立负向写调用、fixture缺口、正常重开及历史样本条件详见最新TODO报告。

## 新增当前 CI 实例实测：#7 未知状态拒绝

父代理已直接通过运行中应用的 MCP 补测 #7：同批前置合法标题修改 + 不存在的状态 identity，明确 closed value 错误拒绝。写前后 getBlock 完整返回相同，Done/title/updated-at/标签/层级保持，拟提交标题 page-scope 搜索无结果。见 parent-issue7-invalid-status.md（实际输入、响应、完整读回与搜索证据）。不重测此用例，未编译、未启动另一 Logseq/CLI，未恢复图。只证明此错误路径的批次原子性，不宣称所有任务相关验收均完成。

## 当前验收载体：已交付的集成 CI build

用户纠正：本轮在运行已融合待测试修复的 CI build，不重新编译，也不派源码自动化测试作为当前 build 验收的替代。CI run 37134185100 的 headSha 已再次实测与工作区 HEAD 一致：eb69c1bd179fa4582ff1f59506d43338dfab9190，conclusion=success。

proc_56d80d31ac3a 因派工偏离上述目标已由父代理停止；日志可见读取已有 shadow 编译产物并运行 node static/tests.js，未见重新编译命令，不宣称该任务完成或产生有效新验收结果。后续 worker 仅测当前 CI 应用的修复行为；若 #12 需要独立 CLI 而 CI 包不提供可用入口，则明确标记该包未能覆盖此项，不自行构建 CLI、不使用旧全局 CLI 冒充本次产物。源码检查仅可帮助定位预期行为，不替代当前产物的运行证据。

## 最新范围约束：仅验收本次 issues 修复（优先于下文旧清单）

用户明确要求：仅测试本次 issues 修复相关行为，不做 Logseq 通用功能验收。本节取代下文“推荐下一步”和 UI 更新中的扩大测试建议；旧记录保留作为历史证据，不是执行指令。

每个新增用例必须写明：issue 编号、具体修复行为或直接受影响路径、已有证据、为何还需要此测试。无法建立该映射，不执行。源代码与用例映射见 docs/testing/issues-20261003.md；其中标题包含 UI/task view，不意味着本次修改实现了整个 UI/task view。

允许的验收范围：
- #4：MCP 写入/编辑列表类型属性及非法值拒绝；对应结果在 UI 正确呈现。已有 number 属性及显示证据不重测；bullet 用例先检查已有自动化证据。不是 UI 编号编辑器的全面测试。
- #6：同批临时 parent-id 嵌套、落地父级/顺序与非法关系拒绝。已有父子孙实测不重复。
- #7：MCP task status 写入、闭集值校验、读回及对应显示。Done→Todo→Done 的 UI/MCP 记录可作补充；任务视图筛选不作为本次修复完成条件。
- #8 及重复纳入的 #11：范围搜索、写后即时索引、树读取、普通块与回收块查询、回收恢复及搜索隔离；不扩展为同步系统验收。
- #9：类型定义/值写入/读回、many 追加去重、错误类型与批次原子性、引用目标约束。URL/Checkbox 负向已有证据，不重新写测。必要的正常重开仅针对本次写入数据的持久性，不是通用桌面启动测试。
- #12：包含修复的 CLI 打开图时 config/CSS/JS 不覆盖、缺失默认文件生成及关联错误路径；桌面重开不能替代此用例。
- #5：已由上游覆盖，不重做本地实现；只有真实历史样本且需要集成回归时验证缺 order 渲染，不直接修改数据库造样本。

明确不作为本轮验收要求：通用编号插入/移动/缩进、UI bullet 切换操作、普通编辑撤销重做、任务视图筛选、无对应本次修复映射的 PDF/Zotero/sync、crash durability。编号/bullet 的 MCP 属性写入与显示仍属于 #4，不能因排除通用 UI 操作而排除它。

最新用户授权：当前图是 disposable graph，破坏性测试结果无需恢复。取消编号项二恢复待办，不执行恢复或清理，也不把测试图还原作为验收条件。编号项二 UUID 76a13258-d931-41c9-a21d-14fd6dbacaed 的 number marker 已移除是已记录状态，不是待修故障。本规则覆盖下文所有“需恢复编号项二”的旧建议。注意区分：恢复测试造成的改动不再要求；#8 recycle/restore 本身的功能测试仍属于 issue 验收，此前已执行完成，不重做。本授权只适用于当前 disposable graph，不扩展到用户其他图。

证据状态：父代理已程序复核 URL before/after 完全相同、读取 Checkbox 原始响应及 session 调用、独立检查 ui-16-expanded-confirm.png，并实时确认 Task 为 Done；其余 worker 自报仍须按具体用例复核，不能泛称全部验收完成。CI 编译成功与自动化全绿分开，本地 websocket 失败的 baseline 归因尚未由父代理独立验证。

工作区：D:/orca/workspaces/logseq/issues-human-test-20261003。
目标：继续用户正在运行的 Windows build 在新 demo 图上的验收，不重新构建、不改代码、不重复已通过用例。

## 先读

1. 本文件。
2. 同目录 parent-recycle-restore.md：最新实测与未验项。
3. 同目录 post-restore-snapshot.json：最后一次真实完整页面快照（不是当前状态保证）。
4. 同目录 parent-negative-atomicity.md：Number 错误类型及批次原子性证据。
5. 同目录 parent-populated.md：专用属性、嵌套、编号、搜索的实测。
6. 必要时 parent-review.md：子代理报告错误与排除结论。

原 .hermes/plans/2026-10-04_002253-build-acceptance.md 是总计划，不是最新状态；原图 TEST-BUILD-20261004-A 的 UUID 不可复用。demo setup.md/setup-results.json 不是完成权威：worker 混用 page-id/parent-id、漏状态和编号，调用次数与回报矛盾，且 raw 字段有截断、时间不一致；已由父代理补齐并独立核验。

## 首个实际动作：只读验证当前图

调用 getPage(pageName="63bab91a-013d-44df-afb7-4c83026b03ef",includeChildren=true,maxBlocks=50)。
预期标题 TEST-DEMO-20261004-A；父→子→孙存在、任务 Done、metadata checkbox=false/number=0/Topics三个引用。
不符合则先确认用户是否又切图或修改数据，停止写入，不能在别的图盲用旧 UUID。
再 listProperties(expand=true) 核对以下定义。App 正在运行，使用桌面 HTTP MCP/native mcp__logseq 工具，不启动 CLI -g/direct writable SQLite 镜像。

## 当前目标 UUID

|目标|UUID|
|---|---|
|页面|63bab91a-013d-44df-afb7-4c83026b03ef|
|metadata|d8d5836c-5a3c-4fe8-8258-f0edd7d3c706|
|Task|a4454633-532a-443f-8d3a-d06426f0a1d0|
|父块|70ec2dc8-8fcc-4c97-b134-bdf2a24f3cda|
|子块|d00c2214-fa31-44af-bbea-7f5f9a7c45b9|
|孙块|61859851-aec0-4973-9543-8c4d5bf2c802|
|关键词对照块|e6a5a455-812d-4672-ae8f-7c0a5e3633c0|
|编号一|69c8cc2b-dc3a-40e1-b133-a0781a131cbb|
|编号二|76a13258-d931-41c9-a21d-14fd6dbacaed|
|编号三|0c8fa0e5-fe1b-4d82-ac90-d5be7bd713f5|

|专用属性|UUID|定义|
|---|---|---|
|TEST-DEMO-CV|00000002-1190-2949-4400-000000000000|url/one|
|TEST-DEMO-ORCID|00000002-2061-0048-5900-000000000000|default/one|
|TEST-DEMO-Topics|00000002-4607-5057-9000-000000000000|node/many|
|TEST-DEMO-Description|00000002-2040-4008-4000-000000000000|default/one|
|TEST-DEMO-Number|00000002-1290-4969-3200-000000000000|number/one|
|TEST-DEMO-Checkbox|00000002-1028-8501-4900-000000000000|checkbox/one|

## 已通过：不重复造数

实测：专用定义+块/页面类型值 roundtrip；false/0 保留；number错误值整批拒绝、前置合法修改也未落地；many追加保留既有值并去重；真实number marker；Task Todo→Done；同批临时parent-id三级嵌套；写/改后搜索与精确块排除后代；树预算不足/错误实体范围拒绝；授权三块回收→搜索排除→恢复，原UUID/父级/order保留，程序确认无重复。
当前三块已恢复，不留待恢复状态。新demo图UI未验；前一副本截图不能当本图证据。

## 本轮续接更新（worker 实测，待主 agent 独立复核）

- 先只读 getPage 与 listProperties：当前页面、目标 UUID 和六属性定义均匹配。未重做此前通过测试。
- 已执行一次 URL 负向两操作 upsert：明确 URL 错误拒绝；前后完整 getBlock 相等，updated-at=1791080520810 未变，拟提交标题搜索为空。结论 PASS_PENDING_PARENT_REVIEW，详见 worker-url-negative.md 和同名前缀 JSON 证据。主 agent 复核前不视作已正式关单，也不重做该写测试。
- 只读 UI capture 探测一次失败：computer_use backend unavailable，完整响应见 worker-ui-probe.json。UI 仍未验；未安装、重启或改配置。
- 用户随后明确「继续」的新请求：已完成一次 Checkbox 字符串 "false" 负向两操作 upsert，明确 boolean 类型拒绝；本请求前后完整 getBlock 相等、更新时间未变，拟提交标题搜索为空。结论 PASS_PENDING_PARENT_REVIEW，见 worker-checkbox-negative.md 与对应 input/response/readbacks JSON；不重做该写测试。
- URL 与 Checkbox 两用例均待主 agent 独立只读复核，不能由 worker 自报正式关单。本次继续请求一次 upsert 额度已用。未重复已知不可用 UI 截图探测。
- 下一步优先主 agent 复核，再做 UI 与正常重开（需截图可用/用户实际操作，不改环境，不强杀）。下方 URL/Checkbox 段保留为用例规范，不是让后续 worker 重跑。

## UI 续接更新：原生 cua-driver 可用

- 实测已通过后台点击与 UIA set_value 打开已有测试页。静态 UI 编号1/2/3、三级嵌套、完成任务标记，以及 Expand 后目标块六属性显示均取得当前 Demo 图真实截图，状态 PASS_PENDING_PARENT_REVIEW。详见 worker-ui-display-20261004.md 与 ui-10-test-page.png / ui-16-expanded-confirm.png。
- 实测 Topics 父块引用跳转 URL 命中父块 UUID，随后返回测试页。最初把引用误当Expand点击，已如实记录；后来坐标点击才真正展开。
- 页面 Number=7 与块 Number=0 已分别核验，未混用。未重做已通过的 API 写测试，未改源码/环境/重启。
- 实测新增 UI Task Done→Todo→Done，每阶段截图与同一 UUID 的 MCP getBlock一致，恢复Done完成；程序核验除status/updated-at外所有返回字段一致。PASS_PENDING_PARENT_REVIEW，见 worker-ui-task-20261004.md 与 worker-ui-task-readbacks.json、ui-17至ui-26原始动作证据。未执行upsertNodes。恢复状态不等于回滚历史，最终截图新增2m标签来源未核实。
- 非视觉补测：两个Checkbox原生UIA ToggleState=Off，与驱动selected=false一致；Task Group及状态Hyperlink无Toggle/Selection能力，原始属性见uia-native-properties.json。
- 最新状态：用户手动打开菜单后，原生UIA InvokePattern成功调用Toggle number list，编号项二的order-list-type已移除，updated-at=1791109053928，UUID/title/parent/page/order保留。当前尚未恢复number，不得关单。需用户再次打开编号项二右键菜单，再次Invoke并读回恢复；详见worker-number-toggle-in-progress.md与ui-37至ui-41证据。
- 编号项二UI切换尝试BLOCKED：后台右键Access denied，按驱动建议前台重试也无法激活窗口（no mouse input was sent）；MCP前后完整块相同，无数据修改。见worker-uia-number-blocked.md，ui-30至ui-36实际输入/响应。若用户手动打开目标右键菜单，可接续后台左键操作；不再重复相同右键失败路径。
- 下一未验：任务视图筛选；编号插入/移动/缩进/bullet切换；普通编辑撤销重做；另行授权正常关闭重开。静态显示通过不能替代这些行为测试。
- 旧 computer_use 包装层失败不再是截图阻塞；直接使用现有原生 cua-driver 并保存每次输入/响应/截图。不要重新安装或改配置。

## 推荐下一步，按优先级

### 1. 负向 URL 验证（下一次明确测试授权请求的一次写调用）

先读metadata，记录全响应。
一次 upsertNodes receipt=true，两操作：第一条编辑metadata标题为 NEGATIVE-URL-SHOULD-NOT-COMMIT；第二条同块 TEST-DEMO-CV UUID 赋值 "not-a-url"。
预期明确拒绝URL错误形状，独立getBlock证实标题、URL、更新时间及其他属性未改变；搜索拟提交标题无该块。失败写也占一次调用。若实际部分落地，立即标FAIL保存证据，不在同请求自动修复/重试。

### 2. Checkbox 错误类型

另一个新授权请求一次upsertNodes，Checkbox UUID赋字符串 "false"（不是boolean），同批前置合法标题修改。预期类型拒绝并无部分落地。不能与URL错误放同批就宣称两个错误路径都已测：校验可能首错短路。

### 3. UI / 正常重开

取得当前测试页截图或用户明确观察：编号、真实Done状态、URL/number/checkbox/many显示及展开/跳转。API值不能代替UI。
用户在UI把Task改状态后getBlock核对双向一致；UI编号插入/移动/缩进与bullet切换独立记录。
正常关闭重开由用户操作或专门授权；重建MCP session，核对同页面的定义、值、层级和搜索。只证明正常关闭持久性，不宣称crash durability。不得强杀。
computer_use曾依赖不可用；最多只读探测一次，不自行安装或改Hermes配置。没有图像就记录UI未验，勿等待/轮询。

### 4. 专项

#12 CLI config/CSS/JS保存需单独可丢弃CLI图与包含本次修复的CLI；不能用旧全局CLI算build验收。#5需要已有缺order历史副本，无样本SKIPPED，不直接改DB造条件。PDF/Zotero/sync无对应样本则SKIPPED，自动测试websocket失败归因尚未独立复现，不能宣布已排除。

## 执行纪律与输出

- 用户授权普通测试自主推进，不对低风险每阶段反复问。一次upsertNodes/用户请求限制仍有效；不能通过多个child、HTTP切换或dry-run绕过。
- 授权只回收此前三块的一次测试已用完且恢复；新回收须重新核对目标、说明后果、确认；无永久删除或自动清理。
- 一个新worker负责单一批次，parent独立getBlock/getPage/search核验。实际子代理模型曾是qwen3.8，不可宣称已满足此前deepseek-v4.1-flash级要求；如仍需worker最低模型要求，用可显式固定模型的executor，或parent直接核验。
- 保存未截断真实payload/response、真实工具时间而非自填时间。在本目录创建下一份case证据，并按新实测更新未验清单。只报告API实测/UI观察/未验，不从worker自报关单。
- 不commit/push、不改源码/运行环境、不关闭issue。本轮停点不是应用故障：用户要求明确新worker续接入口；父代理已完成上述两组测试并留证据。

Suggested skills: logseq-mcp；需要截图时computer-use；委派时dispatching-parallel-agents；CLI专项时logseq-development。先按当前工具schema执行，不照搬旧skill的过时接口。
