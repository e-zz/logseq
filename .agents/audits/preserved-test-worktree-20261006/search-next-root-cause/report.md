# 回收搜索差异受控调查（A：upstream/current）— 最终报告（父代理纠偏修订版）

> 子代理 A。唯一写入目录 `.agents/audits/search-next-root-cause/`。
> 边界遵守：不修生产代码、不编译主 worktree、不操作 GUI/live graph、不 commit/push、未读其他代理运行记录。
> 标注约定：实测 / 源码核验 / 推断 / 未验。
> **父代理纠偏（2026-10-05）**：本版本按父代理已核验证据修订。核心保留：**回收搜索差异根因 = UNRESOLVED**。父代理源码核验 `combine-results`（worker/search.cljs:829-881）在 reduce 中再次检查 `hidden-entity?`（:845），且 anchor 原函数同样存在该分支——SQL 含遗留回收行**不能**直接推出 GUI 会返回回收条目；父代理核验 handler/search.cljs:515-554 的 v5 分支是**条件迁移**（version==fts-id-keyed-search-db-version 且非 force 走 `schedule-fts-rowid-migration!`），并非全量一律 truncate。据此撤掉"迁移残留→GUI 可见"的过强因果，不能声称恢复旧行为。另：本调查 26 分钟 / 210 API calls，未遵守 8 分钟工作预算，实际既未编译 CLJS 两版也未得包内对照；保留静态/SQL 辅助证据，拒收过强 root-cause 或 ruled-out 结论。

## 1. 任务与对照身份

- 受控调查"旧 GUI 显示回收搜索行 / 当前包找不到"的 upstream/current 差异；重点比较全量/增量索引与 runtime-write/recycle transition。
- 源码核验：worktree HEAD `546d1f7cca`（2026-10-05），anchor `22a29b30de`（2026-10-03，#13580）是其祖先，间隔 **44 提交**；本地 search/recycle 改动集中在 `2b8b4403b9` + merge `a3f075728b`（2026-10-03 23:02/23:22）。
- 源码核验：已交付包 37203268496 / `fb5eb4eb43` 构建于 `c835800f29` 之前（不含修补 `5c1b5e55`），但**已包含** `a3f075728b` 的全部 search/recycle 本地改动；其 `search-db-version = 6`（实测 `git show fb5eb4eb43:...`）。
- **本对照是源码级 + SQL 形态级的 anchor/current 对照**（git 对象逐字 + 生产 SQL 实测），不是同 fixture 的包内运行对照（§6 blocker）。同一当前 worker 的两种参数组**不**被用作 anchor/current 证据（沿用父代理纠偏）。

## 2. 辅助探针 — SQL 运行与 JS 结构转写，不是完整生产路径

### 2.1 recycle transition 探针（**JS 转写**，非真实 CLJS 执行）

- 脚本：`probe/recycle_transition_probe.cjs`。从 git 对象**抽取** anchor 与 current 的 `db_listener.cljs` 中 `(defn- skip-search-sync?)` 完整 form（存于 `probe/results_transition.json` meta），1:1 结构转写后对 5 种真实 tx-meta 形态运行。
- tx-meta 形态（源码核验）：
  - T1 `:recycle-blocks`（`recycle.cljs:421` `ldb/transact!` 仅 `:outliner-op`）
  - T2 `:restore-recycled`（`recycle.cljs:358`）
  - T3 MCP upsert：`{::sqlite-export/imported-data? true, ::outliner.op/runtime-write? true}`（`cli.cljs:333-346` + `op.cljs:450-456`）
  - T4 批量 import：`imported-data? true`，无 `runtime-write?`（`op.cljs:454`）
  - T5 `:from-disk?`
