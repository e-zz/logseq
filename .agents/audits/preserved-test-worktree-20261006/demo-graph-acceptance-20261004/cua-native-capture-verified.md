# 原生 cua-driver 窗口截图：实测成功

先前 Hermes computer_use 包装层不可导入依赖不等于 cua-driver 本体不可用。

实测：已安装 cua-driver 0.23.2；现有 daemon pid=13732，pipe=\\.\pipe\cua-driver，未启动/重启/修改 daemon 或配置。doctor --json 返回 ok=true；UI Automation 和 EnumWindows 通过，交互会话警告 foreground_hwnd=0。

实际命令由 Python subprocess 调用现有 exe：call get_window_state，参数 {"pid":25932,"window_id":201312,"max_elements":40,"max_depth":6,"screenshot_out_file":"C:/Users/zhang/AppData/Local/hermes/cache/scratch/logseq-cua-native.png"}。目标此前验证为 Logseq.exe / title Logseq，不是 Explorer。

实际结果：exit=0；screenshot_width=1456，height=861；window_id=201312；tree_markdown 为 Window Logseq / Pane Logseq；element_count=0，degraded=true / ax_tree_empty。所以截图路径可用，元素索引路径尚不能据此认定可用。未执行任何点击或按键。

父侧实测：图片尺寸 1456×861，RGB extrema 各 (0,255)，alpha (255,255)，颜色数1122，不是黑图。vision_analyze 确认有效 Logseq 窗口：侧栏 Demo，Journals 选中；主区 Oct 4th, 2026、Sep 24th, 2026、test。

范围：这是当前 Demo 图的 Journals 视图，不是 TEST-DEMO-20261004-A 测试页，未看到任务/属性/编号，不能关对应 UI 用例。下一步可经原生 cua-driver 已发现接口导航并截图，动作前先捕获、按新快照定位、动作后验证；本报告只证明窗口截图成功。

图片：C:/Users/zhang/AppData/Local/hermes/cache/scratch/logseq-cua-native.png。未改源码/环境、未安装依赖。
