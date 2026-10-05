# Briefs 索引（.agents/briefs/）

这些是**一次性 worker 派工单**（point-in-time），不是活跃计划：每份在被派发后即消费完毕。
当前权威状态**不在**本目录，而在：
- `.hermes/plans/2026-10-05_000818-issue-fixes-remaining-acceptance.md`（issue 收束 + 关闭记录）
- `.hermes/plans/search-mcp-filter-upstream-compatibility.md`（搜索兼容性，PARTIAL）
- `.agents/audits/**`（实测证据）

整理于 2026-10-06；保留原文不动（历史证据），仅在此登记取代链。

## 取代 / 续接链（依据文件自述，实测）
- `human-test-ci.md` — **已被** `upstream-first-ci.md` 取代（后者正文自述 "THIS BRIEF SUPERSEDES …human-test-ci.md"）。
- `upstream-first-ci.md` → `upstream-first-resume.md`（后者自述 "Continue original task from …upstream-first-ci.md"，因 /tmp 权限被拒而续接）。
- `search-next-validation.md` → `search-next-parent-corrections.md`（两子代理结果纠偏后的下一阶段）。

## 其余（均为已消费单次派工）
| 文件 | 用途 |
|---|---|
| `demo-graph-acceptance.md` | 合成图验收建立（子代理执行、父代理独立核验） |
| `gui-search-upstream-compatibility-independent.md` | GUI 搜索 upstream 兼容性独立只读审计 |
| `issue-acceptance-public-updates.md` | 公开 issue 验收说明的文档 worker |
| `issue12-remaining-acceptance.md` | #12 剩余验收 |
| `issue4-11-current-package-ui-acceptance.md` | 当前包针对 #4/#11 的 UI 验收 |
| `live-numbering-and-fresh-page-autocomplete-followup.md` | 动态编号/新页补全的公开 issue 跟进 |
| `search-mcp-filter-resume.md` | 搜索隔离实现的续接 |
| `search-package-ci-validation.md` | 新包验证准备（用户已授权） |
