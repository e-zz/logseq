# 新补丁包内验证执行 brief（B 子代理产出，父代理纠偏修订版）

> **父代理纠偏**：本 brief 原方案（push 本地 HEAD 546d1f7cca → 触发 Build-Desktop-Release 发布整个 546d HEAD 的包）**改为待授权的 source-only CI 分支方案**：从已发布 `fb5eb4eb43cdb3be7a29d816b969d45c127d4861` 基线仅应用 `src/` 的 5 文件补丁（5c1b5e5574），**不发布整个 546d HEAD 的审计资料**（c835800f29/d21ef1502a/50ff825597/546d1f7cca 为 docs/test 提交，不进入包）。**build source SHA 稍后实际建立后记录，不预造。** 另：workflow `actions/checkout` 的 ref 取 `inputs.git-ref`，`workflow_dispatch` 事件的 `run.headSha` 与真正 checkout 的 source SHA **不是必然同一字段**——核验源码版本必须看实际 checkout log/metadata，不能只看 `run.headSha`。

## 0. 前提事实（实测）

| 项 | 值 | 来源 |
|---|---|---|
| 本地 HEAD | 546d1f7cca51ee4cdfeb68f5c3bedbedf820a03a | git rev-parse HEAD |
| 源补丁 | 5c1b5e55740ff0e0979e153e24cb84a5f174f175 | git log |
| origin 分支 SHA（test/issues-mcp-20261003） | fb5eb4eb43cdb3be7a29d816b969d45c127d4861 | git ls-remote origin |
| 本地领先远程的提交 | 5 个（c835800f29, d21ef1502a, 50ff825597, 5c1b5e5574, 546d1f7cca） | git log fb5eb4eb..HEAD |
| 已交付包 | CI 37203268496 @ fb5eb4eb（success, 2026-10-04T12:46:30Z） | gh run list |
| 已交付包是否含源补丁 | 否（fb5eb4eb 是 5c1b5e5574 的祖先） | git merge-base --is-ancestor |
| CI 工作流触发条件 | build.yml: push/PR 仅 branches [master]（paths-ignore *.md） | .github/workflows/build.yml 行 3-9 |
| 包构建工作流 | build-desktop-release.yml: 仅 workflow_dispatch，支持 windows-only 输入 | .github/workflows/build-desktop-release.yml 行 5-54 |
| upstream anchor | 22a29b30dee3b3930cf49bba50454650c31d2a07（= git merge-base HEAD upstream/master） | git fetch upstream + merge-base |

## 1. 阻塞项（必须先解决，否则没有可验证的新包）

1. **远程分支落后本地 5 个提交**，而 Build-Desktop-Release 按 git-ref（分支/SHA）从远端 checkout 构建。不 push 本地 HEAD 就无法产出含 5c1b5e5574 的包。
   - 本任务边界禁止 push。需父代理授权后执行第 2 步。
2. **CI（build.yml）不会自动覆盖该分支**：事件只挂 master 的 push/PR。新分支上 lint/test 作业不会跑。
   - 含义：新包的 CI 质量门禁只能靠 (a) 本地 source tests（父代理已确认 119/431 通过，不再重跑），(b) 本地 clj-kondo（本报告已完成，5 文件 0 errors / 0 warnings / 1 既有 info），(c) 包构建工作流自身的 compile-cljs/test 步骤（见下，只读核对：build-desktop-release.yml 的 compile-cljs 作业含 shadow-cljs 编译 + pnpm cljs:test，构建失败即包失败）。
3. **不能复用 37203268496 的产物做验证**：该包 @ fb5eb4eb 不含源补丁。若下载它做对照只能作为"旧行为基线"，不能作为"新补丁包内验证"的证据。

## 2. source-only CI 分支（需父代理授权；本任务未执行）

> **父代理纠偏**：不 push 整个 546d HEAD。改为从已发布基线 `fb5eb4eb43cdb3be7a29d816b969d45c127d4861` 仅应用 `src/` 的 5 文件补丁（commit `5c1b5e5574`），建一个 **source-only 分支**供 Build-Desktop-Release 构建。这样包内**只含 src/ 补丁**，不含 546d HEAD 的 docs/test 审计提交（c835800f29/d21ef1502a/50ff825597/546d1f7cca）。

