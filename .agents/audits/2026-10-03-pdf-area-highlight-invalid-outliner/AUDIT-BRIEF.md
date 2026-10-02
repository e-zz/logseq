> **⚠️ CAVEAT — this brief is the INPUT, not the conclusion.** It was written by the parent
> agent before the audit and contains **claims the audit overturned** (see `AUDIT-RESULT.md`
> §1 and §6): notably C1 ("target-page is nil") and C3 ("written via MCP"). Both are false.
> Read `AUDIT-RESULT.md` as authoritative. Preserved unedited for audit provenance.

# 审计 brief: Logseq PDF 区域高亮 "Invalid outliner data"

## 背景

Logseq 桌面应用（DB graph 版本，用户装的是 2.0.1 stable win-x64），在 **PDF 区域高亮（area highlight）** 时，
控制台报 `Error: Invalid outliner data`，事务被拒（worker 返回 HTTP 500，`:code :exception`），
PNG 已落盘但 asset 块未建 → 孤儿文件。

同时有一个 **fork**：`D:/Action/logseq`，基于 upstream master，带一批 local-only 修复。
另一处相关代码是 `logseq-pdf-extract`（用户自己的插件，v0.4.0），但插件不在本次失败调用链上。

## 硬约束 / 判读要求

- 只接受**能落到源码行号**的结论；区分「实测（读过源码/日志）」vs「推断」vs「推测」。
- 不要泛泛而谈"可能因为版本差异"，要指名文件:行号 与 具体代码形态。
- 特别注意：**fork 的修复方向是否正确、是否补完**。用户怀疑"这是我们 fork 历史修复里的 PDF 相关修复造成的"。

## 实测证据（原文摘录）

### E1. 失败事务（db-worker-node 日志，2026-10-02T16:15:58）
```
{:db-worker-node-invoke-failed
 {:status 500, :code :exception,
  :error #error {:message "Invalid outliner data",
   :data {:opts {:sibling? true, :replace-empty-target? false, :keep-uuid? true,
                 :keep-block-order? nil, :outliner-op :insert-blocks, :insert-template? nil},
          :tx [{:logseq.property.asset/external-url "assets:///D/logseq__colon/library/qn/Chen%20and%20Chan%20-%202026%20-%20Framework%20for%20Robust%20Quantum%20Speedups%20in%20Practical%20Correlated%20Electron.pdf",
                :logseq.property.asset/type "pdf",
                :block/uuid #uuid "6abfd8be-4731-48d0-a7ab-ee01933cd8d6",
                :block/updated-at 1790957758186,
                :logseq.property.asset/checksum "27aa8bbc...",
                :block/created-at 1790957758186,
                :block/level 1,
                :block/tags ({... :db/ident :logseq.class/Asset, :block/title "Asset" ...}),
                :block/title "Chen%20and%20Chan%20-%20....pdf",     ;; ← URL-encoded
                :logseq.property.asset/size 0,
                :block/parent nil,                                   ;; ← nil
                :block/order "a0",
                :block/page 57693}],                                 ;; ← 指向 Sep 18th, 2026
          :blocks [{... 同上，:block/tags #{:logseq.class/Asset}}],
          :target-block {:block/uuid #uuid "00000001-2026-0918-0000-000000000000",
                         :block/journal-day 20260918,
                         :block/title "Sep 18th, 2026",
                         :block/name "sep 18th, 2026",
                         :db/id 57693}}},
  :method :thread-api/apply-outliner-ops}}
```

### E2. 抛出点（upstream master = fork = 2.0.1 均有）
`deps/outliner/src/logseq/outliner/core.cljs:1082`
```clojure
(if (some (fn [b] (or (nil? (:block/parent b)) (nil? (:block/order b)))) blocks-tx)
  (throw (ex-info "Invalid outliner data" {:opts insert-opts :tx (vec blocks-tx) ...}))
```

### E3. `build-insert-block-tx`（upstream master:722-741）
```clojure
(defn- build-insert-block-tx
  [db block result* {:keys [uuid' parent order target-page outliner-op]}]
  (let [page? (or (ldb/page? block) (:block/name block))
        ;; :block/name is not unique, so pasting a copied page entity
        ;; would create a duplicate page; link to the existing page instead
        existing-page (when (and (= :paste outliner-op) (:block/name block))
                        (ldb/get-page db (:block/name block)))]
    (if existing-page
      {:block/uuid uuid' :block/parent parent :block/order order
       :block/page target-page :block/title "" ... :block/link (:db/id existing-page)}
      (cond-> result*
        (not page?) (assoc :block/page target-page)
        page? (dissoc :block/page)))))
```

### E4. `page?` 判定链
- `deps/db/src/logseq/db/frontend/entity_util.cljs`: `internal-page? = (has-tag? entity :logseq.class/Page)`；
  `page? = (or internal-page? journal? class? property?)`；`object? = (seq (:block/tags node))`
- Asset 块只有 `:logseq.class/Asset` → `page?` = **false**
- 该 asset 由 MCP 写入，**没有 `:block/name`** → 第二分支也 false

### E5. `target-page` 计算（upstream master:709-720）
```clojure
(defn- get-target-block-page [target-block sibling?]
  (or (:db/id (:block/page target-block))
      (when sibling? (when-let [parent (:block/parent target-block)]
                       (when (ldb/page? parent) (:db/id parent))))
      (:db/id target-block)))
```
target-block 是 **Sep 18th, 2026** 这个 journal page 实体 → `(:block/page page)` = **nil** → `target-page` = **nil**
→ `(assoc result* :block/page nil)` → E2 抛错。

