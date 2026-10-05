# Anchor (22a29b30de) vs Current (546d1f7cca) 关键函数逐字对照

方法：`git show <ref>:<path>` 直接读 git 对象（未 checkout、未改工作区）。
extracted 源文件存于 `source/anchor_*.cljs`。

## 1. skip-search-sync?（db_listener.cljs:174）— 唯一有语义 diff 的共享函数

| | anchor 22a29b30de | current 546d1f7cca |
|---|---|---|
| 逐字源 | `(or (:from-disk? tx-meta) (:logseq.graph-parser.exporter/imported-data? tx-meta) (:logseq.db.sqlite.export/imported-data? tx-meta))` | `(or (:from-disk? tx-meta) (:logseq.graph-parser.exporter/imported-data? tx-meta) (and (:logseq.db.sqlite.export/imported-data? tx-meta) (not (:logseq.outliner.op/runtime-write? tx-meta))))` |
| MCP runtime-write import（`imported-data?+runtime-write?`） | **skip=true → 增量索引不跑** | **skip=false → 增量索引跑** |
| 普通 recycle/restore op | 不跳过 | 不跳过 |

实测：`probe/recycle_transition_probe.cjs`（对从 git 对象抽取的真实 defn 做 1:1 结构转写后对 5 种 tx-meta 形态运行），见 `probe/results_transition.json`。唯一 diff 行 = T3-mcp-upsert。

## 2. hidden-entity? / hidden-search-node?（search.cljs）— 无 diff

anchor search.cljs:589-602 与 current search.cljs:600-613 逐字相同（`hidden-search-node?` 走 `ldb/hidden?`，`hidden-entity?` 额外检查 `(:block/page entity)` 页的 `hidden?`）。`entity_util/hidden?`（含祖先 `deleted-at` 链）在 anchor→current 间无 diff（git diff 为空）。

## 3. sync-search-indice（search.cljs）— 增量过滤无 diff

anchor search.cljs:1237-1238 与 current search.cljs:1265-1267 相同：
`:blocks-to-add (->> (entities-for db-after add-eids) (remove hidden-entity?))`
`:blocks-to-remove (entities-for db-before remove-eids)`
`entity-tree`（块 root → `ldb/get-block-and-children`；页 root → `page-tree`）两版相同。

## 4. <build-blocks-index!（handler/search.cljs）— 全量重建过滤无 diff

anchor handler/search.cljs:364 与 current :413 相同：`(remove search/hidden-entity?)` 后按批 upsert。`search-db-version = 6` 两版相同（实测：`git show fb5eb4eb43:handler/search.cljs` 同为 6，已交付包 37203268496 亦同）。

## 5. 本地新增（仅 current，anchor 无此链路）

- `deps/outliner/src/logseq/outliner/recycle.cljs` 的 `recycle!`/`restore!`/`recycle-blocks-tx-data`/`restore-tx-data` 整文件本地新增（anchor 该文件不存在 → `git show 22a29b30de:deps/outliner/.../recycle.cljs` 报 path does not exist）。
- `deps/outliner/src/logseq/outliner/op.cljs:148-152,420-423`：`:recycle-blocks` op schema + 分支（本地）。
- `src/main/logseq/api/db_based/tools.cljs:1264 build-upsert-nodes-edn`、`cli.cljs:333-349` MCP upsert `transact!`（`runtime-write? true`）— 本地。
- `deps/db/src/logseq/db/sqlite/export.cljs:1132 add-uuid-to-page-if-exists` + `deps/db/src/logseq/db/common/initial_data.cljs:23 get-first-page-by-title`（按 eid 最老、仅 `page?` 谓词）— 源码核验存在于两版，但**被 MCP 同名页 add 触发的路径仅 current 有**（anchor 无 upsertNodes→batch-import-edn 链路）。
- `handler/search.cljs` 的 `:thread-api/search-block-uuid`/`:search-page-uuid`、`search.cljs` 的 `:block` scope 选项 — 本地新增，不影响回收可见性谓词。

