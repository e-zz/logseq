(ns frontend.worker.db-listener-mcp-search-test
  "MCP upserts through the real API, batch commit, listener and on-disk SQLite FTS.

  priv contract: an MCP `upsert-nodes` reuses the `:batch-import-edn` op, so the
  transaction carries `::sqlite-export/imported-data?`. What makes its blocks
  searchable is the companion marker `:logseq.outliner.op/runtime-write?`, which
  `db-listener/skip-search-sync?` treats as an incremental runtime write. A
  genuine bulk import carries `imported-data?` WITHOUT `runtime-write?` and is
  therefore skipped. That predicate is transaction-wide, so the skip decision is
  per-transaction, not per-op. The transport is stubbed: the worker round-trip is
  replaced by a direct `api-tools/build-upsert-nodes-edn` call, and the real
  `cli-api/upsert-nodes` caller runs underneath."
  (:require ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as node-path]
            [cljs.test :refer [async deftest is]]
            [datascript.core :as d]
            [frontend.db.transact :as db-transact]
            [frontend.handler.ui :as ui-handler]
            [frontend.state :as state]
            [frontend.worker.db-listener :as db-listener]
            [frontend.worker.handler.search :as search-handler]
            [frontend.worker.platform.node :as platform-node]
            [frontend.worker.search :as search]
            [frontend.worker.state :as worker-state]
            [frontend.worker.sync :as db-sync]
            [logseq.api.db-based.cli :as cli-api]
            [logseq.api.db-based.tools :as api-tools]
            [logseq.db :as ldb]
            [logseq.db.frontend.schema :as db-schema]
            [logseq.db.sqlite.create-graph :as sqlite-create-graph]
            [logseq.db.sqlite.export :as sqlite-export]
            [logseq.outliner.op :as outliner-op]
            [promesa.core :as p]))

