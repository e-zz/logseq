(ns frontend.worker.recycle-persistence-test
  "Drives the recycle/restore slice through a real db-worker node process with
   an on-disk SQLite graph. Unlike the local-db plugin-api stubs used by
   frontend.worker.mcp-verify, this test starts frontend.worker.db-worker-node
   and talks to it over its HTTP invoke endpoint, so recycle/restore is applied
   by a real worker thread and persisted to disk."
  (:require ["@logseq/graph-lifecycle" :as lifecycle]
            ["fs" :as fs]
            ["http" :as http]
            ["path" :as node-path]
            [cljs.test :refer [async deftest is]]
            [frontend.test.node-helper :as node-helper]
            [frontend.worker.db-worker-node :as db-worker-node]
            [logseq.cli.root-dir :as cli-root]
            [logseq.common.graph-dir :as graph-dir]
            [logseq.db :as ldb]
            [promesa.core :as p]))

(defn- http-request
  [opts body]
  (p/create
   (fn [resolve reject]
     (let [req (.request http (clj->js opts)
                         (fn [^js res]
                           (let [chunks (array)]
                             (.on res "data" (fn [chunk] (.push chunks chunk)))
                             (.on res "end" (fn []
                                              (resolve {:status (.-statusCode res)
                                                        :body (.toString (js/Buffer.concat chunks) "utf8")}))))))
           finish! (fn []
                     (when body (.write req body))
                     (.end req))]
       (.on req "error" reject)
       (finish!)))))

(defn- invoke
  [host port method args]
  (let [payload (js/JSON.stringify
                 (clj->js {:method method
                           :argsTransit (ldb/write-transit-str args)}))]
    (p/let [{:keys [status body]}
            (http-request {:hostname host
                           :port port
                           :path "/v1/invoke"
                           :method "POST"
                           :headers {"Content-Type" "application/json"}}
                          payload)
            parsed (js->clj (js/JSON.parse body) :keywordize-keys true)]
      (when (not= 200 status)
        (println "[recycle-persistence-test] invoke failed"
                 {:method method :status status :body body}))
      (is (= 200 status))
      (is (:ok parsed))
      (ldb/read-transit-str (:resultTransit parsed)))))

