# UI 编号切换：第一阶段成功，待恢复

用户手动打开编号项二的右键菜单。原始UIA快照ui-37-user-opened-menu.json含MenuItem Toggle number list，token s00000017:166。ui-38后台driver click返回effect=unverifiable，ui-39菜单仍显示，三个MCP块均为原number，更新时间1791079006228，判定该点击未生效。

改用Windows原生UIA InvokePattern（不是API写）。按Name=Toggle number list在精确HWND201312下匹配唯一菜单项，要求匹配数1；实际支持Invoke/ScrollItem，invoke实际时间与响应见ui-40-native-number-invoke.json。ui-41结构化快照中菜单消失，MCP getBlock实际响应：
{"created-at":1791078193103,"order":"b1d","title":"编号项二","updated-at":1791109053928,"uuid":"76a13258-d931-41c9-a21d-14fd6dbacaed","parent":"63bab91a-013d-44df-afb7-4c83026b03ef","page":"63bab91a-013d-44df-afb7-4c83026b03ef"}
目标块order-list-type已移除；标题/UUID/parent/page/order保留。结构化数据证明number marker移除，未以视觉判断bullet渲染。原number值为{"title":"number","uuid":"6ac1b25e-fcdc-4ee0-82d6-8044b1100c8c"}。

当前编号项二处于无number marker状态，未恢复，整项未通过。右键仍须用户协助重新打开，然后可再次原生Invoke恢复number，读回后才能标本批完成。没有upsertNodes/源码修改/环境变更，不删除、不改其他块。父agent需独立复核。本报告必须在恢复完成后更新，不把未恢复状态隐去。
