# Task UI → MCP 状态一致性：PASS_PENDING_PARENT_REVIEW

## 范围与目标
仅通过原生 cua-driver 后台点击测试 Task UUID a4454633-532a-443f-8d3a-d06426f0a1d0，页面 63bab91a-013d-44df-afb7-4c83026b03ef；不执行 upsertNodes，不改源码/环境、不关闭应用。

## 实测与证据
- 前置 MCP getBlock=status.done，updated-at=1791080520810；ui-17-task-before.png 显示绿色完成勾选。
- 点击 x407/y538 打开 Set Status 菜单（并不直接切换）。ui-19-task-menu.png 当前 Done 被选中，额外只读 getBlock 仍是 Done。
- 裁剪核对 Todo 行后点击 x441/y633。ui-21-task-todo.png 显示任务空心圆圈；MCP getBlock=status.todo，updated-at=1791091748353。
- 再次打开状态菜单，ui-23-task-restore-menu.png 显示 Todo 选中。
- 全图及裁剪视觉工具对 Done 坐标估算不可靠；没有据此盲点。改为 max_depth=30/max_elements=250 的 UIA 深读，发现 Done Hyperlink token s0000000f:147，精确按token点击恢复。
- ui-26-task-done-restored.png 显示绿色勾选，Set Status 菜单不再显示；MCP getBlock=status.done，updated-at=1791091897093。
- 原始驱动输入/响应/真实调用时间存 ui-17-task-before.json 至 ui-26-task-done-restored.json（每个实际动作独立文件，含24深读）。三阶段实际 MCP getBlock 完整JSON读回转录于 worker-ui-task-readbacks.json。程序断言 Done→Todo→Done，status与updated-at以外所有返回字段相同，更新时间严格增加，实际退出码0。

## 判读纪律与限制
实测只证明同一测试任务 UI 状态操作与 MCP 读回一致，不证明任务视图筛选，也不覆盖 Doing/In Review/Canceled。图像工具对 ui-21 的比较文字错误，把属性展开区域误当状态菜单；采用其图中空心圆圈描述并结合 MCP，错误比较不作为证据。恢复的是 Done 状态，不是完整历史回滚：updated-at 变化，最终截图任务旁新增可见2m标签，来源未核实，不宣称整图完全无变化。不清理历史，不重建/重启。

任务视图筛选、编号编辑、撤销重做、正常重开仍未验。主 agent 需独立读取原始响应/截图和目标 UUID，不由本worker正式关单。
