(ns logseq.api.db-based.property-write-test
  (:require [cljs.test :refer [async deftest is use-fixtures]]
            [datascript.core :as d]
            [frontend.db.conn :as conn]
            [logseq.api.db-based.cli :as cli-api]
            [logseq.api.test-helper :as api-test]
            [logseq.db :as ldb]
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
  (js/JSON.parse (js/JSON.stringify (clj->js ops))))

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
                                (is (re-find #"Numeric property|Property must" (str error))
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
    (let [{:keys [block property]} (fixture!)
          ops (json-ops [{:operation "edit" :entityType "block" :id block
                          :data {:properties {property 99}}}])
          before (vec (d/datoms (conn/get-db) :eavt))]
      (-> (api-test/with-plugin-api
           (fn []
             (p/let [_ (upsert ops #js {:dry-run true})]
               (is (= before (vec (d/datoms (conn/get-db) :eavt))))
               (-> (upsert ops #js {:receipt true})
                   (p/then #(is false "Unverified property receipts must reject"))
                   (p/catch (fn [error]
                              (is (re-find #"receipt|Receipt" (str error)))
                              (is (= before (vec (d/datoms (conn/get-db) :eavt))))))))))
          (p/catch #(is false (str %)))
          (p/finally done)))))
