# Alt+C 剪贴板截图尝试：BLOCKED

用户明确授权激活 Logseq 后用 Alt+C 触发截图到 clipboard。

实测目标只读验证：hwnd=201312，pid=25932，title=Logseq，exe=C:/Users/zhang/Downloads/logseq-win-x64-builds/Logseq-win-x64-2.0.2/Logseq.exe。排除此前误选 File Explorer 的目标。

实测 2026-10-04T12:23:46.579446+08:00：激活 SetForegroundWindow 返回 (5, 'SetForegroundWindow', 'Access is denied.')；未发送 Alt+C。clipboard 序号前后均 2254，未读取旧剪贴板图片、未生成截图。仅尝试激活前发了 Alt 按下/释放，未编辑图。

后续只读实测：GetForegroundWindow()=0；OpenInputDesktop 返回非零句柄、last_error=0。推断：当前自动化进程无法获得可交互前台；这些数据不足以确定是 RDP 断开、锁屏还是桌面权限限制，不作根因定论。不调整桌面/权限/配置，不重启。

真实脚本与结果：C:/Users/zhang/AppData/Local/hermes/cache/scratch/logseq-alt-c/capture_clipboard.py、alt-c-result.json。UI 验收仍未验；需要用户在实际桌面激活 Logseq 并按 Alt+C，再告知。父代理可随后只读提取剪贴板图片并核验，不用重复应用写入。
