# GUI 搜索上游兼容性独立审核（B vs U，对照 A）

> **父代理复核：本报告部分归因拒收。** U 不含本文所称 active-visible resolver；索引同步方向、SQL 等价性、A→U ancestry 均有错误。本文不是兼容性通过证据。版本4条件迁移已从U源码核对，但未验证实际迁移。以同目录 `parent-review.md` 为复核结论，raw 文件保留供追溯。

- 审核对象：fork build **B = fb5eb4eb43cdb3be7a29d816b969d45c127d4861**（CI37203268496）
- 上游锚点 **U = 22a29b30dee3b3930cf49bba50454650c31d2a07**；`merge-base(U,B)=U`（已验证，U..B 为 39 个提交）
- 旧观察构建 **A = bb70c73d29352c85473bdc3bba2bd37d1f2abe72**（A..U 为 331 个提交，均为 upstream 变更）
- 方法：全部证据来自对固定 ref 的 `git show`/`git diff`（只读）。未执行任何 UI、构建、live graph 或 MCP 操作。A 与 U 的区分基于 A..U 与 U..B 的独立 diff，而非提交信息。
- 原始材料：`raw/` 目录（见文末清单），含 U..B 全部搜索相关文件差异、A..U 搜索差异、B/U 关键文件全文快照。

## 结论

**NOT VERIFIED（运行时）/ PARTIAL（源码级）**

源码级发现（直接核验，非推断）：

1. **B 相对 U 对 Cmd-K 普通块结果"所在页面标签"链路没有净改动**。链路两端（worker 结果字段映射、renderer 面包屑/导航）在 U 与 B 间完全一致（逐文件 diff 为空）；链路中唯一被 fork 触碰的 worker 查询函数 `search-blocks` 在 GUI 调用形态（无 `:block`、`:page` 可为 page-uuid 字符串）下与 U 生成完全相同的 SQL、绑定参数和结果映射（逐项核验）。
2. 同一症状在 U 上**同样存在**：U 的 `frontend.worker.handler.search/resolve-active-visible-page-uuid` 对任何带 `:logseq.property/deleted-at`（即已回收）的页面抛异常；`cmdk-block-search-options` 的 `:current-page` 过滤器对回收页传入 `:page`，会被该守卫拦截。因此"在回收页上搜索时所在页面显示为 Recycle"这一行为在 U 中同样不成立（源码上），除非由索引数据层解释（见归因候选 C1）。
3. fork 确实引入了一条能改变"块被索引到哪个 page"的机制（`skip-search-sync?` 排除 runtime-write 导入 + `:runtime-write?` 标记），但它只影响**之后新执行**的 upsert/CLI 导入事务，不改变 B 构建时刻已存在的索引内容。
4. `publish-result?` 在 B 源码中**不存在**（任何形式），排除其作为标签消失的原因。
5. 所有已核验的渲染守卫（`block->breadcrumb-segment` 的 `page?` 判定、`breadcrumb` 的 `seq` 守卫、`include-search-block?`、`search-result->block-result` 的 `block-page` 兜底）在 U 与 B 间源码相同，且对回收场景的判定逻辑无变化。

**静态分析不能排除**：U 与 B 运行时行为差异来自（a）同一图在 A 索引版本与 U/B 索引版本之间的**索引数据状态**差异、（b）搜索索引版本迁移（A=v3 → U/B=v6 的 FTS rowid 迁移与整库重建）期间/之后的状态、（c）B 中 runtime-write 排除逻辑与真实导入时序的交互。这些都必须用运行验收关闭，不能凭"源码未碰 UI"宣告兼容。

## 一、链路追踪（B，符号与行号以 `raw/b_*.txt` 快照为准）

Cmd-K（`ls-dialog-cmdk`）数据链路，逐环节：

