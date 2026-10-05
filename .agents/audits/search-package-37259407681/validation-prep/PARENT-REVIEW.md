# 父代理验收：验证脚本拒收，禁止直接运行

2026-10-05。读回 verify_package_gui.mjs / prepare_fixture_graph.mjs / REPORT.md / verify_package_static.mjs 后发现：

1. 实测文件内容：prepare_fixture_graph.mjs 将 Markdown (#标题与-列表) 写成 .edn。不是有效EDN，也不是DB图；不得预造registry冒称真实建图。应使用新包真实CLI graph create/init/import入口，并明确独立root。
2. 排除轮询强证明：500ms指纹轮询会漏掉窗口内瞬时更改与相同值重复写。delta=0最多说明采样时未观察到变化，不能证明无写入。原报告严格证明声称错误。
3. 源码核验：Cmd-K direct-path和renderer聚合handler发布是不同入口。S1只填Cmd-K后读 :search/result不能检验handler默认publish。需要真实window.logseq.api.search正控制，并单独DOM验Cmd-K。
4. ensureBlock把get_page_blocks_tree数组当page实体取db/id，API参数未经包内确认。不得运行未知目标写入。
5. S4工具错误响应未严格验证，watch期间主动GUI查询却要求delta=0，观测条件自相矛盾。
6. finish无runDir参数，常规调用缺少该参数；存在UNVERIFIED/FAIL却最终exit0路径。验收输出与exit语义不可靠。
7. 清理含SIGKILL及browser.close，无必要不得强杀或关闭当前应用。先保证仅隔离实例且采用正常quit；未授权强杀，移除逻辑才可运行。
8. userData/home隔离不能凭环境变量+手工registry推断充分：configs读取app.getPath(home/userData)，plugins读os.homedir。启动前必须真实验证所有路径指向合成隔离区及端口无冲突；不能仅HTTP能响应就判app-start PASS。
9. static脚本匹配asar前8MB首个version字段可能取依赖version，ok忽略部分FAIL，指纹扫描无法代替准确文件抽取。需读取asar目录中精确package.json及js文件。
10. 子代理实际耗时23分钟、184次调用，超10分钟预算；脚本syntax-check不等于运行通过。

结论：NOT ACCEPTED。CI实际成功且checkout source SHA已父核验7b1ae92ee1eb1679b8bb827d12d7e76e22d4d5c5，GUI/MCP包内尚未验。下载proc_5b9fbd0ab63c仍运行，未运行上述GUI脚本，现有应用/图不动。

执行策略：父代理建立窄的安全启动预检及真实DB合成fixture，先核包/路径/目标，再执行聚合search正控制、MCP search异查询与状态采样（如无写事件观测，诚实标限度）、Cmd-K DOM及回收恢复。任何安全边界无法证明则BLOCKED。无重复dispatch CI。
