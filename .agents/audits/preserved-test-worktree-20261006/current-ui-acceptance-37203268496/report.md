# #4 / #11 当前包专项 UI 验收 — 报告（CI 37203268496 / Logseq-win-x64-2.0.2）

执行者: GUI worker（本 worker 为唯一 GUI 操作者）
时间: 2026-10-04 22:41 – 22:59 (本地, UTC+8)
工作目录: D:/orca/workspaces/logseq/issues-human-test-20261003
证据根目录: D:/orca/workspaces/logseq/issues-human-test-20261003/.agents/audits/current-ui-acceptance-37203268496/
（screenshots/ = 原始截图; raw/ = 每次 cua-driver 调用的输入/输出/时间戳 JSON + MCP 读取/写入记录）

## 0. 执行体与前置校验

| 项 | 结果 |
|---|---|
| 可执行文件 | PASS — 主窗口进程 pid 45964 的 Path = `C:\Users\zhang\Downloads\logseq-ci-37203268496-x64\Logseq-win-x64-2.0.2\Logseq.exe`（PowerShell Get-Process 实测，见 raw 记录前的 preflight） |
| 图/页 | PASS — 原生只读 MCP getPage 确认 TEST-DEMO-20261004-A = `63bab91a-013d-44df-afb7-4c83026b03ef`（Demo 图, `C:\Users\zhang\logseq\graphs\Demo`） |
| Hermes computer_use wrapper | FAIL — 本会话未装载（父已说明 Python 依赖导入失败；按要求未安装）。全程使用 raw cua-driver（`C:\Users\zhang\AppData\Local\Programs\Cua\cua-driver\bin\cua-driver.exe`） |
| raw 驱动 list_windows | PASS — Logseq 主窗口 pid 45964 / window_id 5770950（2880×1704, HiDPI 2×；元素 frame 为物理像素，截图为 1456×861 逻辑像素，**坐标换算 = frame ÷ 2**） |
| UIA 树 | 初始 degraded（0 元素，Electron 未启用 UIA）；22:47 窗口曾前台后 UIA 树可用（48–114 元素），成为 #11 的主要读证据 |

## 1. 唯一 MCP 写入（预算 1/1 已用，失败/成功均计）

- 调用: 原生 `mcp__logseq__upsertNodes`，2026-10-04 22:41:56（本地），返回 `Added: {:page 1, :block 3}.`
- payload 先落盘: `raw/upsert-payload-01.json`；调用记录: `raw/upsert-call-01-result.json`
- 新建内容（全部为**新页**，未触碰任何既有测试块；既有页 readback 见 `raw/readbacks.md` 同节）:

| 实体 | 稳定 UUID | 说明 |
|---|---|---|
| 页 ISSUE4-11-UI-FIXTURE-20261004 | `847b0ac1-3e7e-41aa-806b-00f424db2dd0` | 全新 fixture 页 |
| 块 ISSUE4-11-UI-ACCEPTANCE-A | `fcbead8c-4e4c-48a0-a9de-4eb0375c5ccc` | order-list-type=number，**#11 搜索 canary** |
| 块 ISSUE4-11-UI-ACCEPTANCE-B | `0a7ceec6-854b-48aa-a15b-998d7633a99b` | order-list-type=number |
| 块 ISSUE4-11-UI-ACCEPTANCE-C | `56f8da78-b4f6-4d6f-825a-0f2929e50a7e` | order-list-type=number |

写后原生 getPage 全树 readback（独立调用，非 receipt）: 三块顶层 title 均**无数字前缀**（原文即 "ISSUE4-11-UI-ACCEPTANCE-A/B/C"），`order-list-type` 属性 = `number`（内置 List type 属性, ident `logseq.property/order-list-type`, uuid `00000002-6078-1711-1000-000000000000`，经 listProperties(expand) 确认）。
**API 写入时刻（#11 计时基准）: 22:41:56；首次 GUI 搜索命中: 22:50:42（约 8 分 46 秒，期间未做任何手动索引重建）。**

## 2. #11 GUI 搜索 / 引用补全

