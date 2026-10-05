# 父代理纠偏审查（search-next 两子代理 + 包验证 brief）

日期：2026-10-05
工作区：D:/orca/workspaces/logseq/issues-human-test-20261003
HEAD 546d1f7cca51ee4cdfeb68f5c3bedbedf820a03a
source 5c1b5e55740ff0e0979e153e24cb84a5f174f175
upstream anchor 22a29b30dee3b3930cf49bba50454650c31d2a07

本文件记录父代理对 A（root-cause）、B（lint-package）两子代理报告与 B package-validation-brief.md 的纠偏审查，以及父代理独立核验的证据。仅文档修订，未改生产代码/计划/图，未 commit/push，未触发 CI，未委派，未网络请求（除已完成的官方 checksum 获取）。

## 1. 核心裁定

**回收搜索差异根因 = UNRESOLVED。**

父代理源码核验的两条证据足以撤掉 A 报告中"迁移残留 → GUI 可见"的过强因果：

1. **查询后过滤**：`combine-results`（`src/main/frontend/worker/search.cljs:829-881`）在 reduce 中再次检查 `hidden-entity?`（:845），且 anchor 原函数同样存在该分支。因此"SQL/FTS 含遗留回收行"**不能**直接推出"GUI 会返回回收条目"——即使 `blocks`/FTS 表残留回收行，查询合并阶段仍会按 `hidden-entity?` 过滤。**不能声称恢复旧行为。**
2. **v5 条件迁移分支**：`handler/search.cljs:515-554` 的条件为：`version==search-db-version`（=6）且非 force → 直接返回；`version==fts-id-keyed-search-db-version`（=5）且非 force → `schedule-fts-rowid-migration!`（**条件迁移**，非 truncate）；其余/force → 全量重建。**不能断言 current/v6 对 version 5 一律 truncate，也不能由当前 user_version=6 排除历史曾在 5 的路径。**

据此，A 报告的候选 1(a)/(b)"FTS 残留行可被 GUI 搜到"一环不再成立（查询侧会过滤），候选 1 降为弱候选。所有候选保留 UNRESOLVED，等待旧包身份 + live 镜像 `PRAGMA user_version` 输入后重新裁决。

## 2. A 报告（search-next-root-cause/report.md）纠偏项

| # | 原表述 | 父代理核验 | 修订 |
|---|--------|-----------|------|
| A1 | §2.1 标题"真实 `skip-search-sync?` defn"；§7"真实生产函数探针" | `probe/recycle_transition_probe.cjs:65-77` 的 `makeSkipSearchSync` 是**硬编码 JavaScript 两版函数**，抽取的 CLJS 源码仅作记录（存于 results_transition.json meta），**并未执行该源码**（无 CLJS/shadow-cljs 编译） | 标题改为"**JS 转写**，非真实 CLJS 执行"；明确"不实测 recycle transition、不实测增量 FTS 更新，不能叫真实生产函数探针"；T1-T5 结果降为"结构转写核验"级 |
| A2 | §3"`user_version < 6` 时无条件 `<build-blocks-index!`" | handler/search.cljs:515-554 为条件分支：v5 且非 force 走条件迁移，非全量一律 truncate | 改写为三分支（v6 返回 / v5 条件迁移 / 其余全量重建）；加"不能断言 v6 对 v5 一律 truncate，不能由当前 user_version=6 排除历史曾在 5 的路径" |
| A3 | §2.2 含义"回收可见性 100% 由 CLJS 层 hidden-entity? 决定；SQL 层无第二条过滤路径" | combine-results（worker/search.cljs:829-881，:845）在查询合并阶段再次 hidden-entity? 过滤，anchor 同分支 | 加"父代理源码核验修正（查询后过滤）"段落；收窄为"写入侧 + 查询侧两层都过滤 hidden"；"FTS 残留行 ⇒ GUI 可见"因果链不成立 |
| A4 | §6 已排除"anchor 及以后构建触发全量重建（truncate）" | v5 条件迁移分支（A2） | 改写为"version==5 且非 force 走条件迁移（非 truncate）；只有 force 或其余分支才全量重建；不能由当前 user_version=6 排除历史曾在 5 的路径" |
| A5 | §6 候选 1(a)"迁移期间可搜到" | 查询侧过滤（A3） | 撤销"可搜到"，改为"表内可含回收行但查询侧 combine-results 仍过滤，GUI 可见性不成立"；候选 1 降为弱候选 |
| A6 | §6 候选 3":search/result 状态残留 → Cmd-K 结果来源" | 已检查 Cmd-K 直接 `block-search`，不能把 renderer 聚合 handler 的发布当作 Cmd-K 结果来源 | 加"Cmd-K direct-path 与 renderer 聚合 handler 发布路径分开验证" |
| A7 | §6 blocker"8 分钟预算内的真实阻碍" | 实测 26 分钟 / 210 API calls，未遵守 8 分钟预算；既未编译 CLJS 两版也未得包内对照 | 改标题为"时间预算违约"；加"保留静态/SQL 辅助证据，拒收过强 root-cause 或 ruled-out 结论" |
| A8 | 报告无 UNRESOLVED 总裁定 | 父代理裁定根因 UNRESOLVED | 加头部"父代理纠偏"段 + §6 候选标题改"根因 UNRESOLVED" |

