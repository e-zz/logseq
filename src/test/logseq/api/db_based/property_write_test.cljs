(ns logseq.api.db-based.property-write-test
  (:require [cljs.test :refer [async deftest is use-fixtures]]
            [datascript.core :as d]
            [frontend.db.conn :as conn]
            [logseq.api.db-based.cli :as cli-api]
            [logseq.api.db-based.tools :as db-tools]
            [logseq.api.test-helper :as api-test]
            [frontend.test.helper :as test-helper]
            [logseq.db :as ldb]
            [logseq.db.frontend.property :as db-property]
            [logseq.db.frontend.entity-util :as entity-util]
            [logseq.db.sqlite.build :as sqlite-build]
            [promesa.core :as p]))

(use-fixtures :each {:before api-test/start-plugin-api-db!
                     :after api-test/destroy-plugin-api-db!})

(defn- fixture!
  []
  (sqlite-build/create-blocks
   (conn/get-db nil false)
   {:properties {:amount {:block/title "Amount" :logseq.property/type :number}
                 :text {:block/title "Text" :logseq.property/type :default}
                 :user.property/many {:block/title "Many" :logseq.property/type :number
                                      :db/cardinality :db.cardinality/many}
                 :user.property/closed {:block/title "Closed" :logseq.property/type :number
                                        :build/closed-values [{:value 1 :uuid (random-uuid)}]}}
    :pages-and-blocks [{:page {:block/title "Numeric properties"}
                        :blocks [{:block/title "target"
                                  :build/properties {:amount 1 :text "initial"}}]}]})
  (let [db (conn/get-db)
        page (ldb/get-page db "Numeric properties")
        block (first (filter #(= "target" (:block/title %))
                             (ldb/get-page-blocks db (:db/id page))))
        ident (some (fn [datom]
                      (when (and (= "user.property" (namespace (:a datom)))
                                 (= "Amount" (:block/title (d/entity db (:a datom)))))
                        (:a datom)))
                    (d/datoms db :eavt (:db/id block)))
        prop (d/entity db ident)
        text-prop (first (filter #(= "Text" (:block/title %))
                                 (map #(d/entity db (:e %))
                                      (d/datoms db :avet :logseq.property/type :default))))]
    {:page (str (:block/uuid page)) :block (str (:block/uuid block))
     :ident ident :property (str (:block/uuid prop))
     :text-ident (:db/ident text-prop)
     :text-property (str (:block/uuid text-prop))
     :many-property (str (:block/uuid (d/entity db :user.property/many)))
     :closed-property (str (:block/uuid (d/entity db :user.property/closed)))}))

(defn- json-ops [ops]
  (js/JSON.parse (js/JSON.stringify
                  (clj->js ops :keyword-fn (fn [k] (subs (str k) 1))))))

(defn- upsert [ops options]
  (p/then (p/resolved nil) (fn [_] (cli-api/upsert-nodes ops options))))