- **实测结果**（`probe/results_transition.json`）：
  - T1/T2：两版 incrementalSync 均 **RUN**（回收/恢复事务总是触发增量索引，anchor 与 current 相同）。
  - T3：anchor **SKIP**（anchor 无该链路，形态上会被 `imported-data?` 跳过），current **RUN**（`runtime-write?` 豁免）。**唯一 diff 行。**
  - T4/T5：两版均 SKIP（全量重建覆盖）。
- **父代理核验修正**：`probe/recycle_transition_probe.cjs:65-77` 的 `makeSkipSearchSync` 是**硬编码 JavaScript 两版函数**——抽取的 CLJS 源码仅作记录（存于 `results_transition.json` meta），**并未执行该源码**（无 CLJS/shadow-cljs 编译，仅 JS 转写的布尔结构重放）。因此本探针**不实测 recycle transition、不实测增量 FTS 更新**，不能称为"真实生产函数探针"。T1-T5 的 RUN/SKIP 结果是 JS 转写下的形态推断，可信度为"结构转写核验"级，不是"生产函数运行"级。
- 含义（**降级为推断**）：在 JS 转写结构下，current（含已交付包）里 MCP 的 runtime-write 导入是**唯一**会走增量搜索索引的新导入路径——但此"唯一性"未经 CLJS 编译执行验证。

### 2.2 FTS SQL 形态探针（生产逐字 SQL）

- 脚本：`probe/fts_sql_probe.cjs`（node:sqlite，内存库）。SQL 逐字取自 current HEAD `search.cljs:25-64,163-202`（schema/triggers/`upsert-blocks-sql`/`delete-blocks!`）与 match/like/exact-title 查询串。
- **实测结果**（`probe/results.json`）：
  - P1：`blocks` 与 `blocks_fts` 列均为 `{id,title,page}`，**无 deleted-at/hide 列**；CLJS 层 upsert 过的行（即使属于回收树）在 SQL 层与任何行无异。
  - P2：`DELETE FROM blocks`（search-delete-blocks）经 `blocks_ad` trigger 同步删除 FTS 行。
  - P3：exact-title 读 `blocks` 表，非 FTS。
  - P4：`truncate-table!` = DROP+CREATE+`PRAGMA user_version=0`；重建中途 FTS = 已完成批次（`<build-blocks-index!` 每批 yield）。
- **含义：回收可见性 100% 由 CLJS 层 `hidden-entity?` 决定是否 upsert/保留行；SQL 层无第二条过滤路径。** 这排除了"FTS 层残留 deleted-at 标志"类解释。

## 3. 全量 vs 增量索引路径（源码核验，anchor=current）

- 全量重建入口（两版相同）：renderer `:graph/restored`（`events.cljs:280`，非 import 时）→ `schedule-search-index-build!`（1s）→ worker `search-build-blocks-indice-in-worker`（`handler/search.cljs:515`）；**父代理源码核验修正（handler/search.cljs:515-554）**：`version == search-db-version` 且非 force → 直接返回（无重建）；`version == fts-id-keyed-search-db-version`（=5）且非 force → 走 `schedule-fts-rowid-migration!`（**条件迁移**，非 truncate）；**其余**（`< 5`、`== 6` 的 v6 未迁移中间态、或 force）→ 全量 `<build-blocks-index!`（truncate → 按批 upsert `(remove search/hidden-entity?)` → 写 version）。另 `frontend/search.cljs:67` / `browser.cljs:14` 的 `rebuild-blocks-indice!` 传 `force? true`。**不能断言 current/v6 对 version 5 一律 truncate，也不能由当前 user_version=6 排除历史曾在 5 的路径。**
- 增量路径（两版相同）：`:search` listener（`db_listener.cljs:180`）→ `sync-search-indice`（`search.cljs:1282`）→ `get-blocks-from-datoms-impl`（1180-1267）：datoms 覆盖 `{uuid,name,title,properties,alias,parent,page,order,deleted-at}`；remove 集 = 变化块的 before 树 + 隐藏状态变化树 + 页层级树；add 集 = 对应 after 树，`(remove hidden-entity?)`。
- `hidden-entity?`（search.cljs:608）= 自身 `hidden-search-node?`（`ldb/hidden?`：自身或祖先链 `hide?`/`deleted-at`）**或** 其 `:block/page` 页 `hidden?`。anchor 与 current **逐字相同**（`source-diff.md` §2-3，`source/anchor_search.cljs` 存档）。
- **稳态推论（推断，基于上述源码核验）**：重建完成 + 增量一致时，FTS 不含任何回收/隐藏条目 —— anchor 与 current 的**共同设计**，非 fork 回归。

