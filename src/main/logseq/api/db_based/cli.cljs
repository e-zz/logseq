(ns logseq.api.db-based.cli
  "API fns for CLI"
  (:require [clojure.string :as string]
            [frontend.handler.ui :as ui-handler]
            [frontend.modules.outliner.op :as outliner-op]
            [frontend.modules.outliner.ui :as ui-outliner-tx]
            [frontend.state :as state]
            [logseq.api.db-based.util :as api-util]
            [logseq.common.config :as common-config]
            [logseq.db.sqlite.util :as sqlite-util]
            [promesa.core :as p]))

(defn list-tags
  [options]
  (p/let [resp (state/<invoke-db-worker :thread-api/api-list-tags
                                        (state/get-current-repo)
                                        (js->clj options :keywordize-keys true))]
    (clj->js resp)))

(defn list-properties
  [options]
  (p/let [resp (state/<invoke-db-worker :thread-api/api-list-properties
                                        (state/get-current-repo)
                                        (js->clj options :keywordize-keys true))]
    (clj->js resp)))

(defn list-pages
  [options]
  (p/let [resp (state/<invoke-db-worker :thread-api/api-list-pages
                                        (state/get-current-repo)
                                        (js->clj options :keywordize-keys true))]
    (clj->js resp)))

(defn get-page-data
  "Like get_page_blocks_tree but for API clients. Recycled pages are only returned
   when the :include-recycled? option is true."
  [page-title options*]
  (p/let [options (js->clj options* :keywordize-keys true)
          resp (state/<invoke-db-worker :thread-api/api-get-page-data
                                        (state/get-current-repo)
                                        page-title
                                        options)]
    (if resp
      (clj->js resp)
      #js {:error (str "Page " (pr-str page-title) " not found")})))

(defn get-block
  ([uuid-string]
   (get-block uuid-string #js {}))
  ([uuid-string options*]
   (p/let [options (js->clj options* :keywordize-keys true)
           resp (state/<invoke-db-worker :thread-api/api-get-block
                                         (state/get-current-repo)
                                         uuid-string
                                         options)]
     (clj->js resp))))

(defn- receipt-operation
  [index operation uuid-field uuid status readback]
  (cond-> {:index index
           :operation (:operation operation)
           :entity-type (:entityType operation)
           :status status}
    (and (= "add" (:operation operation))
         (some? (:id operation)))
    (assoc :op-id (:id operation))

    (= :verified status)
    (assoc :uuid (str uuid)
           :page-uuid (:block/page readback)
           :parent-uuid (:block/parent readback)
           :entity {:uuid (:block/uuid readback)
                    :title (:block/title readback)})

    (= :dry-run status)
    (assoc uuid-field (str uuid))))

(defn- upsert-nodes-receipt
  [operations uuids mode readbacks]
  {:mode mode
   :operations
   (mapv (fn [index operation]
           (let [add? (= "add" (:operation operation))
                 uuid (if add? (nth uuids index) (uuid (:id operation)))
                 readback (when (= mode :verified) (nth readbacks index))]
             (receipt-operation index
                                operation
                                (if add? :planned-uuid :uuid)
                                uuid
                                mode
                                readback)))
         (range (count operations))
         operations)})

(defn upsert-nodes
  "Upserts API operations. The default return remains the legacy summary string.

   Pass `{:receipt true}` to opt into a structured receipt. This narrow receipt
   accepts only add/edit block operations on existing pages. A `:verified` receipt
   checks the requested UUID, expected page and parent UUIDs, requested title when present,
   and an ordinary visible block on a non-recycled page using worker DB readback;
   it does not claim SQLite crash durability or search-index freshness. Dry runs
   return `:dry-run` with planned UUIDs and perform no transaction. A post-transaction
   mismatch rejects the call but does not roll back the completed transaction."
  [operations options*]
  (p/let [ops (js->clj operations :keywordize-keys true)
          {:keys [dry-run receipt] :as options} (js->clj options* :keywordize-keys true)
          receipt? (= true receipt)
          receipt-uuids (when receipt?
                          (mapv (fn [op]
                                  (when (and (= "block" (:entityType op))
                                             (= "add" (:operation op)))
                                    (random-uuid)))
                                ops))
          _ (when (and receipt?
                       (not= (count ops) (count receipt-uuids)))
              (throw (ex-info "Invalid receipt UUID mapping" {})))
          edn-data (when-not (and receipt? (empty? ops))
                     (state/<invoke-db-worker :thread-api/api-build-upsert-nodes-edn
                                              (state/get-current-repo)
                                              ops
                                              (cond-> {}
                                                receipt?
                                                (assoc :receipt? true
                                                       :receipt-uuids receipt-uuids))))
          receipt-edit-indices (when receipt?
                                 (keep-indexed (fn [index op]
                                                 (when (= "edit" (:operation op)) index))
                                               ops))
          receipt-edit-readbacks (when (seq receipt-edit-indices)
                                   (state/<invoke-db-worker
                                    :thread-api/api-read-upsert-blocks
                                    (state/get-current-repo)
                                    (mapv (fn [index]
                                            {:uuid (str (uuid (:id (nth ops index))))})
                                          receipt-edit-indices)))
          _ (when-let [failure (some :error receipt-edit-readbacks)]
              (throw (ex-info (str "Cannot create verified upsert receipt before transaction: " failure) {})))
          receipt-edit-by-index (zipmap receipt-edit-indices receipt-edit-readbacks)
          receipt-add-ids (when receipt?
                            (into {} (keep-indexed (fn [index op]
                                                     (when (and (= "add" (:operation op)) (:id op))
                                                       [(:id op) (str (nth receipt-uuids index))]))
                                                   ops)))
          receipt-expectations (when receipt?
                                 (mapv (fn [index op]
                                         (let [add? (= "add" (:operation op))
                                               base {:uuid (if add?
                                                             (str (nth receipt-uuids index))
                                                             (str (uuid (:id op))))}
                                               with-page (if add?
                                                          (assoc base :page-uuid
                                                                 (str (uuid (get-in op [:data :page-id]))))
                                                          (assoc base :page-uuid
                                                                 (get-in receipt-edit-by-index [index :block/page])))
                                               with-parent (if add?
                                                             (let [parent-id (get-in op [:data :parent-id])]
                                                               (assoc with-page :parent-uuid
                                                                      (if parent-id
                                                                        (or (get receipt-add-ids parent-id)
                                                                            (str (uuid parent-id)))
                                                                        (:page-uuid with-page))))
                                                             (assoc with-page :parent-uuid
                                                                    (get-in receipt-edit-by-index [index :block/parent])))
                                               data (:data op)]
                                           (if (contains? data :title)
                                             (assoc with-parent :title (:title data))
                                             with-parent)))
                                       (range (count ops))
                                       ops))
          {:keys [error]} (when-not (or dry-run (and receipt? (empty? ops)))
                            (ui-outliner-tx/transact!
                             {:outliner-op :batch-import-edn}
                             (outliner-op/batch-import-edn!
                              edn-data
                              (cond-> {:validate-scope :tx}
                                receipt? (assoc :build-existing-tx? true)))))]
    (when error (throw (ex-info error {})))
    (cond
      (and receipt? (empty? ops))
      (clj->js {:mode :no-op :operations []})

      (not receipt?)
      (do
        (ui-handler/re-render-root!)
        (api-util/summarize-upsert-operations ops options))

      dry-run
      (clj->js (upsert-nodes-receipt ops receipt-uuids :dry-run nil))

      :else
      (p/let [readbacks (state/<invoke-db-worker :thread-api/api-read-upsert-blocks
                                                 (state/get-current-repo)
                                                 receipt-expectations)]
        (when-not (= (count receipt-expectations) (count readbacks))
          (throw (ex-info "Upsert receipt readback failed; transaction completed but no receipt is available"
                          {:expected-count (count receipt-expectations)
                           :actual-count (count readbacks)})))
        (doseq [[index expected actual] (map vector (range) receipt-expectations readbacks)]
          (when (or (:error actual)
                    (not= (:uuid expected) (:block/uuid actual))
                    (not= (:page-uuid expected) (:block/page actual))
                    (and (contains? expected :parent-uuid)
                         (not= (:parent-uuid expected) (:block/parent actual)))
                    (and (contains? expected :title)
                         (not= (:title expected) (:block/title actual))))
            (throw (ex-info "Upsert receipt readback failed; transaction completed but no receipt is available"
                            {:operation-index index
                             :expected expected
                             :actual actual}))))
        (ui-handler/re-render-root!)
        (clj->js (upsert-nodes-receipt ops receipt-uuids :verified readbacks))))))

(defn import-edn
  "Given EDN data as a transitized string, converts to EDN and imports it."
  [edn-data*]
  (p/let [edn-data (sqlite-util/read-transit-str edn-data*)
          {:keys [error]} (ui-outliner-tx/transact!
                           {:outliner-op :batch-import-edn}
                           (outliner-op/batch-import-edn! edn-data {}))]
    (when error (throw (ex-info error {})))
    (ui-handler/re-render-root!)))

(defn export-edn
  "Given sqlite.export options, exports the current graph as a json map with the
  :export-body key containing a transit string of the export EDN"
  [options*]
  (p/let [options (-> (js->clj options* :keywordize-keys true)
                      (update :export-type (fnil keyword :graph)))
          result (state/<invoke-db-worker :thread-api/export-edn (state/get-current-repo) options)]
    (when (:export-edn-error result)
      (throw (ex-info (str "Export EDN Error: " (:export-edn-error result)) {})))
    {:export-body (sqlite-util/write-transit-str result)
     :graph (string/replace-first (state/get-current-repo) common-config/db-version-prefix "")}))