```bash
cd /d/orca/workspaces/logseq/issues-human-test-20261003
# 1. 从已发布基线建 source-only 分支
git checkout -b source-only-ci-5c1b5e55 fb5eb4eb43cdb3be7a29d816b969d45c127d4861
# 2. 仅 cherry-pick src/ 补丁（5c1b5e5574 只动 5 个 src/ 文件，.agents/ 文档不在此 commit 内）
git cherry-pick 5c1b5e55740ff0e0979e153e24cb84a5f174f175
# 3. 推送 source-only 分支（不是 546d HEAD）
git push origin HEAD:refs/heads/source-only-ci-5c1b5e55
# 4. 验证（只读）：
git ls-remote origin source-only-ci-5c1b5e55   # 应显示 cherry-pick 后的新 SHA（稍后实际记录，不预造）
```

- **build source SHA**：cherry-pick 后该 source-only 分支的 HEAD SHA 即为包构建的 source SHA。**稍后实际建立后记录**（`git rev-parse HEAD`），不预造。
- 若 cherry-pick 冲突（基线与补丁上下文不一致），**停止并上报父代理**，不强行解决。

## 3. 触发新包构建（workflow_dispatch，需父代理授权）

命令（本机 gh 已登录 e-zz，实测 auth status OK）：

```bash
gh workflow run build-desktop-release.yml \
  --repo e-zz/logseq \
  -f build-target=non-release \
  -f git-ref=source-only-ci-5c1b5e55 \
  -f is-draft=true \
  -f is-pre-release=true \
  -f enable-file-sync-production=true \
  -f enable-plugins=true \
  -f build-android=false \
  -f publish-linux-stores=false \
  -f windows-only=true
```

参数依据（实测 workflow 文件）：
- `build-target=non-release`：与已交付包 37203268496 一致（fork 手动测试通道，不发 GitHub Release）。
- `git-ref` 指向 **source-only 分支** `source-only-ci-5c1b5e55`（= fb5eb4eb43 + 5c1b5e5574 补丁，**不是** 546d HEAD）。
- `windows-only=true`：fork 专用输入（workflow 行 52-54 "for fork manual-test artifacts"），跳过 Linux/macOS/Android，只产 build-windows-x64 / build-windows-arm64 两个作业，缩短等待。
- `build-android=false`：双保险，android 作业同样受 windows-only 门控。
- 其余布尔输入按 workflow default 显式给出，避免歧义。

注意：`is-draft`/`is-pre-release` 对 non-release 目标无实际发布效果（仅 beta/nightly 走 Release），显式置 true 与 37203268496 的保守姿态一致。

## 4. 等待与取产物（只读轮询 + 下载）

```bash
# 轮询（每 2-3 分钟一次，构建含 shadow-cljs 编译 + pnpm cljs:test，按 37203268496 的历史时长估算）
gh run list --repo e-zz/logseq --workflow build-desktop-release.yml --limit 3 \
  --json databaseId,headSha,headBranch,event,status,conclusion,createdAt

# **关键核验（父代理纠偏）**：run.headSha 是 workflow_dispatch 事件触发时的 HEAD，
# **与真正 checkout 的 source SHA 不是必然同一字段**（actions/checkout ref 取 inputs.git-ref）。
# 不能只看 run.headSha 就断言包源码版本。必须：
#   (a) 确认新 run 的 headBranch == source-only-ci-5c1b5e55（git-ref 指向 source-only 分支）；
#   (b) 拉取实际 checkout job 的 log/metadata（gh run view <id> --log 或 job summary），
#       找 "Checked out" / "Fetch" / SHA 行，证明真正 checkout 的 source SHA == source-only 分支 HEAD；
#   (c) 若 (b) 不可得，降级为"未验 source SHA"，不得断言包含补丁。
# 成功后下载：
NEW_RUN_ID=<新 run 的 databaseId>
gh api repos/e-zz/logseq/actions/runs/$NEW_RUN_ID/artifacts --paginate \
  | jq -r '.artifacts[] | select(.name | test("windows")) | .name + " " + (.archive_download_url | split("?")[0])'
gh run download $NEW_RUN_ID --repo e-zz/logseq --name <windows-x64 产物名> -D <下载目录>
```