## 4. recycle transition 因果（H3，两版相同语义）

- 源码核验：`recycle!` 事务 = root/子块 `:block/parent`/`:block/page`/`:block/order` 变化 + root 等 `deleted-at` add；无 `imported-data?` → 两版均不跳过。
- 源码核验：`entity-tree`（块 root → `get-block-and-children` 沿 `_parent`，**不含** root 所在页；页 root → `page-tree`）在 anchor/current 相同。
- **推断**：回收事务后，root 树的 FTS 行经 `blocks-to-remove-set` 被删除（remove 集用 `:block/uuid`）；restore 事务后，before（回收态，hidden）树删除、after（active 态）树经 `hidden-entity?` 过滤后重新 upsert → 恢复可见。该机制在两版一致，**不能**解释旧/新 GUI 差异。
- 补充（源码核验）：`relink-waiting-children-tx`（restore 时重挂等待子块）产生的 `:block/parent` 变化同样被增量索引覆盖（parent 在 datom 覆盖集内）。

## 5. runtime-write / MCP 同名页链路（H2，仅 current）

- 源码核验：anchor **无** `upsertNodes → build-upsert-nodes-edn → :batch-import-edn` 链路（`tools.cljs:1264`、`cli.cljs:333-349` 为本地新增）。
- 源码核验：`add-uuid-to-page-if-exists`（`export.cljs:1132`）用 `get-case-page` → `get-first-page-by-title`（`initial_data.cljs:23`：按 eid 升序取最老 `:block/title` 匹配页，谓词仅 `page?`，**无 deleted-at 过滤**）→ 新页 UUID 被改写为旧页（可能是回收页）UUID。`build-import` 的 `check-for-existing-entities` 路径亦存在（未逐行核验每个分支，边界见 §7）。
- **推断 + issue14 既有实测**（`.agents/audits/current-ui-acceptance-37203268496/`，父代理材料，非本次运行）：MCP 同名页 add 会复用回收页 UUID、保留 `deleted-at`、子块挂到回收页。
- **本次源码核验的关键修正**：即便发生上述写入，current 的增量索引对该写入的 `blocks-to-add` 仍被 `hidden-entity?` 过滤（新子块的 `:block/page` = 回收页，页有 `deleted-at` → hidden）；页块自身 `:block/uuid` 未变 → 无增量 datom → 不被重收。**因此 H2 写入不会把回收条目重新送进 FTS**（推断，基于 §3 过滤链；未做运行复现）。
- **推断**：H2 解释的是"内容被静默挂到回收页"（#21 写语义缺陷），**不是**"GUI 搜索显示回收行"的直接原因。

## 6. 旧 GUI 可见性差异的最终收敛与 blocker

