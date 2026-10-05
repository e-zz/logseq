# 搜索收尾：父代理独立验收记录

## 结论与交付边界

总体结论：PARTIAL。MCP 搜索结果发布隔离的源码修补及针对性测试已完成验收；原先旧/新 GUI 回收搜索行为差异仍 UNRESOLVED。本轮以带明确缺口的源码增量交付收尾，不将该修补描述为已恢复 GUI 回收搜索，也不据此关闭相关 issue。

- 实测：源码修补提交 `5c1b5e55740ff0e0979e153e24cb84a5f174f175` 存在。父代理已独立执行六个针对性测试 namespace，合计 119 tests / 431 assertions，0 failures / 0 errors。
- 实测：本次收尾重新解析六份既有原始日志，逐项与 tests.json 对账，核验 namespace、计数、退出码和 SHA256；没有重新运行已通过测试。记录见 `.agents/audits/search-mcp-filter-parent/evidence-verification.json`。
- 实测：收尾检查时，上述提交中的两个生产文件与三个新增/变更测试文件没有后续未提交源码差异；工作区存在其他历史未跟踪材料，不能称整个 worktree clean。
- 源码核验：upstream 锚点为 `22a29b30dee3b3930cf49bba50454650c31d2a07`。无条件发布 `:search/result` / `:search/more-result` 在锚点就已存在，不是已证明的 fork 新增缺陷。
- 源码核验：当前 Cmd-K 的已检查路径直接调用 `frontend.search/block-search`，MCP 经 renderer 聚合 handler。共享底层 worker 不等于共享整个 GUI 调用路径。

## 修补内容

源码核验：仅生产代码中的调用方结果发布选项发生变化：

1. `src/main/frontend/handler/search.cljs` 接收 `:publish-result?`，默认 true；false 时不执行结果状态发布，仍返回搜索结果。
2. 同一 handler 在调用 worker 前移除 `:publish-result?`，避免将发布策略传入共享查询引擎。
3. `src/electron/electron/mcp_search.cljs` 为 MCP 明确设置 `:publish-result? false`。

源码核验：本修补没有改变共享 hidden/recycled 谓词、全量/增量索引监听逻辑、回收页同名写入处理或公开 MCP schema。用户报告的回收可见性差异没有据此获得根因解释。该改动是调用方状态隔离增量，而非已实测的用户可见 Cmd-K 修复。

## Standards

- 源码核验：差异限于既有 handler、MCP adapter 和相关测试；没有复制查询引擎、引入共享可变选项或额外搜索服务。审阅了根 AGENTS.md 和 prompts/review.md，并沿用此前父代理独立验收，不启动新一轮多代理/全应用审计。
- 实测：生产提交的 `git diff 5c1b5e5574^ 5c1b5e5574 --check` 在此前父复核通过。收尾文档及证据另作定向提交前的 diff 检查。
- 未验：clj-kondo lint，worker 报告执行被缺失 binary 阻塞，父复核时 PATH 也未找到该程序。此项保留，不用编译无警告或 diff check 代替 lint。
- 未独立复现：worker 描述的 pnpm 编译与临时撤销生产修补后的 RED 过程。报告保留为 worker 自报，不算父代理独立证据。

## Spec：需求与证据对照

