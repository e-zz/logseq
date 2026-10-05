# 父代理复核：GUI 搜索 upstream 兼容性

## 结论

**尚未验证运行兼容性。子代理报告部分拒收，不可作为 upstream 归因或合并依据。**

直接核验固定 refs：U=22a29b30dee3b3930cf49bba50454650c31d2a07；B=fb5eb4eb43cdb3be7a29d816b969d45c127d4861；A=bb70c73d29352c85473bdc3bba2bd37d1f2abe72。本次没有启动应用、访问图、改源码或发布。

## 接受的源码证据

父代理实际从 git show 取完整文本比较：以下文件 U 与 B 字符串相等：cmdk/core.cljs、cmdk/state.cljs、block/breadcrumb.cljs、block/breadcrumb_model.cljs、worker/handler/block_breadcrumb.cljs、frontend/search.cljs。worker/search.cljs 的 search-result->block-result、combine-results 两个函数源文也相等。

这只证明页面路径渲染、字段映射与部分合并逻辑没有净源码差异，不证明输入数据、事务/索引时序或真实 UI 一致。

B 固定 ref 全 src grep 未发现 publish-result?。后来 C 的该选项不能解释在 B 中已经存在的症状；不因此排除 B 的其他 fork 改动。

## 必须撤回的子代理归因

1. **所谓 U 引入 active-visible 页面守卫的 C1 为错误归因。** U 的 worker/handler/search.cljs 根本没有 resolve-active-visible-page-uuid、search-page-uuid 或 search-block-uuid；这些是 U..B 差异新增。B:141-183 的守卫由 API handler 的显式 page-uuid/block-uuid 解析调用。GUI current-page 传的是 :page，直接走 frontend.search/block-search，不能据此声称它触发上述 API resolver。U:139-144 与 B:185-190 的 worker handler search-blocks 都直接调用 worker/search/search-blocks，不经过 resolver。拒绝“该症状在 U 上同样存在”结论。
2. **C2 的索引同步方向写反。** U 的 imported-data? 为 true 时 skip-search-sync? 为 true；B 若仅 imported-data? 与 runtime-write? 都为 true（from-disk? / graph-parser imported-data? 都为 false），skip-search-sync? 为 false，因此 when-not 分支会执行索引同步，而非跳过。U:174-194、B:174-195。其他顶层跳过标记仍可覆盖此效果。对实际时序影响尚未验证。
3. **SQL 完全等价的结论错误。** 有 page 作用域且 namespace 查询时，U 为 page=? AND match1 OR match2，B 为 page=? AND (match1 OR match2)，且 B 的 include-search-block? 新加 :page 检查。它们并非逐字/逻辑相同；匹配其他页面的第二分支时有差异。是否属于期望作用域修正、是否触及本次现象，必须单列，不从全局 Nodes 推及 current-page。
4. **MCP 与 Cmd-K 无共享代码不成立。** B handler.search/search 与 Cmd-K 都调用 frontend.search/block-search，并继续共享 worker 链路。区别是 Cmd-K 不经过 handler.search/search 的全局结果发布包装层；没有证据证明 :search/result 写入导致 Cmd-K 结果标签变化。
5. **A..U 不是纯 upstream ancestry 区间。** git merge-base --is-ancestor A U 返回 1；两者 merge-base 为 be800f171172c259d4dd942346e4d247a0783738。A 含 fork 行为，直接 A→U diff 也包含撤去 A 的 fork 差异，不能把该 diff 或 A..U 提交数量整体标为“均 upstream”。此外父代理 A→U renderer diff 并非空：5文件有真实差异，原报告第70行自相矛盾。
6. **迁移常量已单独核对，不沿用旧摘要。** U:15-24 的 search-db-version=6、fts-id-keyed-search-db-version=4；build/upgrade 函数用后者且非 force 的条件分支执行 rowid migration。因此子代理“版本4条件迁移”这一点成立，先前会话摘要所谓版本5不能作为事实。该源码分支仍不等于实际图已完成迁移，也不能只读版本常量即宣称运行归因。
7. **运行计划不可直接执行。** 不假设 GUI Delete 等于 MCP recycle；不以打开回收 internal page 为前提；不手改版本号伪造旧索引；新图首次打开不能替代 v3→v6 的真实迁移验收。普通页面标签与导航测试先独立验证，回收/迁移另列实际可行输入。

## 后续实际验证路径

已查到正好 U 的官方 Build-Desktop-Release run 37131548966，head_sha 为 U；Windows x64 artifact 11277537465 未过期。compile-cljs job 111227498724。这可以避免重新编译，也不需要借旧 A 行为作为 U 的替身。

下载后仍须核 compile checkout SHA、artifact digest、二进制包版本/内容；单凭 run head_sha 不当作包内源码归属已证实。之后仅在全新合成图及隔离 userData/HOME 下做 U vs B GUI 对照。至少覆盖普通块所在页面标题、嵌套块路径、精确结果项与正常点击导航。回收行为单列 U/B真实结果，不预设可见或不可见。未运行前保持 NOT VERIFIED。

子代理原报告与 raw 保留便于追溯，但错误的 C1/C2/C3 排序及 PASS 前提不具权威；本报告是父代理复核结论。
