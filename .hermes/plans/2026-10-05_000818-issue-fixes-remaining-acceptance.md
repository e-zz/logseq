# Issue fixes：收束验收与交付计划

## 已采纳的推进方向
用户已采纳：现有修复的主要功能验收接近结束，停止扩展范围；完成搜索修补及父代理复核，明确 #12 失败的处理边界，保留 #14/#21 阻塞和样本缺口，交付最终验收表。

这是验收收束计划，不是全部通过声明，不是 release readiness 声明，也不是自动关闭 issue 的授权。
本文件替代此前同文件的待测试列表。历史快照由 Git 提交 `d21ef1502a` 保留，不再将已完成项当作待办。

## 工作区与证据归属
- 专用 worktree：`D:/orca/workspaces/logseq/issues-human-test-20261003`，分支 `test/issues-mcp-20261003`。
- 当前 non-release Windows 包：CI `37203268496`，源码 SHA `fb5eb4eb43cdb3be7a29d816b969d45c127d4861`。
- 较早 API/GUI 证据来自 CI `37134185100`；没有在当前包逐项重跑，最终表必须保留实际构建归属。
- 搜索后续源码修补即使测试通过，也不代表正在运行的旧 CI 包已包含修补。
- 实测、用户实测、源码核验、推断、未验必须区分。receipt 为当前 DataScript 状态读回，不是 SQLite crash durability 或索引完成屏障。
- 下表 GitHub 状态为最后核验记录；本次计划编辑没有重新查询远程状态。

## 当前验收状态
| 项目 | 已有证据与状态 | 收束边界 |
|---|---|---|
| #4 列表属性 | 实测：number-list 写入/读回、静态 UI、非法 bullet 批次拒绝 | removal/bullet 的既有 API 限制保留；动态编号为独立 #20，不在本轮实现 |
| #5 缺 order | 源码核验/整合记录：upstream 已覆盖，未叠加旧 fallback | 缺合格历史样本，当前包 GUI 覆盖标 NOT TESTED；禁止破坏 DB 造样本 |
| #6 嵌套 | 实测：临时父引用、多层/子先于父、顺序和循环拒绝 | 用户已明确关闭；不重复核心测试，不因未测 direct self-parent 重开 |
| #7 状态 | 实测：Task/普通块状态及未知闭集值原子拒绝 | 用户已明确关闭；不扩展 Task 视图测试 |
| #8 树/搜索/回收恢复 | 实测：完整树预算、单块读取、范围搜索、receipt、普通子树回收恢复 | GUI 搜索兼容性单独处理；不新增性能或 crash durability 门槛 |
| #9 类型属性 | 实测：标量、property-only、node-many 追加去重、date/asset/status、false/0、错误原子拒绝；最新 asset 精确引用/目标在用户确认重启后读回；非 journal date 批次拒绝，完整块前后相同 | 本轮两项缺口已补齐，不再重复；移除、空 many、many closed-value 不支持，不能包装成已实现 |
| #11 新写入可检索 | 实测 API + 用户实测 GUI：重启后新普通块搜索、补全、选择引用及导航通过；新页候选可见通过 | 新页独立选择/跳转仍未确认，标 NOT TESTED；不重建通过的普通块 fixture，不做时延 benchmark |
| #12 CLI 文件保留 | 实测：真实 packaged CLI；五文件两轮正常重开内容/哈希保留；追加只读检查每个路径单一实体 | 缺文件测试出现 FAIL，详见下节；原 init-conn / Windows classpath 包内路径 NOT EXERCISED |
| #14 回收页读取 | 实测：默认 getPage/listPages 排除、显式 title/UUID 读回、deleted-at 和内容保留 | 同名并存 fixture 被 #21 阻塞；active-first/歧义未完整验收；GUI 搜索不能借 MCP 排除结果宣称通过 |
| #20 动态编号 | 用户实测：插入后重复/不顺延，切页无效，重启恢复，旧版同样存在 | 独立 OPEN，原因未定，不混入本轮修补 |
| #21 同名回收页写入 | 实测 + 源码核验：新增请求复用回收 UUID，新块落入旧回收页却报告新增成功 | 独立 OPEN；不自动恢复旧页或再次写入掩盖问题；不与搜索修补合并 |

## 剩余推进任务（按顺序）

### 1. 完成搜索兼容性修补与独立复核
执行计划：`.hermes/plans/search-mcp-filter-upstream-compatibility.md`。
- 目标固定：GUI 遵循对应 upstream 默认行为；MCP 默认排除回收实体；MCP 选项/结果发布不得污染 GUI 或共享状态。
- 不能用“过滤条件已存在于 upstream”排除本轮索引/共享路径的间接影响；也不能为迎合旧行为记忆全局撤掉 upstream hidden/private 过滤。
- 已核验模型为 OpenCode `baqis/deepseek-v4.1-flash`。第一轮因外部 `/tmp` 文件权限拒绝而中止，exit 0 不是完成；没有落盘报告或修补。已续接原会话，提供 worktree 内对照文件与 `.agents/briefs/search-mcp-filter-resume.md`。
- Worker 完成标准：实际调查/修补报告，最小源码差异，真实执行的回归测试和准确计数；根因未证实须明确标注，禁止捏造 red/green。
- 父代理独立检查默认策略、调用顺序/共享状态隔离、全量/增量索引、回收祖先、范围与限额、#11 新写入可检索，再重跑针对性测试。不能只采信 worker 摘要。
- 若需要新包 GUI 验收，只针对已修补的直接路径另行形成 non-release CI 验收记录；不自行安装、操作用户图或重启当前应用。