### E6. 调用方（upstream master `src/main/frontend/extensions/pdf/assets.cljs:140-148`）
```clojure
(defn- db-based-persist-hl-area-image [repo png]
  (let [file (js/File. #js [png] "pdf area highlight.png")]
    (editor-assets/db-based-save-assets! repo [file] {:pdf-area? true})))
```

### E7. `db-based-save-assets!` 目标选择
```clojure
insert-to-current-block-page? (boolean (and (not target-block) (:block/uuid edit-block) (not pdf-area?)))
target (cond target-block target-block
             insert-to-current-block-page? edit-block
             save-to-page save-to-page
             :else today-page)
...
(outliner-op/insert-blocks! blocks target {:keep-uuid? true :bottom? true
                                           :sibling? (boolean (and edit-block (= edit-block target)))
                                           :replace-empty-target? insert-to-current-block-page?})
```
（`:pdf-area?` 强制 `insert-to-current-block-page?`=false ⇒ 无编辑块时 target=today-page，`:sibling?`=false）

### E8. **关键差异：fork 相对 upstream**（`git diff upstream/master HEAD`）
`src/main/frontend/handler/editor/assets.cljs`：
```diff
-            asset-page (db-async/<invoke-db-worker :thread-api/pull repo
-                                                  [:block/uuid] :logseq.class/Asset)
+            today-page-name (db-async/<get-today-journal-title repo)
+            today-page-e (db-async/<get-journal-page-by-day repo (date/today-journal-day))
+            today-page (if (nil? today-page-e)
+                         (state/pub-event! [:page/create today-page-name])
+                         today-page-e)
...
-                     asset-page)]
+                     today-page)]
```
即：**upstream 的 fallback target 是 Asset 类实体**（`(:block/page asset-page-entity)` 可能为 nil），
**fork 换成了 today journal page**。

### E9. 2.0.1 stable 的实际形态（从 GitHub tag `2.0.1` 拉源码 + 从安装版 `resources/app.asar`
解出 `js/db-worker-node.js` 对照）
- 2.0.1 的 `build-insert-block-tx` 是**旧结构**（无 `existing-page` 块、无 `target-page` 参数）：
  ```clojure
  page? (or (ldb/page? block) (:block/name block))
  result (cond-> result* (not page?) (assoc :block/page target-page) page? (dissoc :block/page))
  ```
- 但**抛错点完全相同**：
  ```js
  if(l(vg(function(n){return null==MH.h(n)||null==Br.h(n)},m))) throw Tk.g("Invalid outliner data",...)
  ```
  （`null==` 在 CLJS 编译后是 `null?`，**不匹配集合内的 nil**）
- 安装版 `app.asar` 里 `grep -c "existing-page"` 在 `db-worker-node.js` 中 **0 命中**，`Invalid outliner data` **1 命中**

### E10. 未完成核实项
- fork 用的是 **CLJS 源码**；安装版是 **压缩后的 JS bundle**。`date/today-journal-day`、`<get-journal-page-by-day`、
  `<get-today-journal-title` 都是改过的调用，**目前无法证明 fork 的 today-page 这条路径在 DB graph 下必然非 nil**。
- 未能确认用户触发时的 UI 状态（是否有未保存的编辑块 → `edit-block` 是否非 nil → target 是 edit-block 还是 today-page）。

## 请审计以下判断（我给出的结论）

**C1.** 根因：`build-insert-block-tx` 中 `page?` 对 Asset 块判为 false，
导致 `(assoc result* :block/page target-page)`，而 `target-page` 在 target 是 page 实体时为 nil → E2 抛错。

**C2.** `logseq-pdf-extract` 插件**不在**失败调用链上，改插件无效。

**C3.** 失败事务里那个 asset（`assets:///D/logseq__colon/...`、`size 0`、无 `:block/name`）
是**通过 MCP 写入**的（依据：URL 形态 + `:block/name` 缺失 + `:block/tags` 是完整实体）。

**C4.** fork 在 `editor/assets.cljs` 把 fallback target 从 `asset-page` 改成 `today-page`，
**方向正确**（避开 `(:block/page asset-entity)` = nil），但**没有补完**——`page?` 判定本身未修，
所以若 target 落到被 MCP 写入的块（`(:block/page edit-block)` 为 nil）或其它 nil 路径，仍会抛错。

**C5.** 安装的 2.0.1 跑的是**未修版本**，所以无论怎么改插件/自建版，装好的 app 仍会报错。

**C6.** 建议的最小修复（二选一或都做）：
   (a) 在 `build-insert-block-tx` 里把 `page?` 判定改为同时认「class-tagged / asset」或有 `:logseq.class/Asset` 的块；
   (b) 调用侧对 `:pdf-area?` 传 `:sibling? true` 或显式传一个非 page 的 target，使 `target-page` 非 nil。

## 审计要求

1. 逐条判 C1–C6：**成立 / 部分成立 / 不成立**，给出理由与所需证据。
2. 指出我漏掉的**其它**可能根因路径（特别是：`existing-page` 分支是否会在非 `:paste` 操作下被走到？`merge entity block` 是否会复活 `:block/title` 而清掉 `:block/page`？`assign-temp-id` 是否可能覆盖 `:block/parent`？）。
3. 评价 C6 的修复方案：会不会引入回归（尤其对 file graph / 普通 asset 插入 / MCP 写入）？有没有更小、更安全的修法？
4. 明确列出**我目前无法证明**的东西，以及要在 GUI/复现里确认什么。
5. 输出中文，直说问题，不要客套。