产物命名以 37203268496 的 artifacts 列表为准（下载前先用 gh api 列出确认名称）。

## 5. 包内验证步骤（拿到新包后）

前置：合成/可丢弃隔离 fixture（不碰 live graph）；不启动当前 GUI 实例之外的 app 会话需父代理另行授权（本任务边界禁止下载/启动/安装 app——第 5 步属于"启动 app"，必须父代理显式授权后才可执行）。

1. **SHA 核验（不启动 app 即可做）**：
   - 解包 zip 后核对包内版本标记/构建元数据（如 static/js 构建清单、package-info）；确认构建 source SHA = **source-only 分支 HEAD**（= fb5eb4eb43 + 5c1b5e5574，**不是** 546d HEAD）。
   - 对比旧包 37203268496（@fb5eb4eb）：两包 static/js 的搜索相关 bundle 应有 diff（`frontend.handler.search` / `mcp-search` 命名空间代码），可作"新包确实含补丁"的静态证据（推断级，非行为级）。
2. **行为验证（需启动 app + 隔离 fixture，待授权）**：
   - **Cmd-K direct-path**：新 fixture 图内执行 block search，确认结果正常发布（无条件 publish 为 anchor 已有行为，验证无回归）。**父代理纠偏**：Cmd-K 直接调用 `block-search`，其结果来源**与** renderer 聚合 handler 的 `:search/result` 发布路径**不同**，二者必须**分开验证**——不能把 renderer 聚合 handler 的发布当作 Cmd-K 的结果来源。
   - **MCP 路径（opt-out 状态验证）**：MCP search 调用走 renderer 聚合 handler，`publish-result?` 被移除、不写 GUI state。**父代理纠偏**：包内 MCP opt-out 验证必须**观察实际状态发布未发生**（即 GUI state 中 `:search/result` 未被 MCP 调用写入/变更），**不可只凭"进程能起 / MCP server 能响应 search 调用"判 PASS**。具体：(a) MCP search 调用前后分别读 GUI state 的 `:search/result`，确认无变更；(b) 若无法读 GUI state（无 API/无日志），降级为"未验 opt-out 状态"，不得判 PASS。`publish-result? false` 的 wire 行为由 source tests 覆盖（119/431 已过），包内只需补充状态级验证。
   - 回收过滤：synthetic fixture 内回收一个 block/page，搜索不出现（共享过滤逻辑未改，防间接回归）。
3. **明确边界**：source tests 通过 ≠ 包内行为通过；build 成功 ≠ GUI 通过。结论需标注 实测/未验。

## 6. 阻碍与风险

- **Push 被边界禁止**：source-only CI 分支方案仍需 push 一个分支（source-only-ci-5c1b5e55 = fb5eb4eb43 + 5c1b5e5574），需父代理授权（新破坏性授权）。**不 push 整个 546d HEAD**。
- **build source SHA 未建立**：source-only 分支的 HEAD SHA 需在 cherry-pick 后实际记录（`git rev-parse HEAD`），不预造；包构建 source SHA 核验依赖实际 checkout log/metadata，不能只看 `run.headSha`。
- **构建时长未知**：37203268496 历史时长未在本任务实测（未查 run timing），轮询上限建议 ≤ 30 分钟，超时则保存状态返还。
- **CI lint 作业对新分支不可用**：无 push 到 master/PR-to-master 的路径时，lint 门禁只能靠本地（已完成）+ 包构建内 compile 步骤（shadow-cljs 编译失败会挡包）。
- **upstream 对照未做**：upstream/master = 22a29b30（anchor），其与 HEAD 的搜索代码差异对照属 A 子代理范围，不在本 brief。
- **windows-only 输入是 fork 私有**：若该输入在 workflow 中被移除/改名，`gh workflow run -f` 会失败；执行前先 `gh workflow view build-desktop-release.yml --repo e-zz/logseq` 或读远程 workflow 确认输入名（实测本地文件含该输入，远程分支若与本地一致即可）。
