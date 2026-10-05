# 02 — MCP READ reconciliation map (segment-level, read-only)

Parent: `AUDIT-BRIEF.md` §4A; delivery plan `2026-10-03-ezz-logseq-delivery.md` §3 table + §4A.
Mode: **read-only audit**. No file was created/edited except this report. No commit/merge/cherry-pick/push/salvage-application.

## 0. Baselines (实测, git-verified this session)

Command: `git worktree list` and `git rev-parse --short=10 <ref>` in `D:/Action/logseq`.

| Ref | Path / ref | SHA | Role |
| --- | --- | --- | --- |
| priv (main) | `D:/Action/logseq` @ `priv` | `44ce435eab` | read-only reference base; merge-base for both branches below |
| **#17** | `D:/orca/workspaces/logseq/issue6-parent-wip` @ `fix/issue-6-mcp-parent-wip` | `76c7a22327` | **chosen READ baseline** (PR #17 → priv) |
| **#8** | `D:/orca/workspaces/logseq/issue8-scoped-search` @ `fix/issue-8-scoped-search-wip` | `0ded4dbf1b` | scoped-search work (no PR) |
| salvage | `origin/wip/salvage-action-logseq-orphans` | `af19a45591` | orphan salvage (NOT to be wholesale-applied) |

`git merge-base priv 0ded4dbf1b` = `44ce435eab2d871508537abf6758c4deadf082ce`; `git merge-base priv af19a45591` = same. So both #8 and salvage branch **directly off priv** and do **not** contain #17's `mcp_server.cljs` changes (verified: `#17`'s `api-tools` map is absent from both; see §1/§4).

Labeling key: 实测 = verified in this session against source; 推断 = logical consequence of verified source; 推测 = not settled.

---

## 1. SCOPE-8 — scoped search (pageUuid / exact blockUuid / limit, no whole-graph fallback)

### 1a. Segment inventory across the three refs

| Segment | #17 `76c7a22327` | #8 `0ded4dbf1b` | salvage `af19a45591` |
| --- | --- | --- | --- |
| `mcp_server.cljs` searchBlocks **input schema** (`pageUuid`/`blockUuid`/`limit` zod) | ❌ absent | ❌ absent | ✅ present (~L236–244) |
| `mcp_server.cljs` **`api-search-blocks`** routing to scoped args | ❌ plain | ❌ plain | ✅ present (L129–131) |
| `mcp_server.cljs` `require [electron.mcp-search :as mcp-search]` | ❌ | ❌ | ✅ present (L8) — **but the file is missing in salvage** |
| `electron/mcp_search.cljs` (`search-call-args`) | ❌ file absent | ✅ file present (15 lines) | ❌ **file absent** |
| `frontend/handler/search.cljs` scope handling (`page-uuid`/`block-uuid`/`limit` + validation) | ❌ | ✅ (diff vs base) | ✅ (diff vs base) |
| `frontend/worker/handler/search.cljs` `:thread-api/search-block-uuid` + `:thread-api/search-page-uuid` + semantic-exclusion | ❌ | ✅ (diff vs base) | ✅ (diff vs base) |
| `frontend/worker/search.cljs` SQL scope (`page`/`block` bind, page-current-membership filter) | ❌ | ✅ (diff vs base) | ✅ (diff vs base) |
| handler/worker scoped-search **tests** | ❌ | ✅ (`search_test.cljs` both worker/handler) | ✅ (larger test sets: worker 290 ln, handler 189 ln, mcp_server 48 ln) |

**实测** — #8 diff stat (`git diff --stat 44ce435eab 0ded4dbf1b`):
```
src/electron/electron/mcp_search.cljs             |  15 +++
src/main/frontend/handler/search.cljs             |  65 ++++++++---
src/main/frontend/worker/handler/search.cljs      |  47 +++++++
src/main/frontend/worker/search.cljs              | 103 +++++++++++------
src/test/frontend/worker/handler/search_test.cljs | 131 +++++++++++++++++++++
5 files changed, 310 insertions(+), 51 deletions(-)
```
→ Confirms AUDIT-BRIEF fact #7: **#8's HEAD diff does NOT include `mcp_server.cljs`**. The registered tool is never given the scope schema.