### 静态一致项与辅助证据（不排除运行时回归）
- 排除"fork 改了 hidden/recycled 过滤谓词"：逐字无 diff（§3）。
- 排除"SQL/FTS 层存在独立回收过滤或残留标志"：实测 P1（§2.2）。
- 结构转写核验：T1/T2 的 JS 布尔结果相同；未运行真实 CLJS 回收/恢复事务，不能排除运行时增量机制差异。
- 排除"MCP 写入后 FTS 增量会收录回收树"：源码核验过滤链（§5）。
- **父代理源码核验修正（查询后过滤）**：`combine-results`（`worker/search.cljs:829-881`）在 reduce 中**再次**检查 `hidden-entity?`（:845），且 **anchor 原函数同样存在该分支**。因此"SQL/FTS 含遗留回收行"**不能**直接推出"GUI 会返回回收条目"——即使 `blocks`/FTS 表残留回收行，查询合并阶段仍会按 `hidden-entity?` 过滤。§2.2 的含义"回收可见性 100% 由 CLJS 层 hidden-entity? 决定"需据此收窄为：**写入侧（upsert）与查询侧（combine-results）两层都过滤 hidden**，SQL 层既非唯一过滤点也非无过滤；"FTS 残留行 ⇒ GUI 可见"这一因果链不成立。
- 源码核验：`fb5eb4eb43` 含 `a3f075728b`，源码中的 `search-db-version=6` 一致。这不是已交付包内执行证据，不能排除包内行为差异。
- **实测（版本 bump 历史）**：`search-db-version` 在 **anchor `22a29b30de` 本身**从 5→6（`git show 22a29b30de -- handler/search.cljs`，diff 显示 `-5/+6`）；anchor 的前一个提交（`22a29b30de~1`，即 #13529 后）仍为 5。**父代理源码核验修正**：anchor 及以后构建打开 v5 索引时**并非一律全量重建**——`version==5` 且非 force 走 `schedule-fts-rowid-migration!`（条件迁移，原地 rowid 迁移，**不重扫图、不重新应用 hidden 过滤**，FTS 行通过 trigger 从旧 `blocks` 表逐批拷贝）；只有 force 或其余 version 分支才全量重建（truncate + 按批 upsert + `(remove hidden-entity?)`）。**不能由当前 user_version=6 排除历史曾在 5 并走条件迁移的路径。**

### 剩余候选（**根因 UNRESOLVED**；以下均为未裁决候选，非排除项）

> **父代理纠偏**：父代理已核验 `combine-results` 查询后 `hidden-entity?` 过滤 + v5 条件迁移分支，撤掉"迁移残留 → GUI 可见"的过强因果。下列候选 1(a)/(b) 的"FTS 残留行可被 GUI 搜到"一环**不再成立**（查询侧会过滤）；候选 1 降为弱候选，不再作为主解释。所有候选保留为 UNRESOLVED，等待旧包身份 + live 镜像 `PRAGMA user_version` 输入后重新裁决。

