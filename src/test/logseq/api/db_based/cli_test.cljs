(ns logseq.api.db-based.cli-test
  (:require [cljs.test :refer [async deftest is use-fixtures]]
            [datascript.core :as d]
            [frontend.db.conn :as conn]
            [frontend.state :as state]
            [frontend.test.helper :as test-helper]
            [frontend.db.transact :as db-transact]
            [frontend.handler.search :as search-handler]
            [logseq.api :as api]
            [logseq.api.db-based :as db-based-api]
            [logseq.api.db-based.cli :as cli-api]
            [logseq.api.test-helper :as api-test]
            [logseq.db :as ldb]
            [logseq.db.frontend.validate :as db-validate]
            [promesa.core :as p]))

(use-fixtures :each {:before api-test/start-plugin-api-db!
                     :after api-test/destroy-plugin-api-db!})

(deftest list-endpoints-return-graph-entities
  (async done
    (test-helper/load-test-files
     [{:page {:block/title "Cli Page"}
       :blocks [{:block/title "cli block"}]}])
    (-> (api-test/with-plugin-api
          (fn []
            (p/let [_ (db-based-api/create-tag "CliTag" nil)
                    _ (db-based-api/upsert-property "cli-prop" nil nil)
                    tags (cli-api/list-tags #js {:expand true})
                    properties (cli-api/list-properties #js {})
                    pages (cli-api/list-pages #js {})
                    page-data (cli-api/get-page-data "Cli Page" #js {})
                    missing (cli-api/get-page-data "Missing Page" #js {})
                    tag-titles (set (keep :title (js->clj tags :keywordize-keys true)))
                    property-titles (set (keep :title (js->clj properties :keywordize-keys true)))
                    page-titles (set (keep :title (js->clj pages :keywordize-keys true)))]
              (is (contains? tag-titles "CliTag"))
              (is (contains? property-titles "cli-prop"))
              (is (contains? page-titles "Cli Page"))
              (is (= "Cli Page" (api-test/api-title (aget page-data "entity"))))
              (is (pos? (count (aget page-data "blocks"))))
              (is (some? (aget missing "error"))))))
        (p/catch (fn [error]
                   (is false (str error))))
        (p/finally done))))

(deftest recycled-page-opt-in-plumbing
  (async done
    (test-helper/load-test-files
     [{:page {:block/title "Cli Recycled Page"}
       :blocks [{:block/title "cli recycled block"}]}])
    (let [page (ldb/get-page (conn/get-db) "Cli Recycled Page")
         uuid (str (:block/uuid page))
         listed-uuids (fn [resp]
                        (set (map #(get % "uuid")
                                  (js->clj resp :keywordize-keys false))))]
     (conn/transact! nil [[:db/add (:db/id page) :logseq.property/deleted-at 1]])
     (-> (api-test/with-plugin-api
           (fn []
             (p/let [default-by-name (cli-api/get-page-data "Cli Recycled Page" #js {})
                     default-by-uuid (cli-api/get-page-data uuid #js {})
                     opt-in-by-uuid (cli-api/get-page-data uuid #js {:include-recycled? true})
                     default-list (cli-api/list-pages #js {})
                     opt-in-list (cli-api/list-pages #js {:include-recycled? true})]
               (is (some? (aget default-by-name "error"))
                   "Recycled page by name is not returned through the CLI option plumbing")
               (is (some? (aget default-by-uuid "error"))
                   "Recycled page by uuid is not returned through the CLI option plumbing")
               (is (= "Cli Recycled Page" (api-test/api-title (aget opt-in-by-uuid "entity")))
                   "includeRecycled plumbing reaches the worker for getPage")
                (is (not (contains? (listed-uuids default-list) uuid)))
                (is (contains? (listed-uuids opt-in-list) uuid)
                    "includeRecycled plumbing reaches the worker for listPages")
                (is (some (fn [p] (some? (get p "deleted-at")))
                          (js->clj opt-in-list :keywordize-keys false))
                    "Recycled list entries carry the marker through MCP/CLI JSON")
               (is (some? (get-in (js->clj opt-in-by-uuid :keywordize-keys true)
                                  [:entity :deleted-at]))
                   "The recycled marker survives MCP/CLI JSON so callers can detect it"))))
          (p/catch (fn [error]
                     (is false (str error))))
          (p/finally done)))))

(deftest upsert-nodes-dry-run-summarizes-operations
  (async done
    (-> (api-test/with-plugin-api
          (fn []
            (p/let [summary (cli-api/upsert-nodes
                             #js [#js {:operation "add"
                                       :entityType "page"
                                       :id "p1"
                                       :data #js {:title "Upserted Page"}}
                                  #js {:operation "add"
                                       :entityType "block"
                                       :data #js {:title "Upserted Block"
                                                  :page-id "p1"}}]
                             #js {:dry-run true})]
              (is (re-find #"Dry run" summary))
              (is (re-find #"Added" summary)))))
        (p/catch (fn [error]
                   (is false (str error))))
        (p/finally done))))

(deftest import-and-export-require-db-graph
  (with-redefs [state/get-current-repo (constantly "file://notes")]
    (is (thrown-with-msg?
         js/Error
         #"This endpoint must be called on a DB graph"
         (api/import_edn "{}")))
    (is (thrown-with-msg?
         js/Error
         #"This endpoint must be called on a DB graph"
         (api/export_edn #js {})))))

(deftest export-edn-surfaces-worker-error
  (async done
    (-> (api-test/with-plugin-api
          (fn []
            (-> (cli-api/export-edn #js {})
                (p/then (fn [_]
                          (is false "export-edn should fail in unit tests")))
                (p/catch (fn [error]
                           (is (re-find #"Export EDN Error" (str error))))))))
        (p/catch (fn [error]
                   (is false (str error))))
        (p/finally done))))

(deftest exported-get-page-data-accepts-single-argument
  (async done
    (test-helper/load-test-files
     [{:page {:block/title "Arity Page"}
       :blocks [{:block/title "arity block"}]}])
    (-> (api-test/with-plugin-api
          (fn []
            (p/let [page-data (js/logseq.api.get_page_data "Arity Page")]
              (is (= "Arity Page" (api-test/api-title (aget page-data "entity"))))
              (is (pos? (count (aget page-data "blocks")))))))
        (p/catch (fn [error]
                   (is false (str "One-argument public call rejected: " error))))
        (p/finally done))))

(deftest include-children-plumbing-reaches-worker
  (async done
    (test-helper/load-test-files
     [{:page {:block/title "Cli Nested Page"}
       :blocks [{:block/title "cli top"
                 :build/children [{:block/title "cli child"
                                   :build/children [{:block/title "cli grandchild"}]}]}]}])
    (-> (api-test/with-plugin-api
          (fn []
            (p/let [default (cli-api/get-page-data "Cli Nested Page" #js {})
                    opt-in (cli-api/get-page-data "Cli Nested Page"
                                                    #js {:include-children? true :max-blocks 3})
                    under-budget (cli-api/get-page-data "Cli Nested Page"
                                                         #js {:include-children? true :max-blocks 2})
                    default-js (js->clj default :keywordize-keys false)
                    opt-in-js (js->clj opt-in :keywordize-keys false)
                    under-budget-js (js->clj under-budget :keywordize-keys false)
                    nested (get-in opt-in-js ["blocks" 0 "children" 0])]
              (is (true? (get-in default-js ["tree-has-more?"]))
                  "include-children? default must tell the MCP caller the tree is partial")
              (is (= 2 (get-in default-js ["tree-omitted-count"]))
                  "The omitted count must cross the worker boundary")
              (is (= "cli child" (get nested "title"))
                  "include-children? must reach the worker and return nested blocks")
              (is (= "cli grandchild" (get-in nested ["children" 0 "title"]))
                  "Descendants deeper than one level must cross the worker boundary")
              (is (string? (get nested "uuid"))
                  "Nested uuids must be strings after JSON serialization")
              (is (nil? (get-in opt-in-js ["tree-has-more?"]))
                  "A full nested read must not report itself as partial")
              (is (some? (get under-budget-js "error"))
                  "The maxBlocks budget reaches the worker and rejects an incomplete tree")
              (is (nil? (get under-budget-js "blocks"))
                  "An insufficient budget must not return partial blocks"))))
        (p/catch (fn [error]
                   (is false (str error))))
        (p/finally done))))

(deftest get-page-data-nested-tree-is-json-serializable
  (async done
    (test-helper/load-test-files
     [{:page {:block/title "Cli Json Page"}
       :blocks [{:block/title "json top"
                 :build/children [{:block/title "json child"}]}]}])
    (-> (api-test/with-plugin-api
          (fn []
            (p/let [opt-in (cli-api/get-page-data "Cli Json Page"
                                                   #js {:include-children? true :max-blocks 2})
                    json (js/JSON.stringify opt-in)]
              (is (not (re-find #"cljs\$lang\$protocol_mask" json))
                  "The MCP JSON payload must not contain leaked cljs protocol fields")
              (is (re-find #"json child" json)
                  "Nested block content must survive the MCP JSON payload"))))
        (p/catch (fn [error]
                   (is false (str error))))
        (p/finally done))))

(deftest upsert-nodes-returns-opt-in-verified-receipt
  (async done
    (test-helper/load-test-files
     [{:page {:block/title "Receipt Existing Page"}
       :blocks [{:block/title "duplicate title"}
                {:block/title "duplicate title"}]}])
    (let [db (conn/get-db)
          page (ldb/get-page db "Receipt Existing Page")
          page-uuid (str (:block/uuid page))
          edit-block (first (ldb/get-page-blocks db (:db/id page)))
          edit-uuid (str (:block/uuid edit-block))]
      (-> (api-test/with-plugin-api
            (fn []
              (p/let [receipt* (cli-api/upsert-nodes
                                #js [#js {:operation "add"
                                          :entityType "block"
                                          :id "add-one"
                                          :data #js {:title "duplicate title"
                                                     :page-id page-uuid}}
                                     #js {:operation "edit"
                                          :entityType "block"
                                          :id edit-uuid
                                          :data #js {:title "edited duplicate"}}]
                                #js {:receipt true})
                      receipt (js->clj receipt* :keywordize-keys true)
                      added-block (d/entity (conn/get-db)
                                            [:block/uuid (uuid (get-in receipt [:operations 0 :uuid]))])
                      stored-block (d/entity (conn/get-db)
                                             [:block/uuid (uuid (get-in receipt [:operations 1 :uuid]))])]
                (is (map? receipt) "Receipt mode returns a structured value")
                (is (= "verified" (:mode receipt)))
                (is (= 2 (count (:operations receipt))))
                (is (= [0 1] (mapv :index (:operations receipt))))
                (is (not (contains? receipt :db/id)))
                (is (not (contains? (first (:operations receipt)) :db/id)))
                (is (= "add-one" (get-in receipt [:operations 0 :op-id])))
                (is (re-matches #"[0-9a-fA-F-]{36}" (get-in receipt [:operations 0 :uuid])))
                (is (= page-uuid (get-in receipt [:operations 0 :page-uuid])))
                (is (= "duplicate title" (:block/title added-block)))
                (is (= edit-uuid (get-in receipt [:operations 1 :uuid])))
                (is (= page-uuid (get-in receipt [:operations 1 :page-uuid]))
                    "Edit receipt page identity is captured from the pre-mutation block")
                (is (= "edited duplicate" (get-in receipt [:operations 1 :entity :title])))
                (is (= "edited duplicate" (:block/title stored-block))))))
          (p/catch (fn [error]
                     (is false (str error))))
          (p/finally done)))))

(deftest upsert-nodes-receipt-dry-run-does-not-write
  (async done
    (test-helper/load-test-files
     [{:page {:block/title "Receipt Dry Run Page"}
       :blocks [{:block/title "before"}]}])
    (let [page (ldb/get-page (conn/get-db) "Receipt Dry Run Page")
          before-count (count (ldb/get-page-blocks (conn/get-db) (:db/id page)))
          page-uuid (str (:block/uuid page))]
      (-> (api-test/with-plugin-api
            (fn []
              (p/let [result (cli-api/upsert-nodes
                              #js [#js {:operation "add"
                                        :entityType "block"
                                        :data #js {:title "planned only"
                                                   :page-id page-uuid}}]
                              #js {:receipt true :dry-run true})
                      receipt (js->clj result :keywordize-keys true)
                      operation (first (:operations receipt))]
                (is (= "dry-run" (:mode receipt)))
                (is (= "dry-run" (:status operation)))
                (is (re-matches #"[0-9a-fA-F-]{36}" (:planned-uuid operation)))
                (is (= before-count
                       (count (ldb/get-page-blocks (conn/get-db) (:db/id page)))))
                (is (nil? (d/entity (conn/get-db) [:block/uuid (uuid (:planned-uuid operation))]))))))
          (p/catch (fn [error]
                     (is false (str error))))
          (p/finally done)))))

(deftest upsert-nodes-receipt-empty-input-is-explicit-no-op
  (async done
    (-> (api-test/with-plugin-api
          (fn []
            (p/let [result (cli-api/upsert-nodes #js [] #js {:receipt true})
                    receipt (js->clj result :keywordize-keys true)]
              (is (= "no-op" (:mode receipt)))
              (is (= [] (:operations receipt))))))
        (p/catch (fn [error]
                   (is false (str error))))
        (p/finally done))))

(deftest upsert-nodes-receipt-rejects-mixed-operations-before-writing
  (async done
    (test-helper/load-test-files
     [{:page {:block/title "Receipt Mixed Page"}}])
    (let [page (ldb/get-page (conn/get-db) "Receipt Mixed Page")
          page-uuid (str (:block/uuid page))]
      (-> (api-test/with-plugin-api
            (fn []
              (-> (cli-api/upsert-nodes
                   #js [#js {:operation "add"
                             :entityType "block"
                             :data #js {:title "must not be partially added"
                                        :page-id page-uuid}}
                        #js {:operation "add"
                             :entityType "tag"
                             :id "new-tag"
                             :data #js {:title "Unsupported Receipt Tag"}}]
                   #js {:receipt true})
                  (p/then (fn [_]
                            (is false "Mixed receipt operations must reject")))
                  (p/catch (fn [error]
                             (is (re-find #"only add/edit block and page operations" (str error))))))))
          (p/finally
           (fn []
             (is (nil? (some #(= "Unsupported Receipt Tag" (:block/title %))
                             (ldb/get-page-blocks (conn/get-db) (:db/id page)))))
             (is (not-any? #(= "must not be partially added" (:block/title %))
                           (ldb/get-page-blocks (conn/get-db) (:db/id page))))
             (done)))))))

(deftest upsert-nodes-receipt-fails-when-post-transaction-readback-is-missing
  (async done
    (test-helper/load-test-files
     [{:page {:block/title "Receipt Missing Readback Page"}}])
    (let [page (ldb/get-page (conn/get-db) "Receipt Missing Readback Page")
          page-uuid (str (:block/uuid page))]
      (-> (api-test/with-plugin-api
            (fn []
              (p/with-redefs [state/<invoke-db-worker
                              (fn [api & args]
                                (if (= api :thread-api/api-read-upsert-blocks)
                                  (p/resolved [nil])
                                  (apply api-test/<invoke-test-worker api args)))]
                (-> (cli-api/upsert-nodes
                     #js [#js {:operation "add"
                               :entityType "block"
                               :data #js {:title "readback missing"
                                          :page-id page-uuid}}]
                     #js {:receipt true})
                    (p/then (fn [_]
                              (is false "Missing readback must not produce success")))
                    (p/catch (fn [error]
                               (is (re-find #"readback failed" (str error)))))))))
          (p/finally done)))))

(deftest upsert-nodes-receipt-does-not-return-success-on-import-error
  (async done
    (test-helper/load-test-files
     [{:page {:block/title "Receipt Failed Import Page"}}])
    (let [page (ldb/get-page (conn/get-db) "Receipt Failed Import Page")
          page-uuid (str (:block/uuid page))]
      (-> (api-test/with-plugin-api
            (fn []
              (p/with-redefs [db-transact/apply-outliner-ops
                              (fn [& _] (p/resolved {:error "simulated import failure"}))]
                (-> (cli-api/upsert-nodes
                     #js [#js {:operation "add"
                               :entityType "block"
                               :data #js {:title "failed import"
                                          :page-id page-uuid}}]
                     #js {:receipt true})
                    (p/then (fn [_]
                              (is false "Import errors must not return a receipt")))
                    (p/catch (fn [error]
                               (is (re-find #"simulated import failure" (str error)))))))))
          (p/finally done)))))

(deftest upsert-nodes-imports-page
  (async done
    (-> (api-test/with-plugin-api
          (fn []
            (p/let [summary (cli-api/upsert-nodes
                             #js [#js {:operation "add"
                                       :entityType "page"
                                       :id "p1"
                                       :data #js {:title "Imported Cli Page"}}
                                  #js {:operation "add"
                                       :entityType "block"
                                       :data #js {:title "Imported Cli Block"
                                                  :page-id "p1"}}]
                             #js {})
                    page-data (cli-api/get-page-data "Imported Cli Page" #js {})
                    titles (set (keep :title (js->clj (aget page-data "blocks") :keywordize-keys true)))]
              (is (re-find #"Added" summary))
              (is (= "Imported Cli Page" (api-test/api-title (aget page-data "entity"))))
              (is (contains? titles "Imported Cli Block")))))
        (p/catch (fn [error]
                   (is false (str error))))
        (p/finally done))))

(deftest upsert-nodes-uses-tx-scoped-import-validation
  (async done
    (let [calls (atom [])]
      (-> (api-test/with-plugin-api
            (fn []
              (p/with-redefs [db-validate/validate-local-db!
                              (fn [_db & {:as opts}]
                                (swap! calls conj opts)
                                nil)]
                (p/let [summary (cli-api/upsert-nodes
                                 #js [#js {:operation "add"
                                           :entityType "page"
                                           :id "p1"
                                           :data #js {:title "Scoped Page"}}
                                      #js {:operation "add"
                                           :entityType "block"
                                           :data #js {:title "Scoped Block"
                                                      :page-id "p1"}}]
                                 #js {})]
                  (is (re-find #"Added" summary))
                  (is (seq @calls))
                  (is (every? #(seq (:entity-ids %)) @calls))))))
          (p/catch (fn [error]
                     (is false (str error))))
          (p/finally done)))))

(deftest get-block-cli-endpoint-accepts-one-uuid
  (async done
    (test-helper/load-test-files
     [{:page {:block/title "Get Block CLI Page"}
       :blocks [{:block/title "CLI parent"
                 :build/children [{:block/title "CLI target"}]}]}])
    (let [db (conn/get-db)
          block (d/entity db (:e (first (d/datoms db :avet :block/title "CLI target"))))
          expected-parent (str (:block/uuid (:block/parent block)))
          expected-page (str (:block/uuid (:block/page block)))]
      (-> (api-test/with-plugin-api
            (fn []
              (p/let [result (js/logseq.api.get_block_by_uuid (str (:block/uuid block)))
                      parsed (js->clj result :keywordize-keys true)]
                (is (= "CLI target" (:title parsed)))
                (is (= expected-parent (:parent parsed)))
                (is (= expected-page (:page parsed)))
                (is (not (contains? parsed :db/id))))))
          (p/catch (fn [error]
                     (is false (str error))))
          (p/finally done)))))

(deftest app-search-converts-page-uuid-and-limit-options
  (async done
    (let [captured-options (atom nil)
          page-uuid "67e55044-10b1-426f-9247-bb680e5fe0c8"]
      (-> (p/with-redefs [state/get-current-repo (constantly "repo")
                          search-handler/search (fn [_repo _query options]
                                                  (reset! captured-options options)
                                                  (p/resolved {}))]
            (api/search "needle" #js {"page-uuid" page-uuid
                                      "limit" 7}))
          (.then (fn [_]
                   (is (= {:page-uuid page-uuid
                           :limit 7}
                          @captured-options))
                   (done)))
          (.catch (fn [error]
                    (is false (str error))
                    (done)))))))

(defn- entity-by-title
  [db title]
  (d/entity db (:e (first (d/datoms db :avet :block/title title)))))

(defn- tree-titles
  [blocks]
  (set (mapcat (fn [block]
                 (cons (:title block) (tree-titles (:children block))))
               blocks)))

(deftest recycle-restore-get-recycled-block-round-trip
  (async done
    (test-helper/load-test-files
     [{:page {:block/title "Recycle CLI Page"}
       :blocks [{:block/title "keep"
                 :build/children [{:block/title "recycle root"
                                   :build/children [{:block/title "recycle child"}]}]}]}])
    (let [db (conn/get-db)
          root (entity-by-title db "recycle root")
          child (entity-by-title db "recycle child")
          root-uuid (str (:block/uuid root))
          child-uuid (str (:block/uuid child))
          parent-uuid (str (:block/uuid (:block/parent root)))
          page-uuid (str (:block/uuid (:block/page root)))]
      (-> (api-test/with-plugin-api
            (fn []
              (p/let [recycled* (cli-api/recycle-block root-uuid #js {})
                      recycled (js->clj recycled* :keywordize-keys true)
                      _ (is (= "recycle" (:operation recycled)))
                      _ (is (false? (:no-op recycled)))
                      _ (is (= "recycled" (:state recycled)))
                      _ (is (= root-uuid (:root-uuid recycled)))
                      _ (is (= 2 (:affected-count recycled)))
                      _ (is (= #{root-uuid child-uuid} (set (:affected-uuids recycled))))
                      _ (is (number? (:deleted-at recycled)))
                      _ (is (string? (:page-uuid recycled)))
                      hidden* (cli-api/get-block root-uuid #js {})
                      hidden (js->clj hidden* :keywordize-keys true)
                      _ (is (some? (:error hidden))
                            "A recycled root must not be readable through getBlock")
                      fetched* (cli-api/get-recycled-block root-uuid #js {})
                      fetched (js->clj fetched* :keywordize-keys true)
                      _ (is (= "get-recycled" (:operation fetched)))
                      _ (is (= "recycled" (:state fetched)))
                      _ (is (= 2 (:subtree-count fetched)))
                      _ (is (= #{root-uuid child-uuid} (set (:subtree fetched))))
                      _ (is (= parent-uuid (:original-parent-uuid fetched)))
                      _ (is (= page-uuid (:original-page-uuid fetched)))
                      page-after-recycle* (cli-api/get-page-data "Recycle CLI Page"
                                                                  #js {:include-children? true :max-blocks 10})
                      page-after-recycle (js->clj page-after-recycle* :keywordize-keys true)
                      titles-after-recycle (tree-titles (:blocks page-after-recycle))
                      _ (is (not (contains? titles-after-recycle "recycle root"))
                            "A recycled subtree must not remain on its original page")
                      restored* (cli-api/restore-block root-uuid #js {})
                      restored (js->clj restored* :keywordize-keys true)
                      _ (is (= "restore" (:operation restored)))
                      _ (is (= "active" (:state restored)))
                      _ (is (= root-uuid (:root-uuid restored)))
                      _ (is (= page-uuid (:page-uuid restored)))
                      _ (is (= parent-uuid (:parent-uuid restored)))
                      _ (is (= 2 (:affected-count restored)))
                      active* (cli-api/get-block root-uuid #js {})
                      active (js->clj active* :keywordize-keys true)
                      page-after-restore* (cli-api/get-page-data "Recycle CLI Page"
                                                                  #js {:include-children? true :max-blocks 10})
                      page-after-restore (js->clj page-after-restore* :keywordize-keys true)
                      titles-after-restore (tree-titles (:blocks page-after-restore))]
                (is (nil? (:error active))
                    "A restored root must be readable through getBlock again")
                (is (= "recycle root" (:title active)))
                (is (contains? titles-after-restore "recycle root")
                    "A restored subtree must reappear on its original page"))))
          (p/catch (fn [error]
                     (is false (str error))))
          (p/finally done)))))

(deftest recycle-block-repeated-is-noop-preserving-metadata
  (async done
    (test-helper/load-test-files
     [{:page {:block/title "Recycle Noop Page"}
       :blocks [{:block/title "noop root"}]}])
    (let [db (conn/get-db)
          root-uuid (str (:block/uuid (entity-by-title db "noop root")))]
      (-> (api-test/with-plugin-api
            (fn []
              (p/let [_ (cli-api/recycle-block root-uuid #js {})
                      first-read* (cli-api/get-recycled-block root-uuid #js {})
                      first-read (js->clj first-read* :keywordize-keys true)
                      second* (cli-api/recycle-block root-uuid #js {})
                      second-result (js->clj second* :keywordize-keys true)
                      second-read* (cli-api/get-recycled-block root-uuid #js {})
                      second-read (js->clj second-read* :keywordize-keys true)]
                (is (true? (:no-op second-result)))
                (is (= "already-recycled" (:reason second-result)))
                (is (= "recycle" (:operation second-result)))
                (is (= (:deleted-at first-read) (:deleted-at second-result))
                    "A repeated recycle must not rewrite the deleted-at timestamp")
                (is (= (:original-order first-read) (:original-order second-read))
                    "A repeated recycle must not rewrite the original location")
                (is (= (:original-parent-uuid first-read) (:original-parent-uuid second-read)))
                (is (= (:subtree first-read) (:subtree second-read))))))
          (p/catch (fn [error]
                     (is false (str error))))
          (p/finally done)))))

(deftest recycle-block-rejects-ineligible-roots
  (async done
    (test-helper/load-test-files
     [{:page {:block/title "Recycle Reject Page"}
       :blocks [{:block/title "ordinary root"}]}])
    (let [db (conn/get-db)
          page (ldb/get-page db "Recycle Reject Page")
          page-uuid (str (:block/uuid page))
          root-uuid (str (:block/uuid (entity-by-title db "ordinary root")))]
      (-> (api-test/with-plugin-api
            (fn []
              (p/let [page-attempt* (cli-api/recycle-block page-uuid #js {})
                      page-attempt (js->clj page-attempt* :keywordize-keys true)
                      missing* (cli-api/recycle-block "11111111-1111-1111-1111-111111111111" #js {})
                      missing (js->clj missing* :keywordize-keys true)
                      invalid* (cli-api/recycle-block "not-a-uuid" #js {})
                      invalid (js->clj invalid* :keywordize-keys true)
                      active-restore* (cli-api/restore-block root-uuid #js {})
                      active-restore (js->clj active-restore* :keywordize-keys true)]
                (is (some? (:error page-attempt))
                    "A page must be rejected before any write")
                (is (some? (:error missing)))
                (is (some? (:error invalid)))
                (is (some? (:error active-restore))
                    "Restoring an active block is an actionable error")
                (is (nil? (:logseq.property/deleted-at page))
                    "A rejected page recycle must not mutate the page"))))
          (p/catch (fn [error]
                     (is false (str error))))
          (p/finally done)))))

(deftest get-recycled-block-rejects-non-recycled-and-pseudochild-input
  (async done
    (test-helper/load-test-files
     [{:page {:block/title "Get Recycled Reject Page"}
       :blocks [{:block/title "still active"}]}])
    (let [db (conn/get-db)
          active-uuid (str (:block/uuid (entity-by-title db "still active")))
          missing-uuid "22222222-2222-2222-2222-222222222222"]
      (-> (api-test/with-plugin-api
            (fn []
              (p/let [active* (cli-api/get-recycled-block active-uuid #js {})
                      active (js->clj active* :keywordize-keys true)
                      missing* (cli-api/get-recycled-block missing-uuid #js {})
                      missing (js->clj missing* :keywordize-keys true)]
                (is (some? (:error active))
                    "An active block must not be reported as recycled")
                (is (some? (:error missing)))
                (is (not (contains? active :subtree))))))
          (p/catch (fn [error]
                     (is false (str error))))
          (p/finally done)))))
