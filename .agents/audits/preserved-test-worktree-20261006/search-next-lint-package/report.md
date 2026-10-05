# clj-kondo lint + 新包验证 brief（B 子代理报告，父代理纠偏修订版）

- worktree: D:/orca/workspaces/logseq/issues-human-test-20261003
- HEAD（实测）: 546d1f7cca51ee4cdfeb68f5c3bedbedf820a03a
- 源补丁: 5c1b5e55740ff0e0979e153e24cb84a5f174f175（fix: isolate MCP search result publication from GUI state）
- upstream anchor（实测 git merge-base HEAD upstream/master）: 22a29b30dee3b3930cf49bba50454650c31d2a07
- 分支: test/issues-mcp-20261003
- 远程 origin: https://github.com/e-zz/logseq.git；origin/test/issues-mcp-20261003（实测 git ls-remote）: fb5eb4eb43cdb3be7a29d816b969d45c127d4861

## 一、clj-kondo lint（实测通过）

### 工具获取与版本验证

- repo deps.edn 行 82 固定 clj-kondo 2026.04.15（clojars 发布名，非 Maven Central；repo1.maven.org 404，repo.clojars.org 200）。
- ~/.m2/repository/clj-kondo/clj-kondo/2026.04.15/clj-kondo-2026.04.15.jar 是 thin jar：实测 java -jar 报 "Could not find or load main class clojure.main"，java -cp 同 jar 报 "clj-kondo.main not found"；jar 内只有 clj_kondo/main.clj 等源码布局文件，不能作为可执行 standalone 用。未拿它 lint。
- 官方 standalone：经 gh api 确认 release tag v2026.04.15（published 2026-04-15T08:26:25Z），资产 clj-kondo-2026.04.15-windows-amd64.zip（+ .sha256）。
- 已下载到本输出目录 D:/orca/workspaces/logseq/issues-human-test-20261003/.agents/audits/search-next-lint-package/；sha256 实测 0cae02590f03ce7c4dc14cde7b320c52ea26c1f7198c2164fd09e10a1ee36f08，与官方 .sha256 文件一致。
- 解压后实测 clj-kondo.exe --version → clj-kondo v2026.04.15，exit 0。版本与 repo 固定版本一致（实测）。

### 执行命令（repo 配置）

```
cd D:/orca/workspaces/logseq/issues-human-test-20261003
.agents/audits/search-next-lint-package/clj-kondo.exe --parallel --lint \
  src/main/frontend/handler/search.cljs \
  src/electron/electron/mcp_search.cljs \
  src/test/frontend/handler/search_test.cljs \
  src/test/electron/mcp_server_test.cljs \
  src/test/frontend/worker/search_test.cljs
```

- 配置来源（实测）：repo .clj-kondo/config.edn 自动发现（ns-groups、defkeywords/discouraged-namespace/aliased-namespace-symbol 等 linters）+ .clj-kondo/hooks/{defkeywords,defevent,def_thread_api,hsx,path_invalid_construct,regex_checks}.clj；clj-kondo 官方 Windows zip 不含 .clj-kondo，故 repo 配置必须从 repo 根发现，命令 cwd 在 repo 根。
- 五个文件即 5c1b5e5574 源码改动集（实测 git show --stat：2 生产 + 3 测试，另含 .agents/ 文档不在 lint 范围）。

### 原始输出与 exit code

> **父代理核验修正**：本文件引用的 `lint.raw.txt`（"完整命令 + 版本 + 原始输出 + exit code"）**不存在**——目录实际仅有 `lint.log` 等过程文件，子代理自报的 `lint.raw.txt` 未落盘。真实可复核的 lint 原始日志为 **`lint.parent.raw.txt`**（父代理 fresh 下载官方 standalone、校验 checksum 后独立运行产生）。据此，五文件 lint PASS **升为父代理实测 PASS**（非子代理自报 PASS）。

```
（完整命令 + 官方 checksum + 版本 + 原始输出 + exit code，见 lint.parent.raw.txt）
```

- **父代理实测日志（`lint.parent.raw.txt`，可复核）**：
  - 官方 SHA256 fresh 获取自 clj-kondo/clj-kondo v2026.04.15 release：`0cae02590f03ce7c4dc14cde7b320c52ea26c1f7198e10a1ee36f08`；zip 与解压后 exe 均校验一致（zip 内容 = exe）。
  - 版本：`clj-kondo v2026.04.15`（与 repo deps.edn 行 82 固定版本一致）。
  - 命令：`clj-kondo.exe --cache false --parallel --lint` + 五个改动文件（repo 根 cwd，自动发现 `.clj-kondo/config.edn` + hooks）。
  - EXIT: 0；errors: 0, warnings: 0；1 info（`src/test/electron/mcp_server_test.cljs:56:27: info: Redundant boolean coercion`）。
- **版本验证（父代理实测）**：官方 checksum 一致 + `--version` 输出 v2026.04.15 + 与 repo 固定版本一致——三重验证，**PASS**。
- 逐文件通过标记（5 个点）= 5 个文件都被 lint 到（非空 git-changes 路径；未走 bb lint:kondo-git-changes，因当前工作树无 unstaged 改动，该任务会打印 "No clj* files have changed to lint." 并误判通过）。
- 1 条 info（非 error/warning，不影响 CI 判绿）：src/test/electron/mcp_server_test.cljs:56:27 redundant boolean coercion。
- 归属判定（源码核验）：该行 (boolean (clojure.string/includes? ...)) 在 mcp_server_test.cljs 行 54-56，属于 patch 5c1b5e5574 未触及的既有 deftest upsert-nodes-description-matches-supported-property-contract（实测 git show 5c1b5e5574 -- 该文件 diff 仅行 16/29 附近 +:publish-result? false 两行；行 54+ 未变）。CI（build.yml lint 作业）对该文件跑同样 linter 时本就会产出此 info；errors: 0, warnings: 0 与 CI 判绿一致。非本补丁引入（实测比对 diff）。

