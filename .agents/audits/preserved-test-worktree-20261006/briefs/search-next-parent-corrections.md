# 两子代理结果纠偏与下一阶段 brief

工作区 D:/orca/workspaces/logseq/issues-human-test-20261003；HEAD 546d1f7cca51ee4cdfeb68f5c3bedbedf820a03a，source 5c1b5e55740ff0e0979e153e24cb84a5f174f175，anchor 22a29b30dee3b3930cf49bba50454650c31d2a07。

父代理已实测：B 提供的 lint.raw.txt 不存在，实际仅 lint.log 等。父代理 freshly gh release download 官方 SHA256，校验 zip 及 exe 与 zip 内容一致，再执行 clj-kondo v2026.04.15 --cache false --parallel --lint 五个改动文件；exit0、errors0 warnings0、1info。真实日志 .agents/audits/search-next-lint-package/lint.parent.raw.txt。可将五文件 lint 升为父代理实测 PASS；不升为全仓全部 lint通过。

父代理已源码核验：
1. src/main/frontend/worker/search.cljs combine-results:829-881 在 reduce 中再次检查 hidden-entity?（845），anchor 原函数同样存在该分支。SQL 含遗留回收行不能直接推出 GUI 会返回回收条目。
2. worker/handler/search.cljs:515-554 条件：version==search-db-version 且非force返回；version==fts-id-keyed-search-db-version 且非force走 schedule-fts-rowid-migration!；否则 build。不能断言 current/v6 对 version5 一律truncate，也不能由当前 user_version6排除历史曾在5的路径。
3. A recycle_transition_probe.cjs:65-77 是硬编码 JavaScript 两版函数，抽取源码只是记录，并未执行该源码。它不实测 recycle transition、不实测增量FST更新，不能叫真实生产函数探针。
4. A 的 Cmd-K 使用 :search/result 残留候选亦与已知调用链冲突：已检查 Cmd-K 直接 block-search，不能未经新证据把 renderer 聚合handler 发布当作其结果来源。
5. A 26分钟/210API calls 未遵守8分钟工作预算，实际既未编译CLJS两版也未得包内对照；保留静态/SQL辅助证据，拒收过强 root-cause 或 ruled-out结论。
6. Workflow actions/checkout ref取 inputs.git-ref，workflow_dispatch事件 headSha 与真正checkout source SHA不是必然同一字段。brief 必须要求实际checkout log/metadata证明源码版本，不能只看 run.headSha。

仅派一个文档纠偏子代理，不做新运行调查、编译、安装、网络请求。写入仅两 audit 子目录已有 report.md/package-validation-brief.md 及新的 .agents/audits/search-next-parent-review.md；每个旧文件先完整读取再patch。A根因UNRESOLVED，所有过强排除降级，准确记录 JS转写/SQL形态探针局限和上述分支。B修正路径为实际 parent raw日志并明确版本验证，包构建候选改为待授权的source-only CI分支：从已发布 fb5eb4eb43cdb3be7a29d816b969d45c127d4861 基线仅应用 src/ 的5文件补丁，不发布整个546d HEAD的审计资料；build source SHA稍后实际建立后记录，不预造。包内 MCP opt-out 必须观察实际状态发布未发生，不可只凭进程起/搜索响应判PASS；Cmd-K direct-path与rendererhandler发布分开验证。

禁止改源码、既有计划、图/GUI、global tooling、git状态/分支、commit/push、触发CI。不委派、不读取代理trace。不要再长篇历史调查。约5分钟有界文档修订，给绝对文件路径、修改摘要、保留问题。父代理等待用户对远端code-only发布/CI与隔离包验证的授权。