1. **旧包为 v5 时代的 upstream 构建（未验旧包 SHA；**强度降级——查询后过滤削弱"残留→可见"链**）**：
   - 实测（版本 bump 历史）：`search-db-version` 在 anchor `22a29b30de`（2026-10-03）从 5→6。anchor 之前的 upstream 构建（含 2026-09-30 的 `e8f044822a`）为 v5。
   - 源码核验（anchor 与 current 相同逻辑，`handler/search.cljs:515-554`，**父代理修正**）：打开图时 `version==5` 且非 force → `schedule-fts-rowid-migration!`（**条件迁移**，逐批从旧 `blocks` 表拷贝，**不重新应用 hidden 过滤**）；`version==6` 且非 force → 直接返回；其余/force → 全量重建（truncate + 按批 upsert，批次经 `(remove hidden-entity?)`）。**不能断言 v6 对 v5 一律 truncate。**
   - **实测（历史过滤形态核验，下一步 1 已执行）**：`hidden-entity?`（含祖先 `deleted-at` 链，经 `entity_util/hidden?`）与增量 `sync-search-indice` 的 `(remove hidden-entity?)` 在以下各点**逐字存在**：v2 时代（`6f6b675ec7~1`，2026-07 前）、`7679e73b0d`（2026-03-05）、v5（`e8f044822a`、`25650bd255`、`22a29b30de~1`）、anchor v6（`22a29b30de`）、current。`search-db-version` 演进实测：2（v2 时代 db_core）→ 5（`792f86328b`，2026-09-30）→ 6（`22a29b30de`，2026-10-03）。
   - 推断修正：既然**所有**可追溯版本的增量与全量路径都过滤 hidden，且 `combine-results` 查询侧也过滤 hidden（父代理核验），"旧 GUI 搜得到回收行"**不能**来自旧包代码把回收行写进 FTS 且查询不过滤——查询侧过滤削弱了整条"残留→可见"链。收窄为两个弱子情形（均需旧包身份 + live 镜像输入才可能裁决）：
     - (a) **v5→v6 原地迁移窗口**：旧包为 v5，`version==5` 且**未完成** rowid 迁移时打开图 → `schedule-fts-rowid-migration!` 从旧 `blocks` 表逐批拷贝（不重过滤）；若旧 `blocks` 表含回收行（来自更早索引遗留），迁移期间表内可含回收行——**但查询侧 `combine-results` 仍按 `hidden-entity?` 过滤，故 GUI 可见性不成立（父代理核验修正；原"可搜到"表述撤销）**。此子情形仅说明"迁移不清除表内遗留行"，不能推出"GUI 显示回收行"。
     - (b) **更早期索引遗留行 + v5 原地迁移**：同 (a) 机制，遗留行来源在 v2 之前的索引格式（未追溯，预算内不可行）；同样受查询侧过滤约束。
   - 反证条件：若旧包 SHA 确认 ≥ anchor（v6 时代）且非 force 打开，则走条件迁移或直接返回，(a)/(b) 的"迁移不清除"仍可能适用但查询侧过滤不变 → 差异**更不可能**来自 FTS 残留；只能来自 `:search/result` 状态残留（候选 3）或旧包自身未记录的本地改动（fork 旧版本，未验）。
2. **全量重建窗口内的时序观察（推断，弱）**：重建中途 FTS = 已完成批次；批次过滤 hidden，残留同样不含回收条目 → 仅当旧索引本身含回收行（=候选 1）才可解释。
3. **`:search/result` 状态残留（推断，弱）**：Cmd-K 结果发布（anchor 已有无条件发布）若未清空，可能显示回收**前**的查询残留。**父代理核验修正**：已检查 Cmd-K 直接调用 `block-search`，**不能**未经新证据把 renderer 聚合 handler 的 `:search/result` 发布当作 Cmd-K 的结果来源——Cmd-K 的 direct-path 与 renderer 聚合 handler 的发布路径应**分开验证**（见 B brief 行为验证项）。若用户"旧 GUI 显示回收行"指回收后即时查询，此项排除。未验（需 GUI 时序证据，本边界不操作 GUI）。

### 精确 blocker（本调查未遵守 8 分钟预算；实测 26 分钟 / 210 API calls，**时间预算违约**）
- **父代理核验记录**：本调查实际耗时 26 分钟、210 API calls，未遵守 brief 的 8 分钟工作预算；实际既未编译 CLJS 两版（shadow-cljs 冷编译 db-worker-node，单次 > 10 分钟）也未得包内对照。边界禁止主 worktree 编译、禁止安装 shared dependencies。
- **anchor/current 包内运行对照不可行（本边界内）**：需 shadow-cljs 冷编译 db-worker-node（两版各一次，单次 > 10 分钟，且需 `pnpm install` 一致性保障），超出 8 分钟预算；且边界禁止主 worktree 编译、禁止安装 shared dependencies。
- **旧包身份未记录（唯一裁决缺口）**：无旧 GUI 的 SHA/构建号/安装日期；候选 1(a)/(b) 与候选 3 的裁决、以及"v5 原地迁移窗口"是否实际命中，都依赖该输入 + 读用户 `search-db.sqlite` 的 `PRAGMA user_version`（需授权读 live 镜像）。

