# 搜索新包验收：CI 37259407681

## 结论

本次补丁的包内针对性验收完成，结论为 **PASS_WITH_OBSERVATION_LIMIT**：真实新包 renderer 默认搜索发布状态；真实 Electron HTTP MCP 搜索正常返回，三次异查询未持续覆盖预置共享状态；Cmd-K 实际节点结果正常；合成普通块回收/恢复后的 MCP 与 Cmd-K 可见性翻转通过。

原用户报告“旧版 GUI 能搜到回收内容／新版不能”的根因仍 **UNRESOLVED**。未识别旧包并做同 fixture 两包运行对照，不能称恢复旧版行为或整个搜索需求全 PASS。

## 实测构建与文件身份

- 源码-only fork 分支 `source-only-ci-5c1b5e55`，commit `7b1ae92ee1eb1679b8bb827d12d7e76e22d4d5c5`；远端读回精确匹配。
- 基线 `fb5eb4eb43cdb3be7a29d816b969d45c127d4861`，仅五个 src 文件、一个提交；与原 `5c1b5e5574` 的五文件逐字一致。原提交另外两份报告没有发布。
- CI https://github.com/e-zz/logseq/actions/runs/37259407681 实测 success；真实 compile checkout log 已确认精确 source SHA，而非仅依据 run.headSha。
- x64 artifact `11325120064`；下载路径 `C:/Users/zhang/Downloads/logseq-ci-37259407681-x64/unpacked`；未运行安装器。
- 精确 ASAR 读取新旧 package.json 均 2.0.2，入口 electron.js；新包 electron.js 与 js/main.js 含 publish-result?，旧包均不含。静态指纹仅证明文件特征差异，非行为证据。
- 新 app.asar SHA256 `bcee01c59e76e71754a5ad1d8b7b997c443fb3f8b21dd76f4b646f9f9a71f727`。

## 实测环境与范围

新包真实 CLI 创建 `search-package-fixture` DB 图与两个合成块，明确 --root-dir 位于本工作区 audit 的 parent-runtime/home/logseq；正常停止该 CLI worker后由隔离GUI打开。未伪造EDN图。

隔离启动在生产 main 执行前用 Inspector 修改 app home/userData/appData/logs 和 os.homedir，只改测试进程文件路径，不改搜索函数或包文件。读回实际路径均在 parent-runtime 内，appPath 为新包 app.asar。独立Inspector/CDP/HTTP端口19431/19432/19433已启动前检查。HTTP feature 在此隔离localStorage启用，测试MCP不使用现有配置或真实凭据。认证行为/安全强度不在本次验收范围。

## 实测用例

|用例|结果|直接依据|
|---|---|---|
|renderer 默认 search 返回目标并发布共享状态|PASS|真实 window.logseq.api.search；返回和 :search/result 均含合成目标|
|MCP searchBlocks 三次异查询|PASS|真实 streamable-http MCP → Electron IPC → renderer，分别返回对应合成块|
|MCP 不持续覆盖共享状态|PASS，观测有限|预置与查询结果不同的 sentinel；三次调用后即时及延迟样本均原样保留，共六个样本|
|Cmd-K direct-path 节点命中|PASS|实际 .cp__cmdk-item-main-text 文本严格等于目标；不把“Create page”建议算命中|
|普通块回收后 MCP / Cmd-K 不可见|PASS|recycleBlock 返回正常，MCP空结果，Cmd-K精确目标节点数0|
|普通块恢复后 MCP / Cmd-K 可见|PASS|restoreBlock 返回正常，MCP目标恢复，Cmd-K精确目标节点数1|

最终 behavior-parent.json 包含14条PASS检查（包含身份、正控制、重复搜索及目标核验，不是14个独立场景）。最终runner exit0，status PASS_WITH_OBSERVATION_LIMIT。

## 观测边界与失败保留

- 状态样本证明此次未出现已知的持续覆盖回归；**不能证明零瞬时或同值写入**。没有真实状态写事件hook，不提升为无写入的绝对证明。
- Cmd-K 输入清空必须等待500ms再输入相同query，避免debounce合并；快速空→同值重填曾出现timeout。已保留一次失败JSON。此用例仅验证实际重新发起查询后的可见性，不证明打开结果自动刷新/无缓存问题，也不将harness问题写成应用已修缺陷。
- 初次前台启动结束后CDP消失、HTTP feature未开启、认证头缺失、选择器假设不成立等预检/runner问题曾失败，后通过实际包/API/DOM核对纠正；不冒称这些失败是源码缺陷。先前通用子代理脚本未执行，PARENT-REVIEW.md记录拒收原因。
- 没有包内旧/新差分；没有回收页与其子孙的完整包内矩阵；没有性能或崩溃持久性声明。其他issues不因本次测试升级结果或关闭。
- 隔离应用已通过其原生 quit 正常退出；读回19431/19432/19433均拒绝连接。随后仅停止闲置启动器Node进程。无强杀Logseq，无删除fixture，未操作用户原应用/图。

## 直接证据（本地）

根 `.agents/audits/search-package-37259407681/`：
- `compile.raw.log`：CI实际checkout及编译日志。
- `new-package-static.json` / `inspect_package_parent.py`：精确ASAR文件核验。
- `parent-runtime/fixture-cli.raw.json`：真实CLI创建与停止。
- `parent-runtime/startup-isolation.json`：执行前路径读回。
- `parent-runtime/behavior-parent.json`：最终真实包内响应、状态样本、DOM与目标身份。
- `parent-runtime/previous-restored-gui-timeout.json`：失败观测保留。
- `parent-runtime/normal-quit-check.json`：正常退出后端口读回。
- `launch_parent.cjs` / `verify_parent.cjs`：实际执行入口，需已安装playwright/ws；只用于合成隔离测试，不用在现有图。

此前源码六组119 tests/431 assertions与五文件clj-kondo 0 errors/0 warnings为独立证据，本轮未重跑，不当作包内测试数。
