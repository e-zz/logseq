# 非视觉 UIA 核验与编号切换尝试

## UIA 状态核验：实测
原生 .NET UIAutomation 扫描232个节点，实际时间/完整属性响应见 uia-native-properties.json。两个 CheckBox 暴露 TogglePattern.ToggleState=Off；cua-driver ui-27-uia-structured.json 同时给 role=CheckBox/selected=false，分别位于页面属性和展开metadata属性。Task trigger 为 Group，仅Invoke/ScrollItem，不暴露Toggle/Selection；Todo/Doing/Done为Hyperlink，仅Invoke/Value/ScrollItem，不暴露结构化选中状态。仅声明当前UIA接口缺口，不外推DOM不存在ARIA属性。只读打开任务菜单，随后在编号测试前点击页面标题关闭，未切换状态。

## 编号切换：BLOCKED，未修改
目标UUID=76a13258-d931-41c9-a21d-14fd6dbacaed，标题编号项二，页面63bab91a-013d-44df-afb7-4c83026b03ef。测试范围numbered→bullet→numbered。
实际过程：ui-30前置树；ui-31关闭状态菜单；ui-32定位目标control；ui-33后台右键返回background_unavailable，cause=InjectSyntheticPointerInput(pen): Access is denied. (0x80070005)；ui-34后树没有打开上下文菜单；按驱动建议ui-35前台右键，返回foreground_unavailable: Windows did not activate exact target HWND 0x31260 (actual foreground HWND 0x0); no mouse input was sent；ui-36后树确认仍是原页面，未取得上下文菜单。保存所有实际输入/响应/时间于各ui-30至ui-36 JSON。没有根据退出码0宣布动作成功。
前后native MCP getBlock返回完整对象相同，完整转录如下：
{"uuid":"76a13258-d931-41c9-a21d-14fd6dbacaed","updated-at":1791079006228,"created-at":1791078193103,"title":"编号项二","order-list-type":{"title":"number","uuid":"6ac1b25e-fcdc-4ee0-82d6-8044b1100c8c"},"order":"b1d","parent":"63bab91a-013d-44df-afb7-4c83026b03ef","page":"63bab91a-013d-44df-afb7-4c83026b03ef"}
本地shortcut/config.cljs搜索numbered/bullet/order-list/list-type无匹配，不猜快捷键、不用API写绕过UI验收。

此阻塞仅限右键上下文菜单路径，不能宣称全部computer-use不可用；此前后台左键和UIA set_value已实测可用。编号切换/移动/缩进仍未验。用户可手动打开编号项二右键菜单，之后尝试既有后台左键选择菜单项。未改源码、环境、未重启、未执行upsertNodes；未关闭issue，待主agent独立复核。
