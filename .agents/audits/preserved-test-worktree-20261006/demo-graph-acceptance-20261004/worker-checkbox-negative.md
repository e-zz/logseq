# Checkbox 错误类型：worker 实测，待主 agent 独立复核

## 前置与唯一写调用

实测：本次继续请求首先只读 getPage 全树、getBlock(metadata)、listProperties(expand=true)。页面 TEST-DEMO-20261004-A / 63bab91a-013d-44df-afb7-4c83026b03ef 与目标 UUID 匹配；Task Done，嵌套存在，metadata checkbox=false / number=0 / Topics 三引用。Checkbox 定义 00000002-1028-8501-4900-000000000000 为 checkbox/one。

实测：一次 upsertNodes(receipt=true)，先合法改 metadata 标题为 NEGATIVE-CHECKBOX-SHOULD-NOT-COMMIT，再同块 Checkbox 属性赋 JSON 字符串 "false"，不是布尔 false。没有第二次 upsert、dry-run 或旁路写。没有重做 URL 测试。

实际响应：API Error: Checkbox property :00000002-1028-8501-4900-000000000000 value must be a boolean

实测：响应后 getBlock 完整结果与本请求写前完整结果一致，原标题、URL、Checkbox 布尔 false、Number 0、Topics、其他属性及更新时间 1791080520810 均未变化；页面限定拟提交标题搜索 blocks=[]、hasMore?=false。Python 对保存的前后完整对象相等、布尔类型、更新时间、搜索、实际字符串输入、两操作和错误原文作断言，exit_code=0。

结论 PASS_PENDING_PARENT_REVIEW，仅覆盖本次字符串 "false" 拒绝与该两操作批次无部分落地，不能推广到所有错误路径。

## 证据

- worker-checkbox-negative-input.json：唯一真实写 payload。
- worker-checkbox-negative-response.json：真实错误 wrapper。
- worker-checkbox-negative-readbacks.json：本请求两次真实 getBlock result JSON 完整转存、搜索输入/响应、Checkbox 定义完整条目和真实时间边界。完整前置 getPage/listProperties 大响应见本会话原始工具记录；定义条目是其中的一条，不冒充完整属性列表。

时间边界来自 terminal datetime 实测：2026-10-04T12:00:13.705305+08:00 至 2026-10-04T12:00:43.953722+08:00。不是 MCP 精确调用时间，工具未提供精确时间。

上述证据是 worker 从真实工具记录转存，需主 agent 核对转存及独立读取，不能只信 worker 自报通过。

## 主 agent 独立复核

1. 独立 getPage 确認当前仍为上述页面，避免切图后盲用 UUID。
2. 独立 getBlock(metadata) 与 readbacks.json.before 逐字段比较。
3. 独立 searchBlocks(pageUuid=上述页面, searchTerm=NEGATIVE-CHECKBOX-SHOULD-NOT-COMMIT, limit=100)，确认无该块。
4. 核对会话一次写入、错误文本、输入确为字符串及证据转存。仅只读复核，不重跑该负向写入。

## 未验与停点

URL 上轮与 Checkbox 本轮均为 worker 实测、待主 agent 复核。未再探测已知不可用的截图工具，也未改环境。当前图 UI 编号/Done/属性显示与跳转、UI 状态反向编辑、列表插入/移动/缩进/bullet 切换、正常关闭重开仍未验。下一步需要用户实际 UI 操作/截图，或已有可用且不改环境的桌面操作路径。不得将 API 值冒充 UI 验收；不得强杀或自动关闭应用。

CLI 专项需专用可丢弃图与对应修复版本；历史缺 order/PDF/Zotero/sync 缺样本未执行。crash durability 未验证。本请求一次 upsert 额度已用完。

仅保存审计文件与更新续接入口；不改源码/环境、不构建、不回收/删除、不 commit/push、不关闭 issue。