### 2.1 GUI 搜索 canary — **PASS**（真实证据）
1. 打开搜索框: Ctrl+K 未能送达（背景投递被 Chromium surface 丢弃：`background_unavailable`，已记录 raw/b1、b2）；经侧栏 Recent 点击使窗口前台后 UIA 树解锁，搜索模态随后可用（raw/f1、f2，截图 `screenshots/f2-after-click.png`）。
2. 输入 canary: 键盘 type_text 三次未改变输入值（SendInput 落到无主前台窗口 "Jump List for Google Chrome" 0x1e0938，已记录 raw/g1,h2,i2 及读回）；改用 UIA `set_value`（element_token 方式）一次成功 — 后台投递、零前台干扰（raw/k3，返回 route=accessibility）。
3. 读回（独立原生 get_window_state + 截图）:
   - 输入框 Edit value = `ISSUE4-11-UI-ACCEPTANCE-A`（UIA `value` 字段直读，非推测）
   - 结果: `Nodes 1` → 页标签 `ISSUE4-11-UI-FIXTURE-20261004` + 块 `ISSUE4-11-UI-ACCEPTANCE-A`（匹配高亮）；另有 `Create page` 行（说明块标题不作为页补全候选出现，符合预期）。
   - 截图: `screenshots/k4-search-after-setvalue.png`；UIA 原始: `raw/k4-search-after-setvalue.raw.json`
   - 页/块 UUID 与 MCP readback 一致（`847b0ac1…` / `fcbead8c…`），搜索结果**正确**。
   - 附加事实: 此前输入 `TEST-DEMO-20261004-A` 时搜索同样即时返回 Nodes 1（raw/g2,h3,i3 系列截图），证明搜索索引对新写入内容正常刷新，**无需手动重建索引**（未做，也不应做）。

### 2.2 块引用补全 `((...))` — **BLOCKED（GUI 无法启动编辑器输入）**
- 源码期望: `src/main/frontend/modules/shortcut/config.cljs` 中 `:go/search` = `mod+k`（已读，line 306）；块引用触发为编辑器内键入 `((`，属编辑器输入路径。
- 阻塞: 编辑器输入需要键盘事件送达 Chromium 内容；本窗口键盘输入（背景/前台两条路径）在会话后半段全部不可达（见 §4），`set_value` 只作用于搜索框 Edit，不能向块编辑器注入 `((` 触发补全。
- 结论: 不是功能失败，是**本驱动路由下无法执行该测试**；未编造任何结果。

### 2.3 页引用补全 `[[...]]`（新页标题）— **BLOCKED（同因）**
- 预期: 键入 `[[ISSUE4-11-UI-FIXTURE-20261004` 应补全出新页标题（新页在搜索索引中已被 2.1 证实存在）。
- 阻塞同 2.2：无法向块编辑器注入按键。
- 备注: 未要求把普通块标题当页候选（遵守 brief）。

## 3. #4 编号列表自动重编号

| 检查 | 结果 |
|---|---|
| 静态 1/2/3 截图（fixture 页） | **BLOCKED** — 崩溃前最后一拍未能把视图导航到 fixture 页（搜索结果行点击/Enter 在该阶段已不可达，raw/l1..r3、y4 系列） |
| 在 1、2 之间插入普通列表项 → 渲染 1/2/3/4 | **BLOCKED** — 需编辑器输入 |
| 稳定 ID 保留 + 原文无数字前缀 | **PASS（API 层证据）** — 写后 readback 三块 UUID/原文不变；#11 搜索行显示的块原文同样无前缀。GUI 渲染层未验证 |
| 移除测试（转 bullet / 移出编号序列，**非删除**） | **BLOCKED** — 需编辑器命令（列表样式切换命令未找到对应 source 键位可安全发送；MCP 显式 `bullet` 字符串已知被拒，故不把它记为 UI 测试失败） |

## 4. 重大事件: 应用窗口在会话尾部丢失（非 graph 数据丢失）