(defn- start-daemon!
  [opts]
  (p/let [root (cli-root/ensure-root-dir! (:root-dir opts))
          _ (lifecycle/createGraph (lifecycle/resolveStorage root (node-path/join root "graphs")) (:repo opts))]
    (db-worker-node/start-daemon! (update opts :log-level #(or % "error")))))

(defn- transact-outline!
  "Creates a page with a top-level root block (order a0), a nested child under
   the root, and a following sibling (order b0). Returns the uuids."
  [host port repo]
  (let [now (js/Date.now)
        page-uuid (random-uuid)
        root-uuid (random-uuid)
        child-uuid (random-uuid)
        sibling-uuid (random-uuid)]
    (p/let [_ (invoke host port "thread-api/transact"
                      [repo
                       [{:block/uuid page-uuid
                         :block/title "Persistence Page"
                         :block/name (str "persistence-page-" (subs (str page-uuid) 0 8))
                         :block/tags #{:logseq.class/Page}
                         :block/created-at now
                         :block/updated-at now}
                        {:block/uuid root-uuid
                         :block/title "Root"
                         :block/page [:block/uuid page-uuid]
                         :block/parent [:block/uuid page-uuid]
                         :block/order "a0"
                         :block/created-at now
                         :block/updated-at now}
                        {:block/uuid child-uuid
                         :block/title "Child"
                         :block/page [:block/uuid page-uuid]
                         :block/parent [:block/uuid root-uuid]
                         :block/order "a0"
                         :block/created-at now
                         :block/updated-at now}
                        {:block/uuid sibling-uuid
                         :block/title "Sibling"
                         :block/page [:block/uuid page-uuid]
                         :block/parent [:block/uuid page-uuid]
                         :block/order "b0"
                         :block/created-at now
                         :block/updated-at now}]
                       {}
                       nil])]
      {:page-uuid page-uuid
       :root-uuid root-uuid
       :child-uuid child-uuid
       :sibling-uuid sibling-uuid})))

(deftest recycle-restore-roundtrip-through-real-worker
  (async done
         (let [daemon (atom nil)
               data-dir (node-helper/create-tmp-dir "recycle-roundtrip")
               repo (str "logseq_db_recycle_roundtrip_" (subs (str (random-uuid)) 0 8))]
           (-> (p/let [{:keys [host port stop!]} (start-daemon! {:root-dir data-dir :repo repo})
                       _ (reset! daemon {:stop! stop!})
                       _ (invoke host port "thread-api/create-or-open-db" [repo {}])
                       {:keys [page-uuid root-uuid child-uuid]} (transact-outline! host port repo)
                       recycle-response (invoke host port "thread-api/apply-outliner-ops"
                                               [repo [[:recycle-blocks [root-uuid {}]]] {}])
                       recycle-result (:result recycle-response)
                       recycled (invoke host port "thread-api/api-get-recycled-block"
                                        [repo (str root-uuid) {}])
                       restore-response (invoke host port "thread-api/apply-outliner-ops"
                                               [repo [[:restore-recycled [root-uuid]]] {}])
                       restore-result (:result restore-response)
                       restored (invoke host port "thread-api/api-get-block"
                                        [repo (str root-uuid) {}])]
                 (is (= "recycle" (:operation recycle-result)))
                 (is (= "recycled" (:state recycle-result)))
                 (is (= false (:no-op recycle-result)))
                 (is (= (str root-uuid) (:root-uuid recycle-result)))
                 (is (= 2 (:affected-count recycle-result)))
                 (is (= (str root-uuid) (first (:affected-uuids recycle-result))))
                 (is (= #{(str root-uuid) (str child-uuid)} (set (:affected-uuids recycle-result))))
                 (is (= (:page-uuid recycled) (:page-uuid recycle-result)))
                 (is (not= (str page-uuid) (:page-uuid recycle-result)))

                 (is (= "recycled" (:state recycled)))
                 (is (= 2 (:subtree-count recycled)))
                 (is (= (str page-uuid) (:original-page-uuid recycled)))
                 (is (= (str page-uuid) (:original-parent-uuid recycled)))

                 (is (= "restore" (:operation restore-result)))
                 (is (= "active" (:state restore-result)))
                 (is (= false (:no-op restore-result)))
                 (is (= "original" (:position restore-result)))
                 (is (= "original" (:order restore-result)))
                 (is (= (str page-uuid) (:parent-uuid restore-result)))
                 (is (= (str page-uuid) (:page-uuid restore-result)))

                 (is (nil? (:error restored)))
                 (is (= (str page-uuid) (:block/parent restored)))
                 (is (= (str page-uuid) (:block/page restored)))
                 (is (= "a0" (:block/order restored))))
               (p/catch (fn [e]
                          (println "[recycle-persistence-test] roundtrip error:" e)
                          (is false (str e))))
               (p/finally (fn []
                            (if-let [stop! (:stop! @daemon)]
                              (-> (stop!) (p/finally (fn [] (done))))
                              (done))))))))

(defn- property-ops
  "Production upsert operations that write the built-in closed-value status
   property to an existing block and page."
  [page-uuid root-uuid]
  [{:operation "edit" :entityType "block" :id (str root-uuid)
    :data {:properties {"logseq.property/status" "Done"}}}
   {:operation "edit" :entityType "page" :id (str page-uuid)
    :data {:properties {"logseq.property/status" "Doing"}}}])

(defn- build-and-apply-property-write!
  "Writes typed properties through the real worker build -> outliner
   batch-import production path (no direct SQLite access)."
  [host port repo ops]
  (p/let [edn-data (invoke host port "thread-api/api-build-upsert-nodes-edn" [repo ops {}])
          apply-response (invoke host port "thread-api/apply-outliner-ops"
                                [repo [[:batch-import-edn [edn-data {:build-existing-tx? true
                                                                     :validate-scope :tx}]]] {}])
          result (:result apply-response)]
    (when-let [error (:error result)]
      (throw (ex-info (str "batch-import failed: " error) {:result result})))
    result))

(defn- property-expectations
  [page-uuid root-uuid]
  [{:uuid (str root-uuid) :entity-type "block"
    :properties {"logseq.property/status" "Done"}}
   {:uuid (str page-uuid) :entity-type "page"
    :properties {"logseq.property/status" "Doing"}}])

(deftest typed-property-persists-across-worker-restart
  (async done
         (let [daemon-a (atom nil)
               daemon-b (atom nil)
               data-dir (node-helper/create-tmp-dir "typed-property-persist")
               repo (str "logseq_db_typed_property_" (subs (str (random-uuid)) 0 8))
               db-file (node-path/join data-dir
                                       "graphs"
                                       (graph-dir/repo->encoded-graph-dir-name repo)
                                       "db.sqlite")]
           (-> (p/let [{:keys [host port stop!]} (start-daemon! {:root-dir data-dir :repo repo})
                       _ (reset! daemon-a {:stop! stop!})
                       _ (invoke host port "thread-api/create-or-open-db" [repo {}])
                       {:keys [page-uuid root-uuid]} (transact-outline! host port repo)
                       _ (build-and-apply-property-write! host port repo
                                                          (property-ops page-uuid root-uuid))
                       before-readback (invoke host port "thread-api/api-read-upsert-blocks"
                                               [repo (property-expectations page-uuid root-uuid)])
                       block-before (get-in before-readback [0 :properties :logseq.property/status])
                       page-before (get-in before-readback [1 :properties :logseq.property/status])]
                 (is (string? block-before) "block status resolves to a closed-value uuid")
                 (is (string? page-before) "page status resolves to a closed-value uuid")
                 (is (not= block-before page-before) "block and page observe distinct closed values")
                 (p/let [_ (invoke host port "thread-api/close-db" [repo])
                         _ (is (fs/existsSync db-file))
                         _ (is (pos? (.-size (fs/statSync db-file))))
                         _ ((:stop! @daemon-a))
                         {:keys [host port stop!]} (start-daemon! {:root-dir data-dir :repo repo})
                         _ (reset! daemon-b {:stop! stop!})
                         _ (invoke host port "thread-api/create-or-open-db" [repo {}])
                         after-readback (invoke host port "thread-api/api-read-upsert-blocks"
                                                [repo (property-expectations page-uuid root-uuid)])
                         block-after (get-in after-readback [0 :properties :logseq.property/status])
                         page-after (get-in after-readback [1 :properties :logseq.property/status])
                         block (invoke host port "thread-api/api-get-block"
                                       [repo (str root-uuid) {}])
                         page (invoke host port "thread-api/api-get-page-data"
                                      [repo (str page-uuid) {}])]
                   (is (= block-before block-after)
                       "block closed-value ref survives worker restart unchanged")
                   (is (= page-before page-after)
                       "page closed-value ref survives worker restart unchanged")
                   (is (= block-before
                          (str (get-in block [:logseq.property/status :block/uuid])))
                       "getBlock exposes the persisted block closed value")
                   (is (= page-before
                           (str (get-in page [:entity :logseq.property/status :block/uuid])))
                        "getPage exposes the persisted page closed value")))
               (p/catch (fn [e]
                          (println "[recycle-persistence-test] typed property error:" e)
                          (is false (str e))))
               (p/finally (fn []
                            (let [stop-a (:stop! @daemon-a)
                                  stop-b (:stop! @daemon-b)]
                              (cond
                                (and stop-a stop-b)
                                (-> (stop-a) (p/finally (fn [] (-> (stop-b) (p/finally (fn [] (done)))))))
                                stop-a (-> (stop-a) (p/finally (fn [] (done))))
                                stop-b (-> (stop-b) (p/finally (fn [] (done))))
                                :else (done)))))))))

(deftest recycled-state-persists-across-worker-restart
  (async done
         (let [daemon-a (atom nil)
               daemon-b (atom nil)
               data-dir (node-helper/create-tmp-dir "recycle-persist-restart")
               repo (str "logseq_db_recycle_persist_" (subs (str (random-uuid)) 0 8))
               db-file (node-path/join data-dir
                                       "graphs"
                                       (graph-dir/repo->encoded-graph-dir-name repo)
                                       "db.sqlite")]
           (-> (p/let [{:keys [host port stop!]} (start-daemon! {:root-dir data-dir :repo repo})
                       _ (reset! daemon-a {:stop! stop!})
                       _ (invoke host port "thread-api/create-or-open-db" [repo {}])
                       {:keys [page-uuid root-uuid child-uuid]} (transact-outline! host port repo)
                       recycle-response (invoke host port "thread-api/apply-outliner-ops"
                                               [repo [[:recycle-blocks [root-uuid {}]]] {}])
                       recycle-result (:result recycle-response)]
                 (is (= "recycled" (:state recycle-result)))
                 (is (= 2 (:affected-count recycle-result)))
                 (p/let [_ (invoke host port "thread-api/close-db" [repo])
                         _ (is (fs/existsSync db-file))
                         _ (is (pos? (.-size (fs/statSync db-file))))
                         _ ((:stop! @daemon-a))
                         {:keys [host port stop!]} (start-daemon! {:root-dir data-dir :repo repo})
                         _ (reset! daemon-b {:stop! stop!})
                         _ (invoke host port "thread-api/create-or-open-db" [repo {}])
                         reloaded (invoke host port "thread-api/api-get-recycled-block"
                                          [repo (str root-uuid) {}])
                         restore-response (invoke host port "thread-api/apply-outliner-ops"
                                                 [repo [[:restore-recycled [root-uuid]]] {}])
                         restore-result (:result restore-response)
                         restored (invoke host port "thread-api/api-get-block"
                                          [repo (str root-uuid) {}])]
                   (is (= "recycled" (:state reloaded)))
                   (is (= 2 (:subtree-count reloaded)))
                   (is (= (str page-uuid) (:original-page-uuid reloaded)))
                   (is (= #{(str root-uuid) (str child-uuid)} (set (:subtree reloaded))))
                   (is (= "active" (:state restore-result)))
                   (is (= "original" (:position restore-result)))
                   (is (nil? (:error restored)))
                    (is (= (str page-uuid) (:block/parent restored)))
                    (is (= "a0" (:block/order restored)))))
                (p/catch (fn [e]
                           (println "[recycle-persistence-test] restart error:" e)
                           (is false (str e))))
                (p/finally (fn []
                             (let [stop-a (:stop! @daemon-a)
                                   stop-b (:stop! @daemon-b)]
                               (cond
                                 (and stop-a stop-b)
                                 (-> (stop-a) (p/finally (fn [] (-> (stop-b) (p/finally (fn [] (done)))))))
                                 stop-a (-> (stop-a) (p/finally (fn [] (done))))
                                 stop-b (-> (stop-b) (p/finally (fn [] (done))))
                                 :else (done)))))))))

;; ============================================================================
;; Genuine user-property persistence across a real worker restart.
;;
;; Unlike `typed-property-persists-across-worker-restart` (which only proves
;; the built-in closed-value `logseq.property/status`), this test seeds the six
;; user-property types from the issue-9 contract through the production import
;; schema path (`thread-api/import-edn` -> `sqlite-export/build-import` ->
;; `sqlite-build/build-blocks-tx`), discovers the created UUIDs from the worker
;; (`api-list-properties` / `q`), writes them through
;; `api-build-upsert-nodes-edn` -> `:batch-import-edn`, and verifies the receipt
;; (`api-read-upsert-blocks`) and public reads BEFORE a normal close, then
;; restarts on the same graph and re-asserts every value.
;; ============================================================================

(def ^:private user-property-export
  "Import-schema fixture for genuine user properties plus two active topic page
   targets. Titles avoid built-in property title collisions; UUIDs are generated
   by the importer and discovered from the worker, never made up."
  {:properties
   {:user.property/cv {:block/title "CV" :logseq.property/type :url}
    :user.property/orcid {:block/title "ORCID" :logseq.property/type :default}
    :user.property/description {:block/title "Person Description"
                                :logseq.property/type :default}
    :user.property/topics {:block/title "Person Topics"
                           :logseq.property/type :node
                           :db/cardinality :db.cardinality/many}
    :user.property/score {:block/title "Person Score" :logseq.property/type :number}
    :user.property/enabled {:block/title "Person Enabled"
                            :logseq.property/type :checkbox}}
   :pages-and-blocks [{:page {:block/title "Topic Alpha"}}
                      {:page {:block/title "Topic Beta"}}]})

(defn- property-uuid-by-ident
  "The discovered `:block/uuid` string for a user property ident from the
   expanded `api-list-properties` result."
  [properties ident]
  (some->> properties
           (filter #(= ident (:db/ident %)))
           first
           :block/uuid
           str))

(defn- page-uuid-by-title
  [host port repo title]
  (p/let [rows (invoke host port "thread-api/q"
                       [repo ['[:find ?u :in $ ?title
                                :where [?e :block/title ?title] [?e :block/uuid ?u]]
                             title]])]
    (some-> rows first first str)))

(defn- build-and-apply-typed-write!
  "Builds and applies API operations through the production
   `api-build-upsert-nodes-edn` -> `:batch-import-edn` worker path. `opts`
   forwards receipt options (`:receipt?`/`:receipt-uuids`) so add operations get
   deterministic UUIDs for receipt correlation."
  [host port repo ops opts]
  (p/let [edn-data (invoke host port "thread-api/api-build-upsert-nodes-edn" [repo ops opts])
          apply-response (invoke host port "thread-api/apply-outliner-ops"
                                [repo [[:batch-import-edn [edn-data {:build-existing-tx? true
                                                                     :validate-scope :tx}]]] {}])
          result (:result apply-response)]
    (when-let [error (:error result)]
      (throw (ex-info (str "batch-import failed: " error) {:result result})))
    result))

(defn- user-property-page-read
  [host port repo person-uuid]
  (invoke host port "thread-api/api-get-page-data" [repo (str person-uuid) {}]))

(defn- user-property-block-read
  [host port repo person-block-uuid]
  (invoke host port "thread-api/api-get-block" [repo (str person-block-uuid) {}]))

(defn- topic-uuids
  "UUID strings observed for a node-cardinality-many property value."
  [serialized]
  (->> serialized (keep :block/uuid) set))

(defn- seed-user-property-metadata!
  "Imports the user-property fixture and discovers the created property and
   topic UUIDs from the worker (never made up). Returns the UUID map."
  [host port repo]
  (p/let [seed (invoke host port "thread-api/import-edn" [repo user-property-export])
          _ (is (nil? (:error seed)) (str "user-property import error: " (pr-str seed)))
          _ (is (pos? (:tx-count seed)) "user-property import created entities")
          props (invoke host port "thread-api/api-list-properties" [repo {:expand true}])
          cv (property-uuid-by-ident props :user.property/cv)
          orcid (property-uuid-by-ident props :user.property/orcid)
          description (property-uuid-by-ident props :user.property/description)
          topics (property-uuid-by-ident props :user.property/topics)
          score (property-uuid-by-ident props :user.property/score)
          enabled (property-uuid-by-ident props :user.property/enabled)
          _ (is (every? string? [cv orcid description topics score enabled])
                "all six user properties are discoverable by :db/ident")
          topic-alpha (page-uuid-by-title host port repo "Topic Alpha")
          topic-beta (page-uuid-by-title host port repo "Topic Beta")
          _ (is (and (string? topic-alpha) (string? topic-beta))
                "both topic page targets were created")]
    {:cv cv :orcid orcid :description description :topics topics
     :score score :enabled enabled :topic-alpha topic-alpha :topic-beta topic-beta}))

(defn- write-person-properties!
  "Writes the person page + block and a property-only page union edit through
   the production build/import path, then verifies the two receipts."
  [host port repo {:keys [cv orcid description topics score enabled topic-alpha topic-beta]}
   {:keys [person-uuid person-block-uuid person-title]}]
  (p/let [_ (build-and-apply-typed-write!
             host port repo
             [{:operation "add" :entityType "page"
               :data {:title person-title
                      :properties {cv "https://example.org/people/alpha"
                                   orcid "0000-0002-1825-0097"
                                   description "Researcher"
                                   topics [{:uuid topic-alpha}]}}}]
             {:receipt? true :receipt-uuids [person-uuid]})
          _ (build-and-apply-typed-write!
             host port repo
             [{:operation "add" :entityType "block"
               :data {:page-id (str person-uuid) :title "Person launch"
                      :properties {score 0
                                   enabled false
                                   description "Block level description"
                                   topics [{:uuid topic-alpha}
                                           {:uuid topic-beta}]}}}]
             {:receipt? true :receipt-uuids [person-block-uuid]})
          ;; Property-only page edit: change description and union a second node
          ;; ref; the importer must retain topic alpha.
          _ (build-and-apply-typed-write!
             host port repo
             [{:operation "edit" :entityType "page" :id (str person-uuid)
               :data {:properties {description "Senior Researcher"
                                   topics [{:uuid topic-beta}]}}}]
             {:receipt? true :receipt-uuids [nil]})
          receipt (invoke host port "thread-api/api-read-upsert-blocks"
                          [repo [{:uuid (str person-uuid) :entity-type "page"
                                  :title person-title
                                  :properties {"user.property/cv" "https://example.org/people/alpha"
                                               "user.property/orcid" "0000-0002-1825-0097"
                                               "user.property/description" "Senior Researcher"
                                               "user.property/topics" [{:uuid topic-alpha}
                                                                       {:uuid topic-beta}]}}
                                 {:uuid (str person-block-uuid) :entity-type "block"
                                  :page-uuid (str person-uuid)
                                  :title "Person launch"
                                  :properties {"user.property/score" 0
                                               "user.property/enabled" false
                                               "user.property/description" "Block level description"
                                               "user.property/topics" [{:uuid topic-alpha}
                                                                       {:uuid topic-beta}]}}]])]
    (is (nil? (:error (first receipt))) (str "page receipt: " (pr-str (first receipt))))
    (is (nil? (:error (second receipt))) (str "block receipt: " (pr-str (second receipt))))
    receipt))

(defn- assert-user-property-reads!
  "Re-asserts the typed person page/block values from a page and block read."
  [page block topic-alpha topic-beta person-title]
  (is (nil? (:error page)))
  (is (nil? (:error block)))
  (is (= person-title (get-in page [:entity :block/title])))
  (is (= "Senior Researcher"
         (get-in page [:entity :user.property/description :block/title])))
  (is (= "https://example.org/people/alpha"
         (get-in page [:entity :user.property/cv :block/title])))
  (is (= "0000-0002-1825-0097"
         (get-in page [:entity :user.property/orcid :block/title])))
  (is (= #{topic-alpha topic-beta}
         (topic-uuids (get-in page [:entity :user.property/topics])))
      "many refs retain both topics")
  (is (= 0 (get-in block [:user.property/score :logseq.property/value])))
  (is (false? (get-in block [:user.property/enabled])))
  (is (= "Block level description"
         (get-in block [:user.property/description :block/title])))
  (is (= #{topic-alpha topic-beta}
         (topic-uuids (get-in block [:user.property/topics])))))

(deftest user-property-persists-across-worker-restart
  (async done
         (let [daemon-a (atom nil)
               daemon-b (atom nil)
               data-dir (node-helper/create-tmp-dir "user-property-persist")
               repo (str "logseq_db_user_property_" (subs (str (random-uuid)) 0 8))
               db-file (node-path/join data-dir
                                       "graphs"
                                       (graph-dir/repo->encoded-graph-dir-name repo)
                                       "db.sqlite")
               person-uuid (random-uuid)
               person-block-uuid (random-uuid)
               person-title "Person Alpha"]
           (-> (p/let [{:keys [host port stop!]} (start-daemon! {:root-dir data-dir :repo repo})
                       _ (reset! daemon-a {:stop! stop!})
                       _ (invoke host port "thread-api/create-or-open-db" [repo {}])
                       props (seed-user-property-metadata! host port repo)
                       _ (write-person-properties! host port repo props
                                                   {:person-uuid person-uuid
                                                    :person-block-uuid person-block-uuid
                                                    :person-title person-title})
                       page-before (user-property-page-read host port repo person-uuid)
                       block-before (user-property-block-read host port repo person-block-uuid)]
                 (assert-user-property-reads! page-before block-before
                                              (:topic-alpha props) (:topic-beta props) person-title)
                 (let [outline-before (mapv :block/title (:blocks page-before))]
                   (p/let [_ (invoke host port "thread-api/close-db" [repo])
                           _ (is (fs/existsSync db-file) "db.sqlite exists after normal close")
                           _ (is (pos? (.-size (fs/statSync db-file))) "db.sqlite has positive size")
                           _ ((:stop! @daemon-a))
                           {:keys [host port stop!]} (start-daemon! {:root-dir data-dir :repo repo})
                           _ (reset! daemon-b {:stop! stop!})
                           _ (invoke host port "thread-api/create-or-open-db" [repo {}])
                           page-after (user-property-page-read host port repo person-uuid)
                           block-after (user-property-block-read host port repo person-block-uuid)]
                     (assert-user-property-reads! page-after block-after
                                                  (:topic-alpha props) (:topic-beta props) person-title)
                     (is (= (str person-block-uuid) (:block/uuid block-after))
                         "block UUID is stable across restart")
                     (is (= outline-before (mapv :block/title (:blocks page-after)))
                         "persisted outline is unchanged after reopen"))))
               (p/catch (fn [e]
                          (println "[recycle-persistence-test] user-property error:" e)
                          (is false (str e))))
               (p/finally (fn []
                            (let [stop-a (:stop! @daemon-a)
                                  stop-b (:stop! @daemon-b)]
                              (cond
                                (and stop-a stop-b)
                                (-> (stop-a) (p/finally (fn [] (-> (stop-b) (p/finally (fn [] (done)))))))
                                stop-a (-> (stop-a) (p/finally (fn [] (done))))
                                stop-b (-> (stop-b) (p/finally (fn [] (done))))
                                :else (done)))))))))