### 2. 明确 #12 失败的处理边界
实测反例已完成，不能再列为“未测”或 PASS：
- 用户明确授权仅移除隔离 CLI 图 `issue12-preservation` 的 `logseq/custom.css` 文件实体，并正常停止/重开隔离 worker。
- 重开后 file/path 实体仍不存在，get-file-content 为 nil；其他四文件完整内容与现有 UUID/title 行保留。
- fixture 按 disposable 约定保持缺文件状态，不自动恢复；未删除磁盘文件，未触碰当前 GUI 图。

源码核验：当前 packaged worker 的初始化分支与 legacy `logseq.outliner.cli/init-conn` 是不同路径。推断：已有图跳过初始数据构建可解释缺文件未补齐，但包内没有分支 instrumentation，不能称实测根因。
下一步只做有界判断：核对 #12 原承诺与实际调用路径，将“文件保留 PASS / packaged 缺文件 FAIL / legacy init-conn 与 Windows classpath NOT EXERCISED”明确分列。
若现有承诺确实覆盖 packaged 缺文件补齐，才安排独立最小修补/针对性测试；否则作为独立跟进缺陷记录，不默认扩大本轮到 worker 初始化重构。无论采用哪一边界，保留原失败证据，不改写为通过。
不为了运行 legacy 测试重编译整套应用；源码测试不能替代该路径的包内验收。

### 3. 冻结覆盖缺口与阻塞项
- #14 同名 active/recycled 与 UUID/歧义矩阵：BLOCKED by #21。不得直接改 SQLite、擅自恢复或静默写回收页造 fixture。
- #5 合格历史样本：sample-limited / NOT TESTED，不继续无界寻找、不制造损坏数据。
- #11 新页独立跳转：NOT TESTED；若用户后续提供确切操作结果，再追加用户实测记录，不为此重做已通过普通块测试。
- 原 init-conn / Windows classpath 的包内入口：找不到受支持可执行入口时明确 NOT EXERCISED；不以通用 CLI 启动成功冒称 classpath 验证。
- 这些明确缺口可以随最终验收报告交付，不要求全部变 PASS 才结束本轮。

### 4. 最终验收表与交付
创建 `.agents/reports/issue-fixes-final-acceptance.md`，每行包含：issue、具体验收用例、实际 build/SHA、证据类型、PASS/FAIL/NOT TESTED/UNSUPPORTED/BLOCKED、直接证据路径、失败/限制原因、后续跟踪。
- #6/#7 最后记录 CLOSED，其他 issue 最后记录 OPEN；交付前重新查询远程状态，不凭计划快照声称当前状态。
- 区分本地已落盘证据与已发布 GitHub 评论；如发布，先 brief/payload、脱敏，再发布并精确读回目标。计划更新本身不意味着已发布新测试结果。
- 对每个 issue 给出有明确交付边界的结论/关闭建议，不自动关闭。
- 标明 CI success 不等于完整 test suite 全绿；无当前包全用例统一 passed count，无受控性能数据。
- 原 source suite 的 sync.restart-test/connect-calls 失败归因仍未被父代理独立确认，不能照抄“已证明 baseline race”。保留为 source-test attribution 未决，不扩大到本轮全面 sync 测试。
- 关键计划/证据/报告定向 Git 提交，不 broad add，不 push；保留历史证据构建归属。

## 明确排除
不新增 PDF/Zotero/sync/外链功能验收；不重复已通过核心用例；不新增性能、强杀/crash durability、全应用探索 QA；不修 #20 动态编号；不在搜索 worker 内修 #21 importer。
不得操作其他笔记或图，不永久删除，不自作主张关闭/强杀当前应用。图写入只走当前 app 原生 MCP；每用户请求最多一次 upsertNodes，禁止 HTTP/委派绕过。隔离 CLI 图可走其自身 runtime transaction，但必须验证 owner/root/revision，禁止打开当前 GUI 图的第二镜像。

## 直接证据
根目录：`.agents/audits/current-ui-acceptance-37203268496/`
- `issue11-fresh-block-api.md`：API 实测与用户 GUI 结果分别记录。
- `issue14-real-page-results.md`、`gui-search-and-recycled-title-import-diagnosis.md`：回收读取、#21 同名复用与源码诊断。
- `issue9-remaining-gaps-results.md`：asset 重启后精确读回，非 journal date 原子拒绝。
- `issue12-readonly-followup.json`：五文件内容/哈希保留、路径实体计数。
- `issue12-missing-file-probe.py`、`issue12-missing-file-results.json`、`issue12-missing-file-followup.md`：缺文件 FAIL 与其他数据保留。探针现已非 PASS 返回非零；保存结果已独立核验，未重复移除已经缺失的 fixture。
历史 `.agents/audits/demo-graph-acceptance-20261004/parent-issue12-package-file-preservation.md` 与 JSON 保留两轮正常重开证据。

## 本轮结束条件
搜索 worker 产出已独立复核，#12 的失败与原承诺边界已有明确结论，最终验收表完成并逐条有证据或明确缺口。
完成计划不等于所有功能通过。允许 FAIL/BLOCKED/NOT TESTED 随报告正式交付；禁止通过重复测试、扩大范围或改写标准把结果做成全绿。
