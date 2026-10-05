# URL 错误值与批次原子性：worker 实测，待主 agent 独立复核

## 只读前置核验

实测：首先调用 getPage(pageName="63bab91a-013d-44df-afb7-4c83026b03ef", includeChildren=true, maxBlocks=50)，实际标题 TEST-DEMO-20261004-A，父→子→孙 UUID 与 NEXT-TESTS.md 一致；Task Done；metadata false / 0 / Topics 三个引用符合预期。此为当前目标核对，不是重跑此前通过用例。随后 listProperties(expand=true) 核对六个专用属性的 UUID、type、cardinality，均符合续接表。完整前置页面/属性列表响应保留于本会话真实工具记录；本文件不冒充其原始完整响应。

## 本次唯一 upsertNodes

实测：receipt=true，两个 edit 操作作用于 metadata d8d5836c-5a3c-4fe8-8258-f0edd7d3c706：前置合法标题修改 NEGATIVE-URL-SHOULD-NOT-COMMIT；第二条为 URL 属性 00000002-1190-2949-4400-000000000000 赋字符串 not-a-url。

实际返回：API Error: URL property :00000002-1190-2949-4400-000000000000 value must be a URL string

实测：随后 getBlock 完整返回与写前完整返回一致，包括 title、URL、updated-at=1791080520810、其他属性、引用、父级、page、order。页面限定 searchBlocks 查询拟提交标题返回 blocks=[]、hasMore?=false。Python 对保存的完整结果作对象相等断言，并断言搜索结果、错误文本、输入两操作与 receipt=true，exit_code=0。

本用例结论：PASS_PENDING_PARENT_REVIEW。仅证明本次 not-a-url 错误形状被拒绝且该两操作批次未部分落地；不能推广到全部 URL 形状、全部错误路径或 crash durability。

真实工具执行边界（terminal datetime 实测，不是 MCP 精确开始/结束时间）：写调用前 2026-10-04T11:52:07.972735+08:00；写响应后 2026-10-04T11:52:38.045295+08:00。MCP 未暴露精确工具时间，未伪填。

## 完整证据文件

- worker-url-negative-input.json：实际一次 upsert 输入（JSON key 顺序无语义）。
- worker-url-negative-response.json：实际错误响应对象。
- worker-url-negative-before.json / worker-url-negative-after.json：实际 getBlock result 字符串的完整 JSON 内容，分别从前后真实工具输出转存；不是模拟或节选。外层 result wrapper 可从会话记录核查。
- worker-url-negative-search.json：实际搜索输入与完整响应 wrapper。
- worker-ui-probe.json：唯一只读截图探测输入与完整失败响应。

证据由 worker 转存，主 agent 必须结合会话工具记录复核，不应只信 worker 的 PASS。

## UI 探测与未验项

实测：computer_use(action=capture, app=Logseq, mode=som) 一次返回 backend unavailable / packages not importable。未取得图像、未观察 UI；没有重试、安装、配置修改或重启。该失败是截图工具阻塞，不是 Logseq UI 验收失败。

未验：Checkbox 字符串 false 类型拒绝及该路径原子性（下一新授权请求一次 upsert）；当前图 UI 编号、Done、属性显示与跳转；UI 状态反向编辑；编号移动/缩进/bullet 切换；正常关闭重开。CLI 专项、历史缺 order、PDF/Zotero/sync 无对应合格样本仍未验，不新增通过或排除结论。

## 主 agent 复核入口

1. 独立 getPage 当前页面 UUID，确认用户未切图；读取本会话前置响应核对六属性。
2. 独立 getBlock metadata，逐属性/更新时间/标题与 before.json 比较。
3. 独立 searchBlocks(pageUuid=页面 UUID, searchTerm=NEGATIVE-URL-SHOULD-NOT-COMMIT, limit=100)，确认无该块。
4. 核对本会话只有一次 upsert，没有 dry-run 或旁路写；复核错误原文与证据转存。
5. 复核后才采纳本用例为已通过；不得重做该负向写入。

不改源码/运行环境，不构建、不重跑通过测试、不回收/删除、不关闭应用、不 commit/push、不关闭 issue。本次只新增审计证据并更新续接入口。
