# GUI 搜索 upstream 兼容性：包内受控验收

## 判定

**本次合成图覆盖的 GUI 搜索功能：PASS（有限覆盖）。**

**用户原场景的“页面标题消失”：未复现，UNRESOLVED。所有 upstream 功能无回归：未验证，不作此声明。**

本次不是仅凭源码 diff 判断：实际运行 upstream U、整合包 B、最新补丁包 C 的 Electron renderer，驱动 Cmd-K 结果项，读取精确 DOM 行及页面导航，截图核对，并执行自建普通页的回收/恢复。没有修改产品源码，没有操作用户现有图，没有推送/发 Release/关 issue。

## 实测构建身份

| 角色 | 源码 ref | 包来源 |
|---|---|---|
| U：同一 upstream 基线 | 22a29b30dee3b3930cf49bba50454650c31d2a07 | 官方 run37131548966，x64 artifact11277537465 |
| B：多项修复整合包 | fb5eb4eb43cdb3be7a29d816b969d45c127d4861 | 本地 CI37203268496 的 Windows2.0.2包 |
| C：之后的搜索选项补丁包 | 7b1ae92ee1eb1679b8bb827d12d7e76e22d4d5c5 | 本地 CI37259407681 的 Windows2.0.2包 |

U 的官方 ZIP SHA256 实测为 `503ad6252dbf2f123c83ac74d55e6d7201e6eca69d2ed3e9b45bc8b99f8a3c97`，与 GitHub artifact digest 相同。父代理检查 compile-cljs job111227498724 的原日志：`git log -1 --format=%H` 后输出精确 U SHA，不仅依据 run.head_sha。

U/B/C 的实际 exe、appPath、隔离路径和 app.asar SHA256 见 `runtime-results/artifact-identities.json`。B/C 使用本轮此前已确认构建归属的本地 CI 包；本次没有重新编译。

## 输入与隔离（实测）

用 B 的真实 CLI 在全新 `compat-pair-fixture` 图创建两普通页：

- `Compat Page One`：普通块 `compatordinary45831`，父块 `compatparent45831` 及子块 `compatnested45831`。
- `Compat Page Two`：普通块 `compatotherpage45831`。

CLI worker 通过明确 root-dir/graph 的 server stop 正常停止，再复制 seed。U/B 初始副本逐文件 SHA256 相等（`fixture-hashes.json`）；C 从同一 pristine seed 复制。第二/第三轮 U/B 是各自首次启动后的同一合成图，不宣称再次逐字相等。所有图与 userData、HOME、appData、logs 均隔离；在 production Electron main startup pause 只调整路径，不修改搜索代码。启动后读回实际 graph/path 及 appPath 检查实例身份。

实际运行输出根为 `C:/Users/zhang/AppData/Local/Temp/ls-ub-20261005`（终端继承 TMPDIR 与会话声明的 Hermes scratch 不同，已记录此路径差异）。关键输出已复制到本仓库 `runtime-results/`，不依赖临时目录作为唯一证据。

## GUI 验收（实测）

每包执行相同三次查询，共 9 个实际搜索结果样本；统计由 JSON 程序化核对，非手工假定。

| 项目 | U | B | C |
|---|---|---|---|
| 普通块所在页面标题 `Compat Page One` | 可见 | 可见 | 可见 |
| 嵌套块路径 `Compat Page One / compatparent45831` | 可见 | 可见 | 可见 |
| 另一页面的块显示 `Compat Page Two` | 可见 | 可见 | 可见 |
| 点击三种结果，进入对应页面与块 | 通过 | 通过 | 通过 |

标题不是仅在 body 中查找：精确命中 `.cp__cmdk-item-main-text`，取其 `[data-cmdk-item]` 内 `.breadcrumb__label`，记录文本、display/visibility 和非零尺寸。U/B 两份嵌套结果截图另外经过视觉核对，都清晰显示页面/父块路径及 Current Page badge。导航从实际结果行点击触发，并 await get_current_page 读回页信息，核对目标页、URL 和正文块文本。

**排除（范围明确）：这些包把所有结果的页面标题显示整体关掉。** 依据是 U/B/C 各自三个精确结果行仍显示非零尺寸的正确标题；这不排除特定图、特定实体或条件分支的显示缺陷。

## 回收页对照（实测）

在各包的独立合成图中，只操作刚创建的普通页 `Compat Page Two`。SDK delete_page 路径已从源码确认：普通页进入可恢复回收，非属性/标签的永久 retract，也非 delete_recycled_page_permanently。

操作前读取并核对该页 title/uuid。执行后用只读 datascript_query 精确拉取该 UUID，实测三包均存在：

