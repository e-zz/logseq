# 新包验证准备（用户已授权）

2026-10-05 用户明确 ok so go ahead：源码-only分支发布、non-release Windows构建、独立配置与合成图验证；绝不操作当前应用/现有图，不关应用/强杀/安装替换/写原配置。

父代理已执行推送并读回：source-only-ci-5c1b5e55 @ 7b1ae92ee1eb1679b8bb827d12d7e76e22d4d5c5；基线 fb5eb4eb43cdb3be7a29d816b969d45c127d4861。仅五个src文件，1个提交；与source5c1b5e5574五文件逐字一致，窄凭据形态扫描0命中。5c1b5e5574另有两份报告，未被带入此次提交。新增独立发布工作区 D:/orca/workspaces/logseq/search-package-ci-5c1b5e55，不在其写入产物。

CI https://github.com/e-zz/logseq/actions/runs/37259407681 已读回in_progress，headSha匹配；workflow --ref source-only-ci-5c1b5e55，git-ref=精确SHA，windows-only=true，non-release，android=false，publish-linux-stores=false。不dispatch第二个run，不push、不重编译。父代理启动gh run watch后台通知。

分工：只准备可运行的包内验证脚本与隔离配置路径设计，不启动任何app，不触碰图，不等待CI、不下载产物。读 .agents/audits/search-next-lint-package/package-validation-brief.md 和相关生产入口。唯一输出 .agents/audits/search-package-37259407681/validation-prep/。10分钟内交付实际脚本及report；不能用硬编码JS转写CLJS冒称生产验证。读项目skills/AGENTS入口。

必须解决：Electron second instance锁和userData隔离方式、CLI --config独立图创建、production Electron MCP到renderer真实搜索、CDP读取/监听GUI :search/result（前后状态相等不足以证明没有重复写，优先watch），renderer常规搜索正控制，Cmd-K direct-path另测，临时块active→recycle→restore查询。只用合成数据。不从MCP工具绕过每请求一次upsert规则；准备时不写MCP。无法找到安全隔离或真实状态观测明确BLOCKED。

回报精确路径/脚本入口/能验证与不能验证项，父代理独立复核后运行。不得commit/push/网络等待/委派。任何风险边界先停。所有产物本地保存，不公开UUID/路径。