(def ^:private test-repo "issue11-mcp-search-test-repo")
(def ^:private seed-page-id #uuid "00000000-0000-0000-0000-000000000001")

(defn- make-conn
  []
  (let [conn (d/create-conn db-schema/schema)]
    (d/transact! conn (sqlite-create-graph/build-db-initial-data "{}"))
    (d/transact! conn [{:block/title "MCP Seed Page"
                       :block/name "mcp seed page"
                       :block/uuid seed-page-id
                       :block/created-at 1
                       :block/updated-at 1
                       :block/tags :logseq.class/Page}])
    conn))

(defn- add-block-operation
  [title]
  {:operation "add"
   :entityType "block"
   :data {:title title :page-id (str seed-page-id)}})

(defn- import-block-op
  "The :batch-import-edn op itself — an import whose tx-meta comes from the op's
  own options, not from a :transact wrapper. A bulk import takes this shape."
  [db title import-opts]
  [:batch-import-edn [(api-tools/build-upsert-nodes-edn db [(add-block-operation title)])
                      import-opts]])

(defn- runtime-write-op
  "The op shape `cli-api/upsert-nodes` actually emits. `ui-outliner-tx/transact!`
  takes tx-meta as its first argument and collects the import op into
  `*outliner-ops*`; `apply-ops!` later turns the collected op into tx-data. So the
  runtime-write marker lives in the tx-meta passed alongside the op — this is what
  reaches the committed transaction and keeps search in sync."
  [db title]
  [(import-block-op db title {:validate-scope :tx})
   {:outliner-op :batch-import-edn
    :logseq.outliner.op/runtime-write? true}])

(defn- committed-tx-metas!
  "Listen on the original conn, not the temporary batch conn."
  [conn f]
  (let [metas (atom [])]
    (d/listen! conn ::committed-tx #(swap! metas conj (:tx-meta %)))
    (try
      (f)
      @metas
      (finally (d/unlisten! conn ::committed-tx)))))

(defn- <upsert-nodes
  "Run the real caller and op construction, round-tripping the worker payload.
  No MCP/import metadata is supplied by this transport stub."
  [conn operations options]
  (p/with-redefs [state/get-current-repo (constantly test-repo)
                 state/get-editor-info (constantly nil)
                 state/*editor-info (atom nil)
                 state/<invoke-db-worker
                 (fn [api _repo ops]
                   (assert (= :thread-api/api-build-upsert-nodes-edn api))
                   (p/resolved (api-tools/build-upsert-nodes-edn @conn ops)))
                 db-transact/apply-outliner-ops
                 (fn [_conn ops opts]
                   (let [[ops opts] (-> [ops opts] ldb/write-transit-str ldb/read-transit-str)]
                     (p/resolved (outliner-op/apply-ops! conn ops opts))))
                 ui-handler/re-render-root! (fn [& _] nil)]
    (cli-api/upsert-nodes (clj->js operations) (clj->js options))))

(defn- <with-search-db
  "Use the production Node SQLite adapter, DDL, triggers and search handlers.
  Close all handles; leave disposable files in the temporary directory."
  [conn f]
  (p/let [root-dir (fs/mkdtempSync (node-path/join (os/tmpdir) "issue11-mcp-search-"))
          node-platform (platform-node/node-platform {:root-dir root-dir})
          db-path (node-path/join root-dir "search-db.sqlite")
          open-db! #((get-in node-platform [:sqlite :open-db]) {:path db-path})
          close-db! (get-in node-platform [:sqlite :close-db])
          db (open-db!)]
    (search/create-tables-and-triggers! db)
    (-> (p/with-redefs [worker-state/get-datascript-conn (constantly conn)
                       worker-state/get-sqlite-conn (fn [_repo kind]
                                                    (assert (= :search kind))
                                                    db)
                       worker-state/get-vector-index (constantly nil)
                       db-sync/update-local-sync-checksum! (fn [& _] nil)]
          (db-listener/listen-db-changes! test-repo conn :handler-keys [:search])
          (f db open-db! close-db!))
        (p/finally (fn []
                     (d/unlisten! conn :frontend.worker.db-listener/listen-db-changes!)
                     (close-db! db))))))

(defn- fts-hit-ids
  [db query]
  (mapv #(aget % "id")
        (array-seq (.exec db #js {:sql "select id from blocks_fts where blocks_fts match ?"
                                 :bind #js [query]
                                 :rowMode "object"}))))

(defn- block-by-title
  [db title]
  (d/entity db (d/q '[:find ?e . :in $ ?title :where [?e :block/title ?title]] db title)))

(deftest mcp-upsert-marker-reaches-final-transaction
  (let [conn (make-conn)
        [op tx-meta] (runtime-write-op @conn "Marker Unit Block")
        metas (committed-tx-metas!
               conn
               #(outliner-op/apply-ops! conn [op] tx-meta))
        final (first metas)]
    (is (= 1 (count metas)) (pr-str metas))
    ;; The runtime-write marker is what makes the search listener index this tx.
    (is (true? (:logseq.outliner.op/runtime-write? final)) (pr-str final))
    ;; The write reuses the import op, so it also carries the import marker.
    (is (true? (::sqlite-export/imported-data? final)) (pr-str final))
    (is (some? (:block/uuid (block-by-title @conn "Marker Unit Block"))))))

(deftest genuine-import-keeps-imported-data-flag
  (let [conn (make-conn)
        metas (committed-tx-metas!
               conn
               #(outliner-op/apply-ops!
                 conn [(import-block-op @conn "Genuine Import Block" {:validate-scope :tx})]
                 {:outliner-op :batch-import-edn}))
        final (first metas)]
    (is (= 1 (count metas)) (pr-str metas))
    (is (true? (::sqlite-export/imported-data? final)) (pr-str final))
    ;; A genuine bulk import must NOT look like an incremental runtime write,
    ;; otherwise search would index an entire imported graph.
    (is (not (:logseq.outliner.op/runtime-write? final)) (pr-str final))
    (is (some? (:block/uuid (block-by-title @conn "Genuine Import Block"))))))

(deftest mixed-batch-imports-when-transaction-carries-runtime-write
  (let [conn (make-conn)
        [op tx-meta] (runtime-write-op @conn "Small Upsert Block")
        metas (committed-tx-metas!
               conn
               #(outliner-op/apply-ops!
                 conn [op (import-block-op @conn "Full Import Block" {})]
                 tx-meta))]
    (is (= 1 (count metas)))
    (is (true? (::sqlite-export/imported-data? (first metas))) (pr-str metas))
    ;; `skip-search-sync?` is transaction-wide (db_listener.cljs:174-179): the
    ;; runtime-write marker suppresses the import skip for the WHOLE transaction,
    ;; so bulk-import ops batched with a runtime write are indexed too.
    (is (false? (#'db-listener/skip-search-sync? (first metas))) (pr-str metas))))

(deftest mcp-upsert-created-block-is-searchable
  (async done
    (let [conn (make-conn)
          title "Astronomyquasar created block"
          committed (atom [])
          sentinel-id "00000000-0000-0000-0000-000000000002"]
      (-> (<with-search-db
           conn
           (fn [db open-db! close-db!]
             ;; An index-only sentinel detects a truncation/full rebuild.
             (search/upsert-blocks! db #js [#js {:id sentinel-id
                                               :page (str seed-page-id)
                                               :title "Untouched sentinel"}])
             (d/listen! conn ::api-commit #(swap! committed conj (:tx-meta %)))
             (p/let [_ (<upsert-nodes conn [(add-block-operation title)] {})
                     _ (p/delay 0)
                     block (block-by-title @conn title)
                     block-id (str (:block/uuid block))]
               (is (some? (:block/uuid block)) "Block must persist, not just exist in the index")
               (is (= seed-page-id (get-in block [:block/page :block/uuid])))
               (is (= 1 (count @committed)))
               (is (true? (:logseq.outliner.op/runtime-write? (first @committed))) (pr-str @committed))
               (is (= [block-id] (fts-hit-ids db "Astronomyquasar")))
               (is (= [(:block/uuid block)]
                      (mapv :block/uuid (search-handler/search-blocks test-repo "Astronomyquasar" {}))))
               (is (= [sentinel-id] (fts-hit-ids db "Untouched")) "No full index rebuild")
               (p/let [reopened (open-db!)]
                 (try
                   (is (= [block-id] (fts-hit-ids reopened "Astronomyquasar"))
                       "A new SQLite handle reads the committed FTS row")
                   (finally (close-db! reopened)))))))
          (p/catch (fn [error] (is false (str "unexpected error: " error))))
          (p/finally (fn [] (d/unlisten! conn ::api-commit) (done)))))))

(deftest mcp-upsert-retitles-the-same-block
  (async done
    (let [conn (make-conn)
          before "Astronomyquasar original block"
          after "Biochemistryenzyme retitled block"]
      (-> (p/let [_ (<upsert-nodes conn [{:operation "add"
                                          :entityType "block"
                                          :data {:title before
                                                 :page-id (str seed-page-id)}}] {})]
            (let [block-id (:block/uuid (block-by-title @conn before))]
              (<with-search-db
               conn
               (fn [db _open-db! _close-db!]
                 ;; Seed the old index once, before the edit under test.
                 (search/upsert-blocks! db (clj->js (search/build-blocks-indice @conn)))
                 (is (= [(str block-id)] (fts-hit-ids db "Astronomyquasar")))
                 (p/let [_ (<upsert-nodes conn [{:operation "edit"
                                                 :entityType "block"
                                                 :id (str block-id)
                                                 :data {:title after}}] {})
                         _ (p/delay 0)]
                   (is (= after (:block/title (d/entity @conn [:block/uuid block-id]))))
                   ;; The edit must not append a block: the seeded page's block set
                   ;; stays exactly `{block-id}`. A re-find by title would miss a
                   ;; duplicate.
                   (is (= [block-id]
                          (->> (d/q '[:find [?u ...]
                                      :in $ ?page-uuid
                                      :where [?p :block/uuid ?page-uuid]
                                             [?b :block/uuid ?u]
                                             [?b :block/page ?p]]
                                    @conn seed-page-id)
                               sort
                               vec))
                       "Edit must not add a second block")
                   (is (empty? (fts-hit-ids db "Astronomyquasar")) "Old title must leave the FTS index")
                   (is (= [(str block-id)] (fts-hit-ids db "Biochemistryenzyme")))
                   (is (= [block-id]
                          (mapv :block/uuid
                                (search-handler/search-blocks test-repo "Biochemistryenzyme" {})))))))))
          (p/catch (fn [error] (is false (str "unexpected error: " error))))
          (p/finally done)))))

(deftest genuine-import-block-is-not-indexed
  (async done
    (let [conn (make-conn)
          title "Genuineimport unindexed block"]
      (-> (<with-search-db
           conn
           (fn [db _open-db! _close-db!]
             (outliner-op/apply-ops!
              conn [(import-block-op @conn title {:validate-scope :tx})] {})
             (p/let [_ (p/delay 0)]
               (is (some? (:block/uuid (block-by-title @conn title))))
               (is (empty? (fts-hit-ids db "Genuineimport"))))))
          (p/catch (fn [error] (is false (str "unexpected error: " error))))
          (p/finally done)))))

(deftest mixed-batch-indexing-follows-the-transaction-marker
  ;; Behavioural counterpart to `mixed-batch-imports-when-transaction-carries-runtime-write`:
  ;; the skip decision is transaction-wide, so bulk-import ops batched with a
  ;; runtime write ARE indexed, even though a standalone bulk import is not
  ;; (see `genuine-import-block-is-not-indexed`).
  (async done
    (let [conn (make-conn)
          [op tx-meta] (runtime-write-op @conn "Mixedupsert runtime block")]
      (-> (<with-search-db
           conn
           (fn [db _open-db! _close-db!]
             (outliner-op/apply-ops!
              conn [op (import-block-op @conn "Mixedimport bulk block" {:validate-scope :tx})]
              tx-meta)
             (p/let [_ (p/delay 0)]
               (is (some? (:block/uuid (block-by-title @conn "Mixedupsert runtime block"))))
               (is (some? (:block/uuid (block-by-title @conn "Mixedimport bulk block"))))
               (is (= 1 (count (fts-hit-ids db "Mixedupsert"))))
               ;; Indexed despite being a bulk import op: the tx carries runtime-write?.
               (is (= 1 (count (fts-hit-ids db "Mixedimport")))))))
          (p/catch (fn [error] (is false (str "unexpected error: " error))))
          (p/finally done)))))
