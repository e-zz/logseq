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

(defn- get-recycled-block*
  ([uuid-string]
   (get-recycled-block* uuid-string {}))
  ([uuid-string options]
   (state/<invoke-db-worker :thread-api/api-get-recycled-block
                            (state/get-current-repo)
                            uuid-string
                            options)))

(defn get-recycled-block
  "Explicit inspection of a recycled ordinary block root and its retained
   subtree and original location."
  ([uuid-string]
   (get-recycled-block uuid-string #js {}))
  ([uuid-string options*]
   (p/let [options (js->clj options* :keywordize-keys true)
           resp (get-recycled-block* uuid-string options)]
     (clj->js resp))))

(defn recycle-block
  "Soft recycle an ordinary block root and its subtree. Already-recycled roots
   are an explicit no-op that keeps the retained timestamp and original
   location. Non-ordinary roots return an error before any write."
  ([uuid-string]
   (recycle-block uuid-string #js {}))
  ([uuid-string options*]
   (p/let [options (js->clj options* :keywordize-keys true)
           preflight (get-recycled-block* uuid-string {:classify? true})]
     (cond
       (:error preflight)
       (clj->js preflight)

       (= "recycled" (:state preflight))
       (clj->js {:operation "recycle"
                 :state "recycled"
                 :no-op true
                 :reason "already-recycled"
                 :root-uuid (:root-uuid preflight)
                 :affected-uuids (:subtree preflight)
                 :affected-count (:subtree-count preflight)
                 :deleted-at (:deleted-at preflight)
                 :page-uuid (:page-uuid preflight)})

       (= "active" (:state preflight))
       (p/let [result (ui-outliner-tx/transact!
                       {:outliner-op :recycle-blocks}
                       (outliner-op/recycle-blocks! (uuid uuid-string) options))
               readback (get-recycled-block* uuid-string {})]
         (cond
           (:error result)
           (clj->js result)

           (:error readback)
           (clj->js readback)

           (= "recycled" (:state readback))
           (clj->js result)

           :else
           (clj->js {:error "Recycle verification failed: block is not recycled after write"})))

       :else
       (clj->js {:error (str "Block uuid " uuid-string " cannot be recycled")})))))

(defn restore-block
  "Restore a recycled ordinary block root and its retained subtree. Restoring an
   active (non-recycled) block is an actionable error, never a silent no-op."
  ([uuid-string]
   (restore-block uuid-string #js {}))
  ([uuid-string _options*]
   (p/let [preflight (get-recycled-block* uuid-string {:classify? true})]
     (cond
       (:error preflight)
       (clj->js preflight)

       (= "active" (:state preflight))
       (clj->js {:error (str "Block uuid " uuid-string " is not recycled")})

       (= "recycled" (:state preflight))
       (p/let [result (ui-outliner-tx/transact!
                       {:outliner-op :restore-recycled}
                       (outliner-op/restore-recycled! (uuid uuid-string)))
               readback (state/<invoke-db-worker :thread-api/api-get-block
                                                 (state/get-current-repo)
                                                 uuid-string
                                                 {})]
         (cond
           (:error result)
           (clj->js result)

           (:error readback)
           (clj->js readback)

           :else
           (clj->js (assoc result
                           :page-uuid (:block/page readback)
                           :parent-uuid (:block/parent readback)))))

       :else
       (clj->js {:error (str "Block uuid " uuid-string " cannot be restored")})))))

(defn- json-property-map
  "Keyword property idents lose their namespace through `clj->js`; stringify them
   with the leading colon stripped so `user.property/*` survives to the caller."
  [properties]
  (into {}
        (map (fn [[k v]] [(if (keyword? k) (subs (str k) 1) (str k)) v]))
        properties))

(defn- receipt-operation
  [index operation uuid-field uuid status readback]
  (let [base (cond-> {:index index
                      :operation (:operation operation)
                      :entity-type (:entityType operation)
                      :status status}
               (and (= "add" (:operation operation))
                    (some? (:id operation)))
               (assoc :op-id (:id operation)))]
    (case status
      :verified
      (let [entity (cond-> {:uuid (:block/uuid readback)
                            :title (:block/title readback)}
                     (contains? readback :properties)
                     (assoc :properties (json-property-map (:properties readback))))]
        (cond-> (assoc base :uuid (str uuid) :entity entity)
          (= "block" (:entityType operation))
          (assoc :page-uuid (:block/page readback)
                 :parent-uuid (:block/parent readback))))

      :dry-run
      (assoc base uuid-field (str uuid))

      base)))

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

(defn- receipt-uuids-for
  [ops]
  (mapv (fn [op]
          (when (and (contains? #{"block" "page"} (:entityType op))
                     (= "add" (:operation op)))
            (random-uuid)))
        ops))

(defn- preflight-edit-readbacks
  "Reads back existing edit targets before the transaction so a verified receipt
   can compare observed page/parent state. Throws before any write on error."
  [ops]
  (p/let [indices (keep-indexed (fn [index op]
                                  (when (= "edit" (:operation op)) index))
                                ops)
          readbacks (when (seq indices)
                    (state/<invoke-db-worker
                     :thread-api/api-read-upsert-blocks
                     (state/get-current-repo)
                     (mapv (fn [index]
                             (let [op (nth ops index)]
                               {:uuid (str (uuid (:id op)))
                                :entity-type (:entityType op)}))
                           indices)))]
    (when-let [failure (some :error readbacks)]
      (throw (ex-info (str "Cannot create verified upsert receipt before transaction: " failure) {})))
    (zipmap indices readbacks)))

(defn- receipt-add-id-map
  [ops receipt-uuids]
  (into {} (keep-indexed (fn [index op]
                           (when (and (= "add" (:operation op)) (:id op))
                             [(:id op) (str (nth receipt-uuids index))]))
                         ops)))

(defn- receipt-expectations-for
  [ops receipt-uuids receipt-edit-by-index receipt-add-ids]
  (mapv (fn [index op]
          (let [add? (= "add" (:operation op))
                page? (= "page" (:entityType op))
                data (:data op)
                base (cond-> {:uuid (if add?
                                      (str (nth receipt-uuids index))
                                      (str (uuid (:id op))))
                              :entity-type (:entityType op)}
                       (contains? data :properties)
                       (assoc :properties (:properties data)))
                with-title (if (contains? data :title)
                             (assoc base :title (:title data))
                             base)]
            (if page?
              with-title
              (let [with-page (if add?
                                (assoc with-title :page-uuid
                                       (str (uuid (:page-id data))))
                                (assoc with-title :page-uuid
                                       (get-in receipt-edit-by-index [index :block/page])))]
                (if add?
                  (let [parent-id (:parent-id data)]
                    (assoc with-page :parent-uuid
                           (if parent-id
                             (or (get receipt-add-ids parent-id)
                                 (str (uuid parent-id)))
                             (:page-uuid with-page))))
                  (assoc with-page :parent-uuid
                         (get-in receipt-edit-by-index [index :block/parent])))))))
        (range (count ops))
        ops))

(defn- verify-receipt-readbacks!
  [receipt-expectations readbacks]
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
                       :actual actual})))))

(defn upsert-nodes
  "Upserts API operations. The default return remains the legacy summary string.

   Pass `{:receipt true}` to opt into a structured receipt. This receipt
   accepts add/edit block and page operations. A `:verified` receipt checks the
   requested UUID, expected page and parent UUIDs (blocks only), requested title
   when present, requested typed properties, and an ordinary visible block or
   existing non-recycled page using worker DB readback; it does not claim SQLite
   crash durability or search-index freshness. Dry runs return `:dry-run` with
   planned UUIDs and perform no transaction. A post-transaction mismatch rejects
   the call but does not roll back the completed transaction."
  [operations options*]
  (p/let [ops (js->clj operations :keywordize-keys true)
          {:keys [dry-run receipt] :as options} (js->clj options* :keywordize-keys true)
          receipt? (= true receipt)
          receipt-uuids (when receipt? (receipt-uuids-for ops))
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
          receipt-edit-by-index (when receipt?
                                  (preflight-edit-readbacks ops))
          receipt-add-ids (when receipt?
                            (receipt-add-id-map ops receipt-uuids))
          receipt-expectations (when receipt?
                                 (receipt-expectations-for ops receipt-uuids
                                                           receipt-edit-by-index
                                                           receipt-add-ids))
          {:keys [error]} (when-not (or dry-run (and receipt? (empty? ops)))
                            (ui-outliner-tx/transact!
                             {:outliner-op :batch-import-edn
                              :logseq.outliner.op/runtime-write? true}
                             (outliner-op/batch-import-edn!
                              edn-data
                              (cond-> {:validate-scope :tx}
                                (or receipt? (some #(seq (get-in % [:data :properties])) ops))
                                (assoc :build-existing-tx? true)))))]
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
        (verify-receipt-readbacks! receipt-expectations readbacks)
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
