# 新包真实 GUI/MCP 验证准备 — 可行性报告

CI: 37259407681 @ source-only-ci-5c1b5e55（build source SHA 以 CI checkout job log 为准，本准备不轮询）
本地 HEAD: 546d1f7cca51ee4cdfeb68f5c3bedbedf820a03a（工作区 issues-human-test-20261003）
补丁: 5c1b5e5574，5 个 src 文件（mcp_search.cljs、handler/search.cljs + 3 个测试文件）
本任务边界内只读源码 + 写本目录；未启动 app、未触图、未等待 CI、未下载、未 push/commit/编译、未写 MCP。

## 交付物（.agents/audits/search-package-37259407681/validation-prep/）

| 文件 | 用途 | 入口 |
|---|---|---|
| `verify_package_static.mjs` | 包静态核验（不启动 app） | `node verify_package_static.mjs <win-unpacked目录> [旧包目录]` |
| `prepare_fixture_graph.mjs` | 生成合成文件图 | `node prepare_fixture_graph.mjs --out <dir>` |
| `verify_package_gui.mjs` | 包内真实 GUI/MCP 行为验证（4 场景） | `node verify_package_gui.mjs --app <win-unpacked> --graph-dir <fixture目录>` |
| `REPORT.md` | 本报告 | — |
| `results/`（运行后生成） | 隔离 HOME/userData + gui-run.json 证据 | — |

## 补丁内容与验证点（源码实读）

- `electron.mcp-search/search-call-args`：options 增加 `:publish-result? false`（MCP 路径）。
- `frontend.handler.search/search` 三参版：新增形参 `publish-result?`（默认 true），`swap-state! assoc :search/result` 外包 `when publish-result?`，且 worker 调用 opts 中 `dissoc` 掉 `:publish-result?`（不外泄给 worker）。
- MCP 真实链路（`verify_package_gui.mjs` S2 实际驱动的路径）：
  `electron.server/initialize-mcp-routes`（fastify POST /mcp，streamable-http，mcp-session-id 头）
  → `invoke-logseq-api!`（send-to-renderer :invokeLogseqAPI + ipcMain handleOnce 同步回）
  → `electron.listener` safe-api-call "invokeLogseqAPI"
  → `electron.api-method/resolve-real-api-method`："logseq.app.search" → "app@search"
  → `dispatch-real-api-method`：ns'=="app" 命中 `#{"app" "editor" "db" "cli"}` → 取 `window.logseq.api` 对象
  → `logseq.api/search`（src/main/logseq/api.cljs:187，^:export，`js->clj opts :keywordize-keys true` 后调 `search-handler/search (state/get-current-repo) q opts`）
  → `frontend.handler.search/search`（补丁本体）。
  **结论：MCP searchBlocks 打到 renderer 的 `window.logseq.api.search`，即真实生产聚合 handler，非模拟。**
- 状态读取：`window.logseq.api.get_state_from_store("search/result")`（logseq.api/app.cljs get_state_from_store → get-in (state/get-state)；normalize-keyword-for-json 后 JS 对象键为 "blocks"/"has-more"）。

## 隔离可行性（逐条回答 brief 必决项）

1. **Electron second-instance 锁 + userData 隔离**：可行。
   证据：`electron.core/main`（src/electron/electron/core.cljs:471-473）`requestSingleInstanceLock` 失败才 quit；
   Electron 该锁绑定 userData 目录（`--user-data-dir` 改 userData 即换锁域）。
   `electron.configs`：`cfg-root = app.getPath("userData")`、`cfg-path = <userData>/configs.edn`
   → 隔离 userData 写 `{ :server/autostart true :server/mcp-enabled? true :server/port 12315 }` 即同时
   开 HTTP server 自启 + MCP 开关（`server.cljs reset-state!` 读 `:server/mcp-enabled?`），不碰原配置。
   即使 live Logseq 在跑，两实例 userData 不同不冲突；second-instance 回调（core.cljs:492）只在锁冲突时
   触发，且仅做窗口切换，无副作用。