## 3. B 报告（search-next-lint-package/report.md）纠偏项

| # | 原表述 | 父代理核验 | 修订 |
|---|--------|-----------|------|
| B1 | §1"完整在 lint.raw.txt"；§3 产物清单含 lint.raw.txt | `lint.raw.txt` **不存在**——目录实际仅有 lint.log 等过程文件，子代理自报但从未落盘 | 改为引用 `lint.parent.raw.txt`（父代理独立复跑的真实日志）；产物清单加"lint.raw.txt 不存在"标注 |
| B2 | §1 lint PASS 归属子代理自报 | 父代理 fresh 下载官方 standalone，校验 zip 及 exe checksum 一致，独立运行 5 文件 lint：exit 0 / errors 0 / warnings 0 / 1 info | 五文件 lint PASS **升为父代理实测 PASS**（非子代理自报）；加"版本三重验证"（官方 checksum 一致 + `--version` 一致 + repo 固定版本一致） |
| B3 | §1 覆盖声明无"不升全仓"边界 | 父代理强调：PASS 仅指 5 文件，不升为全仓全部 lint 通过 | 加"覆盖边界"段：全仓 clj-kondo 与 bb 其余 linter 未跑，CI 侧全仓对照需另行验证 |
| B4 | 报告无"父代理纠偏"标记 | — | 标题加"父代理纠偏修订版" |

## 4. B package-validation-brief.md 纠偏项

| # | 原表述 | 父代理核验 | 修订 |
|---|--------|-----------|------|
| P1 | §2"push origin HEAD:refs/heads/test/issues-mcp-20261003"（push 整个 546d HEAD） | 父代理要求：从已发布 fb5eb4eb43 基线仅应用 src/ 5 文件补丁，不发布整个 546d HEAD 的审计资料 | 改为 **source-only CI 分支方案**：`git checkout -b source-only-ci-5c1b5e55 fb5eb4eb43` + `git cherry-pick 5c1b5e5574` + push source-only 分支；build source SHA 稍后实际建立后记录，不预造 |
| P2 | §3 `git-ref=test/issues-mcp-20261003` | source-only 方案 | 改为 `git-ref=source-only-ci-5c1b5e55` |
| P3 | §4"确认新 run 的 headSha == 546d1f7cca（关键核验点）" | workflow `actions/checkout` ref 取 `inputs.git-ref`；`workflow_dispatch` 事件 `run.headSha` 与真正 checkout 的 source SHA **不是必然同一字段** | 改为三步核验：(a) headBranch == source-only 分支；(b) 拉实际 checkout job log/metadata 证明 source SHA；(c) 不可得则降级"未验 source SHA"，不得断言含补丁 |
| P4 | §5.1"确认构建 headSha = 546d1f7cca" | source-only 方案 | 改为"确认构建 source SHA = source-only 分支 HEAD（= fb5eb4eb43 + 5c1b5e5574，不是 546d HEAD）" |
| P5 | §5.2 Cmd-K 路径与 MCP 路径混验 | Cmd-K 直接 block-search，与 renderer 聚合 handler 发布路径不同 | 分开验证：Cmd-K direct-path 独立；MCP opt-out 必须观察实际状态发布未发生（不可只凭进程起/搜索响应判 PASS） |
| P6 | §5.2 MCP"只需验证进程能起、MCP server 能响应 search 调用" | 父代理要求：包内 MCP opt-out 必须观察实际状态发布未发生 | 改为：(a) MCP search 前后读 GUI state `:search/result` 确认无变更；(b) 无法读则降级"未验 opt-out 状态"，不得判 PASS |
| P7 | §6 blocker"Push 被边界禁止：唯一硬阻塞" | source-only 方案仍需 push 一个分支；build source SHA 未建立 | 加"build source SHA 未建立"阻塞项；push 范围收窄为 source-only 分支 |

