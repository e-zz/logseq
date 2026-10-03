(ns logseq.outliner.recycle-test
  (:require [cljs.test :refer [deftest is testing]]
            [datascript.core :as d]
            [logseq.common.config :as common-config]
            [logseq.common.util :as common-util]
            [logseq.db :as ldb]
            [logseq.db.test.helper :as db-test]
            [logseq.outliner.op :as outliner-op]
            [logseq.outliner.recycle :as recycle]))

(defn- recycle-page
  [db]
  (ldb/get-built-in-page db common-config/recycle-page-name))

(defn- retract-recycle-page!
  [conn]
  (when-let [page (recycle-page @conn)]
    (d/transact! conn [[:db/retractEntity (:db/id page)]])))

(defn- untag-recycle-page!
  [conn]
  (when-let [page (recycle-page @conn)]
    (d/transact! conn [[:db/retract (:db/id page) :block/tags :logseq.class/Page]])))

(defn- assert-page-recycled-under-tagged-recycle
  [db page-id]
  (let [page (d/entity db page-id)
        recycle (recycle-page db)]
    (is (some? recycle))
    (is (true? (ldb/page? recycle)))
    (is (contains? (set (map :db/ident (:block/tags recycle))) :logseq.class/Page))
    (is (true? (ldb/recycled? page)))
    (is (= (:db/id recycle) (:db/id (:block/parent page))))))

(defn- live-child-orders
  [db parent-id]
  (d/q '[:find [?o ...]
         :in $ ?p
         :where
         [?e :block/parent ?p]
         [?e :block/order ?o]]
       db parent-id))

(defn- insert-live-block!
  "Directly insert a live block under `parent` at `order`. Used by regression
   tests to occupy a recycled root's former sibling slot."
  ([conn parent order title]
   (insert-live-block! conn parent order (random-uuid) title))
  ([conn parent order uuid title]
   (let [now (common-util/time-ms)]
     (d/transact! conn [{:block/uuid uuid
                         :block/title title
                         :block/created-at now
                         :block/updated-at now
                         :block/parent (:db/id parent)
                         :block/page (:db/id parent)
                         :block/order order}])
     uuid)))

(deftest recycle-page-creates-page-tagged-recycle-when-missing
  (let [conn (db-test/create-conn-with-blocks
              [{:page {:block/title "page1"}}])
        page (ldb/get-page @conn "page1")
        page-id (:db/id page)]
    (retract-recycle-page! conn)
    (is (nil? (recycle-page @conn)))
    (ldb/transact! conn (recycle/recycle-page-tx-data @conn page {}) {:outliner-op :delete-page})
    (assert-page-recycled-under-tagged-recycle @conn page-id)))

(deftest recycle-page-repairs-untagged-recycle
  (let [conn (db-test/create-conn-with-blocks
              [{:page {:block/title "page1"}}])
        page (ldb/get-page @conn "page1")
        page-id (:db/id page)
        recycle (recycle-page @conn)]
    (untag-recycle-page! conn)
    (is (some? recycle))
    (is (not (ldb/page? (d/entity @conn (:db/id recycle)))))
    (ldb/transact! conn (recycle/recycle-page-tx-data @conn page {}) {:outliner-op :delete-page})
    (assert-page-recycled-under-tagged-recycle @conn page-id)
    (is (= (:db/id recycle) (:db/id (recycle-page @conn))))))