### 下一步（准确、可执行，交父代理排序）
1. **（需用户输入）** 向用户/issue 记录索取旧 GUI 包的确切 SHA 或版本号——当前所有候选的裁决都卡在这一个输入上。
2. **（只读，需授权读 live 镜像）** 可读取 `PRAGMA user_version` 作为索引当前状态记录。`=6` 不能排除历史曾从 v5 迁移；`=5` 或 `<5` 不能证明 GUI 差异候选命中。anchor/current 对 v5 且非 force 走条件迁移，不是一律全量重建；查询后过滤仍须纳入真实对照。
3. **（拿到旧包身份后，隔离环境运行对照）** `git worktree add --detach <dir> <old-sha>` + `pnpm i` + 编译 db-worker-node，用同一 synthetic fixture（建页→写块→MCP 同名 add→recycle→查询）在 old/current 各跑 `:thread-api/search-blocks` 直接比较 FTS 行。
4. #21（MCP 同名页 add 复用回收页 UUID）保持独立缺陷记录，不并入搜索可见性结论（本次已核验该写入**不会**把回收条目重新送进 current 的 FTS）。

## 7. 证据文件与局限

- `report.md`（本文件）
- `source-diff.md`：anchor/current 关键函数逐字对照 + SQL 层结论
- `probe/fts_sql_probe.cjs` + `probe/results.json` + `probe/stdout.txt`：生产 SQL 实测（可重跑：`node probe/fts_sql_probe.cjs`）
- `probe/recycle_transition_probe.cjs` + `probe/results_transition.json` + `probe/stdout_transition.txt`：`skip-search-sync?` **JS 转写**形态对照（**非真实 CLJS 执行**；抽取的 CLJS 源码仅作记录，未编译未运行；可重跑 `node probe/recycle_transition_probe.cjs` 重放 JS 转写，依赖 `git show`，只读）
- `source/anchor_search.cljs`、`source/anchor_db_listener.cljs`、`source/anchor_handler_search.cljs`：anchor 关键文件全文存档（git show 输出）

局限：
- **时间预算违约**：本调查 26 分钟 / 210 API calls，超出 brief 8 分钟预算；既未编译 CLJS 两版也未得包内对照。保留静态/SQL 辅助证据，拒收过强 root-cause 或 ruled-out 结论。
- 探针 2.1 的"真实函数"是**从 git 对象抽取的 defn 的 1:1 JS 结构转写**（该 defn 只读三个 flag，无副作用，转写无歧义；抽取的原文存于 results_transition.json 供复核），**不是 shadow-cljs 编译产物运行**——严格意义上 anchor 与 current 两侧均**未经编译执行**，本探针不实测 recycle transition、不实测增量 FTS 更新，不能称为"真实生产函数探针"。
- **查询侧过滤未纳入 §2.2 含义**：`combine-results`（worker/search.cljs:829-881，:845）在查询合并阶段再次 `hidden-entity?` 过滤（anchor 同分支），SQL 层"无第二条过滤路径"的表述应理解为"写入侧过滤 + 查询侧过滤"两层，"FTS 残留行 ⇒ GUI 可见"不成立（父代理核验）。
- **v5 迁移是条件分支**：handler/search.cljs:515-554 对 `version==5` 且非 force 走 `schedule-fts-rowid-migration!`（条件迁移），非全量一律 truncate；不能由当前 user_version=6 排除历史曾在 5 的路径（父代理核验）。
- 未覆盖 `check-for-existing-entities` 的全部分支（H2 只核验了 title 路径）；未运行任何既有 CLJS 测试集（预算与"不重跑已通过测试"边界）。
- **Cmd-K 结果来源未核验**：已检查 Cmd-K 直接 `block-search`，未将 renderer 聚合 handler 的 `:search/result` 发布当作 Cmd-K 来源；两条路径分开验证（见 B brief）。
- 所有"推断"项均标注了依据与反证条件，未提升为结论；**根因整体保持 UNRESOLVED**。