## 6. FTS SQL 层（无 diff，probe 实测）

`probe/fts_sql_probe.cjs` 用 current HEAD 逐字 SQL（schema/trigger/upsert/delete/match/like/exact-title，node:sqlite 内存库）实测：
- `blocks` 与 `blocks_fts` 均**无** deleted-at/hide 列（P1）。
- `DELETE FROM blocks`（search-delete-blocks 路径）经 `blocks_ad` trigger 删除 FTS 行（P2）。
- exact-title 读 `blocks` 表而非 FTS（P3）。
- `truncate-table!` = DROP+CREATE+`PRAGMA user_version = 0`；重建中途 FTS = 已完成批次（P4）。
- 结论：**SQL 层对回收条目无任何差别处理；回收可见性 100% 由 CLJS 层 hidden-entity? 是否把行 upsert/保留决定。**

## 7. 因果收敛（标注）

1. 实测：anchor 与 current 的回收过滤谓词（hidden-entity? 链）逐字相同 → "旧 GUI 能看到回收行" **不可能**归因于 fork 改了过滤谓词。
2. 实测：两版全量重建与增量 upsert 均过滤 hidden → 稳态（重建完成+增量一致）下 GUI 查不到回收条目是**两版共同设计**，非回归。
3. 实测：anchor 无 MCP upsert/recycle-blocks 链路 → 只有 current（含已交付包 37203268496）里，MCP 同名页 add 会把新页 UUID 改写为最老同名页（**可能是回收页**，`get-first-page-by-title` 无 deleted-at 过滤）并把子块挂到该回收页（推断：`build-import`→`->block-tx` 按 page-id 设置 `:block/page`；issue14 实测已确认"复用原 UUID、保留 deleted-at、子块挂到回收页"）。
4. 推断（H2 细化）：该 MCP 写入在 **current** 触发增量索引（skip=false，实测）；新子块自身被 `hidden-entity?`（页 deleted-at）过滤 → FTS 增量**不**新增回收树行；页块自身 `:block/uuid` 未变（无 `:block/uuid` datom）→ 增量**不**重收页块。因此 H2 本身**不能**让回收条目出现在 FTS。
5. 推断（H1，最终最强解释）：用户"旧 GUI 能显示回收搜索行"只能发生在**全量重建窗口**之前——即该图上一次完整重建**完成之前**的 FTS 残留状态；或旧包（身份未知）其 `user_version` 未达 6 导致**不**重建、沿用了更古老索引（老索引由老版本 CLJS 构建，其 hidden 语义可能不同——未验，旧包 SHA 未知）。anchor 与 current 对 `user_version<6` 的处理相同（重建），故"旧包"行为差异**必须**依赖旧包自身的 search-db-version 与 hidden-entity? 历史形态（未验）。
6. 未验：旧 GUI 显示的具体查询词/时刻/重建进度；旧包 search-db.sqlite 的 `user_version` 与构建版本；已交付包 37203268496 内实际 FTS 行状态（无法在不操作 GUI/live graph 的边界内读取用户 OPFS 索引）。

## 8. 本对照的有限边界（必须随结论交付）

- 这是**源码级 + SQL 形态级** anchor/current 对照，**不是**同 fixture 的包内运行对照（8 分钟预算内 shadow-cljs 冷编译不可行，见 report.md §blocker）。
- anchor 侧无可用运行产物（anchor 树未独立编译过）；anchor 行为结论全部来自 git 对象逐字源码，标注"源码核验"。
- "旧 GUI 能显示回收行"的用户观察未在本次边界内复现（不操作 GUI、不读 live graph）。
- 两条独立未决：(a) 旧包身份与索引状态（未验）；(b) MCP 同名页 add 静默复用回收页 UUID 的写语义缺陷（#21 范围，本次不修，仅记录其对搜索可见性无增量贡献）。
