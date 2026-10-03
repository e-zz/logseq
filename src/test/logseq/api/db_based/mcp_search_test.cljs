(ns logseq.api.db-based.mcp-search-test
  "Regression for MCP search consistency: a runtime `upsert-nodes` call must emit
   transaction metadata that the worker search listener treats as an incremental
   runtime write instead of a skipped bulk import."
  (:require [cljs.test :refer [async deftest is use-fixtures]]
            [datascript.core :as d]
            [frontend.db.conn :as conn]
            [frontend.test.helper :as test-helper]
            [logseq.api.db-based.cli :as cli-api]
            [logseq.api.test-helper :as api-test]
            [logseq.db :as ldb]
            [logseq.db.sqlite.export :as sqlite-export]
            [promesa.core :as p]))

(use-fixtures :each {:before api-test/start-plugin-api-db!
                     :after api-test/destroy-plugin-api-db!})

(defn- capture-batch-import-tx-meta!
  [conn captured]
  (d/listen! conn ::capture-batch-import-tx
             (fn [{:keys [tx-meta]}]
               (when (= :batch-import-edn (:outliner-op tx-meta))
                 (swap! captured conj tx-meta)))))

(deftest upsert-nodes-emits-search-syncable-runtime-write-test
  (async done
    (test-helper/load-test-files
     [{:page {:block/title "MCP Search Page"}}])
    (let [page-id (str (:block/uuid (ldb/get-page (conn/get-db) "MCP Search Page")))
          conn (conn/get-db nil false)
          captured (atom [])]
      (capture-batch-import-tx-meta! conn captured)
      (-> (api-test/with-plugin-api
           (fn []
             (cli-api/upsert-nodes
              (clj->js [{:operation "add" :entityType "block"
                         :data {:title "searchable via MCP" :page-id page-id}}])
              #js {})))
          (p/then
           (fn [_]
             (let [tx-meta (last @captured)]
               (is (some? tx-meta)
                   "The MCP batch-import-edn transaction was observed on the live conn")
               (is (::sqlite-export/imported-data? tx-meta)
                   "The runtime write still reuses the import op")
               (is (true? (:logseq.outliner.op/runtime-write? tx-meta))
                   "MCP must mark the transaction so the search listener indexes it"))))
          (p/catch (fn [error] (is false (str error))))
          (p/finally
           (fn []
             (d/unlisten! conn ::capture-batch-import-tx)
             (done)))))))