- 22:58:10（raw/z2）最后一拍 UIA 快照仍正常（48 元素，搜索模态开着）。
- 22:58:33–22:58:37 三次后台 click 中，第 3 次返回 `window_target_not_found` → 窗口已消失。
- 随后: pid 45964 终止；`list_windows` 无 Logseq 主窗口；端口 12315 拒连；MCP getPage 超时。详见 `raw/crash-event.json`。
- 归因: **不确定**。两个候选：(a) 应用自身崩溃（期间无任何错误弹窗截图，db-worker 日志无新错误）；(b) 22:58 的 synthetic 点击（路线与之前有效的前台投递不同，且此前 22:55 已出现前台 HWND 被 Chrome Jump List 0x1e0938 长期占用的异常环境）。此前 22:46–22:54 的 foreground(global_input) 序列被证实**真实驱动过应用**（打开搜索、接受输入、移动选中行），而 22:58 三次背景 synthetic click 的 effect 均为 unverifiable，不能证明其触达。
- 数据完整性: fixture 页与三块在崩溃前已由原生 MCP 独立 readback 确认存在；此后未有任何成功的写操作，预期无其他变更。重启后应用 `listPages`/`getPage` 复核即可。

## 5. 最小人工协助请求（重启后，预计 <5 分钟）

前提: 用户重新启动 CI 包 `Logseq.exe`（worker 无权重启/安装），打开 Demo 图。**不需要重建索引**。

1. 在 Logseq 中按 **Ctrl+K**，输入 `ISSUE4-11-UI-ACCEPTANCE-A`，确认出现 Nodes 1（页 ISSUE4-11-UI-FIXTURE-20261004 / 块 A），回车打开；若愿意，顺手对搜索模态截图一张给我（对应补 2.1 的"重启后复证"）。
2. 在 fixture 页块 A 下方，用正常编辑器：Enter 新行 → 键入 `((` 键入块 B 标题前几个字母 → 截图块引用补全（#11 2.2）。
3. 同页键入 `[[ISSUE4-11-UI-FIXTURE-20261004` → 截图页引用补全（#11 2.3）。
4. #4: 在块 A 与块 B 之间插入一行普通文本（Enter 后直接输入，不写 list 标记），截图应显示 1/2/3/4；然后选中插入行用 UI 列表样式菜单把它转为 bullet（或移出编号段），再截图应回 1/2/3。（**不要删除**；若你更想直接删除插入行，告诉我，我给出该 fixture 行的可逆 recycle 方案后再做。）

如果你不方便逐项做，最低限度只需第 1 步 + 截图，其余我可基于现有证据出 BLOCKED 结论。

## 6. #14 前置（本会话未执行任何回收）

- 可安全软回收的测试页: **`ISSUE4-11-UI-FIXTURE-20261004`**（uuid `847b0ac1-3e7e-41aa-806b-00f424db2dd0`，本会话新建、无人引用，纯 fixture）。
- 同页名 active/recycled 生成 fixture: **尚未存在**。需一次"创建→回收→同名再创建"的代际序列（2 次 MCP 写，超出本次 1 次预算），建议在 #14 正式验收时单独授权执行。普通块 recycle 不覆盖该场景（brief 已确认）。

## 7. 产物清单（绝对路径）

- 报告: `D:\orca\workspaces\logseq\issues-human-test-20261003\.agents\audits\current-ui-acceptance-37203268496\report.md`（本文件）
- 截图（原始，未裁剪）: `…\current-ui-acceptance-37203268496\screenshots\` — 关键帧: a11-prestate, f2-after-click（搜索打开）, k4-search-after-setvalue（#11 核心证据）, y4-results-bg（重启前最后一组搜索快照）, x4-check（主视图 114 元素）, aa4-check 之后窗口消失
- 原始调用记录（每次 cua-driver 调用的入参/出参/起止时间）: `…\current-ui-acceptance-37203268496\raw\*.raw.json`
- MCP 写入 payload 与结果: `raw/upsert-payload-01.json`, `raw/upsert-call-01-result.json`
- 崩溃事件记录: `raw/crash-event.json`

## 8. 限制与未承诺项

- 这是**当前包定向 UI 验收**，不承诺重跑全部 API 测试。
- #11 2.2/2.3 与 #4 的 GUI 交互项在本驱动路由下 BLOCKED（键盘→Chromium 内容不可达），未用 API 结果冒充 GUI 结果。
- 未修改任何既有测试块/笔记；未做 issue 状态变更、未发 GitHub 评论、未用 output_schema；未编译/安装/重启/杀进程/访问其他图。
- 时间戳均为本机 UTC+8 墙上时间；API→GUI 搜索延迟按可测量口径（写入 22:41:56 → 命中 22:50:42）记录，未捏造服务端计时。
