(ns logseq.api.db-based.issue6-test
  "Regression slice for e-zz/logseq issue #6: write parented block trees in a
   single MCP/CLI batch. `parent-id` is the only new block/add data field.

   Every hierarchy test imports through the REAL production importer
   (`sqlite-export/build-import`), the same builder that
   `logseq.api.db-based.cli/upsert-nodes` applies via the `:batch-import-edn`
   worker op. No transport stubs: `build-upsert-nodes-edn` runs against a live
   worker db and the import is applied to a live conn, so a persisted
   parent/order readback is a real hierarchy, not a fake one."
  (:require [cljs.test :refer [async deftest is use-fixtures]]
            [datascript.core :as d]
            [frontend.db.conn :as conn]
            [frontend.test.helper :as test-helper]
            [logseq.api.db-based.tools :as api-tools]
            [logseq.api.db-based.cli :as cli-api]
            [promesa.core :as p]
            [logseq.api.test-helper :as api-test]
            [logseq.db :as ldb]
            [logseq.db.sqlite.export :as sqlite-export]
            [logseq.outliner.tree :as otree]))

(use-fixtures :each {:before api-test/start-plugin-api-db!
                     :after api-test/destroy-plugin-api-db!})

;; --- helpers ---------------------------------------------------------------
(defn- block-by-title
  "First block entity with the exact title on the live db (nil if none)."
  [db title]
  (let [datom (first (d/datoms db :avet :block/title title))]
    (when datom (d/entity db (:e datom)))))

(defn- validated-import
  "Returns {:tx-data ... :db-after ...} for a valid import, or {:error ...}."
  [import-edn]
  (let [db (conn/get-db)
        txs (sqlite-export/build-import import-edn db {})]
    (if (some? (:error txs))
      txs
      (sqlite-export/validate-import-txs txs db {:edn-label "Imported EDN"
                                                 :validate-scope :tx}))))

(defn- import-edn!
  "Applies `import-edn` to the live test conn through the production builder
   used by the real `:batch-import-edn` worker op. Throws on a build or
   validation error so a rejected import is a hard test failure."
  [import-edn]
  (let [conn (conn/get-db nil false)
        result (validated-import import-edn)]
    (when (:error result)
      (throw (ex-info (str "import rejected: " (:error result)) result)))
    (ldb/transact! conn (:tx-data result) {::sqlite-export/imported-data? true})
    {:db (deref conn)}))

(defn- page-block-tree
  [db page-title]
  (let [page (ldb/get-page db page-title)
        blocks (ldb/get-page-blocks db (:db/id page))]
    (otree/blocks->vec-tree db blocks (:db/id page))))