(deftest restore-recycled-page-removes-recycle-parent
  (let [conn (db-test/create-conn-with-blocks
              [{:page {:block/title "page1"}
                :blocks [{:block/title "b1"}]}])
        page (ldb/get-page @conn "page1")]
    (recycle/recycle-page-tx-data @conn page {})
    (ldb/transact! conn (recycle/recycle-page-tx-data @conn page {}) {:outliner-op :delete-page})
    (recycle/restore! conn (:block/uuid page))
    (let [page' (ldb/get-page @conn "page1")]
      (is (nil? (:block/parent page')))
      (is (nil? (:logseq.property/deleted-at page')))
      (is (nil? (:logseq.property.recycle/original-parent page'))))))

(deftest apply-ops-restore-recycled-page-removes-recycle-parent
  (let [conn (db-test/create-conn-with-blocks
              [{:page {:block/title "page1"}
                :blocks [{:block/title "b1"}]}])
        page (ldb/get-page @conn "page1")
        page-uuid (:block/uuid page)]
    (ldb/transact! conn (recycle/recycle-page-tx-data @conn page {}) {:outliner-op :delete-page})
    (is (true? (ldb/recycled? (d/entity @conn [:block/uuid page-uuid]))))
    (outliner-op/apply-ops! conn [[:restore-recycled [page-uuid]]] {})
    (let [page' (ldb/get-page @conn "page1")]
      (is (nil? (:block/parent page')))
      (is (nil? (:logseq.property/deleted-at page')))
      (is (nil? (:logseq.property.recycle/original-parent page'))))))

(deftest permanently-delete-recycled-page-removes-page-and-descendants
  (let [conn (db-test/create-conn-with-blocks
              [{:page {:block/title "page1"}
                :blocks [{:block/title "b1"}]}])
        page (ldb/get-page @conn "page1")
        block (db-test/find-block-by-content @conn "b1")
        page-uuid (:block/uuid page)
        block-uuid (:block/uuid block)]
    (ldb/transact! conn (recycle/recycle-page-tx-data @conn page {}) {:outliner-op :delete-page})
    (is (true? (ldb/recycled? (d/entity @conn [:block/uuid page-uuid]))))
    (is (true? (recycle/permanently-delete! conn page-uuid)))
    (is (nil? (d/entity @conn [:block/uuid page-uuid])))
    (is (nil? (d/entity @conn [:block/uuid block-uuid])))))

(deftest permanently-delete-recycled-page-removes-blocks-parented-by-page
  (let [conn (db-test/create-conn-with-blocks
              [{:page {:block/title "page1"}}
               {:page {:block/title "page2"}}])
        page1 (ldb/get-page @conn "page1")
        page2 (ldb/get-page @conn "page2")
        block-uuid (random-uuid)
        now (common-util/time-ms)]
    (d/transact! conn [{:block/uuid block-uuid
                        :block/title "parented by page1"
                        :block/created-at now
                        :block/updated-at now
                        :block/parent (:db/id page1)
                        :block/page (:db/id page2)
                        :block/order "a0"}])
    (ldb/transact! conn (recycle/recycle-page-tx-data @conn page1 {}) {:outliner-op :delete-page})
    (is (true? (ldb/recycled? (d/entity @conn (:db/id page1)))))
    (is (true? (recycle/permanently-delete! conn (:block/uuid page1))))
    (is (nil? (d/entity @conn [:block/uuid block-uuid])))))

(deftest permanently-delete-recycled-converted-page-removes-property-value-blocks
  (let [conn (db-test/create-conn-with-blocks
              [{:page {:block/title "outer"}
                :blocks [{:block/title "target"
                          :build/properties {:default "value"}}]}])
        target (db-test/find-block-by-content @conn "target")
        target-id (:db/id target)
        target-uuid (:block/uuid target)
        value-id (d/q '[:find ?value .
                        :in $ ?target
                        :where
                        [?value :block/parent ?target]
                        [?value :logseq.property/created-from-property]]
                      @conn target-id)]
    (d/transact! conn [[:db/retract target-id :block/page]
                       [:db/add target-id :block/name "target"]
                       [:db/add target-id :block/tags :logseq.class/Page]])
    (let [page (d/entity @conn target-id)]
      (ldb/transact! conn (recycle/recycle-page-tx-data @conn page {}) {:outliner-op :delete-page}))
    (is (true? (recycle/permanently-delete! conn target-uuid)))
    (is (nil? (d/entity @conn target-id)))
    (is (nil? (d/entity @conn value-id)))))

(deftest gc-recycled-converted-page-removes-property-value-blocks
  (let [conn (db-test/create-conn-with-blocks
              [{:page {:block/title "outer"}
                :blocks [{:block/title "target"
                          :build/properties {:default "value"}}
                         {:block/title "unrelated"}]}])
        outer (ldb/get-page @conn "outer")
        target (db-test/find-block-by-content @conn "target")
        unrelated (db-test/find-block-by-content @conn "unrelated")
        target-id (:db/id target)
        value-id (d/q '[:find ?value .
                        :in $ ?target
                        :where
                        [?value :block/parent ?target]
                        [?value :logseq.property/created-from-property]]
                      @conn target-id)]
    (d/transact! conn [[:db/retract target-id :block/page]
                       [:db/add target-id :block/name "target"]
                       [:db/add target-id :block/tags :logseq.class/Page]])
    (let [page (d/entity @conn target-id)]
      (ldb/transact! conn
                     (recycle/recycle-page-tx-data @conn page {:now-ms 0})
                     {:outliner-op :delete-page}))
    (is (true? (recycle/gc! conn {:now-ms (* 31 24 3600 1000)})))
    (is (nil? (d/entity @conn target-id)))
    (is (nil? (d/entity @conn value-id)))
    (is (some? (d/entity @conn (:db/id outer))))
    (is (some? (d/entity @conn (:db/id unrelated))))))

(deftest gc-keeps-unexpired-recycled-page
  (let [conn (db-test/create-conn-with-blocks
              [{:page {:block/title "page1"}}])
        page (ldb/get-page @conn "page1")
        page-id (:db/id page)]
    (ldb/transact! conn
                   (recycle/recycle-page-tx-data @conn page {:now-ms 0})
                   {:outliner-op :delete-page})
    (is (nil? (recycle/gc! conn {:now-ms (* 29 24 3600 1000)})))
    (is (some? (d/entity @conn page-id)))))

(deftest apply-ops-permanently-delete-recycled-page-removes-page-and-descendants
  (let [conn (db-test/create-conn-with-blocks
              [{:page {:block/title "page1"}
                :blocks [{:block/title "b1"}]}])
        page (ldb/get-page @conn "page1")
        block (db-test/find-block-by-content @conn "b1")
        page-uuid (:block/uuid page)
        block-uuid (:block/uuid block)]
    (ldb/transact! conn (recycle/recycle-page-tx-data @conn page {}) {:outliner-op :delete-page})
    (is (true? (ldb/recycled? (d/entity @conn [:block/uuid page-uuid]))))
    (outliner-op/apply-ops! conn [[:recycle-delete-permanently [page-uuid]]] {})
    (is (nil? (d/entity @conn [:block/uuid page-uuid])))
    (is (nil? (d/entity @conn [:block/uuid block-uuid])))))

(deftest permanently-delete-recycled-block-removes-subtree-only
  (let [conn (db-test/create-conn-with-blocks
              [{:page {:block/title "page1"}
                :blocks [{:block/title "parent"
                          :build/children [{:block/title "child"}]}]}])
        page (ldb/get-page @conn "page1")
        parent (db-test/find-block-by-content @conn "parent")
        child (db-test/find-block-by-content @conn "child")
        parent-uuid (:block/uuid parent)
        child-uuid (:block/uuid child)]
    (ldb/transact! conn (recycle/recycle-blocks-tx-data @conn [parent] {}) {:outliner-op :delete-blocks})
    (is (true? (ldb/recycled? (d/entity @conn [:block/uuid parent-uuid]))))
    (is (true? (recycle/permanently-delete! conn parent-uuid)))
    (is (some? (d/entity @conn [:block/uuid (:block/uuid page)])))
    (is (nil? (d/entity @conn [:block/uuid parent-uuid])))
    (is (nil? (d/entity @conn [:block/uuid child-uuid])))))

(deftest apply-ops-permanently-delete-recycled-block-removes-subtree-only
  (let [conn (db-test/create-conn-with-blocks
              [{:page {:block/title "page1"}
                :blocks [{:block/title "parent"
                          :build/children [{:block/title "child"}]}]}])
        parent (db-test/find-block-by-content @conn "parent")
        child (db-test/find-block-by-content @conn "child")
        parent-uuid (:block/uuid parent)
        child-uuid (:block/uuid child)]
    (ldb/transact! conn (recycle/recycle-blocks-tx-data @conn [parent] {}) {:outliner-op :delete-blocks})
    (is (true? (ldb/recycled? (d/entity @conn [:block/uuid parent-uuid]))))
    (outliner-op/apply-ops! conn [[:recycle-delete-permanently [parent-uuid]]] {})
    (is (nil? (d/entity @conn [:block/uuid parent-uuid])))
    (is (nil? (d/entity @conn [:block/uuid child-uuid])))))

(deftest permanently-delete-recycled-block-removes-corresponding-view-history
  (let [conn (db-test/create-conn-with-blocks
              [{:page {:block/title "page1"}
                :blocks [{:block/title "target"}]}])
        target (db-test/find-block-by-content @conn "target")
        target-uuid (:block/uuid target)
        view-uuid (random-uuid)
        target-history-uuid (random-uuid)
        view-history-uuid (random-uuid)
        now (common-util/time-ms)
        _ (d/transact! conn [{:block/uuid view-uuid
                              :block/title "target view"
                              :block/created-at now
                              :block/updated-at now
                              :logseq.property/view-for (:db/id target)
                              :logseq.property.view/type :logseq.property.view/type.table
                              :logseq.property.view/feature-type :linked-references}
                             {:block/uuid target-history-uuid
                              :block/created-at now
                              :block/updated-at now
                              :logseq.property.history/block (:db/id target)
                              :logseq.property.history/property (:db/id (d/entity @conn :logseq.property/status))
                              :logseq.property.history/scalar-value "Todo"}
                             {:block/uuid view-history-uuid
                              :block/created-at now
                              :block/updated-at now
                              :logseq.property.history/block [:block/uuid view-uuid]
                              :logseq.property.history/property (:db/id (d/entity @conn :logseq.property/status))
                              :logseq.property.history/scalar-value "List"}])]
    (ldb/transact! conn (recycle/recycle-blocks-tx-data @conn [target] {}) {:outliner-op :delete-blocks})
    (is (true? (recycle/permanently-delete! conn target-uuid)))
    (is (nil? (d/entity @conn [:block/uuid target-uuid])))
    (is (nil? (d/entity @conn [:block/uuid view-uuid])))
    (is (nil? (d/entity @conn [:block/uuid target-history-uuid])))
    (is (nil? (d/entity @conn [:block/uuid view-history-uuid])))))

;; --- restore order regression tests (see 06-recycle-restore-contract.md) ---

(deftest restore-block-reuses-original-order-when-free
  (let [conn (db-test/create-conn-with-blocks
              [{:page {:block/title "page1"}
                :blocks [{:block/title "a"}
                         {:block/title "b"}]}])
        page (ldb/get-page @conn "page1")
        a (db-test/find-block-by-content @conn "a")
        a-uuid (:block/uuid a)
        a-order (:block/order a)]
    (ldb/transact! conn (recycle/recycle-blocks-tx-data @conn [a] {}) {:outliner-op :delete-blocks})
    (is (true? (ldb/recycled? (d/entity @conn [:block/uuid a-uuid]))))
    (recycle/restore! conn a-uuid)
    (let [a' (d/entity @conn [:block/uuid a-uuid])]
      (is (nil? (:logseq.property/deleted-at a')))
      (is (= (:db/id page) (:db/id (:block/parent a'))))
      (is (= a-order (:block/order a'))))))

(deftest restore-block-regenerates-occupied-original-order
  (let [conn (db-test/create-conn-with-blocks
              [{:page {:block/title "page1"}
                :blocks [{:block/title "a"}
                         {:block/title "b"}]}])
        page (ldb/get-page @conn "page1")
        a (db-test/find-block-by-content @conn "a")
        a-uuid (:block/uuid a)
        a-order (:block/order a)]
    (ldb/transact! conn (recycle/recycle-blocks-tx-data @conn [a] {}) {:outliner-op :delete-blocks})
    (let [c-uuid (insert-live-block! conn page a-order "c")]
      (is (= a-order (:block/order (d/entity @conn [:block/uuid c-uuid]))))
      (recycle/restore! conn a-uuid)
      (let [a' (d/entity @conn [:block/uuid a-uuid])
            orders (live-child-orders @conn (:db/id page))]
        (is (nil? (:logseq.property/deleted-at a')))
        (is (not= a-order (:block/order a')))
        (is (= (count orders) (count (distinct orders)))
            "no two live siblings may share a :block/order")
        (is (= a-order (:block/order (d/entity @conn [:block/uuid c-uuid])))
            "existing sibling keeps its order")))))

(deftest restore-occupied-order-legacy-rule-would-have-collided
  ;; Contrast test for the pre-fix restore rule. It is copied as a local value
  ;; expression so the defect is demonstrated without reverting the source edit:
  ;; the legacy rule reused the stored original order unconditionally, while the
  ;; current restore! regenerates a free key at the insertion point.
  (let [conn (db-test/create-conn-with-blocks
              [{:page {:block/title "page1"}
                :blocks [{:block/title "a"}]}])
        page (ldb/get-page @conn "page1")
        a (db-test/find-block-by-content @conn "a")
        a-uuid (:block/uuid a)
        a-order (:block/order a)
        legacy-select-order (fn [original-order] (or original-order :regenerated))]
    (ldb/transact! conn (recycle/recycle-blocks-tx-data @conn [a] {}) {:outliner-op :delete-blocks})
    (insert-live-block! conn page a-order "occupier")
    (is (= a-order (legacy-select-order a-order))
        "legacy rule would have reused the now-occupied original order")
    (recycle/restore! conn a-uuid)
    (is (not= a-order (:block/order (d/entity @conn [:block/uuid a-uuid])))
        "fixed restore! does not reuse the occupied original order")))

(deftest restore-block-regenerates-malformed-original-order
  (doseq [malformed ["" "!!!" "a b"]]
    (let [conn (db-test/create-conn-with-blocks
                [{:page {:block/title "page1"}
                  :blocks [{:block/title "a"}]}])
          a (db-test/find-block-by-content @conn "a")
          a-uuid (:block/uuid a)]
      (ldb/transact! conn (recycle/recycle-blocks-tx-data @conn [a] {}) {:outliner-op :delete-blocks})
      (d/transact! conn [{:db/id (:db/id (d/entity @conn [:block/uuid a-uuid]))
                          :logseq.property.recycle/original-order malformed}])
      (is (= malformed
             (:logseq.property.recycle/original-order (d/entity @conn [:block/uuid a-uuid])))
          (str "fixture set malformed order " (pr-str malformed)))
      (recycle/restore! conn a-uuid)
      (let [order (:block/order (d/entity @conn [:block/uuid a-uuid]))]
        (is (and (string? order)
                 (not (empty? order))
                 (not= malformed order))
            (str "malformed " (pr-str malformed) " must not be reused"))))))

(deftest restore-top-level-page-preserves-order-shape
  (let [conn (db-test/create-conn-with-blocks
              [{:page {:block/title "page1"}
                :blocks [{:block/title "b1"}]}])
        page (ldb/get-page @conn "page1")
        page-uuid (:block/uuid page)
        before-order (:block/order page)]
    (ldb/transact! conn (recycle/recycle-page-tx-data @conn page {}) {:outliner-op :delete-page})
    (recycle/restore! conn page-uuid)
    (let [page' (ldb/get-page @conn "page1")]
      (is (nil? (:logseq.property/deleted-at page')))
      (is (nil? (:block/parent page')))
      (is (= before-order (:block/order page'))
          "page-root restore must not invent an order when there is no parent"))))

(deftest apply-ops-restore-recycled-page-preserves-order-shape
  (let [conn (db-test/create-conn-with-blocks
              [{:page {:block/title "page1"}
                :blocks [{:block/title "b1"}]}])
        page (ldb/get-page @conn "page1")
        page-uuid (:block/uuid page)
        before-order (:block/order page)]
    (ldb/transact! conn (recycle/recycle-page-tx-data @conn page {}) {:outliner-op :delete-page})
    (is (true? (ldb/recycled? (d/entity @conn [:block/uuid page-uuid]))))
    (outliner-op/apply-ops! conn [[:restore-recycled [page-uuid]]] {})
    (let [page' (ldb/get-page @conn "page1")]
      (is (nil? (:block/parent page')))
      (is (nil? (:logseq.property/deleted-at page')))
      (is (= before-order (:block/order page'))
          "GUI page restore must not invent an order when there is no parent"))))

;; --- recycle! eligibility and result tests ---

(deftest recycle-block-moves-subtree-under-recycle-and-records-result
  (let [conn (db-test/create-conn-with-blocks
              [{:page {:block/title "page1"}
                :blocks [{:block/title "parent"
                          :build/children [{:block/title "child"
                                            :build/children [{:block/title "grandchild"}]}]}]}])
        page (ldb/get-page @conn "page1")
        parent (db-test/find-block-by-content @conn "parent")
        child (db-test/find-block-by-content @conn "child")
        grandchild (db-test/find-block-by-content @conn "grandchild")
        root-uuid (:block/uuid parent)
        original-order (:block/order parent)
        result (recycle/recycle! conn root-uuid {})]
    (is (= "recycle" (:operation result)))
    (is (= "recycled" (:state result)))
    (is (false? (:no-op result)))
    (is (= 3 (:affected-count result)))
    (is (= (str root-uuid) (:root-uuid result)))
    (is (= (set (map str [root-uuid (:block/uuid child) (:block/uuid grandchild)]))
           (set (:affected-uuids result))))
    (is (some? (:deleted-at result)))
    (is (= (str (:block/uuid (recycle-page @conn))) (:page-uuid result)))
    (let [root' (d/entity @conn [:block/uuid root-uuid])
          recycle-id (:db/id (recycle-page @conn))]
      (is (true? (ldb/recycled? root')))
      (is (= (:db/id page) (:db/id (:logseq.property.recycle/original-parent root'))))
      (is (= (:db/id page) (:db/id (:logseq.property.recycle/original-page root'))))
      (is (= original-order (:logseq.property.recycle/original-order root')))
      (is (= recycle-id (:db/id (:block/parent root')))))))

(deftest recycle-already-recycled-block-is-noop-preserving-metadata
  (let [conn (db-test/create-conn-with-blocks
              [{:page {:block/title "page1"}
                :blocks [{:block/title "a"}]}])
        a (db-test/find-block-by-content @conn "a")
        a-uuid (:block/uuid a)
        page-id (:db/id (ldb/get-page @conn "page1"))
        first-result (recycle/recycle! conn a-uuid {})
        first' (d/entity @conn [:block/uuid a-uuid])
        deleted-at (:logseq.property/deleted-at first')
        original-order (:logseq.property.recycle/original-order first')
        second-result (recycle/recycle! conn a-uuid {})]
    (is (false? (:no-op first-result)))
    (is (true? (:no-op second-result)))
    (is (= "already-recycled" (:reason second-result)))
    (let [a' (d/entity @conn [:block/uuid a-uuid])]
      (is (= deleted-at (:logseq.property/deleted-at a'))
          "re-recycling must not rewrite :logseq.property/deleted-at")
      (is (= original-order (:logseq.property.recycle/original-order a'))
          "re-recycling must not rewrite the original order")
      (is (= page-id (:db/id (:logseq.property.recycle/original-parent a')))
          "re-recycling must not rewrite the original parent"))))

(deftest recycle-rejects-page-root-and-missing-block
  (let [conn (db-test/create-conn-with-blocks
              [{:page {:block/title "page1"}}])
        page (ldb/get-page @conn "page1")
        page-uuid (:block/uuid page)
        page-result (recycle/recycle! conn page-uuid {})
        missing-uuid (random-uuid)
        missing-result (recycle/recycle! conn missing-uuid {})]
    (is (string? (:error page-result)))
    (is (nil? (:logseq.property/deleted-at (d/entity @conn [:block/uuid page-uuid])))
        "rejected page root must be left untouched")
    (is (string? (:error missing-result)))
    (is (= (str missing-uuid) (:root-uuid missing-result)))))

(deftest recycle-rejects-recycle-page-itself
  (let [conn (db-test/create-conn-with-blocks
              [{:page {:block/title "page1"}
                :blocks [{:block/title "a"}]}])
        _ (recycle/recycle! conn (:block/uuid (db-test/find-block-by-content @conn "a")) {})
        recycle (recycle-page @conn)
        result (recycle/recycle! conn (:block/uuid recycle) {})]
    (is (string? (:error result)))
    (is (nil? (:logseq.property/deleted-at (d/entity @conn [:block/uuid (:block/uuid recycle)]))))))

(deftest restore-block-returns-contract-shaped-result
  (let [conn (db-test/create-conn-with-blocks
              [{:page {:block/title "page1"}
                :blocks [{:block/title "a"}]}])
        page (ldb/get-page @conn "page1")
        a (db-test/find-block-by-content @conn "a")
        a-uuid (:block/uuid a)]
    (recycle/recycle! conn a-uuid {})
    (let [result (recycle/restore! conn a-uuid)]
      (is (= "restore" (:operation result)))
      (is (= "active" (:state result)))
      (is (false? (:no-op result)))
      (is (= (str a-uuid) (:root-uuid result)))
      (is (= 1 (:affected-count result)))
      (is (= [(str a-uuid)] (:affected-uuids result)))
      (is (= (str (:block/uuid page)) (:page-uuid result)))
      (is (= (str (:block/uuid page)) (:parent-uuid result)))
      (is (= "original" (:position result)))
      (is (= "original" (:order result))))))

(deftest restore-active-block-errors
  (let [conn (db-test/create-conn-with-blocks
              [{:page {:block/title "page1"}
                :blocks [{:block/title "a"}]}])
        a (db-test/find-block-by-content @conn "a")
        a-uuid (:block/uuid a)
        result (recycle/restore! conn a-uuid)]
    (is (string? (:error result)))
    (is (nil? (:logseq.property/deleted-at (d/entity @conn [:block/uuid a-uuid]))))))

(deftest apply-ops-recycle-block-moves-subtree-under-recycle
  (let [conn (db-test/create-conn-with-blocks
              [{:page {:block/title "page1"}
                :blocks [{:block/title "parent"
                          :build/children [{:block/title "child"}]}]}])
        parent (db-test/find-block-by-content @conn "parent")
        child (db-test/find-block-by-content @conn "child")
        root-uuid (:block/uuid parent)
        result (outliner-op/apply-ops! conn [[:recycle-blocks [root-uuid {}]]] {})]
    (is (= "recycle" (:operation result)))
    (is (= "recycled" (:state result)))
    (is (= 2 (:affected-count result)))
    (is (true? (ldb/recycled? (d/entity @conn [:block/uuid root-uuid]))))
    (is (true? (ldb/recycled? (d/entity @conn [:block/uuid (:block/uuid child)]))))))

;; --- subtree preflight, invalid-target restore, retention, independent descent ---

(deftest recycle-rejects-subtree-containing-page-before-any-write
  (let [conn (db-test/create-conn-with-blocks
              [{:page {:block/title "page1"}
                :blocks [{:block/title "ordinary root"
                          :build/children [{:block/title "convert me"}]}]}])
        page (ldb/get-page @conn "page1")
        root (db-test/find-block-by-content @conn "ordinary root")
        root-id (:db/id root)
        root-uuid (:block/uuid root)
        target (db-test/find-block-by-content @conn "convert me")
        target-id (:db/id target)]
    (d/transact! conn [[:db/retract target-id :block/page]
                       [:db/add target-id :block/name "convert me"]
                       [:db/add target-id :block/tags :logseq.class/Page]])
    (is (true? (ldb/page? (d/entity @conn target-id)))
        "fixture converts a descendant into a page")
    (let [result (recycle/recycle! conn root-uuid {})]
      (is (string? (:error result)))
      (is (re-find #"contains pages" (:error result)))
      (is (nil? (:logseq.property/deleted-at (d/entity @conn root-id)))
          "a rejected subtree must leave the root untouched")
      (is (nil? (:logseq.property/deleted-at (d/entity @conn target-id)))
          "a rejected subtree must not recycle the page descendant")
      (is (= (:db/id page) (:db/id (:block/parent (d/entity @conn root-id))))
          "the rejected root keeps its original parent"))))

(deftest restore-without-valid-target-errors-and-leaves-recycled-state
  (let [conn (db-test/create-conn-with-blocks
              [{:page {:block/title "doomed page"}
                :blocks [{:block/title "a"}]}])
        page-id (:db/id (ldb/get-page @conn "doomed page"))
        a (db-test/find-block-by-content @conn "a")
        a-uuid (:block/uuid a)
        _ (recycle/recycle! conn a-uuid {})
        recycle-id (:db/id (recycle-page @conn))]
    (d/transact! conn [[:db/retractEntity page-id]])
    (is (nil? (:logseq.property.recycle/original-parent (d/entity @conn [:block/uuid a-uuid])))
        "retracting the page clears the stored original location")
    (let [result (recycle/restore! conn a-uuid)
          a' (d/entity @conn [:block/uuid a-uuid])]
      (is (string? (:error result)))
      (is (some? (:logseq.property/deleted-at a'))
          "a failed restore must keep the block recycled")
      (is (= recycle-id (:db/id (:block/parent a')))
          "a failed restore must leave the block under the Recycle page"))))

(deftest restore-leaves-independently-recycled-descendant-recycled
  (let [conn (db-test/create-conn-with-blocks
              [{:page {:block/title "page1"}
                :blocks [{:block/title "root"
                          :build/children [{:block/title "solo child"}]}]}])
        page (ldb/get-page @conn "page1")
        root (db-test/find-block-by-content @conn "root")
        root-uuid (:block/uuid root)
        child (db-test/find-block-by-content @conn "solo child")
        child-uuid (:block/uuid child)
        child-id (:db/id child)]
    (recycle/recycle! conn child-uuid {})
    (is (true? (ldb/recycled? (d/entity @conn child-id))))
    (let [root-result (recycle/recycle! conn root-uuid {})]
      (is (= 1 (:affected-count root-result))
          "an independently recycled child is no longer part of the root subtree"))
    (recycle/restore! conn root-uuid)
    (let [root' (d/entity @conn [:block/uuid root-uuid])
          child' (d/entity @conn child-id)]
      (is (nil? (:logseq.property/deleted-at root')))
      (is (= (:db/id page) (:db/id (:block/parent root'))))
      (is (true? (ldb/recycled? child'))
          "restoring the ancestor must not resurrect an independently recycled child")
      (is (= root-uuid (:block/uuid (:logseq.property.recycle/original-parent child')))
          "the child keeps its own original location metadata"))))

(deftest gc-retention-boundary-is-deterministic
  (let [day-ms (* 24 3600 1000)
        retention-ms (* 30 day-ms)]
    (testing "just before retention the recycled subtree is retained"
      (let [conn (db-test/create-conn-with-blocks
                  [{:page {:block/title "page1"}
                    :blocks [{:block/title "retained root"
                              :build/children [{:block/title "retained child"}]}]}])
            root (db-test/find-block-by-content @conn "retained root")
            child (db-test/find-block-by-content @conn "retained child")
            root-uuid (:block/uuid root)
            child-uuid (:block/uuid child)]
        (recycle/recycle! conn root-uuid {:now-ms 0})
        (is (nil? (recycle/gc! conn {:now-ms (dec retention-ms)})))
        (is (some? (d/entity @conn [:block/uuid root-uuid])))
        (is (some? (d/entity @conn [:block/uuid child-uuid])))))
    (testing "at retention the recycled subtree is collected"
      (let [conn (db-test/create-conn-with-blocks
                  [{:page {:block/title "page1"}
                    :blocks [{:block/title "collected root"
                              :build/children [{:block/title "collected child"}]}]}])
            root (db-test/find-block-by-content @conn "collected root")
            child (db-test/find-block-by-content @conn "collected child")
            root-uuid (:block/uuid root)
            child-uuid (:block/uuid child)]
        (recycle/recycle! conn root-uuid {:now-ms 0})
        (is (true? (recycle/gc! conn {:now-ms retention-ms})))
        (is (nil? (d/entity @conn [:block/uuid root-uuid])))
        (is (nil? (d/entity @conn [:block/uuid child-uuid])))))))