| 验收对象 | 状态 | 证据类型与直接证据 | 限制 |
|---|---|---|---|
| MCP adapter 显式禁用结果发布 | PASS（源码测试） | 实测：electron.mcp-server-test，5 tests / 22 assertions；两个参数映射断言包含 false | 不是真实 packaged IPC 调用 |
| handler 禁止 MCP 发布、默认调用方仍发布，返回结果不变 | PASS（源码测试） | 实测：frontend.handler.search-test，13 tests / 34 assertions；search-publishes-result-state-only-when-requested | worker 查询由 mock 返回，不证明包内端到端 |
| MCP 后默认调用方的选项独立、发布标记不传给 worker | PASS（所测顺序） | 实测：search-does-not-leak-scope-options-across-callers | 默认调用方后 MCP 的反向顺序及真正 page/block scope 组合不是这条新测试的独立覆盖 |
| 当前 worker 对 GUI-like/MCP-like 选项组过滤回收条目，不挤掉 limit=1 的 active 匹配 | PASS（当前 fixture） | 实测：frontend.worker.search-test，79 tests / 300 assertions；recycled-exclusion-is-identical-for-gui-and-mcp-option-sets | 同一个当前 worker，不是 upstream/current 差分运行 |
| 范围解析、MCP DB search、增量监听相关既有回归 | PASS（所跑测试集） | 实测：worker.handler.search-test 6/19、db-based.mcp-search-test 1/3、worker.db-listener-test 15/53 | 不声称覆盖整个搜索计划要求矩阵或全套应用测试 |
| GUI 默认行为与对应 upstream 的运行兼容性 | NOT TESTED | 源码核验：发布默认 true、共享可见性谓词未改 | 无同 fixture 的 anchor/current 或旧/新包运行对照 |
| 用户报告旧/新 GUI 回收搜索差异的根因 | UNRESOLVED | 用户实测记录与源码检查分别保留 | 相同谓词不能排除共享索引/运行路径的间接影响 |
| 新修补的当前包 GUI/MCP 验收 | NOT TESTED | 实测：当前已交付包的 build SHA 为 fb5eb4eb43cdb3be7a29d816b969d45c127d4861；新源码提交另列 | CI 37203268496 不包含本搜索修补，不移用其 GUI 证据 |

计数是对六个测试集的整体计数；不能逐行相加成新增测试数量，也不是全部应用用例 passed count。

## 未决项与后续入口

- 推测：索引更新时机、runtime-write listener 或不同 GUI 搜索模式可能与可见性差异有关。现有数据不能区分这些解释，不据此修改过滤规则。
- 未验：旧应用的确切 SHA、相同 disposable fixture、相同查询及 GUI 模式的受控对照。只有另行明确开展这项对照，才能宣称恢复旧行为或排除 fork 回归。
- 未验：新修补的 non-release 包内验证。本轮不构建/安装新包、不操作当前 GUI、不重启应用、不写 live graph。
- 实测：续接 dispatcher exit 1，但提交与测试产物存在。退出码的具体原因未定位；既不作为测试失败，也不作为整项通过依据。
- 边界：#21 回收同名写入不在搜索修补内；不扩展到该 importer、不发布评论、不关闭 issue、不 push。

交付建议（推断）：作为明确限于 MCP 结果状态发布隔离的源码增量保留；依据是默认发布保留、MCP 显式 opt-out、针对性测试通过。此建议不是整个 GUI 搜索兼容性验收通过或 release readiness。原可见性差异和 lint 缺口必须随最终 issue 验收表交付，不静默消失。

## 可追溯文件

- 搜索执行计划：`.hermes/plans/search-mcp-filter-upstream-compatibility.md`。
- 收束总计划：`.hermes/plans/2026-10-05_000818-issue-fixes-remaining-acceptance.md`。
- 已纠正的调查/worker 报告：`.agents/reports/search-mcp-filter-investigation.md`、`search-mcp-filter-worker.md`。
- 父代理逐项运行命令、计数和原始日志路径：`.agents/audits/search-mcp-filter-parent/tests.json`。
- 本次日志对账与 SHA256：`.agents/audits/search-mcp-filter-parent/evidence-verification.json`。
- 六份原始日志：同目录下以完整 namespace 命名的 `.txt`；总数由 JSON 和文件对账，不依赖 worker 自报。

收尾仅定向提交本报告、纠正后的搜索报告、两份计划和上述独立测试证据。其他历史 GUI/API 证据、fixture、scratch 和 integration-inputs 不在本次提交范围。