2. **CLI --config 独立图创建**：未采用 CLI，改为 **app 内建图**（见下），CLI 路径不进入脚本。
   原因：`graph create` 的根目录控制为 `--root-dir`（cli 全局选项，默认 ~/logseq）+ `--config <cli.edn>`，
   与 Electron 侧 registry（`<home>/.logseq/graphs.edn`，electron.configs/graph-registry-path）分离；
   让 CLI 建图再让 app 认图需要两边 registry 对齐，多一层不确定。app 内建图（预置隔离 registry +
   隔离 `~/logseq/graphs/<name>`）单侧闭环，隔离更彻底。live registry 只读核对过格式
   （`{:repo "logseq_db_X" :graph-name "X" :local-graph-id ... :graph-id ... :updated-at ...}` 向量），
   脚本按同格式预置并加 `:path` 指向隔离图副本。
3. **production Electron MCP → renderer 真实搜索**：可行（上节链路）。MCP 开关经隔离 configs.edn 预开；
   server 自启经 `:server/autostart`（`server-indicator` 组件挂载后延迟 1s `:server/do :restart`）。
   若 90s 内 API 未起 → S2 记 FAIL 并落日志，不判 PASS。
4. **CDP 读取/监听 GUI :search/result**：读取可行；**真 watch 不可用，降级为 500ms 轮询事件流**。
   证据：RF64 `frontend.state` 无公开 add-watch/`get-state-all`（api.cljs 仅导出
   `get_state_from_store`，路径式读取）；state 写路径 `swap-state!`→`replace-state!`（state.cljs:832）。
   轮询判定语义（写进脚本与结果）：**delta=0 是严格结论**（轮询持续运行时期间任何 swap 必被捕获，
   满足 brief"前后相等不足以下无重复写结论"的要求——比的是事件流计数，不是首尾快照）；
   delta>0 时事件 from/to 落盘供父代理归因。结果恒标 `pollOnly: true`。
5. **renderer 常规搜索正控制（S1）**：可行。入口与 clj-e2e `util/search`（clj-e2e/src/logseq/e2e/util.clj:128）
   相同：`#search-button` → `.cp__cmdk-search-input` fill（先空后填防 onChange 不触发）→ 断言
   `:search/result` blocks 含 needle。
6. **Cmd-K direct-path 另测（S3）**：可行且分开记录两条独立证据：(a) cmdk 列表 DOM 文本命中
   （`.cp__cmdk-item-main-text`，list_item.cljs），(b) `:search/result` 发布层命中。
   注：global 搜索的 cmdk nodes 组经 `frontend.search` 引擎走 worker `:thread-api/search-blocks`，
   与 MCP 经 `logseq.api/search`→`search-handler/search` 的发布路径确实不同（S1/S3 只验证 GUI 侧，
   MCP 侧由 S2 独立验证，二者不混用证据）。
7. **临时块 active→recycle→restore（S4）**：可行，有降级。
   造块：`window.logseq.api` 导出族（api.cljs:102/110/121/128：create_page、get_block、
   get_page_blocks_tree、insert_block）；`insert_block` 底层 `editor-handler/api-insert-new-block!`
   （editor.cljs:621）接受 `{:page ...}`，不依赖 UI 焦点块。
   回收/恢复：**`window.logseq.api` 未导出 recycleBlock/restoreBlock**（api.cljs 仅导出
   db_based/cli 的 list/get/upsert/import 族，无 recycle；logseq.api.db-based.cli 有 recycle-block/
   restore-block 实现但未挂 ^:export）→ 走 **production MCP 工具 recycleBlock/restoreBlock**
   （electron.mcp-server api-tools，经 logseq.cli.recycleBlock），仍为包内真实链路。
   查询三段：可见 → 回收后不可见 → 恢复后可见；期间 watcher delta 期望 0。
