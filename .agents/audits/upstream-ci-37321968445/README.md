# 最新 upstream Windows 对照 CI

实测：调用 GitHub API 核对 `logseq/logseq` 的 `master` 最新提交，仍是 `22a29b30dee3b3930cf49bba50454650c31d2a07`，与此前官方 U 包的源码基线相同。`feat/db` 不是本次 master 基线的分支，不用它替代“最新 upstream”。

用户授权触发 CI。新建 fork 分支 `ci/upstream-22a29b30`，直接指向精确 upstream SHA，无新提交、无产品代码差异、无 fork 功能修补。push 后通过 ls-remote 读回完全一致。现有测试分支、source-only 分支不修改。

为避免给纯 upstream 源码添加 fork 适配，直接复用已通过 CI37259407681 的构建工作流：执行 ref 为 `source-only-ci-5c1b5e55` / `7b1ae92ee1eb1679b8bb827d12d7e76e22d4d5c5`，但显式 `git-ref` 输入为精确 upstream SHA22a29b30。两者必须分别记账：GitHub run.headSha 是工作流执行 ref，不能冒称它就是待编译源码 SHA。

源码与构建工作流分离：编译 job 的 checkout 使用 `github.event.inputs.git-ref`；打包 job 下载它产出的 static。工作流提供 unsigned Windows packaging、fork Sentry guard、Windows-only 条件等已有构建适配。本次不替换 CLI、不修改搜索/索引/页面代码，不发布 Release。

## 实际触发命令

```
gh workflow run build-desktop-release.yml --repo e-zz/logseq --ref source-only-ci-5c1b5e55 -f build-target=non-release -f git-ref=22a29b30dee3b3930cf49bba50454650c31d2a07 -f is-draft=true -f is-pre-release=true -f enable-file-sync-production=true -f enable-plugins=true -f build-android=false -f publish-linux-stores=false -f windows-only=true
```

实测：gh 返回 run37321968445，并直接读取该 run 验证存在、workflow_dispatch、执行 ref7b1ae92匹配。compile-cljs job111803045090 已开始，checkout step success，查询时正在 Set up OCaml。artifact 查询为0；尚未声称编译/打包成功。最初 run list 尚未出现新 run，不据此重复触发。

- Run：https://github.com/e-zz/logseq/actions/runs/37321968445
- 纯源码分支：https://github.com/e-zz/logseq/tree/ci/upstream-22a29b30
- 目标 x64 artifact（构建完成后才可确认存在）：`logseq-win-x64-builds`

## 当前未验

原始 compile checkout 日志中的 SHA 尚待 job 日志可用后核验。构建完成及 ZIP/app.asar hash 尚未验证；没有下载/启动这次的新包，没有对用户图进行操作。此记录只证明已正确触发并读回 CI，不证明包内行为通过。

构建输入采用 upstream 工作流的插件/production sync 默认 true，并明确禁用 Android/store publishing、选择 non-release/windows-only。对比应使用同一可丢弃图和配置；原图标题缺失仍是另一个未决场景，不因新 CI 触发而关闭。