### 覆盖声明

- 覆盖：5c1b5e5574 改动的 5 个 cljs 源文件，repo 配置（config.edn + hooks），standalone clj-kondo v2026.04.15（= repo 固定版本）。**父代理独立复跑实测 PASS（exit 0 / errors 0 / warnings 0 / 1 info），版本三重验证通过**（官方 checksum 一致 + `--version` 一致 + repo 固定版本一致）。
- **覆盖边界（父代理强调）**：此 PASS 仅指**本补丁触碰的 5 个文件**经 standalone clj-kondo v2026.04.15 lint 通过；**不升为"全仓全部 lint 通过"**。全仓 clj-kondo 与 bb 其余 linter 未跑（见下），CI 侧全仓对照需另行验证。
- 未覆盖（明确边界）：bb dev:lint-and-test 的其余 linter（lint:carve / large-vars / ns-docstrings / lang:validate-translations / lang:lint-hardcoded / worker-and-frontend-separate）未跑；全仓 clj-kondo（clojure -M:clj-kondo --parallel --lint src）未跑，只 lint 了本补丁触碰的 5 个文件。父代理若要求全仓对照可在 CI 侧验证。

## 二、CI / 包内验证入口只读核对（全部实测，未 push/未触发）

### 工作流

- .github/workflows/build.yml（name: CI）：on push branches [master]（paths-ignore *.md）+ on pull_request branches [master]。含 lint 作业，clj-kondo 命令 clojure -M:clj-kondo --parallel --lint src（实测上游/本 fork 该文件一致，upstream/master 行 183-184 同）。
  - 含义：CI 不会在 test/issues-mcp-20261003 分支的 push 上自动跑；新分支上需要走 pull_request 事件。
- .github/workflows/build-desktop-release.yml（name: Build-Desktop-Release）：仅 on workflow_dispatch，输入 build-target（beta/nightly/non-release，default non-release）、git-ref（default master）、windows-only（default false，fork 手动测试用）、build-android（default true）等。windows-only=true 时跳过 Linux/macOS/Android 作业（实测 if 条件），只产 Windows 产物。

### 远端 SHA / 分支（实测 git ls-remote origin）

- origin/test/issues-mcp-20261003 = fb5eb4eb43cdb3be7a29d816b969d45c127d4861
- 本地 HEAD = 546d1f7cca...；fb5eb4eb 是 HEAD 的祖先（实测 git merge-base --is-ancestor），本地领先 5 个提交：
  - c835800f29 docs: plan MCP search isolation and upstream compatibility
  - d21ef1502a test: record remaining metadata and CLI acceptance evidence
  - 50ff825597 docs: narrow issue acceptance to remaining fixes and evidence
  - 5c1b5e5574 fix: isolate MCP search result publication from GUI state   ← 本补丁
  - 546d1f7cca docs: finalize search isolation acceptance
- 即：已交付包 CI 37203268496（fb5eb4eb）不含本补丁（实测祖先关系，与 brief 陈述一致）。
- 远程另有 master = 025d1fd7301a733d7e51a6bd3f2fd30d4a1c88a2（fork 默认分支 dev；未取 dev SHA，与本验证无关）。

### 最近 runs（实测 gh run list --repo e-zz/logseq，只读）

- 37203268496 Build-Desktop-Release，2026-10-04T12:46:30Z，test/issues-mcp-20261003 @ fb5eb4eb43cdb3be7a29d816b969d45c127d4861，success。
- 37134185100 Build-Desktop-Release，2026-10-03T15:42:21Z，test/issues-mcp-20261003 @ eb69c1bd179fa4582ff1f59506d43338dfab9190，success。
- build.yml CI 最近 run 停留在 2026-08-04（@ fork master 7fbc53a5）——该 fork 上 CI 工作流长期未触发过，属预期（事件只挂 master push/PR）。

### 产物入口

- 下载方式（只读 API，实测可用）：
  - gh api repos/e-zz/logseq/actions/runs/37203268496/artifacts --paginate
  - 单个：gh run download 37203268496 --repo e-zz/logseq -D <dir>（需已登录，本机 gh 已登录 e-zz，实测 auth status）
- 37203268496 的 windows 作业名（实测 gh run view jobs）：build-windows-arm64、build-windows-x64。

## 三、产物清单（绝对路径）

- D:/orca/workspaces/logseq/issues-human-test-20261003/.agents/audits/search-next-lint-package/report.md（本文件）
- D:/orca/workspaces/logseq/issues-human-test-20261003/.agents/audits/search-next-lint-package/lint.parent.raw.txt（**父代理独立复跑**的完整命令 + 官方 checksum + 版本 + 原始输出 + exit code；真实可复核日志）
- ~~lint.raw.txt~~（**不存在**——子代理自报但从未落盘；目录实际仅有 lint.log 等过程文件。父代理核验：以 lint.parent.raw.txt 为准）
- D:/orca/workspaces/logseq/issues-human-test-20261003/.agents/audits/search-next-lint-package/package-validation-brief.md（新包验证执行 brief）
- D:/orca/workspaces/logseq/issues-human-test-20261003/.agents/audits/search-next-lint-package/clj-kondo.exe（官方 standalone v2026.04.15，sha256 已验）
- D:/orca/workspaces/logseq/issues-human-test-20261003/.agents/audits/search-next-lint-package/clj-kondo-2026.04.15-windows-amd64.zip（+ .sha256）
- 过程证据：kondo-probe.log、v2026.04.15-assets.txt、config-head.txt、lint.log（thin-jar 探针记录）