- 原 page UUID 保留；
- `:logseq.property/deleted-at` 已设置；
- parent.title 为 `Recycle`。

| 精确查询/恢复 | U | B | C |
|---|---|---|---|
| 回收前页面标题查询 | 1条 | 1条 | 1条 |
| 回收前该页内容块查询 | 1条 | 1条 | 1条 |
| 回收后页面标题查询 | 0条 | 0条 | 0条 |
| 回收后该页内容块查询 | 0条 | 0条 | 0条 |
| restore_page 后内容块查询 | 1条 | 1条 | 1条 |
| 恢复后 UUID 不变、deleted-at 去除、页面标题恢复显示 | 通过 | 通过 | 通过 |

因此，**当前 U 在这一新图场景中，也不会显示该回收页结果及其 Recycle 路径标签**；B/C 表现与它相同。不能据此将旧 A 截图的 Recycle 行归为“共享状态泄漏”，也不能断言某个 upstream 提交引入该差异。旧 A 是 fork 包且与 U 不构成单一 upstream 祖先区间，原图/旧索引的现象需单独复现。

## 测试脚本问题与处置

- 深 audit 路径下 fixture CLI 曾报 unable to open database file；缩短路径后相同 CLI 创建成功。这仅支持路径相关的推断，不宣称已定位具体 SQLite/Windows 根因。失败记录在 `runtime-pair/fixture-cli.raw.json`，没有伪造成功。
- 首轮 get_current_page 未 await，Promise 序列化为 `{}`，navPage=false 是测试器错误而非产品失败。已修后重新驱动三包全部三次 GUI 点击，实际异步页读回均匹配。
- app.quit 请求发出后，在调试传输仍连着时 child exit event 未立即到达，原 JSON 的 normalExit=null 不能当作已退出。脚本结束后独立核对本轮三组记录的 PID22816/41268/18060 均不存在，六个专属 inspector/CDP 端口全部关闭。只正常退出本次测试实例，没有强杀/关用户实例。

## 未覆盖与原现象边界

- 用户实际图上“普通块页面标题消失”尚未复现，不能说已解决或否定观察。
- 旧 v3 索引真实迁移、用户保存的过滤条件/配置、特定实体引用/隐藏祖先、旧 A 包回收页行来源未验证。
- 本轮不覆盖 standalone 普通块回收、API/MCP 所有选项、全部导入/插件/PDF/sync 功能；不是所有 upstream features 的总验收。
- 同一可见结果样本通过，不能证明没有瞬态状态写入；本轮没有新增 MCP 状态发布的零写声称。

## 合并建议

这批证据支持“没有发现当前覆盖的标题/路径/导航及普通页回收搜索相对 U 的回归”。**不应为了尚未复现的标签消失盲目回退 publish-result? 或修改公共渲染。** 用户原场景仍列为合并前缺口，需在该场景上定位后再决定是否修复。源码独立报告的错误归因已在 `parent-review.md` 拒收，不作为根因。

## 产出

原始 JSON 的 `scope/limits` 文字沿用第一轮 U/B 模板，尚未更新为第三轮完整覆盖；不将该模板文字作为最终覆盖声明。第三轮的实测内容以 `sides.*.queries`、`sides.*.recycle` 和父代理 `acceptance-summary.json`、本报告为准。原始输出保留不重写。

归档检查：`git diff --cached --check` 对原始 `.diff` 的空白上下文行、逐行源码核证文本及 Electron/CI 原始日志报告 trailing whitespace（exit2）。这些证据不为排版检查重写；新写的 `.md/.py/.cjs/.json` 定向检查通过（exit0），两份 CJS 的 `node --check` 通过。窄字面量 token 扫描无命中，仅是有限规则扫描，不等于全部隐私检查。

- `runtime-results/gui-pair-recycle.json`：最终三包 GUI、原始页/回收/恢复读回、导航与关闭核验。
- `runtime-results/acceptance-summary.json`：父代理逐包程序化验收。
- `runtime-results/gui-pair-v2.json`：修正异步导航后的三包普通结果检查。
- `runtime-results/gui-pair.json`：保留第一轮测试器 async 判据错误，不采作导航 FAIL。
- `runtime-results/{U,B,C}/*.png` 与 electron.raw.log：精确命中截图、回收后搜索截图及日志。
- `prepare_pair.py`、`run_pair.cjs`、`fetch_upstream.py`：重运行输入与实际执行脚本；先检查输出目录/端口，勿覆盖既有证据。
- `parent-review.md`、`raw/parent-checks.*`：独立审核父复核与固定 ref 源码证据。