**实测** — salvage diff stat (`git diff --stat 44ce435eab af19a45591`):
```
src/electron/electron/mcp_server.cljs      |  73 +++-
src/main/frontend/handler/search.cljs      |  65 +++-
src/main/frontend/worker/search.cljs       | 103 +++--
src/main/logseq/api/db_based/tools.cljs    | 591 ++++++++++++++++++++++++-----
src/test/electron/mcp_server_test.cljs     |  48 +++
src/test/frontend/handler/search_test.cljs | 189 +++++++++
src/test/frontend/worker/search_test.cljs  | 290 ++++++++++++++
7 files changed, 1190 insertions(+), 169 deletions(-)
```
Note: salvage has `worker/search.cljs` but **no** `worker/handler/search.cljs` in its diff — the `:thread-api/search-*` route definitions (which live in `worker/handler/search.cljs` in #8) are absent from salvage. Salvage's "wire" therefore depends on a worker-handler segment #8 provides but salvage lacks.

### 1b. #17 baseline (what the registered tool does TODAY)

`#17/src/electron/electron/mcp_server.cljs:128-130` (实测):
```clojure
(defn- api-search-blocks
  [call-api-fn args]
  (call-api-fn "logseq.app.search" [(aget args "searchTerm") #js {:enable-snippet? false}]))
```
`#17/src/electron/electron/mcp_server.cljs:235-239` (实测) — schema exposes **only** `searchTerm`:
```clojure
:searchBlocks
{:fn api-search-blocks
 :config #js {:title "Search Blocks"
              :description "Search graph for blocks containing search term"
              :inputSchema #js {:searchTerm (z/string)}}}
```
→ No `pageUuid`, no `blockUuid`, no `limit`. An agent cannot express a scope.

Routing to the handler (实测, #17):
- `src/electron/electron/server.cljs:145-148` `api-fn` → `resolve-real-api-method` (L73-81) turns `"logseq.app.search"` → `app@search` (snake-case) → `invoke-logseq-api!` (L106-113) → `:invokeLogseqAPI` to renderer.
- `src/main/electron/listener.cljs:110-132`: ns `app` ⇒ `sdk1 = js/window.logseq.api`; `(apply js-invoke methodTarget "search" args)`.
- `src/main/logseq/api.cljs:187-192` (the actual `search` export):
```clojure
(defn ^:export search
  [q' & [opts]]
  (-> (search-handler/search
       (state/get-current-repo) q'
       (if opts (js->clj opts :keywordize-keys true) {}))
      (p/then #(bean/->js (sdk-utils/normalize-keyword-for-json %)))))
```
**This `js->clj opts :keywordize-keys true` is the linchpin** (推断): it converts the `#js` string keys produced by `mcp_search.cljs` (`"page-uuid"`, `"block-uuid"`, `"limit"`) into the Clojure keywords `:page-uuid` / `:block-uuid` / `:limit` that #8's `frontend.handler.search/search` destructures. So once the schema + `api-search-blocks` + `mcp_search.cljs` are in place, the options flow end-to-end into #8's handler **without any change to `api.cljs`** (api.cljs already keywordizes).

### 1c. #8 — what is actually present (source, wired to the *handler*, NOT to the MCP tool)

`#8/src/electron/electron/mcp_search.cljs:1-15` (实测, full file):
```clojure
(ns electron.mcp-search)

(defn search-call-args
  [args]
  (let [options #js {:enable-snippet? false}
        page-uuid (aget args "pageUuid")
        block-uuid (aget args "blockUuid")
        limit (aget args "limit")]
    (when page-uuid
      (aset options "page-uuid" page-uuid))
    (when block-uuid
      (aset options "block-uuid" block-uuid))
    (when limit
      (aset options "limit" limit))
    [(aget args "searchTerm") options]))
```

`#8/src/main/frontend/handler/search.cljs` (实测, diff vs `44ce435eab`) adds, inside `search`:
```clojure
([repo q {:keys [page-db-id page-uuid block-uuid limit more?]
          :or {page-db-id nil page-uuid nil block-uuid nil limit 10}
          :as opts}]
 (validate-search-limit! opts limit)
 (when (and page-uuid (not (and (string? page-uuid) (common-util/uuid-string? page-uuid))))
   (throw (js/Error. "pageUuid must be a valid page UUID string")))
 (when (and block-uuid (not (and (string? block-uuid) (common-util/uuid-string? block-uuid))))
   (throw (js/Error. "blockUuid must be a valid block UUID string")))
 (when (and page-db-id page-uuid)
   (throw (js/Error. "pageDbId and pageUuid cannot both be specified")))
 ...
 (p/let [page-uuid (when page-uuid (<resolve-page-uuid repo page-uuid))
         block (when block-uuid
                 (state/<invoke-db-worker :thread-api/search-block-uuid repo block-uuid))]
   (when (and page-uuid block-uuid (not= page-uuid (:page-uuid block)))
     (throw (js/Error. "pageUuid and blockUuid must identify the same page")))
   (when-not (string/blank? q)
     (p/let [page-db-id (<resolve-page-db-id repo page-db-id)
             opts (cond-> (assoc (dissoc opts :page-uuid :block-uuid) :limit limit)
                    page-db-id (assoc :page (str page-db-id))
                    page-uuid (assoc :page page-uuid)
                    block-uuid (assoc :block (:block-uuid block)))
             blocks (search/block-search repo q opts)
             files (when-not (or page-db-id page-uuid block-uuid)
                     (search/file-search q))]
       ...))))
```
Invalid scope ⇒ `throw` ⇒ **no silent whole-graph fallback** (实测 of the code path; runtime behavior not executed — see §6). When a scope is set, `files` (file-search, a whole-graph channel) is suppressed: `files (when-not (or page-db-id page-uuid block-uuid) ...)`.

`#8/src/main/frontend/worker/handler/search.cljs` (实测, diff) adds:
```clojure
(defn- resolve-active-visible-page-uuid [db page-uuid]
  (when-not (and (string? page-uuid) (common-util/uuid-string? page-uuid))
    (throw (ex-info "pageUuid must be a valid page UUID string" {:page-uuid page-uuid})))
  (if-let [page (d/entity db [:block/uuid (uuid page-uuid)])]
    (cond
      (not (ldb/page? page))
      (throw (ex-info (str "UUID " page-uuid " does not identify a page") {:page-uuid page-uuid}))
      (ldb/hidden? page)
      (throw (ex-info (str "Page UUID " page-uuid " is not active and visible") {:page-uuid page-uuid}))
      :else (str (:block/uuid page)))
    (throw (ex-info (str "Page UUID " page-uuid " not found") {:page-uuid page-uuid}))))

(defn- resolve-active-visible-block [db block-uuid]
  ...
  (let [result (api-tools/get-block db block-uuid {})]
    (when-let [error (:error result)] (throw (ex-info error {:block-uuid block-uuid})))
    (let [page-uuid (resolve-active-visible-page-uuid db (:block/page result))]
      {:block-uuid (:block/uuid result) :page-uuid page-uuid})))

(def-thread-api :thread-api/search-block-uuid [repo block-uuid] ...)
(def-thread-api :thread-api/search-page-uuid  [repo page-uuid] ...)
```
`resolve-active-visible-block` **reuses `api-tools/get-block`** (the #8 baseline read endpoint) for block eligibility → exact-block scope inherits LOOKUP-8's "not page/property/pseudochild" semantics. `resolve-active-visible-page-uuid` rejects recycled/hidden/non-page UUIDs (`ldb/hidden?` covers `deleted-at`/hidden ⇒ **page current-member filtering** + no-recycled-scope).

`#8/src/main/frontend/worker/search.cljs` (实测, diff) adds scope binds to the SQL exact/prefix search (`scope-sql (str (when page "page = ? and ") (when block "id = ? and "))`) and, at the top of the semantic path, `(not (:block option))` so exact-block scope **excludes semantic/vector results** (the MCP schema text promises "excludes descendants and semantic/vector results").

### 1d. salvage — the wiring that is missing from #17, **but salvage is internally broken**

`salvage mcp_server.cljs:8-9` (实测):
```clojure
            [electron.mcp-search :as mcp-search]
            [electron.mcp-upsert :as mcp-upsert]
```
`salvage mcp_server.cljs:129-131` (实测):
```clojure
(defn- api-search-blocks
  [call-api-fn args]
  (call-api-fn "logseq.app.search" (mcp-search/search-call-args args)))
```
`salvage mcp_server.cljs:236-244` (实测) — scoped input schema:
```clojure
:searchBlocks
{:fn api-search-blocks
 :config #js {:title "Search Blocks"
              :description "Search graph for blocks containing search term. Optionally scope to an active page by stable page UUID or exactly one active block by stable block UUID; pageUuid and blockUuid must agree when both are set. Exact-block scope uses indexed text search and excludes descendants and semantic/vector results. Limit defaults to the API default and must be between 1 and 100."
              :inputSchema #js {:searchTerm (z/string)
                                :pageUuid (-> (z/string) .uuid .optional (.describe "Stable UUID of an existing active visible page. Invalid or recycled pages fail instead of triggering a global search."))
                                :blockUuid (-> (z/string) .uuid .optional (.describe "Stable UUID of exactly one active visible block. Its descendants are excluded. Invalid, hidden, recycled, pseudochild, cyclic, or broken-chain blocks fail visibly."))
                                :limit (-> (z/number) .int .positive (.max 100) .optional (.describe "Maximum number of results, from 1 through 100. Defaults to the existing search API default."))}}}
```

**实测 — salvage is structurally unbuildable.** `git ls-tree -r --name-only origin/wip/salvage-action-logseq-orphans | grep -i mcp` returns:
```
deps/db-sync/worker/mcp_request.mjs
src/electron/electron/mcp_server.cljs
src/electron/electron/mcp_transport.cljs
src/test/electron/mcp_server_test.cljs
src/test/electron/mcp_transport_test.cljs
```
`grep "mcp_search"` → **NOT FOUND**; `mcp_upsert` → **NOT FOUND**. Yet salvage's `mcp_server.cljs` (L8) and `mcp_server_test.cljs` (L3) `require [electron.mcp-search :as mcp-search]`, and `mcp_server.cljs` (L9, L173) `require`/call `mcp-upsert/api-upsert-nodes`. **Both required namespaces are absent from the branch** → the branch cannot compile, regardless of whether the #8 worker-handler routes (which salvage's diff also lacks) are present.

**Consequence (推断):** salvage is a *partial snapshot*, not a drop-in. Its mcp_server search schema + `api-search-blocks` are the only two "wire" segments an executor needs for SCOPE-8, but they cannot be taken from salvage's HEAD — they must be **re-applied onto the #17 baseline** (which already carries the `mcp-upsert` file, the `:receipt`/parent upsert work, and the full read set), and `mcp_search.cljs` must come from **#8** (which actually has the file). This is exactly "reconcile segment by segment; do NOT wholesale-apply salvage."

### 1e. EXACT missing wiring to make scoped search reachable from the registered tool (on #17 baseline)

On `76c7a22327` (chosen baseline), three segments are absent and must be added:

1. **New file** `src/electron/electron/mcp_search.cljs` — take **verbatim from #8 `0ded4dbf1b`** (§1c). Salvage does NOT have this file.
2. **`mcp_server.cljs` require** — add `[electron.mcp-search :as mcp-search]` to the `(:require ...)` list (salvage L8 form; #17 currently lacks it, #17 L7-9 only requires mcp-transport/mcp-upsert/promesa).
3. **`mcp_server.cljs:128-130`** — change `api-search-blocks` body from
   `(call-api-fn "logseq.app.search" [(aget args "searchTerm") #js {:enable-snippet? false}])`
   to `(call-api-fn "logseq.app.search" (mcp-search/search-call-args args))`.
4. **`mcp_server.cljs:235-239`** — expand the `:searchBlocks` `inputSchema` with `pageUuid` / `blockUuid` / `limit` (salvage L236-244 form, or the equivalent #8-style schema) and update the description.

**Plus the handler/worker runtime segments (from #8, already present in #8 but absent from #17):**
5. `src/main/frontend/handler/search.cljs` scope-handling block (§1c).
6. `src/main/frontend/worker/handler/search.cljs` `:thread-api/search-block-uuid` + `:thread-api/search-page-uuid` + semantic-exclusion guard.
7. `src/main/frontend/worker/search.cljs` SQL scope binds + page-membership filter.
8. (optional, for acceptance) #8's scoped-search tests.

`api.cljs` needs **no** change (its `search` already does `js->clj :keywordize-keys true`). **No** change to `cli.cljs` (search is not a `logseq.cli.*` method; it is `logseq.app.search` → `sdk1` `api.cljs/search`).

### 1f. SCOPE-8 acceptance checklist vs #17 baseline

| Acceptance (plan §3) | #17 baseline | Source | Runtime |
| --- | --- | --- | --- |
| pageUuid scope | ❌ (no schema, no arg) | 实测 | 实测 (never callable) |
| exact blockUuid scope | ❌ | 实测 | 实测 |
| limit | ❌ | 实测 | 实测 |
| invalid scope must NOT fall back to whole-graph | n/a (no scope) | — | — |
| page current-member filtering | n/a | — | — |
| NOT subtree search (exact block excludes descendants) | n/a | — | — |

**Verdict: SCOPE-8 = MISSING on the #17 baseline** (not even partially wired: the registered tool has zero scope input). The *implementation* of scope semantics EXISTS in #8 (source, not wired to the tool) and salvage (tool-side only, branch unbuildable). Nothing here has been exercised end-to-end (no HTTP MCP run this session).

---

## 2. TREE-8 — full-tree read with budget + truncation signal + JSON-serializable dynamic refs

All on `#17` baseline `76c7a22327`.

**Tool registration (实测):** `mcp_server.cljs:100-106` `api-get-page` calls `"logseq.cli.getPageData"` with `{:include-recycled? :include-children? :max-blocks}`; `mcp_server.cljs:151-170` registers `getPage` with the `includeChildren`/`maxBlocks` schema (maxBlocks described as "a positive integer node budget", "block-count bound, not a byte-size bound").

**Impl (实测) `src/main/logseq/api/db_based/tools.cljs:221-268` `get-page-data`:**
- tree built once (`page-block-tree db page-id`), `total-blocks (count-tree-blocks tree)`; default (no children) returns top-level only and attaches `:block/tree-has-more? true` + `:block/tree-omitted-count omitted` when `omitted>0` → **complete/truncated signal** (实测 L245-246, L267-268).
- **explicit failure when full-tree budget insufficient** (实测 L247-258):
```clojure
invalid-budget? (and include-children?
                     (not (and (integer? max-blocks) (pos? max-blocks))))
...
  invalid-budget?
  {:error (str "This page requires " total-blocks
               " blocks. Pass includeChildren=true with a positive integer maxBlocks "
               "of at least " total-blocks " to return the complete tree.")}
  (and include-children? (> total-blocks max-blocks))
  {:error (str "This page requires " total-blocks " blocks, exceeding maxBlocks="
               max-blocks ". Retry with maxBlocks at least " total-blocks
               " to return the complete tree.")}
```
  No partial blocks returned on budget failure (实测).
- **stable UUIDs**: every entity goes through `entity->serializable` (`:block/uuid str`) — `tools.cljs:95-121`.
- **dynamic refs finally JSON-serializable** (实测 `entity->serializable` L101-121): reduces over `db-schema/card-many-ref-type-attributes`, then `dynamic-ref-attributes db (keys base)` (def at `tools.cljs:85`), then `card-one-ref-type-attributes`, each via `reference->serializable db`. So dynamic (non-schema) node-property refs are projected to serializable form.

**Tests present in #17 (实测, `src/test/logseq/api/db_based/tools_test.cljs`):**
- `get-page-data-signals-partial-when-children-are-omitted` (L318) — asserts `tree-has-more?` true and `tree-omitted-count` 4.
- `get-page-data-include-children-returns-full-recursive-tree` (L332, `:max-blocks 6`).
- `get-page-data-include-children-serializes-uuids-at-every-level` (L356).
- `get-page-data-nested-tree-json-round-trips` (L378), `nested-json-excludes-entity-refs-and-parent-ids` (L392), `nested-json-projects-all-schema-reference-attributes` (L419), `include-children-serializes-page-entity` (L465).
- `get-page-data-dynamic-node-property-refs-are-json-safe` (L518) — the dynamic-ref JSON-safety case.
- **`get-page-data-include-children-requires-valid-block-budget`** (L566) — table `[{:include-children? true} "maxBlocks"]`, `0`/`-1`/`2.5`/`"6"` → `"positive integer"`, `5` → `"requires 6 blocks"`; asserts the error string and that no blocks leak.
- `get-page-data-include-children-accepts-exact-block-budget` (L589).

**Verdict: TREE-8 = EXISTS_AND_WIRED on #17.** Source + tool registration + focused unit tests all present. **Caveat (实测 boundary):** coverage is at the `tools.get-page-data` unit level; the plan's own note "补真实 MCP 验收" (add real registered-HTTP-MCP acceptance) is NOT satisfied by these tests — a full `bb dev:lint-and-test` / registered HTTP MCP run has not been executed this session (推断: the code path is complete and unit-covered; runtime end-to-end is 推断, not 实测).

---

## 3. LOOKUP-8 — read exactly one ordinary block by exact UUID

**Tool (实测):** `mcp_server.cljs:108-112` `api-get-block` → `"logseq.cli.getBlockByUuid"` with `{:include-recycled?}`; `mcp_server.cljs:134-140` registers `getBlock` (description: "Pages, tags, properties, and property-value pseudochildren are not blocks returned by this tool… Descendants are not returned").

**Impl (实测) `tools.cljs:270-327` `get-block`:**
- rejects non-uuid (`"Block uuid must be a valid uuid string"` L278).
- `:else` (L281-327): rejects page/tag/property (`"is a page, tag, or property, not a block"` L296); rejects nil page (`"is not a block"` L299); rejects pseudochild via `(:block/closed-value-property block)` / `(:logseq.property/created-from-property block)` → `"identifies a property-value pseudochild and is not returned by getBlock"` (L301-304); **explicit lifecycle semantics** — recycled-page gate L306-309 (`"belongs to a recycled page and is not returned by default. Pass includeRecycled=true to read it"`), hidden L311-312, parent cycle L314-315, broken parent chain L317-319; on success associates `:block/parent`/`:block/page` as string UUIDs and, if recycled, `:logseq.property/deleted-at` (L322-326).
- parent-chain state machine (L284-293) distinguishes `:broken` / `:reaches-page` / `:cycle` / `:hidden`.

**Tests (实测, `tools_test.cljs`):**
- `get-block-resolves-one-block-by-uuid` (L647) + asserts page is rejected `"not a block"` (L673).
- `get-block-rejects-invalid-and-missing-uuids` (L677).
- `get-block-rejects-parent-cycles-without-leaking-content` (L686).
- `get-block-rejects-broken-parent-chain` (L702).
- `get-block-hidden-ancestor-remains-denied` (L715).
- `get-block-recycled-page-gate-and-opt-in` (L730).
- `get-block-rejects-hidden-property-value-pseudochildren` (L749).
- `get-block-json-round-trips-dynamic-node-property-reference` (L767).

**Verdict: LOOKUP-8 = EXISTS_AND_WIRED on #17.** Source, tool registration, and thorough unit tests (lifecycle + pseudochild + cycle/broken + JSON) all present. Same caveat as TREE-8: unit-level, not a registered-HTTP-MCP runtime run this session.

---

## 4. LIFE-14 — active/recycled page lookup + listing consistency, multi-generation disambiguation

**Impl (实测) `tools.cljs`:**
- `resolve-page-for-read` (L183-219): by UUID → `ldb/get-page`, gated by `(or include-recycled? (not (recycled-page? page)))`; by name → `generations-by-name` (L171-177) splits `{:active :recycled}`; **active wins** (single active ⇒ return it, even with opt-in, L197-198 and the test at L76-81); ambiguous (N>1 of same generation) ⇒ error listing `candidate-uuids` (L200-203, L216-219); recycled-only without opt-in ⇒ explicit "in the recycle bin… pass includeRecycled" error (L208-211).
- `get-page-data` (L221-268) and `list-pages` (L329-352) both key off `recycled-page?` (L166) and attach `:logseq.property/deleted-at` on opt-in (L351-352).

**Tests (实测, `tools_test.cljs`):**
- `get-page-data-hides-recycled-page-by-uuid` (L23) — recycled page unreadable by uuid or name without opt-in; "No block content may leak."
- `get-page-data-include-recycled-marks-page` (L42) — opt-in carries `deleted-at` marker.
- `get-page-data-prefers-active-over-recycled-generation` (L59) — **default does not misread prior-generation content**; opt-in "must not switch to the recycled generation when an active one exists."
- `get-page-data-ambiguous-recycled-generations` (L85) — two same-name recycled gens ⇒ error "ambiguous", lists both candidate uuids, disambiguate by explicit uuid; no block content leaked.
- `get-page-data-ambiguous-active-generations` (L122).
- `list-pages-include-recycled` (L160) — recycled excluded by default; opt-in lists with stable uuid + `deleted-at`.
- `list-and-get-agree-about-recycled-pages` (L186) — **lookup and listing are consistent** across include-recycled? true/false, by uuid and by name.

**"Final MCP JSON verified" boundary (推断):** the serialization goes `get-page-data` → `entity->serializable`/`block-tree->serializable` → `api.cljs:192` `bean/->js (sdk-utils/normalize-keyword-for-json %)` → MCP `mcp-success-response` `js/JSON.stringify`. The `deleted-at` marker and `tree-has-more?`/`tree-omitted-count` are plain values, so they survive JSON (推断; a focused tools_test exists but a registered-HTTP-MCP JSON round-trip for the *recycled-page* case specifically was not executed this session).

**Verdict: LIFE-14 = EXISTS_AND_WIRED on #17.** Source + tool registration + a strong unit suite (consistency, active-wins, opt-in deleted marker, multi-gen disambiguation, no-content-leak) present. Caveat: unit-level; registered-HTTP-MCP final-JSON runtime not exercised this session (推断).

---

## 5. RECYCLE-8 / RESTORE-8 — agent block recycle & restore

### 5a. Is there ANY implementation in the baseline (#17)? **No.**

**实测** — `#17` registered MCP tools (`mcp_server.cljs` `api-tools` map) are exactly: `getBlock`, `listPages`, `getPage`, `upsertNodes`, `searchBlocks`, `listTags`, `listProperties`. **No** recycle/restore/delete tool (grep for `recycle|restore|delete|trash` in `#17 mcp_server.cljs` returns only `includeRecycled` read opts + the HTTP `handle-delete-request`).
**实测** — `#17` `cli.cljs` methods (`list-tags`, `list-properties`, `list-pages`, `get-page-data`, `get-block`, `upsert-nodes`, `import-edn`, `export-edn`) — **no** recycle/restore.
**实测** — `#17` `tools.cljs` references to "recycle" are read-side only (`recycled-page?`, `generations-by-name`, `resolve-page-for-read`, `get-block` gate). No mutation to recycled state.

→ **RECYCLE-8 and RESTORE-8 are entirely unimplemented at the agent/MCP layer in the #17 baseline.** Nothing is reachable through a registered MCP tool.

### 5b. Backend recycle helper DOES exist (brief-12 claim verified, 实测 on `priv@44ce435eab`)

`deps/outliner/src/logseq/outliner/recycle.cljs` (实测):
- **L127-157 `recycle-blocks-tx-data`**: creates/ensures a "Recycle" page, and per root block takes `subtree (block-subtree db block)`, sets `:block/parent page-id`, `:block/page page-id`, a new `:block/order`, `:logseq.property/deleted-at now-ms`, and records **`recycle/original-parent` / `recycle/original-page` / `recycle/original-order`** (L147-153). Subtree nodes get `{:db/id :block/page page-id}` (L155-157). ⇒ matches brief 12 "moves roots + subtree page membership, sets deleted-at, records original-parent/page/order."
- **`restore-tx-data` / `restore-target`** (~L186-242, per brief 12): restores into original parent with existing-page fallback, reuses original order. **`restore!`** (实测 L244-250):
```clojure
(defn ^:api restore!
  [conn root-uuid]
  (when-let [root (d/entity @conn [:block/uuid root-uuid])]
    (when-let [tx-data (seq (restore-tx-data @conn root))]
      (ldb/transact! conn tx-data {:outliner-op :restore-recycled})
      true)))
```
  实测: `restore!` does **not** preflight that the caller's UUID is a recycled *ordinary* block — it only checks the entity exists and builds tx. Brief 12's "do not expose unchecked" caution is valid (推断).
- **`permanently-delete!`** (实测 L259-267) and **`gc!`/`gc-tx-data`** (实测 L269-298): retention `retention-ms (* 30 24 3600 1000)` = 30 days, `gc-interval-ms (* 24 3600 1000)` (L13-14); `gc-tx-data` retracts entities whose `:logseq.property/deleted-at <= now-30d`. ⇒ **30-day retention + GC confirmed; do not promise infinite restore** (plan §2 实测).

### 5c. Normal block delete does NOT call the recycle helper (brief-12 claim verified, 实测)

Chain (实测 on `priv`):
- `deps/outliner/src/logseq/outliner/op.cljs:316-319`:
```clojure
:delete-blocks
(let [[block-ids opts] args
      blocks (keep #(d/entity @conn [:block/uuid %]) block-ids)]
  (outliner-core/delete-blocks! conn blocks (merge opts opts')))
```
- `core.cljs:1563-1566`: `(op-transact! :delete-blocks f conn blocks opts)` where `f` is `core/delete-blocks` (bound L1563).
- `core.cljs:1227-1276` `delete-blocks` ("Delete blocks from the tree."): builds top-level blocks, `block-subtree-ids`, then `otree/-del node txs-state db` (L1266) + page-updated-at retraction (L1268-1275). **No reference to `outliner.recycle` anywhere in the delete path** (实测 `grep outliner.recycle core.cljs` → only paste-remint comments, no helper call).
- `core.cljs:1227` also guards built-ins (`"Built-in nodes can't be deleted"` L1244-1249) and handles the default-value-property placeholder case (L1256-1261) — i.e., it is a **hard tree retraction**, not a soft recycle.

→ **Confirmed (实测): the ordinary block delete path takes tree-deletion (`otree/-del`), NOT the recycle helper.** Recycle is only reachable through the explicit backend ops:
- `op.cljs:413-419` (实测): `:restore-recycled` → `outliner-recycle/restore! conn root-uuid`; `:recycle-delete-permanently` → `outliner-recycle/permanently-delete! conn root-uuid`. (A recycle-*initiate* op for an ordinary block subtree is the gap — the helper `recycle-blocks-tx-data` exists but no outliner op/MCP method invokes it for ordinary blocks.)

### 5d. What an agent-only recycle/restore would need to call (推断, from verified seams)

- **Recycle (one call, root UUID):**
  1. Validate root is an **ordinary block** (not page/tag/property/pseudochild — reuse `api-tools/get-block` eligibility, as #8's `resolve-active-visible-block` does), active (not already recycled), not hidden, subtree eligible; built-ins/pages/tags/properties rejected.
  2. Build tx via **`outliner.recycle/recycle-blocks-tx-data db [root-block] {:deleted-by-uuid <agent-id> :now-ms <now>}`** (priv recycle.cljs L127) — this moves root+subtree to the Recycle page, sets `deleted-at`, records `recycle/original-parent/-page/-order`.
  3. Transact through the **worker/outliner transact path** (e.g. `op-transact!` with a new `:recycle-blocks` op, or `ldb/transact!` as `restore!` does) — **not** raw DataScript/SQLite from Electron (plan §6 / brief 12 "reuse outliner transaction pipeline").
  4. Result: root UUID, state, verified location (worker readback), affected subtree info; repeated recycle of a retained root ⇒ explicit no-op (do not rewrite original location/deleted-at).
- **Restore (root UUID):**
  1. Validate the UUID is an explicitly **recycled** ordinary block (the existing `restore!` does NOT — brief 12 caution; an agent wrapper must add this).
  2. Call **`outliner.recycle/restore!`** (recycle.cljs L244) / `restore-tx-data` — original-parent → valid original-page fallback; detect occupied/invalid original order **before** transacting (restore code currently does not itself establish collision-free restore — 实测/推断 gap).
  3. Report actual restored parent/page + whether original position was preserved.
- **Search/read exclusion:** recycled blocks (moved under the Recycle page) must stay out of active `getPage`/`getBlock`/`searchBlocks`; existing reads already gate on `recycled-page?` (page-level) — but brief 12 notes existing API reads must be **audited** for blocks *moved under the built-in Recycle page* (block-level), not only trashed original pages (推断; needs a dedicated audit during implementation).
- **Retention:** 30-day (`retention-ms`) + `gc!` exist — do NOT promise infinite undo (实测).
- **Permanent delete:** `permanently-delete!` exists (op `:recycle-delete-permanently`) but must **not** be exposed via the agent contract (plan §6 "不暴露 permanent delete").

**Verdict: RECYCLE-8 = MISSING (agent/MCP). RESTORE-8 = MISSING (agent/MCP).** Backend *helpers* exist and are verified in source (实测), but none are reachable through a registered MCP tool in #17, and the ordinary-block recycle-initiate op does not exist at all (only `restore!`/`permanently-delete!` ops + the unused `recycle-blocks-tx-data` helper). Distinguishing: **helper exists in source (实测) ≠ reachable through the registered MCP tool (实测: not reachable).**

---

## 6. Runtime-verification status (诚实边界)

| ID | Source wired to tool? | Focused unit tests? | Registered-HTTP-MCP run this session? |
| --- | --- | --- | --- |
| SCOPE-8 | ❌ on #17 (schema/args absent) | #8 has handler/worker tests; #17 has none | 无 — not executed |
| TREE-8 | ✅ on #17 | ✅ (budget-failure, partial signal, uuid-at-every-level, json-round-trip, dynamic-refs-json-safe) | 无 — 推断 only |
| LOOKUP-8 | ✅ on #17 | ✅ (lifecycle, pseudochild, cycle, broken, json) | 无 — 推断 only |
| LIFE-14 | ✅ on #17 | ✅ (active-wins, opt-in marker, multi-gen disambiguation, list/get agreement) | 无 — 推断 only |
| RECYCLE-8 / RESTORE-8 | ❌ (no tool, no op for ordinary-block recycle) | backend `recycle-test` exists (priv) but not run | 无 |

**What would settle the 推断s:** compile `#17` (+ the 3 SCOPE-8 wiring segments), then run a real registered HTTP MCP session against a synthetic/disposable DB graph: scoped `searchBlocks` with an invalid `pageUuid` (expect an error, not global results); `getPage` with `includeChildren` over budget (expect explicit failure, no partial blocks); `getBlock` on a recycled page (expect deleted-at marker in JSON); two same-name recycled pages (expect ambiguity error with candidate uuids). For RECYCLE-8/RESTORE-8: implement the op + tool, then recycle → active read/search hidden → restore → reappearance, on a disposable graph (brief 12 verification gate). No runtime execution was performed in this audit — all "wired" claims are source-level (实测 of code), not runtime (推断).

## 7. Segment-level take-away (what an executor should pull, what to protect)

- **From #17 `76c7a22327`** (protect, do not touch): the full read set — `getBlock` (LOOKUP-8), `getPage` tree+maxBlocks (TREE-8), `listPages` (LIFE-14), `upsertNodes` receipt/parent (#6/#8 write), `searchBlocks` (plain), `listTags`, `listProperties`; plus `mcp_upsert.cljs`, `mcp_transport.cljs`.
- **From #8 `0ded4dbf1b`** (take, source present + tested): `mcp_search.cljs`, `handler/search.cljs` scope block, `worker/handler/search.cljs` thread-api routes, `worker/search.cljs` SQL scope, scoped-search tests.
- **From salvage `af19a45591`** (take *only* the two wire segments, re-applied onto #17 — do NOT use salvage HEAD): the `:searchBlocks` scoped input schema (L236-244) and the `api-search-blocks`→`mcp-search/search-call-args` one-liner (L129-131) + the `require` (L8). **Do NOT** pull salvage's `mcp_server.cljs` wholesale: it is missing `mcp_search.cljs` **and** `mcp_upsert.cljs` (both required), so the branch is unbuildable (实测).
- **RECYCLE-8/RESTORE-8:** net-new on #17 — implement the ordinary-block recycle op + tool and a safe restore wrapper around `outliner.recycle/restore!`; reuse `recycle-blocks-tx-data` / `restore!` / `gc!`; do not expose `permanently-delete!`; do not change GUI delete.

*Read-only audit. No source file, branch, or index was modified. Report written to the dedicated worktree path per user instruction.*
