# 搜索后续验证：两子代理派工说明

用户最新授权：继续推进 GUI 回收搜索差异、clj-kondo lint 和新补丁包内验证，明确要求用 subagents。第一批最多并发 2；父代理独立复核后决定下一阶段。

## 版本与已知状态
- 专用 worktree：D:/orca/workspaces/logseq/issues-human-test-20261003
- 当前 HEAD：546d1f7cca51ee4cdfeb68f5c3bedbedf820a03a
- 搜索生产修补：5c1b5e55740ff0e0979e153e24cb84a5f174f175
- upstream anchor：22a29b30dee3b3930cf49bba50454650c31d2a07
- 当前已交付 non-release 包：CI 37203268496 / SHA fb5eb4eb43cdb3be7a29d816b969d45c127d4861，不包含新搜索修补。
- 父代理报告：.agents/reports/search-mcp-filter-parent-review.md；此前六集 source tests 119/431 全通过，不能冒充包内或 upstream/current 对照。
- 两文件修补只增加 :publish-result? 默认 true，MCP 传 false，handler 转发 worker 前移除该选项。共享隐藏/回收过滤未改。
- 锚点已有无条件结果发布和回收过滤，不能称本轮新增这些行为；过滤代码相同也不能证明无间接 GUI 回归。
- Cmd-K 已检查路径直接调用 frontend.search/block-search；MCP 经 renderer 聚合 handler。不可混淆。

## 第一批分工与文件所有权
A：受控 upstream/current 差异调查，含 index-listener/runtime-write 及回收祖先。唯一写入目录 .agents/audits/search-next-root-cause/。禁止改生产代码、既有测试或主 worktree 的编译输出；如需独立 probe/编译，在该目录内完全隔离。先建立能捕捉 active→recycled 可见性变化的窄反馈命令，再测试假设；优先真实生产函数，不复制过滤逻辑。不足预算时保存完整可重跑 probe、实际失败日志和剩余步骤，不伪称根因已查明。
B：补实际 clj-kondo lint，并只读核对 CI/包内验证入口。唯一写入目录 .agents/audits/search-next-lint-package/。可查本机工具位置，必要时从官方项目获取便携 binary 到此目录并验证版本；不全局安装，不持久修改 PATH。lint 必须覆盖上述源码提交改动的两个生产文件和三个测试文件，使用 repo 配置；空 git-changes 结果不算覆盖。禁改源文件/config；真实报错交父代理判定。核现有工作流、远端 SHA/分支与 artifact。不 push、不触发 CI、不下载/启动/安装 app；输出具备确切命令、SHA、fixture/步骤和阻碍的新包验证执行 brief。

## 共同边界
先读 AGENTS.md、适用目录规则、repo-local skills。当前 source/tests 为权威。宿主 Windows11、terminal 是 git-bash，原生程序使用 D:/... 或 C:/... 路径。工具出错换合法路径，不能给假数据。
不操作当前 GUI、不重启/关闭 app、不读写 live graph、不打开其 DB 镜像、不调用 MCP upsertNodes；无新破坏性授权。只用 synthetic/disposable 隔离 fixture。
不得 git checkout/reset/clean/stash、删除文件、改分支、commit、push、发布 issue/comment；保留所有历史未跟踪材料。不重跑已经通过的六集测试，不做全应用 QA，不修 #21。
双方不写对方目录或全局 .shadow-cljs/static/tests.js，不安装 shared dependencies。不要启动独立 agent 或委派。不要等其他 agent，不读取其他代理运行记录。用实测/源码核验/推断/推测/未验标明结论，推断必须说明依据与局限。

## 输出约定
A：report.md、可运行 probe/命令、实际 stdout/stderr、结果 JSON，明确是否执行真正 anchor/current 对照及没有观察到差异的有限边界。
B：report.md、lint.raw.txt、命令/version/覆盖清单及 exit code、package-validation-brief.md；远端信息只读，不公开用户图名/UUID/凭据。
两者先尽早落盘初步 report，再继续有界工作；约 8 分钟内返还已验证产物或明确 blocker，不无界调查/等待。最终回复给绝对产出路径及事实摘要，不关闭父代理任务。
