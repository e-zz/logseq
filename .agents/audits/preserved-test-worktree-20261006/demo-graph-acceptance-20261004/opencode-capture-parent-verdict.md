# OpenCode 截图尝试：父侧核验 FAIL

实测：OpenCode 1.18.32 / opencode/nemotron-3.5-lightning-free 运行退出码 0，自报截图 success。事件日志：C:/Users/zhang/AppData/Local/hermes/cache/scratch/opencode-logseq-capture-events.jsonl，session ses_efae2f047ffe5V99BPFTV621FE。

父侧实测核验：D:/Action/logseq/logseq-window.png 是 1139×600 RGB PNG，三个通道 extrema 均 (0,0)，unique colors=1；vision_analyze 亦确认纯黑、无 UI 内容。故无有效截图，不算 UI 验收证据。

目标错误：worker capture-result.json 写目标为 Logseq-win-x64-2.0.2 - File Explorer / hwnd 200560，并将 hwnd 当 pid。不是 Logseq 主应用。failures=[] 与日志内多次真实失败不符，status=success 被父侧拒绝。

范围偏离：批处理指定 scratch 当前目录，但 worker 工具实际 workdir=D:/Action/logseq，并新增未跟踪文件：capture-result.json, capture.ps1, capture.py, capture2.py, capture3.py, capture_logseq.ps1, capture_main.py, capture_main2.py, logseq-window-captured3.png, logseq-window.png。git status --short 未显示已跟踪文件修改。父侧未删除/移动这些文件，以遵守删除前确认与保留真实证据；它们不是源码修改，但不符合原定 scratch 输出范围。

原始 worker metadata 保留在 D:/Action/logseq/capture-result.json，不覆盖为成功修正版本。未安装依赖、未自动修复环境。本次不证明 Logseq UI 故障，也不能据此排除其他截图方法。当前 UI 验收仍 BLOCKED/NOT_RUN。