1. **输入 → 选项**：`frontend.components.cmdk.state/cmdk-block-search-options`（b_cmdk_state.txt:81-112）。`:nodes` 与 `:current-page` 均带 `:include-breadcrumb? true`、`:include-matched-count? true`、`:enable-snippet? true`；`:current-page` 额外带 `:page (str page-uuid)`。
2. **加载**：`frontend.components.cmdk.core` 的 `load-results :nodes` / `:current-page`（b_cmdk_core.txt:394-425、499-536）→ `frontend.search/block-search`（b_frontend 路径，`frontend/search.cljs`）→ worker `:thread-api/search-blocks`。
3. **worker 查询**：`frontend.worker.handler.search/<search-blocks`（b_worker_handler_search.txt:313-338，新增 `(not (:block option))` 语义跳过守卫）→ `frontend.worker.search/search-blocks`（b_worker_search_full.txt:1013-1090）。
4. **结果字段映射**：`search-result->block-result`（b_worker_search_full.txt:923-981）产出 `:block/uuid`、`:block/title`、`:page?`、`:block.temp/breadcrumb`（当 `:include-breadcrumb?`，调 `frontend.worker.handler.block-breadcrumb/block-breadcrumb`，b_worker_search_full.txt:961-962）、`:block.temp/breadcrumb-ref-titles`、`:block/page`（page-uuid 字符串，b_worker_search_full.txt:943-948 的 `block-page` 或 `:page` 参数兜底）。**该函数 U 与 B 除行号外完全相同**（a_u_search_delta.diff 仅显示 `:title` 键与 `breadcrumb-ref-titles` 的 U 侧新增，B 侧与 U 一致）。
5. **renderer**：`cmdk/core.cljs` 的 `page-item`（b_cmdk_core.txt:332-362）/ `block-item`（364-380）→ `frontend.components.block.breadcrumb/breadcrumb`（b_breadcrumb.txt:258-270）：`block` 上已有 `:block.temp/breadcrumb` 且非空则直接渲染（variant `:search-result`），否则回退 `db-hooks/use-resource [:block-breadcrumb block-id 16]` 订阅。
6. **段构建**：`frontend.components.block.breadcrumb-model/block->breadcrumb-segment`（b_breadcrumb_model.txt:103-150）：`page? = (some? (:block/name entity))` → 页面段显示标题/名（经 `resolve-uuid-refs`）。祖先列表来自 `block-breadcrumb/block-breadcrumb`（b_worker 侧 handler/block_breadcrumb.cljs:107-130）：`ldb/get-block-parents`（deps/db/src/logseq/db.cljs:676，U..B 未变）走 `:block/parent` 链（对回收页即 Recycle 子节点），截断时才在头部补 `:block/page`。
7. **导航**：`cmdk/core.cljs` 的 `handle-action :open-block`（b_cmdk_core.txt:619-645）：`<block-parent-page-uuid` 优先取**结果自带的 `:block/page`**，回退按块 uuid pull `{:block/page [:block/uuid]}`；`redirect-to-page! page-uuid {:anchor "ls-block-<id>"}`。面包屑段点击走 `breadcrumb-fragment` 的 pointer 事件 → `route-handler/redirect-to-page!`（b_breadcrumb.txt:16-38）。

**渲染守卫清单（决定标签可见性的全部条件）**：`cmdk-block-search-options` 的 `:include-breadcrumb?`；`search-result->block-result` 的 `include-search-block?` 过滤与 `block-page` 兜底；`breadcrumb` 的 `(when-let ... (seq ...))`；`block->breadcrumb-segment` 的 `(when entity)`/page? 判定；`build-breadcrumb-view` 的可见前/后缀切分。以上在 U 与 B 间**源码全部相同**（cmdk/state、cmdk/core、breadcrumb、breadcrumb_model、block_breadcrumb handler 五个文件 U..B diff 为空；worker 映射函数相同）。

## 二、U..B 搜索相关差异逐项核验（fork 改动）

完整清单见 `raw/u_b_search_delta.diff`、`raw/u_b_worker_search_full.diff`（worker/search.cljs 仅 9 个 hunk，全部审过）。