(deftest numeric-add-and-property-only-edit
  (async done
    (let [{:keys [page block ident property]} (fixture!)
          before (d/entity (conn/get-db) [:block/uuid (uuid block)])
          parent (:block/uuid (:block/parent before))
          order (:block/order before)
          transactions (atom [])
          db-conn (conn/get-db nil false)]
      (d/listen! db-conn ::numeric-write #(swap! transactions conj (:tx-data %)))
      (is (qualified-keyword? ident) "Fixture creates a real user numeric property")
      (-> (api-test/with-plugin-api
           (fn []
             (p/let [_ (upsert
                        (json-ops [{:operation "add" :entityType "block"
                                    :data {:page-id page :title "numeric added"
                                           :properties {property 42}}}
                                   {:operation "edit" :entityType "block" :id block
                                    :data {:properties {property 7}}}]) #js {})]
               (let [db (conn/get-db)
                     edited (d/entity db [:block/uuid (uuid block)])
                     added (first (filter #(= "numeric added" (:block/title %))
                                          (ldb/get-page-blocks db (:db/id (ldb/get-page db page)))))]
                 (is (= 42 (:logseq.property/value (get (d/entity db (:db/id added)) ident))))
                 (is (= 7 (:logseq.property/value (get edited ident))))
                 (is (= "target" (:block/title edited)))
                 (is (= parent (:block/uuid (:block/parent edited))))
                 (is (= order (:block/order edited)))
                 (is (= 1 (count @transactions)) "Adds, edits and property values share one transaction")))))
          (p/catch #(is false (str %)))
          (p/finally (fn [] (d/unlisten! db-conn ::numeric-write) (done)))))))

(deftest invalid-values-reject-entire-batch
  (async done
    (let [{:keys [page block property text-property many-property closed-property]} (fixture!)]
      (-> (api-test/with-plugin-api
           (fn []
             (p/doseq [[key value] [[property "7"] [property nil] [property [7]] [property true]
                                    [property {"value" 7}]
                                    [(str "not-a-uuid/" property) 7]
                                    [text-property 7] [many-property 7] [closed-property 7]
                                    ["aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa" 7]]]
               (let [before (vec (d/datoms (conn/get-db) :eavt))]
                 (-> (upsert
                      (json-ops [{:operation "add" :entityType "block"
                                  :data {:page-id page :title "must not exist"}}
                                 {:operation "edit" :entityType "block" :id block
                                  :data {:properties {key value}}}]) #js {})
                     (p/then #(is false (str "Must reject " key " " value)))
                     (p/catch (fn [error]
                                (is (re-find #"must be|does not accept|requires a JSON array" (str error))
                                    "Rejected by property preflight, not downstream importer")
                                (is (= before (vec (d/datoms (conn/get-db) :eavt)))
                                    "Rejected batch leaves every datom unchanged"))))))))
          (p/catch #(is false (str %)))
          (p/finally done)))))

(deftest zero-and-negative-decimal-values
  (async done
    (let [{:keys [block property ident text-ident]} (fixture!)]
      (-> (api-test/with-plugin-api
           (fn []
             (p/doseq [value [0 -7.5]]
               (p/let [_ (upsert (json-ops [{:operation "edit" :entityType "block" :id block
                                           :data {:properties {property value}}}]) #js {})]
                 (let [entity (d/entity (conn/get-db) [:block/uuid (uuid block)])]
                   (is (= value (:logseq.property/value (get entity ident))))
                   (is (= "target" (:block/title entity)))
                   (is (= "initial" (:block/title (get entity text-ident)))))))))
          (p/catch #(is false (str %)))
          (p/finally done)))))

(deftest property-dry-run-and-receipt-boundary
  (async done
    (let [{:keys [block property ident]} (fixture!)
          ops (json-ops [{:operation "edit" :entityType "block" :id block
                          :data {:properties {property 99}}}])
          before (vec (d/datoms (conn/get-db) :eavt))]
      (-> (api-test/with-plugin-api
           (fn []
             (p/let [_ (upsert ops #js {:dry-run true})]
               (is (= before (vec (d/datoms (conn/get-db) :eavt))))
               (p/let [receipt* (upsert ops #js {:receipt true})
                       receipt (js->clj receipt* :keywordize-keys true)]
                 (is (= "verified" (:mode receipt)))
                 (is (= 99 (get-in receipt [:operations 0 :entity :properties ident]))
                     "Verified receipt carries the observed property value")
                 (is (= 99 (:logseq.property/value
                             (get (d/entity (conn/get-db) [:block/uuid (uuid block)]) ident))))))))
           (p/catch #(is false (str %)))
           (p/finally done)))))

;; ============================================================================
;; Shared fixtures for the widened typed-property contract (LIST-4, TASK-7).
;; status / order-list-type are BUILT-IN property pages (no `:block/uuid` key in
;; a plain transact!), so we reach them by :db/ident and use list-properties
;; (expanded) to surface their uuids, mirroring what an MCP agent does.
;; ============================================================================
(defn- built-in-prop-uuid
  "The :block/uuid of a built-in property, looked up by :db/ident."
  [db ident]
  (let [ent (d/entity db ident)]
    (some-> ent :block/uuid str)))

(defn- task-status-closed-value-uuid
  "The stable :block/uuid of a status closed value, looked up by its :db/ident."
  [db ident]
  (some-> (d/entity db ident) :block/uuid str))

(defn- status-todo-uuid [db] (task-status-closed-value-uuid db :logseq.property/status.todo))
(defn- status-doing-uuid [db] (task-status-closed-value-uuid db :logseq.property/status.doing))
(defn- status-done-uuid [db] (task-status-closed-value-uuid db :logseq.property/status.done))

(defn- status-uuid [] (built-in-prop-uuid (conn/get-db) :logseq.property/status))
(defn- list-type-uuid [] (built-in-prop-uuid (conn/get-db) :logseq.property/order-list-type))

(defn- task-fixture!
  "A page with one Task block (via the real importer) and one plain block."
  []
  (test-helper/load-test-files
   [{:page {:block/title "Task Write Page"}
     :blocks [{:block/title "todo task" :build/tags #{:logseq.class/Task}}
              {:block/title "plain block"}]}])
  (let [db (conn/get-db)
        page (ldb/get-page db "Task Write Page")
        blocks (ldb/get-page-blocks db (:db/id page))
        task (first (filter #(= "todo task" (:block/title %)) blocks))
        plain (first (filter #(= "plain block" (:block/title %)) blocks))]
    {:page (str (:block/uuid page))
     :task (str (:block/uuid task))
     :plain (str (:block/uuid plain))}))

(defn- list-fixture!
  "A page with two plain blocks to become an ordered list."
  []
  (test-helper/load-test-files
   [{:page {:block/title "List Write Page"}
     :blocks [{:block/title "first item"}
              {:block/title "second item"}]}])
  (let [db (conn/get-db)
        page (ldb/get-page db "List Write Page")
        blocks (ldb/get-page-blocks db (:db/id page))
        first-block (first (filter #(= "first item" (:block/title %)) blocks))
        second-block (first (filter #(= "second item" (:block/title %)) blocks))]
    {:page (str (:block/uuid page))
     :first (str (:block/uuid first-block))
     :second (str (:block/uuid second-block))}))

;; ============================================================================
;; TASK-7 (#7): real status closed value (todo/doing/done), written and
;; updatable, recognized by the task query/UI. Values are stored as refs to the
;; existing built-in closed-value entities — not ad-hoc strings.
;; ============================================================================
(deftest task-status-closed-value-write
  (async done
    (let [{:keys [task]} (task-fixture!)
          status-prop-uuid (status-uuid)
          done-uuid (status-done-uuid (conn/get-db))]
      (is (some? status-prop-uuid) "status property has a discoverable uuid")
      (is (some? done-uuid) "Done closed value has a discoverable stable uuid")
      (-> (api-test/with-plugin-api
           (fn []
             ;; set status to Done by display value string (agent-friendly form)
             (p/let [_ (upsert
                        (json-ops [{:operation "edit" :entityType "block" :id task
                                    :data {:properties {status-prop-uuid "Done"}}}])
                        #js {})
                     db (conn/get-db)
                     block (d/entity db [:block/uuid (uuid task)])
                     status-val (:logseq.property/status block)
                     status-ent (if (:block/uuid status-val) status-val (d/entity db status-val))]
               (is (some? status-val) "block now carries a status value")
               (is (= done-uuid (str (:block/uuid status-ent)))
                   "stored status is a ref to the real Done closed value entity")
               (is (contains? (set (map :db/ident (:block/tags block))) :logseq.class/Task)
                   "task identity preserved")
               ;; update: Done -> Doing by the fully-qualified ident an agent
               ;; discovers from listProperties
               (p/let [_ (upsert
                           (json-ops [{:operation "edit" :entityType "block" :id task
                                       :data {:properties {status-prop-uuid "logseq.property/status.doing"}}}])
                           #js {})
                       db (conn/get-db)
                       block (d/entity db [:block/uuid (uuid task)])
                       doing-uuid (status-doing-uuid db)
                       doing-val (:logseq.property/status block)
                       doing-ent (if (:block/uuid doing-val) doing-val (d/entity db doing-val))]
                 (is (= doing-uuid (str (:block/uuid doing-ent)))
                     "status is updatable to Doing via its closed-value identity")
                 ;; update: Doing -> Todo by the canonical stable uuid
                  (p/let [todo-uuid (status-todo-uuid db)
                          _ (upsert
                             (json-ops [{:operation "edit" :entityType "block" :id task
                                         :data {:properties {status-prop-uuid todo-uuid}}}])
                             #js {})
                         db (conn/get-db)
                         block (d/entity db [:block/uuid (uuid task)])
                         todo-val (:logseq.property/status block)
                         todo-ent (if (:block/uuid todo-val) todo-val (d/entity db todo-val))]
                   (is (= todo-uuid (str (:block/uuid todo-ent)))
                       "status is writable by its canonical closed-value uuid"))))))
          (p/catch (fn [error]
                     (is false (str "task status write failed: " error))))
          (p/finally done)))))
(defn- status-value-entity
  "The stored status ref, whether the DB returns an entity, a raw eid, or a
   pulled {:db/id ..} map (as `get-page-blocks` returns for ref attributes)."
  [db v]
  (cond
    (:block/uuid v) v
    (:db/id v) (d/entity db (:db/id v))
    :else (d/entity db v)))

(deftest task-status-write-on-add-and-plain-block
  (async done
    (let [{:keys [page plain]} (task-fixture!)
          status-prop-uuid (status-uuid)
          task-class (str (:block/uuid (d/entity (conn/get-db) :logseq.class/Task)))]
      (is (some? task-class) "Task class has a discoverable uuid")
      (-> (api-test/with-plugin-api
           (fn []
             (p/let [_ (upsert
                        (json-ops [{:operation "add" :entityType "block"
                                     :data {:page-id page :title "added task"
                                           :tags [task-class]
                                           :properties {status-prop-uuid "Done"}}}
                                   {:operation "edit" :entityType "block" :id plain
                                    :data {:properties {status-prop-uuid "Todo"}}}])
                        #js {})
                     db (conn/get-db)
                     added (first (filter #(= "added task" (:block/title %))
                                          (ldb/get-page-blocks db (:db/id (ldb/get-page db page)))))
                     task-eid (:db/id (d/entity db :logseq.class/Task))
                     task-done-uuid (status-done-uuid db)
                     added-status (status-value-entity db (:logseq.property/status added))
                     plain-block (d/entity db [:block/uuid (uuid plain)])
                     plain-status (status-value-entity db (:logseq.property/status plain-block))]
               (is (some? added) "new block was added")
               (is (contains? (set (map :e (d/datoms db :avet :block/tags task-eid)))
                              (:db/id added))
                   "added block is queryable as a real Task via :block/tags")
               (is (= task-done-uuid (str (:block/uuid added-status)))
                   "added Task carries the real Done status closed value")
               (is (= (status-todo-uuid db) (str (:block/uuid plain-status)))
                   "an existing plain block is editable to the real Todo status"))))
          (p/catch (fn [error]
                     (is false (str "add/plain status write failed: " error))))
          (p/finally done)))))

(deftest task-status-unknown-closed-value-rejected
  (async done
    (let [{:keys [task]} (task-fixture!)
          status-prop-uuid (status-uuid)
          bogus (str (random-uuid))
          before (vec (d/datoms (conn/get-db) :eavt))]
      (-> (api-test/with-plugin-api
           (fn []
             (p/doseq [value [bogus "NotAStatus" 42 nil]]
               (-> (upsert
                     (json-ops [{:operation "edit" :entityType "block" :id task
                                 :data {:properties {status-prop-uuid value}}}])
                     #js {})
                   (p/then #(is false (str "unknown/invalid status value should be rejected: " value)))
                   (p/catch (fn [error]
                              (is (re-find #"status|closed|Status|value" (str error))
                                  (str "rejection mentions the problem: " error))
                              (is (= before (vec (d/datoms (conn/get-db) :eavt)))
                                  "rejected status write leaves db unchanged")))))))
          (p/catch (fn [e] (is false (str e))))
          (p/finally done)))))
(deftest list-type-numbered-write
  (async done
    (let [{first-block :first} (list-fixture!)
          list-type-prop-uuid (list-type-uuid)]
      (is (some? list-type-prop-uuid) "order-list-type property has a discoverable uuid")
      (-> (api-test/with-plugin-api
           (fn []
             (p/let [_ (upsert
                        (json-ops [{:operation "edit" :entityType "block" :id first-block
                                    :data {:properties {list-type-prop-uuid "number"}}}])
                        #js {})
                     db (conn/get-db)
                     block (d/entity db [:block/uuid (uuid first-block)])
                     list-type-val (db-property/lookup block :logseq.property/order-list-type)]
               (is (= "number" list-type-val)
                   "stored list type reads back as the real number marker (UI-equivalent)")
               (is (= "first item" (:block/title block))
                   "title is untouched — no forged number prefix"))))
          (p/catch (fn [error]
                     (is false (str "list-type write failed: " error))))
          (p/finally done)))))

(deftest list-type-unsupported-values-rejected
  (async done
    (let [{first-block :first} (list-fixture!)
          list-type-prop-uuid (list-type-uuid)
          before (vec (d/datoms (conn/get-db) :eavt))]
      (-> (api-test/with-plugin-api
           (fn []
             (p/doseq [value [nil "bullet" "Numbered" 1]]
               (-> (upsert
                    (json-ops [{:operation "edit" :entityType "block" :id first-block
                                :data {:properties {list-type-prop-uuid value}}}])
                    #js {})
                   (p/then #(is false (str "unsupported list-type value should be rejected: " value)))
                   (p/catch (fn [error]
                              (is (re-find #"List-type|number" (str error))
                                  (str "rejection mentions the list-type contract: " error))
                              (is (= before (vec (d/datoms (conn/get-db) :eavt)))
                                  "rejected list-type write leaves db unchanged")))))))
          (p/catch (fn [e] (is false (str e))))
          (p/finally done)))))

(defn- discoverable-property
  "The expanded listProperties entry for a built-in property uuid."
  [db ident]
  (let [uuid (built-in-prop-uuid db ident)]
    (first (filter #(= uuid (str (:block/uuid %)))
                   (db-tools/list-properties db {:expand true})))))

(deftest list-properties-exposes-writable-closed-values
  (task-fixture!)
  (let [db (conn/get-db)
        status (discoverable-property db :logseq.property/status)
        choices (:property/closed-values status)
        by-uuid (into {} (map (juxt (comp str :block/uuid) identity)) choices)]
    (is (some? status) "status property is discoverable by its stable uuid")
    (is (contains? status :property/closed-values)
        "expanded property carries its allowed choices")
    (is (= "Done" (:block/title (get by-uuid (status-done-uuid db))))
        "Done choice is discoverable with its display title")
    (is (= "logseq.property/status.done" (:db/ident (get by-uuid (status-done-uuid db))))
        "Done choice carries the fully-qualified ident the resolver accepts")
    (is (= (status-doing-uuid db)
           (str (:block/uuid (get by-uuid (status-doing-uuid db)))))
        "Doing choice carries its canonical writable uuid")
    (is (nil? (:logseq.property/deleted-at (get by-uuid (status-doing-uuid db))))
        "choices are not recycled")))

(deftest list-properties-omits-hidden-closed-values-and-write-agrees
  (async done
    (let [{:keys [task]} (task-fixture!)
          db (conn/get-db)
          status (d/entity db :logseq.property/status)
          raw (:property/closed-values status)
          hidden-uuids (set (map (comp str :block/uuid) raw))]
      (is (seq raw) "status property has closed values to hide")
      ;; Hide half and recycle the other half so the allowed set is empty and
      ;; both exclusion branches are exercised.
      (conn/transact!
       nil
       (mapv (fn [i cv]
               (if (even? i)
                 [:db/add (:db/id cv) :logseq.property/hide? true]
                 [:db/add (:db/id cv) :logseq.property/deleted-at 1]))
             (range) raw))
      (let [db (conn/get-db)
            listed (discoverable-property db :logseq.property/status)
            choices (or (:property/closed-values listed) [])
            choice-uuids (set (map :block/uuid choices))
            status-prop-uuid (status-uuid)]
        (is (some? listed) "status property is still discoverable")
        (is (empty? choices)
            "a property whose choices are all hidden/recycled exposes no choices")
        (is (not (some #(contains? hidden-uuids %) choice-uuids))
            "no protected closed-value identity leaks through discovery")
        (is (not (re-find #"protocol_mask|logseq\\$db" (js/JSON.stringify (clj->js listed))))
            "expanded discovery output is JSON-safe")
        (-> (api-test/with-plugin-api
             (fn []
               (-> (upsert
                    (json-ops [{:operation "edit" :entityType "block" :id task
                                :data {:properties {status-prop-uuid "Done"}}}])
                    #js {})
                   (p/then #(is false "a hidden-only property must reject status writes"))
                   (p/catch (fn [error]
                              (is (re-find #"Status value must be an existing" (str error))
                                  "write rejection matches the discovery allowed set"))))))
            (p/catch (fn [e] (is false (str e))))
            (p/finally done))))))

(deftest order-list-type-is-discoverable
  (list-fixture!)
  (let [db (conn/get-db)
        list-type (discoverable-property db :logseq.property/order-list-type)]
    (is (some? list-type) "hidden order-list-type is discoverable for write metadata")
    (is (= "List type" (:block/title list-type)))))

;; NOTE: list-type removal (nil → bullet) is NOT supported through the importer
;; path because build-existing-tx? merges attribute maps and cannot emit
;; :db/retract. The UI handles removal via the outliner's batch-remove-property!
;; which uses a real retract tx. This is a structural limitation of the import
;; path, not a gap in the resolver. Documented here; the acceptance for LIST-4
;; is "write a real numbered list; read back the real list property" which the
;; list-type-numbered-write test covers.

;; ============================================================================
;; Stage 1: generic, schema-driven typed properties on add/edit blocks.
;; The contract is driven entirely by the property's real metadata (type,
;; cardinality, closed values, ref classes); the property type is never guessed
;; from the key. Every case below writes through the real API + importer and
;; reads back from the graph.
;; ============================================================================
(defn- typed-fixture!
  "One page with a typed target block, a plain ref target, a real Asset block
   and a journal page, plus one property per supported user type."
  []
  (sqlite-build/create-blocks
   (conn/get-db nil false)
   {:properties {:user.property/text {:block/title "Text" :logseq.property/type :default}
                 :user.property/website {:block/title "Website" :logseq.property/type :url}
                 :user.property/flag {:block/title "Flag" :logseq.property/type :checkbox}
                 :user.property/moment {:block/title "Moment" :logseq.property/type :datetime}
                 :user.property/score {:block/title "Score" :logseq.property/type :number}
                 :user.property/related {:block/title "Related" :logseq.property/type :node}
                 :user.property/day {:block/title "Day" :logseq.property/type :date}
                 :user.property/media {:block/title "Media" :logseq.property/type :asset}
                 :user.property/numbers {:block/title "Numbers" :logseq.property/type :number
                                         :db/cardinality :db.cardinality/many}}
    :pages-and-blocks [{:page {:block/title "Typed Write Page"}
                        :blocks [{:block/title "typed target"}
                                 {:block/title "ref target"}
                                 {:block/title "attachment"
                                  :block/tags [{:db/ident :logseq.class/Asset}]}]}
                       {:page {:build/journal 20240101}}]})
  (let [db (conn/get-db)
        page (ldb/get-page db "Typed Write Page")
        blocks (ldb/get-page-blocks db (:db/id page))
        by-title (fn [t] (first (filter #(= t (:block/title %)) blocks)))]
    (doseq [ident [:user.property/text :user.property/website :user.property/flag
                   :user.property/moment :user.property/score :user.property/related
                   :user.property/day :user.property/media :user.property/numbers]]
      (assert (d/entity db ident) (str "fixture property " ident)))
    {:page (str (:block/uuid page))
     :target (str (:block/uuid (by-title "typed target")))
     :ref-target (str (:block/uuid (by-title "ref target")))
     :asset (str (:block/uuid (by-title "attachment")))
     :journal (str (:block/uuid (ldb/get-page db "Jan 1st, 2024")))
     ;; Exact qualified idents are accepted as property keys and are also the
     ;; block attribute the importer writes, so theyare used for readback too.
     :text "user.property/text"
     :website "user.property/website"
     :flag "user.property/flag"
     :moment "user.property/moment"
     :score "user.property/score"
     :related "user.property/related"
     :day "user.property/day"
     :media "user.property/media"
     :numbers "user.property/numbers"}))

(defn- resolve-ref
  "Resolves a stored ref, which may be an entity, a raw eid or a pulled
   {:db/id ..} map (as `get-page-blocks` returns)."
  [v]
  (cond
    (nil? v) nil
    (:block/uuid v) v
    (:db/id v) (d/entity (conn/get-db) (:db/id v))
    :else v))

(defn- pval
  "A scalar property value whether it is stored directly (:checkbox/:datetime) or
   on a property-value block (:default/:number/:url)."
  [block ident]
  (let [v (resolve-ref (get block (keyword ident)))]
    (cond
      (nil? v) nil
      (:block/uuid v) (if (contains? v :logseq.property/value)
                        (:logseq.property/value v)
                        (:block/title v))
      :else v)))

(defn- ref-uuid [block ident]
  (some-> (resolve-ref (get block (keyword ident))) :block/uuid str))

(deftest typed-scalars-write-and-edit
  (async done
    (let [{:keys [page target text website flag moment score]} (typed-fixture!)
          props {text "hello"
                 website "https://example.com/path"
                 flag true
                 moment 1700000000000
                 score 3.5}
          expected [[text "hello"] [website "https://example.com/path"]
                    [flag true] [moment 1700000000000] [score 3.5]]]
      (-> (api-test/with-plugin-api
           (fn []
             (p/let [_ (upsert
                        (json-ops [{:operation "add" :entityType "block"
                                    :data {:page-id page :title "typed added" :properties props}}
                                   {:operation "edit" :entityType "block" :id target
                                    :data {:properties props}}])
                        #js {})]
               (let [db (conn/get-db)
                     added (first (filter #(= "typed added" (:block/title %))
                                          (ldb/get-page-blocks db (:db/id (ldb/get-page db page)))))
                     edited (d/entity db [:block/uuid (uuid target)])]
                 (doseq [[ident value] expected]
                   (is (= value (pval added ident)) (str "added " ident))
                   (is (= value (pval edited ident)) (str "edited " ident)))
                 (is (= "typed target" (:block/title edited)))
                 (is (= "hello" (pval (d/entity db [:block/uuid (uuid target)]) text)))))))
          (p/catch #(is false (str "typed scalar write failed: " %)))
          (p/finally done)))))

(deftest typed-scalars-reject-wrong-shape
  (async done
    (let [{:keys [target text website flag moment score]} (typed-fixture!)
          bad [[text 7] [website "not a url"] [flag "yes"] [moment "tomorrow"]
               [score "3.5"] [text ["a"]] [score nil]]]
      (-> (api-test/with-plugin-api
           (fn []
             (p/doseq [[key value] bad]
               (let [before (vec (d/datoms (conn/get-db) :eavt))]
                 (-> (upsert
                      (json-ops [{:operation "edit" :entityType "block" :id target
                                  :data {:properties {key value}}}])
                      #js {})
                     (p/then #(is false (str "must reject " key " " (pr-str value))))
                     (p/catch (fn [error]
                                (is (re-find #"must be|does not accept|requires a JSON array"
                                             (str error)))
                                (is (= before (vec (d/datoms (conn/get-db) :eavt)))
                                    "rejected typed write leaves db unchanged"))))))))
          (p/catch #(is false (str %)))
          (p/finally done)))))

(deftest node-reference-write-and-reject
  (async done
    (let [{:keys [target ref-target related]} (typed-fixture!)
          todo-uuid (status-todo-uuid (conn/get-db))]
      (-> (api-test/with-plugin-api
           (fn []
             (p/let [_ (upsert
                        (json-ops [{:operation "edit" :entityType "block" :id target
                                    :data {:properties {related {:uuid ref-target}}}}])
                        #js {})
                     db (conn/get-db)
                     edited (d/entity db [:block/uuid (uuid target)])]
               (is (= ref-target (ref-uuid edited related))
                   "node ref stores a real :block/uuid ref")
               ;; raw string / malformed envelope / unknown uuid / closed-value target
               (p/doseq [value [ref-target {:uuid "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"}
                                {:uuid ref-target :extra 1} {:uuid todo-uuid}]]
                 (let [before (vec (d/datoms (conn/get-db) :eavt))]
                   (-> (upsert
                        (json-ops [{:operation "edit" :entityType "block" :id target
                                    :data {:properties {related value}}}])
                        #js {})
                       (p/then #(is false (str "must reject node ref " (pr-str value))))
                       (p/catch (fn [error]
                                  (is (re-find #"envelope|unknown uuid|block-shaped"
                                               (str error)))
                                  (is (= before (vec (d/datoms (conn/get-db) :eavt))))))))))))
          (p/catch #(is false (str "node ref write failed: " %)))
          (p/finally done)))))

(deftest node-reference-recycled-target-rejected
  (async done
    (let [{:keys [target ref-target related]} (typed-fixture!)]
      (conn/transact! nil [{:db/id [:block/uuid (uuid ref-target)]
                            :logseq.property/deleted-at 1}])
      (let [before (vec (d/datoms (conn/get-db) :eavt))]
        (-> (api-test/with-plugin-api
             (fn []
               (-> (upsert
                    (json-ops [{:operation "edit" :entityType "block" :id target
                                :data {:properties {related {:uuid ref-target}}}}])
                    #js {})
                   (p/then #(is false "recycled ref target must be rejected"))
                   (p/catch (fn [error]
                              (is (re-find #"recycled" (str error)))
                              (is (= before (vec (d/datoms (conn/get-db) :eavt)))))))))
            (p/catch #(is false (str %)))
            (p/finally done))))))

(deftest date-reference-write-and-reject
  (async done
    (let [{:keys [target ref-target journal day]} (typed-fixture!)]
      (-> (api-test/with-plugin-api
           (fn []
             (p/let [_ (upsert
                        (json-ops [{:operation "edit" :entityType "block" :id target
                                    :data {:properties {day {:uuid journal}}}}])
                        #js {})
                     db (conn/get-db)
                     edited (d/entity db [:block/uuid (uuid target)])]
               (is (= journal (ref-uuid edited day)) "date ref stores the journal page uuid")
               (p/doseq [value [ref-target {:uuid "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"}]]
                 (let [before (vec (d/datoms (conn/get-db) :eavt))]
                   (-> (upsert
                        (json-ops [{:operation "edit" :entityType "block" :id target
                                    :data {:properties {day value}}}])
                        #js {})
                       (p/then #(is false (str "must reject date ref " (pr-str value))))
                       (p/catch (fn [error]
                                  (is (re-find #"journal page uuid|unknown uuid|reference envelope"
                                               (str error)))
                                  (is (= before (vec (d/datoms (conn/get-db) :eavt))))))))))))
          (p/catch #(is false (str "date ref write failed: " %)))
          (p/finally done)))))

(deftest asset-reference-write-and-reject
  (async done
    (let [{:keys [target ref-target asset media]} (typed-fixture!)]
      (-> (api-test/with-plugin-api
           (fn []
             (p/let [_ (upsert
                        (json-ops [{:operation "edit" :entityType "block" :id target
                                    :data {:properties {media {:uuid asset}}}}])
                        #js {})
                     db (conn/get-db)
                     edited (d/entity db [:block/uuid (uuid target)])]
               (is (= asset (ref-uuid edited media)) "asset ref stores the Asset block uuid")
               (let [before (vec (d/datoms (conn/get-db) :eavt))]
                 (-> (upsert
                      (json-ops [{:operation "edit" :entityType "block" :id target
                                  :data {:properties {media {:uuid ref-target}}}}])
                      #js {})
                     (p/then #(is false "non-asset target must be rejected"))
                     (p/catch (fn [error]
                                (is (re-find #"Asset block" (str error)))
                                (is (= before (vec (d/datoms (conn/get-db) :eavt)))))))))))
          (p/catch #(is false (str "asset ref write failed: " %)))
          (p/finally done)))))

(deftest many-typed-values-are-additive
  (async done
    (let [{:keys [target numbers]} (typed-fixture!)]
      (-> (api-test/with-plugin-api
           (fn []
             (p/let [_ (upsert
                        (json-ops [{:operation "edit" :entityType "block" :id target
                                    :data {:properties {numbers [1 2]}}}])
                        #js {})
                     _ (upsert
                        (json-ops [{:operation "edit" :entityType "block" :id target
                                    :data {:properties {numbers [3]}}}])
                        #js {})
                     db (conn/get-db)
                     edited (d/entity db [:block/uuid (uuid target)])
                     values (set (map #(:logseq.property/value (resolve-ref %))
                                      (get edited (keyword numbers))))]
               (is (= #{1 2 3} values) "importer unions many values instead of replacing")
               (p/doseq [value [1 "x" nil []]]
                 (let [before (vec (d/datoms (conn/get-db) :eavt))]
                   (-> (upsert
                        (json-ops [{:operation "edit" :entityType "block" :id target
                                    :data {:properties {numbers value}}}])
                        #js {})
                       (p/then #(is false (str "must reject many value " (pr-str value))))
                       (p/catch (fn [_] (is (= before (vec (d/datoms (conn/get-db) :eavt))))))))))))
          (p/catch #(is false (str "many write failed: " %)))
          (p/finally done)))))

;; ============================================================================
;; Stage 2: content page add/edit with properties, and property-only page edits.
;; A page property edit must update only properties: it must not create a block,
;; duplicate the page, or disturb the existing outline.
;; ============================================================================
(defn- page-fixture!
  []
  (sqlite-build/create-blocks
   (conn/get-db nil false)
   {:properties {:user.property/label {:block/title "Label" :logseq.property/type :default}
                 :user.property/link {:block/title "Link" :logseq.property/type :node}
                 :user.property/keywords {:block/title "Keywords" :logseq.property/type :default
                                          :db/cardinality :db.cardinality/many}}
    :pages-and-blocks [{:page {:block/title "Person Page"}
                        :blocks [{:block/title "child one"}
                                 {:block/title "child two"}]}
                       {:page {:block/title "Ref Page"}}]})
  (let [db (conn/get-db)
        page (ldb/get-page db "Person Page")
        ref-page (ldb/get-page db "Ref Page")]
    (assert (d/entity db :user.property/label))
    (assert (d/entity db :user.property/link))
    (assert (d/entity db :user.property/keywords))
    {:page (str (:block/uuid page))
     :ref-page (str (:block/uuid ref-page))
     :label "user.property/label"
     :link "user.property/link"
     :keywords "user.property/keywords"}))

(deftest page-add-with-properties
  (async done
    (let [{:keys [ref-page label link keywords]} (page-fixture!)]
      (-> (api-test/with-plugin-api
           (fn []
             (p/let [_ (upsert
                        (json-ops [{:operation "add" :entityType "page"
                                    :data {:title "Added Person Page"
                                           :properties {label "Ada"
                                                        link {:uuid ref-page}
                                                        keywords ["math" "code"]}}}])
                        #js {})
                     db (conn/get-db)
                     page (ldb/get-page db "Added Person Page")]
               (is (some? page) "page add creates the page once")
               (is (= "Ada" (pval page label)))
               (is (= ref-page (ref-uuid page link)))
               (is (= #{"math" "code"}
                      (set (map :block/title (get page (keyword keywords)))))
                   "many-valued page property stores both keywords")
               (is (= 1 (count (entity-util/get-pages-by-name db "Added Person Page")))
                   "no duplicate page generation is created"))))
           (p/catch #(is false (str "page add failed: " %)))
           (p/finally done)))))

(deftest page-property-only-edit-retains-outline
  (async done
    (let [{:keys [page ref-page label link]} (page-fixture!)
          before-datoms (vec (d/datoms (conn/get-db) :eavt))]
      (-> (api-test/with-plugin-api
           (fn []
             (p/let [_ (upsert
                        (json-ops [{:operation "edit" :entityType "page" :id page
                                    :data {:properties {label "Grace" link {:uuid ref-page}}}}])
                        #js {})
                     db (conn/get-db)
                     edited (d/entity db [:block/uuid (uuid page)])
                     blocks (->> (ldb/get-page-blocks db (:db/id edited))
                                 (remove #(or (:logseq.property/created-from-property %)
                                              (:block/closed-value-property %))))]
               (is (= "Person Page" (:block/title edited)) "title is preserved")
               (is (= "Grace" (pval edited label)))
               (is (= ref-page (ref-uuid edited link)))
               (is (= ["child one" "child two"] (mapv :block/title blocks))
                   "outline children are untouched by a property-only edit")
               (is (some? (ldb/get-page db "Person Page")) "page is not duplicated")
               (is (< (count before-datoms) (count (d/datoms db :eavt)))
                   "the edit adds property datoms rather than replacing the page"))))
           (p/catch #(is false (str "page edit failed: " %)))
           (p/finally done)))))

(deftest page-property-edit-rejects-empty-and-non-property-data
  (async done
    (let [{:keys [page label]} (page-fixture!)]
      (-> (api-test/with-plugin-api
           (fn []
             (p/doseq [ops [[{:operation "edit" :entityType "page" :id page :data {:properties {}}}]
                            [{:operation "edit" :entityType "page" :id page
                              :data {:title "Renamed" :properties {label "x"}}}]
                            [{:operation "edit" :entityType "page" :id page
                              :data {:properties {"user.property/nope" "x"}}}]
                            [{:operation "edit" :entityType "page"
                              :id "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
                              :data {:properties {label "x"}}}]]]
               (let [before (vec (d/datoms (conn/get-db) :eavt))]
                 (-> (upsert (json-ops ops) #js {})
                     (p/then #(is false (str "must reject page edit " (pr-str ops))))
                     (p/catch (fn [_]
                                (is (= before (vec (d/datoms (conn/get-db) :eavt)))
                                    "rejected page edit leaves db unchanged"))))))))
           (p/catch #(is false (str %)))
           (p/finally done)))))

(deftest property-key-accepts-uuid-and-qualified-ident-only
  (async done
    (let [{:keys [target score]} (typed-fixture!)
          score-uuid (str (:block/uuid (d/entity (conn/get-db) :user.property/score)))]
      (-> (api-test/with-plugin-api
           (fn []
             (p/let [_ (upsert
                        (json-ops [{:operation "edit" :entityType "block" :id target
                                    :data {:properties {score 11}}}])
                        #js {})
                     _ (upsert
                        (json-ops [{:operation "edit" :entityType "block" :id target
                                    :data {:properties {score-uuid 12}}}])
                        #js {})
                     db (conn/get-db)
                     edited (d/entity db [:block/uuid (uuid target)])]
               (is (= 12 (pval edited score))
                   "the property UUID and the exact qualified ident are both accepted")
               (p/doseq [key ["Score" "not-a-uuid/score"
                              "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa" "nope"]]
                 (let [before (vec (d/datoms (conn/get-db) :eavt))]
                   (-> (upsert
                        (json-ops [{:operation "edit" :entityType "block" :id target
                                    :data {:properties {key 1}}}])
                        #js {})
                       (p/then #(is false (str "must reject property key " key)))
                       (p/catch (fn [error]
                                  (is (re-find #"existing property UUID|bare title"
                                               (str error)))
                                  (is (= before (vec (d/datoms (conn/get-db) :eavt))))))))))))
           (p/catch #(is false (str "key resolution failed: " %)))
           (p/finally done)))))

(deftest block-add-property-receipt-verifies-observed-value
  (async done
    (let [{:keys [page score]} (typed-fixture!)]
      (-> (api-test/with-plugin-api
           (fn []
             (p/let [receipt* (upsert
                               (json-ops [{:operation "add" :entityType "block"
                                           :data {:page-id page :title "receipt add"
                                                  :properties {score 12.5}}}])
                               #js {:receipt true})
                     receipt (js->clj receipt* :keywordize-keys true)
                     uuid-str (get-in receipt [:operations 0 :uuid])]
               (is (= "verified" (:mode receipt)))
               (is (= 12.5 (get-in receipt [:operations 0 :entity :properties (keyword score)]))
                   "receipt reports the observed block property")
               (is (= 12.5 (pval (d/entity (conn/get-db) [:block/uuid (uuid uuid-str)]) score))))))
           (p/catch #(is false (str "block property receipt failed: " %)))
           (p/finally done)))))

(deftest page-add-property-receipt-verifies-planned-uuid
  (async done
    (let [{:keys [ref-page label link]} (page-fixture!)
          page-name "Receipt Person Page"]
      (-> (api-test/with-plugin-api
           (fn []
             (p/let [receipt* (upsert
                               (json-ops [{:operation "add" :entityType "page"
                                           :data {:title page-name
                                                  :properties {label "Ada"
                                                               link {:uuid ref-page}}}}])
                               #js {:receipt true})
                     receipt (js->clj receipt* :keywordize-keys true)
                     uuid-str (get-in receipt [:operations 0 :uuid])
                     db (conn/get-db)
                     page (d/entity db [:block/uuid (uuid uuid-str)])]
               (is (= "verified" (:mode receipt)))
               (is (not (contains? (first (:operations receipt)) :page-uuid))
                   "page receipts do not claim a parent page")
               (is (= "Ada" (get-in receipt [:operations 0 :entity :properties (keyword label)])))
               (is (= ref-page (get-in receipt [:operations 0 :entity :properties (keyword link)])))
               (is (= page-name (:block/title page)))
               (is (= 1 (count (entity-util/get-pages-by-name db page-name)))))))
           (p/catch #(is false (str "page property receipt failed: " %)))
           (p/finally done)))))

(deftest page-property-edit-receipt-verifies-observed-value
  (async done
    (let [{:keys [page label]} (page-fixture!)]
      (-> (api-test/with-plugin-api
           (fn []
             (p/let [receipt* (upsert
                               (json-ops [{:operation "edit" :entityType "page" :id page
                                           :data {:properties {label "Grace"}}}])
                               #js {:receipt true})
                     receipt (js->clj receipt* :keywordize-keys true)]
               (is (= "verified" (:mode receipt)))
               (is (= "Grace" (get-in receipt [:operations 0 :entity :properties (keyword label)])))
               (is (= "Person Page" (get-in receipt [:operations 0 :entity :title]))))))
           (p/catch #(is false (str "page property edit receipt failed: " %)))
           (p/finally done)))))

(deftest many-property-receipt-accepts-observed-superset
  (async done
    (let [{:keys [target numbers]} (typed-fixture!)]
      (-> (api-test/with-plugin-api
           (fn []
             (p/let [_ (upsert
                        (json-ops [{:operation "edit" :entityType "block" :id target
                                    :data {:properties {numbers [1 2 3]}}}])
                        #js {})
                     receipt* (upsert
                               (json-ops [{:operation "edit" :entityType "block" :id target
                                           :data {:properties {numbers [2 3]}}}])
                               #js {:receipt true})
                     receipt (js->clj receipt* :keywordize-keys true)]
               (is (= "verified" (:mode receipt))
                   "a subset of the observed many values verifies additively")
                (is (= #{1 2 3}
                       (set (get-in receipt [:operations 0 :entity :properties (keyword numbers)])))
                     "the receipt reports the full observed many-value set"))))
           (p/catch #(is false (str "many property receipt failed: " %)))
           (p/finally done)))))

;; ============================================================================
;; Stage 4 gap 2: ident-less user closed values resolve by uuid or display value,
;; and unknown or ambiguous display values fail visibly instead of nil-erroring
;; or silently picking the first choice.
;; ============================================================================
(defn- closed-fixture!
  []
  (sqlite-build/create-blocks
   (conn/get-db nil false)
   {:properties
    {:user.property/tier {:block/title "Tier" :logseq.property/type :default
                          :build/closed-values [{:value "Alpha" :uuid (random-uuid)}
                                                {:value "Beta" :uuid (random-uuid)}]}
     :user.property/dup {:block/title "Dup" :logseq.property/type :default
                         :build/closed-values [{:value "Same" :uuid (random-uuid)}
                                               {:value "Same" :uuid (random-uuid)}]}}
    :pages-and-blocks [{:page {:block/title "Closed Page"}
                        :blocks [{:block/title "closed target"}]}]})
  (let [db (conn/get-db)
        page (ldb/get-page db "Closed Page")
        block (first (filter #(= "closed target" (:block/title %))
                             (ldb/get-page-blocks db (:db/id page))))
        choice (fn [prop title]
                 (first (filter #(= title (:block/title %))
                                (:property/closed-values (d/entity db prop)))))]
    (assert (choice :user.property/tier "Alpha"))
    (assert (choice :user.property/tier "Beta"))
    (assert (choice :user.property/dup "Same"))
    {:target (str (:block/uuid block))
     :tier "user.property/tier"
     :dup "user.property/dup"
     :alpha (str (:block/uuid (choice :user.property/tier "Alpha")))}))

(deftest ident-less-closed-values-write-by-uuid-and-display
  (async done
    (let [{:keys [target tier alpha]} (closed-fixture!)]
      (-> (api-test/with-plugin-api
           (fn []
             (p/let [_ (upsert
                        (json-ops [{:operation "edit" :entityType "block" :id target
                                    :data {:properties {tier alpha}}}])
                        #js {})
                     db (conn/get-db)
                     edited (d/entity db [:block/uuid (uuid target)])]
               (is (= "Alpha" (pval edited tier))
                   "an ident-less closed value is writable by its stable uuid")
               (p/let [_ (upsert
                          (json-ops [{:operation "edit" :entityType "block" :id target
                                      :data {:properties {tier "Beta"}}}])
                          #js {})
                       edited (d/entity (conn/get-db) [:block/uuid (uuid target)])]
                 (is (= "Beta" (pval edited tier))
                     "an ident-less closed value is writable by its display value")))))
           (p/catch #(is false (str "ident-less closed write failed: " %)))
           (p/finally done)))))

(deftest closed-value-writes-reject-unknown-and-ambiguous
  (async done
    (let [{:keys [target tier dup]} (closed-fixture!)]
      (-> (api-test/with-plugin-api
           (fn []
             (p/doseq [[key value pattern]
                       [[tier "Gamma" #"must be an existing closed value"]
                        [dup "Same" #"ambiguous"]
                        [tier "not-a-uuid" #"must be an existing closed value"]]]
               (let [before (vec (d/datoms (conn/get-db) :eavt))]
                 (-> (upsert
                      (json-ops [{:operation "edit" :entityType "block" :id target
                                  :data {:properties {key value}}}])
                      #js {})
                     (p/then #(is false (str "must reject closed value " (pr-str value))))
                     (p/catch (fn [error]
                                (is (re-find pattern (str error)))
                                (is (= before (vec (d/datoms (conn/get-db) :eavt)))
                                    "rejected closed-value write leaves db unchanged"))))))))
           (p/catch #(is false (str "closed reject test failed: " %)))
           (p/finally done)))))

;; ============================================================================
;; Stage 4 gap 3: hidden targets and targets with a hidden ancestor are rejected
;; by reference writes, not only recycled ones.
;; ============================================================================
(defn- ref-story-fixture!
  []
  (sqlite-build/create-blocks
   (conn/get-db nil false)
   {:properties {:user.property/linked {:block/title "Linked" :logseq.property/type :node}}
    :pages-and-blocks [{:page {:block/title "Ref Story Page"}
                        :blocks [{:block/title "story target"}
                                 {:block/title "story parent"}
                                 {:block/title "story child"}]}]})
  (let [db (conn/get-db)
        page (ldb/get-page db "Ref Story Page")
        blocks (ldb/get-page-blocks db (:db/id page))
        by-title (fn [t] (first (filter #(= t (:block/title %)) blocks)))
        parent (by-title "story parent")
        child (by-title "story child")]
    (assert parent "fixture creates the story parent")
    (assert child "fixture creates the nested story child")
    (conn/transact! nil [{:db/id (:db/id child) :block/parent (:db/id parent)}])
    {:target (str (:block/uuid (by-title "story target")))
     :parent (str (:block/uuid parent))
     :child (str (:block/uuid child))
     :link "user.property/linked"}))

(deftest hidden-reference-targets-and-ancestors-are-rejected
  (async done
    (let [{:keys [target parent child link]} (ref-story-fixture!)]
      (-> (api-test/with-plugin-api
           (fn []
             (p/let [_ (upsert
                        (json-ops [{:operation "edit" :entityType "block" :id target
                                    :data {:properties {link {:uuid child}}}}])
                        #js {})
                     db (conn/get-db)
                     edited (d/entity db [:block/uuid (uuid target)])]
               (is (= child (ref-uuid edited link))
                   "a visible child is a valid reference target before its parent is hidden")
               (conn/transact! nil [{:db/id [:block/uuid (uuid parent)]
                                     :logseq.property/hide? true}])
               (let [before (vec (d/datoms (conn/get-db) :eavt))]
                 (-> (upsert
                      (json-ops [{:operation "edit" :entityType "block" :id target
                                  :data {:properties {link {:uuid child}}}}])
                      #js {})
                     (p/then #(is false "a target with a hidden ancestor must be rejected"))
                     (p/catch (fn [error]
                                (is (re-find #"hidden" (str error)))
                                (is (= before (vec (d/datoms (conn/get-db) :eavt))))))))
               (conn/transact! nil [{:db/id [:block/uuid (uuid target)]
                                     :logseq.property/hide? true}])
               (let [before (vec (d/datoms (conn/get-db) :eavt))]
                 (-> (upsert
                      (json-ops [{:operation "edit" :entityType "block" :id target
                                  :data {:properties {link {:uuid target}}}}])
                      #js {})
                     (p/then #(is false "a directly hidden target must be rejected"))
                     (p/catch (fn [error]
                                (is (re-find #"hidden|visible" (str error)))
                                (is (= before (vec (d/datoms (conn/get-db) :eavt)))))))))))
           (p/catch #(is false (str "hidden ref target test failed: " %)))
           (p/finally done)))))

;; ============================================================================
;; Stage 4 gap 4: public getPage/getBlock round-trip property values without
;; leaking hidden metadata or property-value pseudochildren.
;; ============================================================================
(deftest get-page-and-block-round-trip-typed-properties
  (async done
    (let [{:keys [target text website flag moment score related day numbers
                  ref-target journal]} (typed-fixture!)]
      (-> (api-test/with-plugin-api
           (fn []
             (p/let [props {text "hello"
                            website "https://example.com/path"
                            flag true
                            moment 1700000000000
                            score 3.5
                            related {:uuid ref-target}
                            day {:uuid journal}
                            numbers [1 2]}
                     _ (upsert
                        (json-ops [{:operation "edit" :entityType "block" :id target
                                    :data {:properties props}}])
                        #js {})
                     db (conn/get-db)
                     block (db-tools/get-block db target {})
                     page-data (db-tools/get-page-data db "Typed Write Page"
                                                       {:include-children? true :max-blocks 100})
                     entity (:entity page-data)
                     content-blocks (tree-seq (constantly true) :block/children (:blocks page-data))]
               (is (nil? (:error block)))
               (is (= "hello" (get-in block [(keyword text) :block/title]))
                   "getBlock exposes a default property's value")
               (is (= "https://example.com/path"
                      (get-in block [(keyword website) :block/title]))
                   "getBlock exposes a url property's value")
               (is (= 3.5 (get-in block [(keyword score) :logseq.property/value]))
                   "getBlock exposes a number property's value, not just its ref")
               (is (= true (get-in block [(keyword flag)]))
                   "getBlock exposes a checkbox stored directly on the block")
               (is (= 1700000000000 (get-in block [(keyword moment)]))
                   "getBlock exposes a datetime stored directly on the block")
               (is (= ref-target (get-in block [(keyword related) :block/uuid]))
                   "getBlock exposes a node ref's stable uuid")
               (is (= journal (get-in block [(keyword day) :block/uuid]))
                   "getBlock exposes a date ref's journal uuid")
               (is (= #{1 2} (set (map :logseq.property/value (get block (keyword numbers)))))
                   "getBlock exposes every many-valued number")
               (is (not-any? #(or (:block/closed-value-property %)
                                  (:logseq.property/created-from-property %)
                                  (:logseq.property/hide? %))
                             content-blocks)
                   "no property-value pseudochildren or hidden metadata leak into getPage blocks")
               (is (not (contains? entity :logseq.property/hide?))
                   "hidden metadata never surfaces on the page entity"))))
           (p/catch #(is false (str "round-trip test failed: " %)))
           (p/finally done)))))

(deftest get-page-round-trip-page-properties
  (async done
    (let [{:keys [page ref-page label link keywords]} (page-fixture!)]
      (-> (api-test/with-plugin-api
           (fn []
             (p/let [_ (upsert
                        (json-ops [{:operation "edit" :entityType "page" :id page
                                    :data {:properties {label "Ada"
                                                        link {:uuid ref-page}
                                                        keywords ["math" "code"]}}}])
                        #js {})
                     db (conn/get-db)
                     data (db-tools/get-page-data db "Person Page" {})
                     entity (:entity data)]
               (is (= "Ada" (get-in entity [(keyword label) :block/title]))
                   "getPage exposes a default page property")
                (is (= ref-page (str (get-in entity [(keyword link) :block/uuid])))
                    "getPage exposes a node page property's stable uuid")
               (is (= #{"math" "code"}
                      (set (map :block/title (get entity (keyword keywords)))))
                    "getPage exposes every many-valued page property")
                (is (not (contains? entity :db/id))
                    "default getPage entity omits internal DataScript ids")
                (is (not (contains? entity :block/refs))
                    "default getPage entity omits raw ref attributes")
               (is (= ["child one" "child two"]
                      (mapv :block/title (:blocks data)))
                   "a page property edit does not disturb the content outline"))))
           (p/catch #(is false (str "page round-trip test failed: " %)))
           (p/finally done)))))

;; ============================================================================
;; Stage 4 gap 3: reference writes honor the property's allowed target classes
;; using the shared identity/inheritance check, so a target outside the range
;; fails visibly while an in-range target is written.
;; ============================================================================
(defn- class-fixture!
  []
  (sqlite-build/create-blocks
   (conn/get-db nil false)
   {:classes {:user.class/widget {:block/title "Widget"}}
    :properties {:user.property/owned {:block/title "Owned" :logseq.property/type :node
                                       :build/property-classes [:user.class/widget]}}
    :pages-and-blocks [{:page {:block/title "Class Page"}
                        :blocks [{:block/title "widget target"
                                  :block/tags [{:db/ident :user.class/widget}]}
                                 {:block/title "plain target"}]}]})
  (let [db (conn/get-db)
        page (ldb/get-page db "Class Page")
        blocks (ldb/get-page-blocks db (:db/id page))
        by-title (fn [t] (first (filter #(= t (:block/title %)) blocks)))]
    (assert (d/entity db :user.property/owned) "fixture creates the restricted property")
    {:widget (str (:block/uuid (by-title "widget target")))
     :plain (str (:block/uuid (by-title "plain target")))
     :owned "user.property/owned"}))

(deftest reference-target-class-restriction
  (async done
    (let [{:keys [widget plain owned]} (class-fixture!)]
      (-> (api-test/with-plugin-api
           (fn []
             (p/let [_ (upsert
                        (json-ops [{:operation "edit" :entityType "block" :id widget
                                    :data {:properties {owned {:uuid widget}}}}])
                        #js {})
                     db (conn/get-db)
                     edited (d/entity db [:block/uuid (uuid widget)])]
               (is (= widget (ref-uuid edited owned))
                   "a target tagged with the allowed class is writable")
               (let [before (vec (d/datoms (conn/get-db) :eavt))]
                 (-> (upsert
                      (json-ops [{:operation "edit" :entityType "block" :id widget
                                  :data {:properties {owned {:uuid plain}}}}])
                      #js {})
                     (p/then #(is false "a target outside the allowed class must be rejected"))
                     (p/catch (fn [error]
                                (is (re-find #"requires a target tagged" (str error)))
                                (is (= before (vec (d/datoms (conn/get-db) :eavt)))))))))))
           (p/catch #(is false (str "class restriction test failed: " %)))
           (p/finally done)))))

;; ============================================================================
;; Stage 4 gap 5: the receipt verifier rejects wrong, missing and
;; non-additive observed values instead of reporting a verified receipt.
;; ============================================================================
(deftest receipt-verifier-rejects-mismatched-observed-values
  (async done
    (let [{:keys [target ref-target score related numbers text]} (typed-fixture!)]
      (-> (api-test/with-plugin-api
           (fn []
             (p/let [_ (upsert
                        (json-ops [{:operation "edit" :entityType "block" :id target
                                    :data {:properties {score 5
                                                        related {:uuid ref-target}
                                                        numbers [1 2]}}}])
                        #js {})
                     db (conn/get-db)
                     verify (fn [props]
                              (db-tools/read-upsert-blocks
                               db [{:uuid target :properties props}]))]
               (is (not (:error (first (verify {score 5}))))
                   "the real observed scalar verifies")
               (is (re-find #"does not match" (str (:error (first (verify {score 6})))))
                   "a wrong scalar observed value is rejected")
               (is (re-find #"does not match"
                            (str (:error (first (verify {related {:uuid target}})))))
                   "a wrong ref observed value is rejected")
               (is (re-find #"does not match"
                            (str (:error (first (verify {numbers [1 2 9]})))))
                   "a many value that is not a subset of the observed set is rejected")
               (is (re-find #"does not match" (str (:error (first (verify {text "nope"})))))
                   "an unobserved property value is rejected"))))
           (p/catch #(is false (str "receipt verifier negative test failed: " %)))
           (p/finally done)))))

;; Issue #22: the API should be able to write a simple query onto a block via
;; the built-in hidden `logseq.property/query` property. The value is a
;; property-value node titled with the query content. Current gate rejects
;; hidden properties, so this test is expected RED until that is implemented.
(deftest controlled-query-simple-write
  (async done
    (let [{:keys [block]} (fixture!)]
      (-> (api-test/with-plugin-api
           (fn []
             (p/let [receipt* (upsert
                               (json-ops [{:operation "edit" :entityType "block" :id block
                                           :data {:properties {"logseq.property/query"
                                                               {:mode "simple" :content "(task Todo)"}}}}])
                               #js {:receipt true})
                     receipt (js->clj receipt* :keywordize-keys true)]
               (is (= "verified" (:mode receipt))
                   "the controlled query write succeeds and returns a verified receipt")
               (let [db (conn/get-db)
                     edited (d/entity db [:block/uuid (uuid block)])
                     query-val (:logseq.property/query edited)]
                 (is (some? query-val) "block now carries a query property value")
                 (is (= "(task Todo)"
                        (:block/title (if (:block/uuid query-val)
                                        query-val
                                        (d/entity db query-val))))
                     "query property value node title is the query content")))))
           (p/catch #(is false (str "controlled query simple write failed: " %)))
           (p/finally done)))))

;; Focused contract tests for the controlled query write (issue #22).
(defn- query-value-node
  "The stored query property value node of an edited block, or nil.
   Pull results and entities may return the value node as a bare {:db/id n}
   stub, so always re-resolve through the db."
  [db block-uuid-str]
  (when-let [block (d/entity db [:block/uuid (uuid block-uuid-str)])]
    (when-let [v (:logseq.property/query block)]
      (cond
        (:db/id v) (d/entity db (:db/id v))
        (map? v) v
        :else (d/entity db v)))))

(defn- query-write-rejected?
  "Runs one query property upsert and returns [rejected? error-str db-after]."
  [block-uuid-str value]
  (p/let [before (vec (d/datoms (conn/get-db) :eavt))
          result (-> (upsert
                      (json-ops [{:operation "edit" :entityType "block" :id block-uuid-str
                                  :data {:properties {"logseq.property/query" value}}}])
                      #js {})
                     (p/then (fn [receipt*]
                               [false (js->clj receipt* :keywordize-keys true)]))
                     (p/catch (fn [error] [true (str error)])))
          [rejected? payload] result
          db-after (conn/get-db)]
    [rejected? payload db-after before]))

(deftest controlled-query-advanced-write-and-same-mode-edit
  (async done
    (let [{:keys [block]} (fixture!)]
      (-> (api-test/with-plugin-api
           (fn []
             (p/let [receipt* (upsert
                               (json-ops [{:operation "edit" :entityType "block" :id block
                                           :data {:properties {"logseq.property/query"
                                                               {:mode "advanced" :content "{:query [(task Todo)]}"}}}}])
                               #js {:receipt true})
                     receipt (js->clj receipt* :keywordize-keys true)
                     _ (is (= "verified" (:mode receipt)) "advanced write receipt is verified")
                     entity (get-in receipt [:operations 0 :entity])
                     observed (get (:properties entity) :logseq.property/query)]
               (is (= {:mode "advanced" :content "{:query [(task Todo)]}"} observed)
                   "the receipt readback verifies content and mode from actual storage")
               (let [db (conn/get-db)
                     node (query-value-node db block)
                     uuid-before (:block/uuid node)]
                 (is (some? node) "advanced write creates the value node")
                 (is (= :code (:logseq.property.node/display-type node))
                     "advanced value node carries the code display type")
                 (is (string? (:logseq.property.code/lang node))
                     "advanced value node carries a code lang")
                 ;; same-mode edit: content changes, lang survives, uuid preserved
                 (p/let [receipt2* (upsert
                                    (json-ops [{:operation "edit" :entityType "block" :id block
                                                :data {:properties {"logseq.property/query"
                                                                    {:mode "advanced" :content "{:query [(task Doing)]}"}}}}])
                                    #js {:receipt true})
                         receipt2 (js->clj receipt2* :keywordize-keys true)
                         node2 (query-value-node (conn/get-db) block)]
                   (is (= "{:query [(task Doing)]}" (get-in receipt2 [:operations 0 :entity :properties :logseq.property/query :content]))
                       "the same-mode advanced edit updates the content")
                   (is (= uuid-before (:block/uuid node2))
                       "the same-mode advanced edit preserves the value node uuid")
                   (is (= :code (:logseq.property.node/display-type node2))
                       "the same-mode advanced edit keeps the code display type")
                   (is (= "clojure" (:logseq.property.code/lang node2))
                       "the same-mode advanced edit keeps the exact clojure lang"))))))
           (p/catch #(is false (str "controlled query advanced write failed: " %)))
           (p/finally done)))))

(deftest controlled-query-mode-switches-retract-and-preserve
  (async done
    (let [{:keys [block]} (fixture!)]
      (-> (api-test/with-plugin-api
           (fn []
             (p/let [_ (upsert
                        (json-ops [{:operation "edit" :entityType "block" :id block
                                    :data {:properties {"logseq.property/query"
                                                        {:mode "advanced" :content "{:query [(task Todo)]}"}}}}])
                        #js {})
                        node1 (query-value-node (conn/get-db) block)
                        uuid1 (:block/uuid node1)
                        _ (is (some? (:logseq.property.node/display-type node1))
                              "advanced mode has code metadata before the switch")
                        ;; advanced -> simple: stale metadata retracted, uuid preserved
                        receipt* (upsert
                                  (json-ops [{:operation "edit" :entityType "block" :id block
                                              :data {:properties {"logseq.property/query"
                                                                  {:mode "simple" :content "(task Todo)"}}}}])
                                  #js {:receipt true})
                        receipt (js->clj receipt* :keywordize-keys true)
                        observed (get-in receipt [:operations 0 :entity :properties :logseq.property/query])]
               (is (= {:mode "simple" :content "(task Todo)"} observed)
                   "the switch receipt reads back a metadata-free simple mode from storage")
               (let [db (conn/get-db)
                     node2 (query-value-node db block)]
                 (is (= uuid1 (:block/uuid node2))
                     "the advanced->simple switch preserves the value node uuid")
                 (is (nil? (:logseq.property.node/display-type node2))
                     "the switch retracts the stale code display type")
                 (is (nil? (:logseq.property.code/lang node2))
                     "the switch retracts the stale code lang")
                 (is (= "(task Todo)" (:block/title node2))
                     "the switch keeps the new simple content on the same node")
                 ;; simple -> advanced: metadata seeded, uuid still preserved
                 (p/let [receipt3* (upsert
                                    (json-ops [{:operation "edit" :entityType "block" :id block
                                                :data {:properties {"logseq.property/query"
                                                                    {:mode "advanced" :content "{:query [(task Todo)]}"}}}}])
                                    #js {:receipt true})
                         receipt3 (js->clj receipt3* :keywordize-keys true)]
                   (is (= "advanced" (get-in receipt3 [:operations 0 :entity :properties :logseq.property/query :mode]))
                       "the simple->advanced switch reads back advanced mode")
                   (let [node3 (query-value-node (conn/get-db) block)]
                     (is (= uuid1 (:block/uuid node3))
                         "the simple->advanced switch preserves the value node uuid")
                     (is (= :code (:logseq.property.node/display-type node3))
                         "the switch seeds the code display type")
                     (is (string? (:logseq.property.code/lang node3))
                         "the switch seeds a code lang")))))))
           (p/catch #(is false (str "controlled query mode switch failed: " %)))
           (p/finally done)))))

(deftest controlled-query-rejects-malformed-input-and-invalid-contexts
  (async done
    (let [{:keys [block page]} (fixture!)]
      (-> (api-test/with-plugin-api
           (fn []
             (p/let [_ (p/doseq [[label value pattern]
                                 [["bare string" "(task Todo)" #"envelope"]
                                  ["missing mode" {:content "(task Todo)"} #"envelope"]
                                  ["unknown mode" {:mode "query" :content "(task Todo)"} #"envelope"]
                                  ["missing content" {:mode "simple"} #"envelope"]
                                  ["non-string content" {:mode "simple" :content 42} #"envelope"]
                                  ["blank content" {:mode "simple" :content "   "} #"non-empty string"]
                                  ["advanced non-map-edn" {:mode "advanced" :content "(task Todo)"} #":query key"]
                                  ["advanced not-a-query-map" {:mode "advanced" :content "{:rules []}"} #":query key"]
                                  ["advanced unevalable-form" {:mode "advanced" :content "#=(js/alert 1)"} #"query EDN"]]]
                      (p/let [[rejected? error db-after before] (query-write-rejected? block value)]
                        (is rejected? (str label " is rejected: " (pr-str error)))
                        (when rejected?
                          (is (re-find pattern error)
                              (str label " rejection mentions the contract: " (pr-str error)))
                          (is (= before (vec (d/datoms db-after :eavt)))
                              (str label " rejection leaves db unchanged")))))
                     ;; the query property stays rejected on page operations
                     _ (-> (upsert
                            (json-ops [{:operation "edit" :entityType "page" :id page
                                        :data {:properties {"logseq.property/query"
                                                            {:mode "simple" :content "(task Todo)"}}}}])
                            #js {})
                          (p/then (fn [_] (is false "a page query write must be rejected")))
                          (p/catch (fn [error]
                                     (is (re-find #"only on add/edit block operations" (str error))
                                         "a page query write is rejected before any write"))))]
               nil)))
           (p/catch #(is false (str "controlled query rejects test failed: " %)))
           (p/finally done)))))

(deftest controlled-query-write-on-add-block
  (async done
    (let [{:keys [page]} (fixture!)]
      (-> (api-test/with-plugin-api
           (fn []
             (p/let [_ (upsert
                        (json-ops [{:operation "add" :entityType "block"
                                    :data {:page-id page :title "query carrier"
                                           :properties {"logseq.property/query"
                                                        {:mode "simple" :content "(task Doing)"}}}}])
                        #js {})
                     db (conn/get-db)
                     page-ent (ldb/get-page db page)
                     added (first (filter #(= "query carrier" (:block/title %))
                                          (ldb/get-page-blocks db (:db/id page-ent))))
                     node (when added
                            (when-let [v (:logseq.property/query added)]
                              (cond
                                (:db/id v) (d/entity db (:db/id v))
                                (map? v) v
                                :else (d/entity db v))))]
               (is (some? added) "the block with a query add op was created")
               (is (some? node) "the add op creates the query value node")
               (is (= "(task Doing)" (:block/title node))
                   "the add op value node carries the query content")
               (is (nil? (:logseq.property.node/display-type node))
                   "a simple add op value node carries no code metadata")
               (is (nil? (:logseq.property.code/lang node))
                   "a simple add op value node carries no code lang"))))
           (p/catch #(is false (str "controlled query add write failed: " %)))
           (p/finally done)))))

;; A value node whose existing lang is anything but clojure is not valid
;; advanced query configuration: an advanced write must overwrite it to the
;; exact UI contract (display-type :code, lang clojure).
(deftest controlled-query-advanced-overwrites-stale-lang
  (async done
    (let [{:keys [block]} (fixture!)]
      (-> (api-test/with-plugin-api
           (fn []
             (p/let [_ (upsert
                        (json-ops [{:operation "edit" :entityType "block" :id block
                                    :data {:properties {"logseq.property/query"
                                                        {:mode "advanced" :content "{:query [(task Todo)]}"}}}}])
                        #js {})
                     ;; Simulate a node that carries a foreign lang (e.g. from a
                     ;; corrupted or manually edited state) via the import seam.
                     _ (let [conn (conn/get-db nil false)
                             node (query-value-node (conn/get-db) block)]
                         (ldb/transact! conn [[:db/retract (:db/id node) :logseq.property.code/lang]
                                              [:db/add (:db/id node) :logseq.property.code/lang "javascript"]]))
                     _ (upsert
                        (json-ops [{:operation "edit" :entityType "block" :id block
                                    :data {:properties {"logseq.property/query"
                                                        {:mode "advanced" :content "{:query [(task Doing)]}"}}}}])
                        #js {})
                     node2 (query-value-node (conn/get-db) block)]
               (is (= "clojure" (:logseq.property.code/lang node2))
                   "an advanced write normalizes a stale non-clojure lang to clojure")
               (is (= :code (:logseq.property.node/display-type node2))
                   "an advanced write keeps the code display type"))))
           (p/catch #(is false (str "stale lang test failed: " %)))
           (p/finally done)))))

;; The receipt verifier measures what the worker actually stored. Exact
;; :code + clojure means advanced; BOTH metadata keys absent means simple;
;; any inconsistent combination must fail the receipt.
(deftest controlled-query-receipt-modes-are-strict
  (async done
    (let [{:keys [block]} (fixture!)]
      (-> (api-test/with-plugin-api
           (fn []
             (p/let [_upsert-result (upsert
                                     (json-ops [{:operation "edit" :entityType "block" :id block
                                                 :data {:properties {"logseq.property/query"
                                                                     {:mode "advanced" :content "{:query [(task Todo)]}"}}}}])
                                     #js {})
                      node (query-value-node (conn/get-db) block)
                      node-id (:db/id node)
                      ;; Observe through the PUBLIC worker readback path: an
                      ;; advanced claim verifies only against exact :code +
                      ;; clojure metadata; anything else fails the receipt.
                      verify-mode (fn []
                                    (let [result (first (db-tools/read-upsert-blocks
                                                         (conn/get-db)
                                                         [{:uuid block
                                                           :properties {"logseq.property/query"
                                                                        {:mode "advanced" :content "{:query [(task Todo)]}"}}}]))]
                                      (if (:error result) :simple :advanced)))
                      ;; consistent advanced observes advanced
                      _check-adv (is (= :advanced (verify-mode))
                                  "exact :code + clojure metadata verifies advanced")
                      ;; lang-only is NOT advanced
                      _check1 (ldb/transact! (conn/get-db nil false)
                                             [[:db/retract node-id :logseq.property.node/display-type]])
                      _check2 (is (= :simple (verify-mode))
                                  "a lang without display-type does not verify advanced")
                      ;; display-type :code with absent lang is NOT advanced
                      _check3 (ldb/transact! (conn/get-db nil false)
                                             [[:db/add node-id :logseq.property.node/display-type :code]
                                              [:db/retract node-id :logseq.property.code/lang]])
                      _check4 (is (= :simple (verify-mode))
                                  "a display-type without lang does not verify advanced")
                      ;; both present but wrong lang is NOT advanced
                      _check5 (ldb/transact! (conn/get-db nil false)
                                             [[:db/add node-id :logseq.property.code/lang "javascript"]])
                      _check6 (is (= :simple (verify-mode))
                                  "a non-clojure lang does not verify advanced")
                      ;; fully absent metadata is simple
                      _check7 (ldb/transact! (conn/get-db nil false)
                                             [[:db/retract node-id :logseq.property.node/display-type]
                                              [:db/retract node-id :logseq.property.code/lang]])
                      _check8 (is (= :simple (verify-mode))
                                  "no metadata observes simple mode")])))
           (p/catch #(is false (str "receipt strictness test failed: " %)))
           (p/finally done)))))


(deftest controlled-query-receipt-rejects-inconsistent-metadata
  (async done
    (let [{:keys [block]} (fixture!)]
      (-> (api-test/with-plugin-api
           (fn []
             (p/let [_ (upsert
                        (json-ops [{:operation "edit" :entityType "block" :id block
                                    :data {:properties {"logseq.property/query"
                                                        {:mode "advanced" :content "{:query [(task Todo)]}"}}}}])
                        #js {:receipt true})
                     db (conn/get-db)
                     node (query-value-node db block)
                     node-id (:db/id node)
                     ;; A simple-mode claim against metadata that IS present
                     ;; must fail the receipt readback: force an inconsistent
                     ;; state by dropping the lang from the live node.
                     _ (ldb/transact! (conn/get-db nil false)
                                      [[:db/retract node-id :logseq.property.code/lang]])
                     result (db-tools/read-upsert-blocks
                             (conn/get-db)
                             [{:uuid block
                               :properties {"logseq.property/query"
                                            {:mode "simple" :content "{:query [(task Todo)]}"}}}])]
               (is (re-find #"does not match" (str (:error (first result))))
                   "a simple claim against present code metadata fails the receipt")
               ;; and a foreign lang under an advanced claim fails too
               (ldb/transact! (conn/get-db nil false)
                              [[:db/add node-id :logseq.property.code/lang "javascript"]])
               (let [result2 (db-tools/read-upsert-blocks
                              (conn/get-db)
                              [{:uuid block
                                :properties {"logseq.property/query"
                                             {:mode "advanced" :content "{:query [(task Todo)]}"}}}])]
                 (is (re-find #"does not match" (str (:error (first result2))))
                     "an advanced claim against a non-clojure lang fails the receipt")))))
           (p/catch #(is false (str "receipt inconsistency test failed: " %)))
           (p/finally done)))))

;; The backend must attach the :logseq.class/Query tag on query add/edit
;; (matching the UI's advanced-query command) WITHOUT replacing other tags.
(deftest controlled-query-attaches-query-class-preserving-tags
  (async done
    (let [task-uuid "00000002-1282-1814-5700-000000000000"
          {:keys [page block]} (fixture!)]
      (-> (api-test/with-plugin-api
           (fn []
             (p/let [_ (upsert
                        (json-ops [{:operation "add" :entityType "block"
                                    :data {:page-id page :title "tagged query carrier"
                                           :tags [task-uuid]
                                           :properties {"logseq.property/query"
                                                        {:mode "advanced" :content "{:query [(task Todo)]}"}}}}
                                   {:operation "edit" :entityType "block" :id block
                                    :data {:properties {"logseq.property/query"
                                                        {:mode "simple" :content "(task Todo)"}}}}])
                        #js {})
                     db (conn/get-db)
                     page-ent (ldb/get-page db page)
                     added (d/entity db (:db/id (first (filter #(= "tagged query carrier" (:block/title %))
                                                               (ldb/get-page-blocks db (:db/id page-ent))))))
                     edited (d/entity db [:block/uuid (uuid block)])
                     tag-idents (fn [b] (set (map :db/ident (:block/tags b))))]
               (is (contains? (tag-idents added) :logseq.class/Query)
                   "a query add attaches the Query class")
               (is (contains? (tag-idents added) :logseq.class/Task)
                   "a query add preserves the block's other tags")
               (is (contains? (tag-idents edited) :logseq.class/Query)
                   "a query edit attaches the Query class"))))
           (p/catch #(is false (str "query class test failed: " %)))
           (p/finally done)))))

;; A dry-run query write performs no transaction.
(deftest controlled-query-dry-run-performs-no-write
  (async done
    (let [{:keys [block]} (fixture!)]
      (-> (api-test/with-plugin-api
           (fn []
             (p/let [before (vec (d/datoms (conn/get-db) :eavt))
                     receipt* (upsert
                               (json-ops [{:operation "edit" :entityType "block" :id block
                                           :data {:properties {"logseq.property/query"
                                                               {:mode "advanced" :content "{:query [(task Todo)]}"}}}}])
                               #js {:receipt true :dry-run true})
                     receipt (js->clj receipt* :keywordize-keys true)
                     after (vec (d/datoms (conn/get-db) :eavt))]
               (is (= "dry-run" (:mode receipt)) "the dry-run receipt reports dry-run mode")
               (is (= "advanced" (get-in receipt [:operations 0 :properties :logseq.property/query :mode]))
                   "the dry-run receipt still echoes the requested query envelope")
               (is (= before after) "a dry-run query write leaves the db unchanged"))))
           (p/catch #(is false (str "dry run test failed: " %)))
           (p/finally done)))))

;; One failing operation in a mixed batch must reject the whole batch before
;; any write, leaving the db byte-identical.
(deftest controlled-query-mixed-batch-failure-is-atomic
  (async done
    (let [{:keys [page block]} (fixture!)]
      (-> (api-test/with-plugin-api
           (fn []
             (p/let [before (vec (d/datoms (conn/get-db) :eavt))
                     result (-> (upsert
                                 (json-ops [{:operation "add" :entityType "block"
                                             :data {:page-id page :title "innocent sibling"}}
                                            {:operation "edit" :entityType "block" :id block
                                             :data {:properties {"logseq.property/query"
                                                                 {:mode "advanced" :content "(task Todo)"}}}}])
                                 #js {})
                                (p/then (fn [_] :written))
                                (p/catch (fn [error] [:rejected (str error)])))
                     outcome result]
               (is (vector? outcome) "the mixed batch rejects")
               (is (= :rejected (first outcome))
                   "a batch with an invalid query envelope is rejected")
               (is (re-find #"query EDN|:query key" (second outcome))
                   "the rejection names the advanced EDN contract")
               (is (= before (vec (d/datoms (conn/get-db) :eavt)))
                   "the rejected batch leaves the db byte-identical (no partial add)"))))
           (p/catch #(is false (str "mixed batch test failed: " %)))
           (p/finally done)))))

;; Editing a block with an existing query preserves host identity and
;; unrelated properties; the value node keeps a stable uuid property key.
(deftest controlled-query-edit-preserves-host-and-node-identity
  (async done
    (let [{:keys [block ident]} (fixture!)
          query-key "logseq.property/query"]
      (-> (api-test/with-plugin-api
           (fn []
             (p/let [_ (upsert
                        (json-ops [{:operation "edit" :entityType "block" :id block
                                    :data {:properties {ident 11
                                                        query-key {:mode "advanced"
                                                                   :content "{:query [(task Todo)]}"}}}}])
                        #js {})
                     db1 (conn/get-db)
                     host1 (d/entity db1 [:block/uuid (uuid block)])
                     node1 (query-value-node db1 block)
                     uuid1 (:block/uuid node1)
                     order1 (:block/order host1)
                     parent1 (:block/uuid (:block/parent host1))
                     _ (upsert
                        (json-ops [{:operation "edit" :entityType "block" :id block
                                    :data {:properties {query-key {:mode "advanced"
                                                                   :content "{:query [(task Doing)]}"}}}}])
                        #js {})
                     db2 (conn/get-db)
                     host2 (d/entity db2 [:block/uuid (uuid block)])
                     node2 (query-value-node db2 block)]
               (is (= uuid1 (:block/uuid node2))
                   "the query edit reuses the same value node uuid")
               (is (= (:block/order node1) (:block/order node2))
                   "the query value node order is preserved")
               (is (= (:block/created-at node1) (:block/created-at node2))
                   "the query value node creation timestamp is preserved")
               (is (= order1 (:block/order host2)) "the host block order is preserved")
               (is (= parent1 (:block/uuid (:block/parent host2)))
                   "the host block parent is preserved")
               (is (= 11 (some-> (get host2 ident) :logseq.property/value))
                   "the unrelated numeric property is preserved")
               (is (uuid? uuid1) "the value node is identified by its uuid property"))))
           (p/catch #(is false (str "identity preservation test failed: " %)))
           (p/finally done)))))