(defn- child-titles
  "Ordered :block/title of the direct children of the block titled
   `parent-title` (empty if it has none or is absent)."
  [db page-title parent-title]
  (let [tree (page-block-tree db page-title)]
    (some-> (some #(when (= parent-title (:block/title %)) %)
                  (mapcat #(tree-seq (comp seq :block/children) :block/children %) tree))
            :block/children
            (#(mapv :block/title %)))))

(defn- build-edn
  [& operations]
  (api-tools/build-upsert-nodes-edn (conn/get-db) operations))

(defn- expect-throw
  [msg-regex ops]
  (is (thrown-with-msg? js/Error msg-regex (apply build-edn ops))))

;; ============================================================================
;; 1. Core regression: new parent + child via a batch temp id, appended after
;;    an existing sibling, read back through the real tree.
;; ============================================================================
(deftest parented-add-on-existing-page-persists-parent-page-and-order
  (test-helper/load-test-files
   [{:page {:block/title "Issue6 Parented Page"}
     :blocks [{:block/title "existing sibling"}]}])
  (let [page-uuid (str (:block/uuid (ldb/get-page (conn/get-db) "Issue6 Parented Page")))]
    (import-edn! (build-edn
                  {:operation "add" :entityType "block" :id "p1"
                   :data {:title "new parent" :page-id page-uuid}}
                  {:operation "add" :entityType "block"
                   :data {:title "new child" :page-id page-uuid
                          :parent-id "p1"}}))
    (let [db (conn/get-db)
          parent (block-by-title db "new parent")
          child (block-by-title db "new child")]
      (is (some? parent) "The parent block must be persisted")
      (is (some? child) "The child block must be persisted (no child silently dropped)")
      (is (= (:db/id parent) (:db/id (:block/parent child)))
          "The child's :block/parent must resolve to the requested parent block")
      (is (= (:db/id (ldb/get-page db "Issue6 Parented Page"))
             (:db/id (:block/page child)))
          "The child must be a block on the same page")
      (is (= ["existing sibling" "new parent"]
             (mapv :block/title (page-block-tree db "Issue6 Parented Page")))
          "The new parent is appended after the existing top-level sibling")
      (is (= ["new child"] (child-titles db "Issue6 Parented Page" "new parent"))
          "The child reads back as a direct child of the requested parent"))))

;; ============================================================================
;; 2. Parent reference is order-independent: the child op may precede the
;;    parent op in the batch.
;; ============================================================================
(deftest child-before-parent-still-resolves
  (test-helper/load-test-files
   [{:page {:block/title "Issue6 Order Page"}}])
  (let [page-uuid (str (:block/uuid (ldb/get-page (conn/get-db) "Issue6 Order Page")))]
    (import-edn! (build-edn
                  {:operation "add" :entityType "block"
                   :data {:title "child first" :page-id page-uuid
                          :parent-id "P"}}
                  {:operation "add" :entityType "block" :id "P"
                   :data {:title "parent later" :page-id page-uuid}}))
    (let [db (conn/get-db)
          parent (block-by-title db "parent later")
          child (block-by-title db "child first")]
      (is (some? parent) "Parent persisted")
      (is (some? child) "Child persisted")
      (is (= (:db/id parent) (:db/id (:block/parent child)))
          "The child resolves to its parent even when listed before it")
      (is (= ["child first"] (child-titles db "Issue6 Order Page" "parent later"))
          "Child reads back under its parent"))))

;; ============================================================================
;; 3. Existing parent with existing siblings: the new child is appended after
;;    the existing children.
;; ============================================================================
(deftest parented-add-under-existing-parent-appends-after-siblings
  (test-helper/load-test-files
   [{:page {:block/title "Issue6 Existing Parent Page"}
     :blocks [{:block/title "anchor"
               :build/children [{:block/title "child one"}
                                {:block/title "child two"}]}]}])
  (let [db (conn/get-db)
        anchor (block-by-title db "anchor")
        anchor-uuid (str (:block/uuid anchor))
        page-uuid (str (:block/uuid (:block/page anchor)))]
    (import-edn! (build-edn
                  {:operation "add" :entityType "block"
                   :data {:title "appended child" :page-id page-uuid
                          :parent-id anchor-uuid}}))
    (let [db-after (conn/get-db)]
      (is (= ["child one" "child two" "appended child"]
             (child-titles db-after "Issue6 Existing Parent Page" "anchor"))
          "The new child is appended after the existing siblings in deterministic order"))))

;; ============================================================================
;; 4. Multi-level tree in a single call: parent -> child -> grandchild, via
;;    temp ids, with deterministic sibling order at every level.
;; ============================================================================
(deftest multi-level-parented-tree-in-one-call
  (test-helper/load-test-files
   [{:page {:block/title "Issue6 Deep Page"}}])
  (let [page-uuid (str (:block/uuid (ldb/get-page (conn/get-db) "Issue6 Deep Page")))]
    (import-edn! (build-edn
                  {:operation "add" :entityType "block" :id "P"
                   :data {:title "root" :page-id page-uuid}}
                  {:operation "add" :entityType "block" :id "C"
                   :data {:title "mid" :page-id page-uuid :parent-id "P"}}
                  {:operation "add" :entityType "block"
                   :data {:title "leaf" :page-id page-uuid :parent-id "C"}}
                  {:operation "add" :entityType "block"
                   :data {:title "mid sibling" :page-id page-uuid :parent-id "P"}}))
    (let [db (conn/get-db)]
      (is (= ["root"] (mapv :block/title (page-block-tree db "Issue6 Deep Page")))
          "Only the root lands at top level")
      (is (= ["mid" "mid sibling"] (child-titles db "Issue6 Deep Page" "root"))
          "Level-2 siblings preserve batch order")
      (is (= ["leaf"] (child-titles db "Issue6 Deep Page" "mid"))
          "The grandchild lands under the intermediate block"))))

;; ============================================================================
;; 5. Legacy new-page batch: parent + child on a page created in the same call,
;;    via the temp page id and the temp parent id.
;; ============================================================================
(deftest parented-add-on-new-page-in-same-batch
  (import-edn! (build-edn
                {:operation "add" :entityType "page" :id "np"
                 :data {:title "Issue6 Fresh Page"}}
                {:operation "add" :entityType "block" :id "p1"
                 :data {:title "fresh parent" :page-id "np"}}
                {:operation "add" :entityType "block"
                 :data {:title "fresh child" :page-id "np" :parent-id "p1"}}))
  (let [db (conn/get-db)]
    (is (some? (ldb/get-page db "Issue6 Fresh Page")) "The new page is created")
    (let [parent (block-by-title db "fresh parent")
          child (block-by-title db "fresh child")]
      (is (some? parent) "Parent persisted")
      (is (some? child) "Child persisted")
      (is (= (:db/id parent) (:db/id (:block/parent child)))
          "The child resolves to its parent on the new page")
      (is (= ["fresh child"] (child-titles db "Issue6 Fresh Page" "fresh parent"))))))

;; ============================================================================
;; 6. Invalid parents are rejected BEFORE any transaction: missing, duplicate,
;;    cross-page, tag/property pseudochild, hidden, recycled. No DB change.
;; ============================================================================
(deftest invalid-parent-refs-rejected-without-db-change
  (test-helper/load-test-files
   [{:page {:block/title "Issue6 Invalid Page"}
     :blocks [{:block/title "anchor block"}]}
    {:page {:block/title "Issue6 Other Page"}
     :blocks [{:block/title "other page block"}]}])
  (let [db (conn/get-db)
        page (ldb/get-page db "Issue6 Invalid Page")
        page-uuid (str (:block/uuid page))
        anchor (block-by-title db "anchor block")
        anchor-uuid (str (:block/uuid anchor))
        other-block (block-by-title db "other page block")
        other-block-uuid (str (:block/uuid other-block))
        blocks-before (count (ldb/get-page-blocks db (:db/id page)))
        tag-uuid (str (:block/uuid (d/entity db :logseq.class/Page)))
        property-uuid (str (:block/uuid (d/entity db :block/alias)))]
    ;; 6a. Missing temp id (no such op in the batch)
    (expect-throw #"parent"
      [{:operation "add" :entityType "block"
        :data {:title "c" :page-id page-uuid :parent-id "ghost"}}])
    ;; 6b. Duplicate temp id: two block ops share id "dup"
    (expect-throw #"parent"
      [{:operation "add" :entityType "block" :id "dup"
        :data {:title "a" :page-id page-uuid}}
       {:operation "add" :entityType "block" :id "dup"
        :data {:title "b" :page-id page-uuid}}
       {:operation "add" :entityType "block"
        :data {:title "c" :page-id page-uuid :parent-id "dup"}}])
    ;; 6c. Cross-page: parent is an existing block on a different page
    (expect-throw #"parent"
      [{:operation "add" :entityType "block"
        :data {:title "c" :page-id page-uuid :parent-id other-block-uuid}}])
    ;; 6d. Tag as parent
    (expect-throw #"parent"
      [{:operation "add" :entityType "block"
        :data {:title "c" :page-id page-uuid :parent-id tag-uuid}}])
    ;; 6e. Property as parent
    (expect-throw #"parent"
      [{:operation "add" :entityType "block"
        :data {:title "c" :page-id page-uuid :parent-id property-uuid}}])
    ;; 6f. Hidden parent
    (conn/transact! nil [[:db/add (:db/id anchor) :logseq.property/hide? true]])
    (expect-throw #"parent"
      [{:operation "add" :entityType "block"
        :data {:title "c" :page-id page-uuid :parent-id anchor-uuid}}])
    (let [db (conn/get-db)]
      (is (= blocks-before (count (ldb/get-page-blocks db (:db/id page))))
          "No invalid parented add reached the db")))

  ;; 6g. Recycled parent (separate fixture to keep the main db clean)
  (test-helper/load-test-files
   [{:page {:block/title "Issue6 Recycled Parent Page"}
     :blocks [{:block/title "rp block"}]}])
  (let [db (conn/get-db)
        rp-page (ldb/get-page db "Issue6 Recycled Parent Page")
        rp-block (block-by-title db "rp block")
        rp-page-uuid (str (:block/uuid rp-page))
        rp-block-uuid (str (:block/uuid rp-block))]
    (conn/transact! nil [[:db/add (:db/id rp-page) :logseq.property/deleted-at 1]])
    (expect-throw #"parent"
      [{:operation "add" :entityType "block"
        :data {:title "c" :page-id rp-page-uuid :parent-id rp-block-uuid}}])))

;; ============================================================================
;; 7. Self-reference and a batch cycle are rejected before any transaction.
;; ============================================================================
(deftest self-reference-and-ancestor-cycle-rejected
  (test-helper/load-test-files
   [{:page {:block/title "Issue6 Cycle Page"}
     :blocks [{:block/title "cycle anchor"}]}])
  (let [db (conn/get-db)
        page-uuid (str (:block/uuid (ldb/get-page db "Issue6 Cycle Page")))]
    ;; 7a. A block lists itself as parent (temp id self-ref)
    (expect-throw #"parent"
      [{:operation "add" :entityType "block" :id "selfy"
        :data {:title "selfy" :page-id page-uuid :parent-id "selfy"}}])
    ;; 7b. A real batch cycle: X -> Y -> X.
    (expect-throw #"parent"
      [{:operation "add" :entityType "block" :id "X"
        :data {:title "cycle X" :page-id page-uuid :parent-id "Y"}}
       {:operation "add" :entityType "block" :id "Y"
        :data {:title "anchor under X" :page-id page-uuid :parent-id "X"}}])))

;; ============================================================================
;; 8. EDN-shape guard (no db write): the temp-parent case encodes the child via
;;    :build/children, and an existing-parent case encodes an explicit
;;    :block/parent reference.
;; ============================================================================
(deftest build-edn-encodes-parented-structure
  (test-helper/load-test-files
   [{:page {:block/title "Issue6 Shape Page"}
     :blocks [{:block/title "shape anchor"}]}])
  (let [db (conn/get-db)
        page-uuid (str (:block/uuid (ldb/get-page db "Issue6 Shape Page")))
        anchor-uuid (str (:block/uuid (block-by-title db "shape anchor")))]
    (let [edn (build-edn
               {:operation "add" :entityType "block" :id "p1"
                :data {:title "shape parent" :page-id page-uuid}}
               {:operation "add" :entityType "block"
                :data {:title "shape child" :page-id page-uuid :parent-id "p1"}})]
      (is (some? (:pages-and-blocks edn)) "pages-and-blocks present")
      (let [group (first (:pages-and-blocks edn))
            blocks (:blocks group)]
        (is (= 2 (count (mapcat (fn [b] (cons b (:build/children b))) blocks)))
            "Both the parent and the child are represented")
        (is (some #(and (= "shape parent" (:block/title %))
                        (seq (:build/children %))) blocks)
            "The temp parent encodes its child via :build/children")))
    (let [edn (build-edn
               {:operation "add" :entityType "block"
                :data {:title "shape child 2" :page-id page-uuid
                       :parent-id anchor-uuid}})
          blocks (get-in edn [:pages-and-blocks 0 :blocks])
          child (first blocks)]
        (is (= "shape child 2" (:block/title child)))
        (is (= {:db/id [:block/uuid (uuid anchor-uuid)]} (:block/parent child))
            "The existing-parent case encodes an explicit :block/parent reference"))))


(deftest receipt-correlates-temporary-and-existing-parents
  (async done
    (test-helper/load-test-files
     [{:page {:block/title "Issue6 Receipt Page"}
       :blocks [{:block/title "receipt anchor"}]}])
    (let [anchor (block-by-title (conn/get-db) "receipt anchor")
          anchor-id (str (:block/uuid anchor))
          page-id (str (:block/uuid (:block/page anchor)))]
      (-> (api-test/with-plugin-api
           (fn []
             (p/let [result (cli-api/upsert-nodes
                             (clj->js [{:operation "add" :entityType "block" :id "child"
                                        :data {:title "receipt child" :page-id page-id :parent-id "parent"}}
                                       {:operation "add" :entityType "block" :id "parent"
                                        :data {:title "receipt parent" :page-id page-id :parent-id anchor-id}}])
                             #js {:receipt true})
                     receipt (js->clj result :keywordize-keys true)
                     [child-op parent-op] (:operations receipt)
                     child (api-tools/get-block (conn/get-db) (:uuid child-op) {})
                     parent (api-tools/get-block (conn/get-db) (:uuid parent-op) {})]
               (is (= "verified" (:mode receipt)))
               (is (= (:uuid parent-op) (:block/parent child)))
               (is (= anchor-id (:block/parent parent)))
               (is (= ["child" "parent"] (mapv :op-id (:operations receipt)))))))
          (p/catch (fn [error] (is false (str error))))
          (p/finally done)))))

(deftest parented-receipt-dry-run-does-not-transact
  (async done
    (test-helper/load-test-files [{:page {:block/title "Issue6 Dry Run"}}])
    (let [page-id (str (:block/uuid (ldb/get-page (conn/get-db) "Issue6 Dry Run")))
          before (vec (d/datoms (conn/get-db) :eavt))]
      (-> (api-test/with-plugin-api
           (fn []
             (p/let [result (cli-api/upsert-nodes
                             (clj->js [{:operation "add" :entityType "block" :id "parent"
                                        :data {:title "dry parent" :page-id page-id}}
                                       {:operation "add" :entityType "block"
                                        :data {:title "dry child" :page-id page-id :parent-id "parent"}}])
                             #js {:receipt true :dry-run true})
                     receipt (js->clj result :keywordize-keys true)]
               (is (= "dry-run" (:mode receipt)))
               (is (= 2 (count (:operations receipt))))
               (is (= before (vec (d/datoms (conn/get-db) :eavt)))))))
          (p/catch (fn [error] (is false (str error))))
          (p/finally done)))))