| 改动 | 位置 | 对 GUI 搜索的影响（核验结论） |
|---|---|---|
| `skip-search-sync?` 新增 `(and imported-data? (not runtime-write?))` | db_listener.cljs:175-179（b_db_listener.txt） | **行为变化（条件性）**：fork 新增的 `upsert-nodes` receipt/typed-property 导入事务（`outliner-op :batch-import-edn` + `:logseq.outliner.op/runtime-write? true`，cli.cljs）现在**跳过**逐事务搜索索引同步，改依赖批内已包含的 datom 同步路径；U 下这类导入（若存在同形事务）会触发 `sync-search-indice`。仅影响改动**之后**新执行的导入；不影响已建索引中的既有行。 |
| `upsert-nodes` 大改（receipt/typed props/parent-id） | api/db_based/cli.cljs | 导入内容模型变化（property 值伪块等），经上行机制进入索引的时机变化；**不改变** `:block/page` 语义。 |
| `search-blocks` 增加 `:block` 作用域 | worker/search.cljs（build-search-bind、exact/fuzzy/fts SQL、include-search-block?） | **GUI 无影响**：GUI 调用不带 `:block`；无 `:block` 时逐分支比对：无 page 时 bind/SQL 与 U 逐字节相同；page-only 时 `[page input limit]`/`[page input last-part limit]` 与 U 的 cond 分支一一对应（U:440-453 vs B:440-462）；含 page 的 namespace 查询中 B 给 FTS 匹配加了括号（仅影响 SQL 优先级，不改绑定与命中集）。 |
| `:thread-api/search-page-uuid`、`:thread-api/search-block-uuid`（active-visible 守卫，`ldb/hidden?` 抛错） | worker/handler/search.cljs:138-187 | **GUI `:nodes`/全局搜索无影响**（该路径只被 `frontend.handler.search/search` 在调用方显式传 `page-uuid` 时触发，即插件 API/MCP `logseq.app.search`）。**GUI `:current-page` 在回收页上有影响**：见归因候选 C1/C2。 |
| `frontend.handler.search/search` 重写（page-uuid/block-uuid 校验、limit 校验、files 条件化） | handler/search.cljs | GUI Cmd-K 不走此函数（它走 `frontend.search/block-search`）。仅影响 API/插件 `logseq.app.search` 消费者。 |
| MCP `searchBlocks` 新增 pageUuid/blockUuid/limit（`electron/mcp_search.cljs`） | mcp_server.cljs | 消费路径 = MCP 客户端 → `electron.server/api-fn` → `electron.listener` → `window.logseq.api.app.search`（`logseq.api/search`，api.cljs:187-192，U..B 未变）→ 上一行重写的 `frontend.handler.search/search`。**与 Cmd-K 无共享代码路径**（Cmd-K 直接调 `frontend.search/block-search`，绕过 handler/search）。 |
| API 方法调度重构 | `electron/api_method.cljs`（新文件）+ listener.cljs + server.cljs | 纯重构：`resolve-real-api-method` 与 `dispatch` 逻辑逐行等价于原 listener 内联代码（B 将 A/U 已有的拆分保留）。不影响搜索。 |
| `electron.window` 外部协议白名单、PDF/assets 系列 | 多文件 | 与搜索无关（已核对 diff 无搜索符号）。 |
| worker/handler/search.cljs 其余部分（index build/migration） | b_worker_handler_search.txt | 与 U 逐行相同（U..B 仅 3 个 hunk，均在上表）。 |
| `deps/outliner/recycle.cljs`（`restore!`/新增 `recycle!`/`subtree-uuids`/order 复用） | b_outliner_recycle.txt | 回收/恢复的事务构造变化（API 路径）；对**搜索索引**的影响：`restore!` 现在先校验 `recycled?` 并返回结构化结果（原为 `true`）；`recycle-blocks-tx-data`（被 `restore!`/`recycle!` 共用）U..B 未变。回收后的块仍由 `sync-search-indice` 处理（`deleted-at` datom 触发 reindex），`hidden-entity?` 判定不变。 |

**U..B 与搜索完全无关**的改动（pdf/electron import 等）已逐一核对 diff 确认不含搜索符号。

## 三、A..U 上游变化（同名提交，不据此归因）

A..U 共 331 提交，与搜索直接相关的（`raw/a_u_search_delta.diff`）：