## 5. 父代理独立核验证据

### 5.1 lint 父代理实测（`lint.parent.raw.txt`）

- 官方 SHA256 fresh 获取自 clj-kondo/clj-kondo v2026.04.15 release：`0cae02590f03ce7c4dc14cde7b320c52ea26c1f7198e10a1ee36f08`
- zip 与解压后 exe 均校验一致（zip 内容 = exe）
- 版本：`clj-kondo v2026.04.15`（与 repo deps.edn 行 82 固定版本一致）
- 命令：`clj-kondo.exe --cache false --parallel --lint` + 5 文件（repo 根 cwd）
- EXIT: 0；errors: 0, warnings: 0；1 info（mcp_server_test.cljs:56:27 redundant boolean coercion）
- 结论：**五文件 lint 父代理实测 PASS**（不升全仓）

### 5.2 源码核验（父代理）

- `combine-results`（worker/search.cljs:829-881）reduce 中 :845 再次 `hidden-entity?`；anchor 同分支 → 查询后过滤存在
- handler/search.cljs:515-554 三分支条件（v6 返回 / v5 条件迁移 / 其余全量重建）→ v5 非一律 truncate
- Cmd-K 直接 `block-search`，非 renderer 聚合 handler 发布 → 两路径分开验证

## 6. 修订文件清单（绝对路径）

1. `D:/orca/workspaces/logseq/issues-human-test-20261003/.agents/audits/search-next-root-cause/report.md`（A 报告，8 处纠偏）
2. `D:/orca/workspaces/logseq/issues-human-test-20261003/.agents/audits/search-next-lint-package/report.md`（B 报告，4 处纠偏）
3. `D:/orca/workspaces/logseq/issues-human-test-20261003/.agents/audits/search-next-lint-package/package-validation-brief.md`（B brief，7 处纠偏）
4. `D:/orca/workspaces/logseq/issues-human-test-20261003/.agents/audits/search-next-parent-review.md`（本文件，新写）

## 7. 保留问题（UNRESOLVED，等待输入）

1. **旧包身份未记录**：无旧 GUI 的 SHA/构建号/安装日期。候选 1(a)/(b) 与候选 3 的裁决都卡在这一个输入。
2. **live 镜像 user_version**：需授权读用户 `search-db.sqlite` 的 `PRAGMA user_version`：`=6` → 排除迁移窗口子情形；`=5` 或 `<5` → 候选 1 命中（但查询侧过滤仍约束 GUI 可见性）。
3. **source-only CI 分支待授权**：source-only-ci-5c1b5e55 的 cherry-pick + push 需父代理授权（新破坏性授权）；build source SHA 稍后实际记录。
4. **包内 MCP opt-out 状态验证**：需启动 app + 隔离 fixture（待授权）；必须观察实际状态发布未发生，不可只凭进程起/搜索响应判 PASS。
5. **Cmd-K direct-path vs renderer handler**：两路径分开验证，需 GUI 时序证据（本边界不操作 GUI）。
6. **全仓 lint 对照**：CI 侧 build.yml lint 作业对新分支不可用（事件只挂 master push/PR）；全仓对照需另行安排。
7. **A 时间预算违约**：26 分钟 / 210 API calls 超出 8 分钟预算；保留静态/SQL 辅助证据，拒收过强 root-cause 或 ruled-out 结论。

## 8. 边界遵守声明

- 仅文档修订：改两 audit 子目录已有 report.md + package-validation-brief.md，新写 search-next-parent-review.md
- 未改生产代码、未改既有计划、未改图/GUI
- 未 commit/push、未触发 CI、未改 git 状态/分支
- 未委派、未读代理 trace、未网络请求（除已完成的官方 checksum 获取）
- 每个旧文件先完整读取再 patch
- 保留根因 UNRESOLVED，所有过强排除降级
- 准确记录 JS 转写/SQL 形态探针局限、查询后过滤、v5 迁移分支、时间预算违约、lint 真实路径与父验证 PASS、workflow checkout SHA 核验、code-only CI 发布及真实 MCP 状态验证要求