8. **只用合成数据**：`prepare_fixture_graph.mjs` 生成 1 页 4 块，needle 词唯一（s1needle/s4needle/
   fixturechildnested/fixturecontrolword），无真实 UUID/路径；图先复制到隔离 `~/logseq/graphs/`
   再被 app 使用，源目录只读。

## 能验证 / 不能验证（包内, 父代理验收后运行）

能验证（实测级）:
- S1 publish-result? true 默认路径在包内发布 :search/result（GUI 正控制）。
- S2 MCP searchBlocks 返回正确 + 3 次调用期间 :search/result 轮询事件 delta=0（opt-out 状态级验证,
  严格"无重复写"判定成立）。
- S3 Cmd-K UI 渲染层与 :search/result 发布层分别有证据（direct-path 无回归）。
- S4 回收/恢复后搜索可见性翻转（共享过滤逻辑未改的间接回归防护）。
- 静态：新/旧包 app.asar 指纹差异 + 版本号（推断级, 脚本自带降级判定）。

不能验证 / 降级项:
- G1 `window.logseq.api` 无 recycle/restore 导出 → S4 用 MCP 工具替代（仍是 production 链路, 非降级为模拟）。
- G2 无 RF64 公开 watch → 状态观测为 500ms 轮询事件流（delta=0 严格、delta>0 需归因, 恒标 pollOnly）。
- G3 **build source SHA 无法从包内文件直接读取**：workflow "Update APP Version" 只改 package.json version
  与 VERSION 文件（.github/workflows/build-desktop-release.yml compile-cljs 作业），无 git SHA 落盘；
  `build-metadata-hook`（shadow-cljs.edn electron build 用）存在但 asar 内是否可读未验证 →
  源码版本证明仍以 CI checkout job log（brief §4 关键核验 (b)）为准；静态脚本的 asar 指纹只作
  "包含/不包含补丁特征"的推断级证据，脚本对"新旧包同检出/同未检出"自动判 UNVERIFIED。
- G4 `rebuild_search_indices` 导出名按 `logseq.api` ns 的 `^:export search`/rebuild 族推断
  （search-handler/rebuild-indices! 存在, api.cljs 导出名以包内实际为准）; 脚本对其做了 try/catch,
  失败不致命（图加载本身会触发索引构建, handler/events.cljs:91 <build-search-index!）。
- G5 server 端口固定 12315（隔离 configs.edn 指定）; 若本机其它进程占 12315 → API 探测失败 →
  app-start FAIL 落日志, 不重试其它端口（避免引入不确定性, 父代理可先 `netstat` 预检）。
- 旧包对照（37203268496 @ fb5eb4eb）需父代理另行下载; 不传对照参数时静态脚本只验新包。

## 运行顺序（父代理, 验收后）

```
cd D:/orca/workspaces/logseq/issues-human-test-20261003/.agents/audits/search-package-37259407681/validation-prep
node prepare_fixture_graph.mjs --out ./fixture-graph
# CI 成功 + checkout log 核验 source SHA 后, 下载并解压 win-x64 产物到某目录:
node verify_package_static.mjs <win-unpacked目录> [旧包win-unpacked目录]
node verify_package_gui.mjs --app <win-unpacked目录> --graph-dir ./fixture-graph
```

退出码约定: 0=PASS; 1=FAIL; 2=前置 BLOCKED; 3=app 启动失败。
隔离区（results/<ts>/home、userdata、gui-run-*.json）不自动删, 留证。
脚本只 kill 自己 spawn 的 Logseq.exe（SIGTERM→4s→SIGKILL）; 不碰任何既有进程; 不关 app。

## 未做（边界遵守）

- 未跑脚本（无新包, 运行需父代理下载产物后授权）。
- 未触碰当前图/当前 Logseq 实例/原配置; 未写 MCP; 未 push/commit/编译/下载/委派。
- live ~/.logseq/graphs.edn 仅只读核对格式, 未修改。