| 上游提交 | 内容 | 对症状的可能关系 |
|---|---|---|
| `792f86328b` perf(search): find FTS rows by rowid | FTS 触发器从 `id = old.id` 改为 `rowid = old.rowid`；索引版本 3→4 | **索引 schema 变化**：A 的索引（v3/v4 行）与 U/B 的 v6 索引**不是同一物理状态**。旧图在 U/B 中打开会走整库重建（版本 < 4）或 `blocks_fts_next` 迁移（版本 = 4）。重建/迁移是否产生与 A 相同的结果集未验证。 |
| `e8f044822a` fix(search): validate migration before creating temporary index | 迁移健壮性 | 同上。 |
| `22a29b30de` fix: resolve uuid refs in search results, breadcrumbs, and nested page refs (#13580) | `search-result->block-result` 改用 `block-result-title`+实体重取；新增 `breadcrumb-ref-titles` | **A→U 的显示变化**：uuid 引用块（`[[uuid]]` 页名）在 A 的搜索结果中可能直接显示 uuid 文本，U 起解析为页名。若用户"在回收页看到的标签"是页面标题，此提交会改变**内容**，但不改变**是否显示**。B 完全继承该行为（源码相同）。 |
| 搜索索引版本 3→6（`792f86328b`/`e8f044822a` 组合，worker/handler/search.cljs:15-24） | FTS rowid 化 + 迁移机制 | 见 C3。 |
| `686b11ef08` fix: match umlauts in node property value search | `block->index` 的 title 一律 `sanitize` | A→U 的**索引内容**变化（重音剥离范围扩大），影响 A 索引与 U/B 索引的命中差异。 |
| `5220b1dc53`、`66c1a434b7`、`09c07b0f23` | 快照/视图性能 | 与搜索链路无直接符号交集。 |
| `3c27f1e875` unwrap cmdk codes search results；`f3dacf9a99` cmdk results wiped on open when refresh key is memoized | Cmd-K 自身修复 | **A→U 的 Cmd-K 行为变化**：A 中 codes 组结果可能带包装层/刷新时序问题。若用户对比的 A 基线早于这些修复，A 与 U/B 在 Cmd-K 渲染上存在上游引入的差异（B 继承 U）。 |

另：`frontend/handler/search.cljs`、`api.cljs`、cmdk 各文件、breadcrumb 各文件在 A..U 的搜索链路 diff 为空（A 与 U 相同，或经 U..B 复核 B 与 U 相同）——即 **GUI 搜索的 renderer 层在 A、U、B 三个 ref 间除 `22a29b30de` 的字段级改动外一致**。

## 四、归因候选（按可能性排序；均标注为推断，未运行验证）

**C1（upstream 候选，源码可支持）：回收页 + `:current-page` 过滤器被 U 的 active-visible 守卫拦截。**
机制：`resolve-active-visible-page-uuid`（U 与 B 相同）用 `ldb/hidden?`（entity_util.cljs:69-84，U..B 未变）判定，`hidden?` 包含 `deleted-at` → 对回收页抛 "not active and visible"。`cmdk-block-search-options` 的 `:current-page` 传 `:page (str page-uuid)`。结果：在回收页上，Cmd-K 的 Current Page 组会**无结果或报错**（而非"结果带 Recycle 标签"）。A 无此守卫（A..U diff 证实该函数 A 中不存在）：A 中回收页块的 `:block/page` 在回收事务后已指向 Recycle 页，`block->index` 与 `search-result->block-result` 都会给出 Recycle 作为所在页——这与用户描述的 A 观察一致。**若用户报告的"旧行为"来自 A 的构建，则此守卫（U 引入、B 继承）是回收页场景标签变化的最直接源码解释。** 限制：用户症状表述为"普通块结果不再显示所在页面"，若症状出现在**全局 Nodes 组**（非 current-page 组），C1 不直接覆盖，见 C3。

**C2（fork 候选，低可能性）：runtime-write 索引同步排除导致**之后导入**的块索引状态漂移。**
机制：B 的 `skip-search-sync?` 使带 `:runtime-write?` 的导入事务跳过逐事务 FTS 同步。若导入批内 datom 覆盖不完整（如 property 值伪块、parent-id 批内父块等 U 时代不存在的新结构），块的 `:page` 索引行可能与图状态不一致。限制：只影响 B 中**新执行**的导入；对 B 构建前已存在的图/索引无追溯影响；且 U 中同形导入（无该标记）会正常同步——这是行为差异，但是否导致"标签消失"未验证（需要对照导入后搜索的运行时结果）。

**C3（数据/状态候选，无法源码判定）：索引版本迁移（v3/v4 → v6）在 B 打开该图时执行，重建/迁移期间或之后 FTS 行状态与 A 索引不同。**
机制：A 的索引版本为 3（A 源码 `worker/handler/search.cljs:15-20` 为 3；A..U diff 中 `792f86328b`/`e8f044822a` 将其升至 6）；U/B 对版本 <4 的图做整库重建、版本 =4 做 rowid 迁移。重建路径（`build-blocks-indice`→`get-all-blocks`→`block->index`）与增量路径在边界（空标题、>10000 字符、closed-value）上一致，但**迁移/重建是否完整**、**重建后 current-page 过滤与守卫的交互**都未运行验证。这是"同一图在 A 与 B 下结果不同"的最常见运行时解释，也是唯一能覆盖全局 Nodes 组症状的候选。

**已排除（源码核验）**：
- `publish-result?`：B 源码中不存在该符号（任何命名空间），不构成机制。
- fork 对 renderer 层改动：U..B 中 cmdk/breadcrumb/breadcrumb_model/block_breadcrumb/api.cljs 的搜索链路文件 diff 为空。
- fork 修改了 `search-result->block-result` 的字段映射：U..B 无差异（该函数的最后改动在 A..U，即 `22a29b30de`，B 完整继承）。
- fork 修改了导航（`handle-action :open-block`）：U..B 无差异。

## 五、尚缺的运行验收（U vs B 对照，最小受控集）

前置：两个**独立新建**的空图（fixture，绝不用现有图）；分别用 U 与 B 的可执行包打开；每图写入固定 fixture 后再跑同一批操作；先关闭语义搜索以固定路径；记录 console 与 worker 日志。

fixture 页（两图内容逐字一致）：
1. 普通页 P1：顶层块 b1「alpha marker」、b1 子块 b2「beta marker」、b2 子块 b3「gamma marker」。
2. 嵌套页结构 P2（P1 下的子页）含块 b4「delta marker」。
3. 带 `[[uuid]]` 引用的块 b5（`22a29b30de` 行为对照）。
4. 空标题块 b6 与 >10000 字符块 b7（边界）。
5. 回收对象：b3 经 GUI 删除（进入回收）；整页 P2 经 GUI 删除（页面回收）；再回收一个含子块的块 b8。
6. 导入对象：通过 `logseq.app.search` 可达的 upsert 导入（B 的 receipt 路径）写 2 个块，验证其可被 Cmd-K 搜到且所在页正确（针对 C2）。

验收项（每项 U 与 B 各跑一次，输出截图 + worker 日志）：
- T1 全局 Nodes 组搜 "alpha marker"：结果块 b1 的 header 是否显示 `P1` 页面段（标签文本逐字记录）；b3（已回收）搜 "gamma marker"：A 的预期是 Recycle 标签——**记录 U 与 B 各自的实际行为**（U 是否显示 Recycle、B 是否显示 Recycle；两者不一致即为 B 回归的直接证据，一致则 A 的差异属 A..U 上游变化）。
- T2 current-page 组：切到 P1 搜 "delta marker"（P2 为子页）：两侧是否显示嵌套页面包屑（P1 / P2）。
- T3 current-page 组 + 回收页：切到已回收的 P2（经 Recycle 页进入）搜其内容块：记录是否出错、是否无结果、`resolve-active-visible-page-uuid` 的异常是否上抛（C1 直接验证；U 与 B 预期相同——若相同，证明该症状非 B 引入）。
- T4 回收页 P2 上的块（其 `:block/page` 已指向 Recycle）在**全局 Nodes 组**中的标签：是 Recycle 还是原页？U vs B 对比（C1/C3 区分关键：若 U 显示 Recycle 而 B 不显示，则 B 有回归，指向 C2/C3；若两者相同，则 A 的差异归因于 A..U 上游，如 `22a29b30de` 或索引迁移）。
- T5 索引迁移路径：U 侧用 A 的 v3 索引图（或新建后再手动降版本——不可行时改为：U 与 B 各自首次打开同一张新 fixture 图，记录 worker 日志中 `search-db-version` 重建/迁移事件）——验证两侧最终 FTS 内容一致（逐条 dump `blocks`/`blocks_fts` 表行）。
- T6 导航：选中 b3（回收块）回车，锚点跳转目标页（U vs B）；选中 b1 回车，P1 的 `#ls-block-<id>` 锚点。
- T7 API 对照：`logseq.app.search`（插件 API 面）搜 "beta marker"，与 Cmd-K 结果字段逐键对比（确认 MCP `searchBlocks` 消费者路径不污染 GUI 状态；B 的 `frontend.handler.search/search` 会写 `state/swap-state! :search/result`，而 Cmd-K 不读该 state——验证无串扰）。

**判定规则**：任何一项 U≠B 且能由上表 fork 差异解释 → B 回归成立，报告 FAIL；全部 U=B 而 A 行为不同 → 归因 A..U 上游（列出具体提交）；U=B 且与 A 观察一致 → 症状非 GUI 回归（可能为 fixture/图状态问题）。未跑完 T1-T7 前不得给出 PASS。

## 六、交付物清单（绝对路径）

- 审核报告：`D:/orca/workspaces/logseq/issues-human-test-20261003/.agents/audits/gui-search-upstream-compatibility-independent/review.md`
- U..B 差异（原始 git diff）：
  - `...\raw\u_b_search_delta.diff`（worker/handler/search、frontend handler/search、worker/search、db_listener、api、cli、tools、listener 全部 8 文件）
  - `...\raw\u_b_worker_search_full.diff`（worker/search.cljs 全量 hunk 清单，9 hunks）
  - `...\raw\u_b_electron_api.diff`（api_method、mcp_search、mcp_recycle、mcp_upsert、mcp_server、server、window）
  - `...\raw\u_b_recycle_ops.diff`（deps/outliner/recycle、modules/outliner/op、worker/handler/cli）
  - `...\raw\u_b_deps_outliner.diff`、`...\raw\u_b_db_graphparser.diff`、`...\raw\u_b_assets.diff`（scope 内其余改动，已核对与搜索无关）
- A..U 差异：`...\raw\a_u_search_delta.diff`（同名 11 文件）
- B/U/A 关键文件全文快照（行号引用依据）：`raw/b_worker_search_full.txt`、`raw/u_worker_search_full.txt`、`raw/a_worker_search_full.txt`、`raw/b_worker_handler_search.txt`、`raw/u_worker_handler_search.txt`、`raw/b_db_listener.txt`、`raw/u_db_listener.txt`、`raw/b_frontend_handler_search.txt`、`raw/u_frontend_handler_search.txt`、`raw/b_cmdk_state.txt`、`raw/u_cmdk_state.txt`、`raw/b_cmdk_core.txt`、`raw/b_breadcrumb.txt`、`raw/b_breadcrumb_model.txt`、`raw/b_outliner_recycle.txt`、`raw/b_entity_util.txt`、`raw/b_api_method.txt`、`raw/b_block_breadcrumb_handler.txt`、`raw/u_block_breadcrumb_handler.txt`、`raw/b_frontend_search.txt`、`raw/u_frontend_search.txt`、`raw/b_api.txt`、`raw/b_listener.txt`、`raw/u_build_search_bind.txt`、`raw/b_build_search_bind.txt`

## 七、限制声明

1. 本审核全部为**源码核验**（直接证据）；所有"运行时行为"表述均为**推断**，未执行、未验证。
2. 静态分析无法证明 B 无回归：FTS 索引为运行时状态，重建/迁移/同步路径的正确性只有在 T5/T6 完成后才可断言。
3. A 构建的二进制与其索引版本（v3）无法在只读审核中复核（A 的 `search-db-version` 源码为 3，但 A 用户图的实际索引版本取决于图历史）。
4. 未阅读任何既往 worker/parent 搜索报告，所有发现独立推导。
