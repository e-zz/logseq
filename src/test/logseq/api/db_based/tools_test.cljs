(ns logseq.api.db-based.tools-test
  (:require [cljs.test :refer [deftest is use-fixtures]]
            [datascript.core :as d]
            [frontend.db.conn :as conn]
            [frontend.test.helper :as test-helper]
            [logseq.api.db-based.tools :as api-tools]
            [logseq.api.test-helper :as api-test]
            [logseq.db :as ldb]
            [logseq.db.frontend.entity-util :as entity-util]
            [logseq.db.frontend.property.build :as property-build]
            [logseq.db.sqlite.build :as sqlite-build]))

(use-fixtures :each {:before api-test/start-plugin-api-db!
                     :after api-test/destroy-plugin-api-db!})

(defn- recycle-page!
  "Marks the page with the given title as recycled and returns its uuid"
  [title]
  (let [page (ldb/get-page (conn/get-db) title)]
    (conn/transact! nil [[:db/add (:db/id page) :logseq.property/deleted-at 1]])
    (:block/uuid page)))

(deftest get-page-data-hides-recycled-page-by-uuid
  (test-helper/load-test-files
   [{:page {:block/title "Recycled By Uuid"}
     :blocks [{:block/title "recycled block"}]}])
  (let [uuid (recycle-page! "Recycled By Uuid")
        db (conn/get-db)
        by-uuid (api-tools/get-page-data db (str uuid) {})
        by-name (api-tools/get-page-data db "Recycled By Uuid" {})]
    (is (nil? by-uuid)
        "A recycled page must not be readable by uuid without opt-in")
    (is (nil? (:entity by-name))
        "A recycled page must not be readable by name without opt-in")
    (is (re-find #"recycle bin" (:error by-name))
        "Name lookup of a recycled page must say it is recycled")
    (is (re-find #"includeRecycled=true with a page uuid" (:error by-name))
        "The error must not suggest a uuid alone can bypass the opt-in")
    (is (not (re-find #"recycled block" (pr-str by-uuid)))
        "No block content may leak for a recycled page")))

(deftest get-page-data-include-recycled-marks-page
  (test-helper/load-test-files
   [{:page {:block/title "Recycled Opt In"}
     :blocks [{:block/title "recycled opt in block"}]}])
  (let [uuid (recycle-page! "Recycled Opt In")
        db (conn/get-db)
        by-uuid (api-tools/get-page-data db (str uuid) {:include-recycled? true})
        by-name (api-tools/get-page-data db "Recycled Opt In" {:include-recycled? true})]
    (is (= "Recycled Opt In" (get-in by-uuid [:entity :block/title]))
        "Opt-in uuid lookup returns the recycled page")
    (is (some? (get-in by-uuid [:entity :logseq.property/deleted-at]))
        "Recycled page carries its deleted-at marker")
    (is (= "Recycled Opt In" (get-in by-name [:entity :block/title]))
        "Opt-in name lookup returns the sole recycled generation")
    (is (some? (get-in by-name [:entity :logseq.property/deleted-at]))
        "Name lookup also carries the deleted-at marker")))

(deftest get-page-data-prefers-active-over-recycled-generation
  (test-helper/load-test-files
   [{:page {:block/title "Colliding Gen"}
     :blocks [{:block/title "old generation block"}]}])
  (recycle-page! "Colliding Gen")
  (test-helper/load-test-files
   [{:page {:block/title "Colliding Gen"}
     :blocks [{:block/title "new generation block"}]}])
  (let [db (conn/get-db)
        gens (->> (entity-util/get-pages-by-name db "Colliding Gen")
                  (map #(d/entity db (:e %))))
        by-name (api-tools/get-page-data db "Colliding Gen" {})
        opt-in (api-tools/get-page-data db "Colliding Gen" {:include-recycled? true})]
    (is (= 2 (count gens))
        "Fixture must actually create two generations sharing a name")
    (is (= 1 (count (remove #(:logseq.property/deleted-at %) gens)))
        "Exactly one generation is active")
    (is (nil? (get-in by-name [:entity :logseq.property/deleted-at]))
        "The active generation wins even without opt-in")
    (is (some #(= "new generation block" (:block/title %)) (:blocks by-name))
        "The active generation's blocks are returned")
    (is (nil? (get-in opt-in [:entity :logseq.property/deleted-at]))
        "Opt-in must not switch to the recycled generation when an active one exists")
    (is (some #(= "new generation block" (:block/title %)) (:blocks opt-in))
        "Opt-in still returns the active generation")))

(deftest get-page-data-ambiguous-recycled-generations
  (test-helper/load-test-files
   [{:page {:block/title "Twice Recycled"}
     :blocks [{:block/title "first recycled block"}]}])
  (let [first-uuid (recycle-page! "Twice Recycled")
        ;; A second recycled generation with the same name, as happens when the same
        ;; page is created and recycled more than once
        second-uuid (random-uuid)]
    (conn/transact! nil [{:block/uuid second-uuid
                          :block/name "twice recycled"
                          :block/title "Twice Recycled"
                          :block/tags #{:logseq.class/Page}
                          :block/created-at (js/Date.now)
                          :block/updated-at (js/Date.now)
                          :logseq.property/deleted-at 2}])
    (let [db (conn/get-db)
          gens (->> (entity-util/get-pages-by-name db "Twice Recycled")
                    (map #(d/entity db (:e %))))
          result (api-tools/get-page-data db "Twice Recycled" {:include-recycled? true})]
      (is (= 2 (count gens))
          "Fixture must create two recycled generations sharing a name")
      (is (= 2 (count (filter #(:logseq.property/deleted-at %) gens)))
          "Both generations are recycled")
      (is (nil? (:entity result))
          "Ambiguous recycled name must not return an arbitrary generation")
      (is (re-find #"ambiguous" (:error result)))
      (is (re-find (re-pattern (str first-uuid)) (:error result))
          "Error lists the first candidate uuid")
      (is (re-find (re-pattern (str second-uuid)) (:error result))
          "Error lists the second candidate uuid")
      (is (not (re-find #"recycled block" (pr-str result)))
          "Ambiguity error must not leak block content")
      (is (= "Twice Recycled"
             (get-in (api-tools/get-page-data db (str first-uuid) {:include-recycled? true})
                     [:entity :block/title]))
          "Explicit uuid is how the caller disambiguates"))))

(deftest get-page-data-ambiguous-active-generations
  (test-helper/load-test-files
   [{:page {:block/title "Two Active"}
     :blocks [{:block/title "active block one"}]}])
  (let [second-uuid (random-uuid)]
    (conn/transact! nil [{:block/uuid second-uuid
                          :block/name "two active"
                          :block/title "Two Active"
                          :block/tags #{:logseq.class/Page}
                          :block/created-at (js/Date.now)
                          :block/updated-at (js/Date.now)}])
    (let [db (conn/get-db)
          result (api-tools/get-page-data db "Two Active" {})
          opt-in (api-tools/get-page-data db "Two Active" {:include-recycled? true})]
      (is (nil? (:entity result))
          "Two active pages with one name must not resolve to an arbitrary one")
      (is (re-find #"ambiguous" (:error result)))
      (is (re-find (re-pattern (str second-uuid)) (:error result))
          "Error lists candidate uuids")
      (is (nil? (:entity opt-in))
          "Opt-in must not resolve an ambiguous active name either"))))

(deftest list-pages-does-not-expose-non-recycled-hidden-pages
  (test-helper/load-test-files
   [{:page {:block/title "Visible Page"}
     :blocks [{:block/title "visible block"}]}])
  (let [db (conn/get-db)
        hidden-uuids (->> (d/datoms db :avet :block/name)
                          (map #(d/entity db (:e %)))
                          (filter entity-util/hidden?)
                          (remove #(:logseq.property/deleted-at %))
                          (map #(str (:block/uuid %)))
                          set)
        listed (api-tools/list-pages db {})]
    (is (seq hidden-uuids) "Fixture has non-recycled hidden entities to guard")
    (is (empty? (filter #(contains? hidden-uuids (str (:block/uuid %))) listed))
        "Non-recycled hidden pages must stay out of listPages")))

(deftest list-pages-include-recycled
  (test-helper/load-test-files
   [{:page {:block/title "Listed Active"}
     :blocks [{:block/title "listed active block"}]}
    {:page {:block/title "Listed Recycled"}
     :blocks [{:block/title "listed recycled block"}]}])
  (let [recycled-uuid (recycle-page! "Listed Recycled")
        db (conn/get-db)
        default-list (api-tools/list-pages db {})
        with-recycled (api-tools/list-pages db {:include-recycled? true})
        uuids (fn [pages] (set (map #(str (:block/uuid %)) pages)))
        recycled-entry (first (filter #(= (str recycled-uuid) (str (:block/uuid %))) with-recycled))]
    (is (contains? (uuids default-list) (str (get-in (api-tools/get-page-data db "Listed Active" {})
                                                     [:entity :block/uuid])))
        "The active page is listed by default")
    (is (not (contains? (uuids default-list) (str recycled-uuid)))
        "Recycled pages are excluded by default")
    (is (contains? (uuids with-recycled) (str recycled-uuid))
        "Opt-in lists the recycled generation with its stable uuid")
    (is (some? (:logseq.property/deleted-at recycled-entry))
        "The listed recycled page carries an explicit deleted-at marker")
    (is (contains? (uuids with-recycled)
                   (str (get-in (api-tools/get-page-data db "Listed Active" {})
                                [:entity :block/uuid])))
        "Opt-in still lists active pages")))

(deftest list-and-get-agree-about-recycled-pages
  (test-helper/load-test-files
   [{:page {:block/title "Agree Page"}
     :blocks [{:block/title "agree block"}]}])
  (let [uuid (recycle-page! "Agree Page")]
    (doseq [include? [false true]]
      (let [db (conn/get-db)
            listed? (boolean (some #(= (str uuid) (str (:block/uuid %)))
                                   (api-tools/list-pages db {:include-recycled? include?})))
            get-by-uuid (api-tools/get-page-data db (str uuid) {:include-recycled? include?})
            get-by-name (api-tools/get-page-data db "Agree Page" {:include-recycled? include?})]
        (is (= listed? (some? (:entity get-by-uuid)))
            (str "listPages and getPage disagree by uuid with include-recycled?=" include?))
        (is (= listed? (some? (:entity get-by-name)))
            (str "listPages and getPage disagree by name with include-recycled?=" include?))))))

(deftest list-and-get-page-data
  (test-helper/load-test-files
   [{:page {:block/title "Tools Page"}
     :blocks [{:block/title "tools block"}]}])
  (let [db (conn/get-db)
        pages (api-tools/list-pages db {})
        page-data (api-tools/get-page-data db "Tools Page" {})
        missing (api-tools/get-page-data db "Missing" {})]
    (is (some #(= "Tools Page" (:block/title %)) pages))
    (is (= "Tools Page" (get-in page-data [:entity :block/title])))
    (is (some #(= "tools block" (:block/title %)) (:blocks page-data)))
    (is (= "Page \"Missing\" not found" (:error missing)))))

(deftest build-upsert-nodes-edn-from-add-operations
  (let [db (conn/get-db)
        edn (api-tools/build-upsert-nodes-edn
             db
             [{:operation "add"
               :entityType "page"
               :id "p1"
               :data {:title "Upsert Tools Page"}}
              {:operation "add"
               :entityType "block"
               :data {:title "Upsert Tools Block"
                      :page-id "p1"}}
              {:operation "add"
               :entityType "tag"
               :data {:title "Upsert Tag"}}])]
    (is (= "Upsert Tools Page" (get-in edn [:pages-and-blocks 0 :page :block/title])))
    (is (= "Upsert Tools Block" (get-in edn [:pages-and-blocks 0 :blocks 0 :block/title])))
    (is (some #(= "Upsert Tag" (:block/title %)) (vals (:classes edn))))))

(deftest build-upsert-nodes-edn-rejects-invalid-operations
  (is (thrown-with-msg?
       js/Error
       #"Tool arguments are invalid"
       (api-tools/build-upsert-nodes-edn
        (conn/get-db)
        [{:operation "add" :entityType "block" :data {:title "no page"}}])))
  (is (thrown-with-msg?
       js/Error
       #"isn't supported yet"
       (api-tools/build-upsert-nodes-edn
        (conn/get-db)
        [{:operation "edit"
          :entityType "page"
          :id "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
          :data {:title "Nope"}}])))
  (is (thrown-with-msg?
       js/Error
       #"must be a page uuid or the id of a page added"
       (api-tools/build-upsert-nodes-edn
        (conn/get-db)
        [{:operation "add"
          :entityType "block"
          :data {:title "orphan"
                 :page-id "Some Page Name"}}]))))

(deftest build-upsert-nodes-edn-resolves-uuid-page-ids
  (test-helper/load-test-files
   [{:page {:block/title "Existing Tools Page"}
     :blocks [{:block/title "existing block"}]}])
  (let [db (conn/get-db)
        page (api-tools/get-page-data db "Existing Tools Page" {})
        page-uuid (str (get-in page [:entity :block/uuid]))
        block-uuid (str (:block/uuid (first (:blocks page))))
        local-id "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
        edn (api-tools/build-upsert-nodes-edn
             db
             [{:operation "add"
               :entityType "page"
               :id local-id
               :data {:title "Local Uuid Page"}}
              {:operation "add"
               :entityType "block"
               :data {:title "local block" :page-id local-id}}
              {:operation "add"
               :entityType "block"
               :data {:title "existing page block" :page-id page-uuid}}])]
    (is (= [{:page {:block/title "Local Uuid Page"}
             :blocks [{:block/title "local block"}]}
            {:page {:block/uuid (uuid page-uuid)}
             :blocks [{:block/title "existing page block"}]}]
           (:pages-and-blocks edn)))
    (is (thrown-with-msg?
         js/Error
         #"is not an existing page"
         (api-tools/build-upsert-nodes-edn
          db
          [{:operation "add"
            :entityType "block"
            :data {:title "bad parent" :page-id block-uuid}}])))))

;; getPage tree completeness (#8 read slice)
;; ========================================
(def ^:private nested-page-fixture
  "One page with three levels below the top level so that a depth-1 read is
   distinguishable from a complete read."
  [{:page {:block/title "Nested Tree Page"}
    :blocks [{:block/title "top one"
              :build/children [{:block/title "child a"
                                :build/children [{:block/title "grandchild a1"}
                                                 {:block/title "grandchild a2"}]}
                               {:block/title "child b"}]}
             {:block/title "top two"}]}])

(defn- titles-at-depth
  "Titles reachable from `blocks` by following :block/children up to `depth` levels."
  [blocks depth]
  (if (zero? depth)
    []
    (mapcat (fn [b]
              (cons (:block/title b)
                    (titles-at-depth (:block/children b) (dec depth))))
            blocks)))

(deftest get-page-data-signals-partial-when-children-are-omitted
  (test-helper/load-test-files nested-page-fixture)
  (let [db (conn/get-db)
        page-data (api-tools/get-page-data db "Nested Tree Page" {})
        blocks (:blocks page-data)]
    (is (= ["top one" "top two"] (map :block/title blocks))
        "Default read returns top-level blocks in sibling order")
    (is (not-any? #(contains? % :block/children) blocks)
        "Default read must not carry a children key it did not fill in")
    (is (true? (:block/tree-has-more? page-data))
        "Default read must positively signal that it is a partial tree")
    (is (= 4 (:block/tree-omitted-count page-data))
        "The signal must count the blocks the caller would have to fetch separately")))

(deftest get-page-data-include-children-returns-full-recursive-tree
  (test-helper/load-test-files nested-page-fixture)
  (let [db (conn/get-db)
        page-data (api-tools/get-page-data db "Nested Tree Page" {:include-children? true :max-blocks 6})
        blocks (:blocks page-data)]
    (is (nil? (get-in page-data [:block/tree-has-more?]))
        "A complete tree must not be marked partial")
    (is (= ["top one" "top two"] (map :block/title blocks))
        "Top-level sibling order is preserved")
    (is (= [1 1] (map :block/level blocks))
        "Top-level blocks are level 1")
    (let [[top-one _top-two] blocks
          [child-a child-b :as children] (:block/children top-one)]
      (is (= ["child a" "child b"] (map :block/title children))
          "Children come back in sibling order")
      (is (= [2 2] (map :block/level children))
          "Children are level 2")
      (is (= ["grandchild a1" "grandchild a2"] (map :block/title (:block/children child-a)))
          "Grandchildren come back in sibling order")
      (is (= [3 3] (map :block/level (:block/children child-a)))
          "Grandchildren are level 3")
      (is (= [] (:block/children child-b))
          "A childless block carries an empty children vector, not nil"))))

(deftest get-page-data-include-children-serializes-uuids-at-every-level
  (test-helper/load-test-files nested-page-fixture)
  (let [db (conn/get-db)
        page-data (api-tools/get-page-data db "Nested Tree Page" {:include-children? true :max-blocks 6})
        top (first (:blocks page-data))
        child (first (:block/children top))
        grand (first (:block/children child))
        every-uuid (fn every-uuid [b]
                     (cons (:block/uuid b)
                           (mapcat every-uuid (:block/children b))))]
    (is (string? (:block/uuid top)) "Top-level uuid is a string")
    (is (string? (:block/uuid child)) "Nested child uuid is a string")
    (is (string? (:block/uuid grand)) "Grandchild uuid is a string")
    (is (every? string? (every-uuid top))
        "Every uuid in the tree must survive JSON as a string")
    (is (not-any? uuid? (every-uuid top))
        "No raw cljs UUID may reach the payload, which would stringify to a protocol object")
    (is (every? #(and (not (contains? % :db/id))
                      (not (contains? % :block/page)))
                (tree-seq #(seq (:block/children %)) :block/children top))
        "Internal db ids and page refs must not leak into the nested payload")))

(deftest get-page-data-nested-tree-json-round-trips
  (test-helper/load-test-files nested-page-fixture)
  (let [db (conn/get-db)
        page-data (api-tools/get-page-data db "Nested Tree Page" {:include-children? true :max-blocks 6})
        json (js/JSON.stringify (clj->js (:blocks page-data)))
        parsed (js->clj (js/JSON.parse json))]
    (is (not (re-find #"cljs\$lang\$protocol_mask" json))
        "Serialized payload must not contain leaked cljs protocol fields")
    (is (= "Nested Tree Page" (get-in page-data [:entity :block/title])))
    (is (string? (get-in parsed [0 "uuid"]))
        "Nested uuid must be a JSON string after a clj->js round trip")
    (is (= "child a" (get-in parsed [0 "children" 0 "title"]))
        "Nested children must survive JSON serialization")))

(deftest get-page-data-nested-json-excludes-entity-refs-and-parent-ids
  (test-helper/load-test-files
   [{:page {:block/title "Nested Tree Page"}
     :blocks [{:block/title "top one"
               :build/children [{:block/title "child a"}]}]}
    {:page {:block/title "Referenced Page"}}])
  (let [db (conn/get-db)
        source (first (filter #(= "top one" (:block/title %))
                              (map #(d/entity db (:e %))
                                   (d/datoms db :avet :block/title "top one"))))
        target (ldb/get-page db "Referenced Page")]
    (conn/transact! nil [[:db/add (:db/id source) :block/refs (:db/id target)]])
    (let [page-data (api-tools/get-page-data (conn/get-db) "Nested Tree Page"
                                             {:include-children? true :max-blocks 2})
          blocks (:blocks page-data)
          json (js/JSON.stringify (clj->js blocks))
          tree (js->clj (js/JSON.parse json))
          top (first tree)
          child (get-in top ["children" 0])]
      (is (not (re-find #"parent|db/id|cljs\\$lang\\$protocol_mask" json))
          "Nested JSON must not expose parent entities, db ids, or cljs protocol fields")
      (is (= [{"title" "Referenced Page" "uuid" (str (:block/uuid target))}]
             (get top "refs"))
          "Reference attrs retain stable user-meaningful title and uuid data")
      (is (nil? (get child "parent"))
          "Tree ancestry is already represented by children; no parent entity is serialized"))))

(deftest get-page-data-nested-json-projects-all-schema-reference-attributes
  (test-helper/load-test-files
   [{:page {:block/title "Reference Source Page"}
     :blocks [{:block/title "reference source"}]}
    {:page {:block/title "Reference Target Page"}}])
  (let [db (conn/get-db)
        source (first (filter #(= "reference source" (:block/title %))
                              (map #(d/entity db (:e %))
                                   (d/datoms db :avet :block/title "reference source"))))
        target (ldb/get-page db "Reference Target Page")
        alias-property (d/entity db :block/alias)]
    (is (some? alias-property) "Fixture includes the built-in alias property entity")
    (conn/transact! nil [[:db/add (:db/id source) :block/link (:db/id target)]
                         [:db/add (:db/id source) :block/alias (:db/id target)]
                         (property-build/build-property-value-block
                          source alias-property "Alias"
                          :properties {:block/closed-value-property #{(:db/id alias-property)}})])
    (let [result (api-tools/get-page-data (conn/get-db) "Reference Source Page"
                                          {:include-children? true :max-blocks 2})
          json (js/JSON.stringify (clj->js (:blocks result)))
          tree (js->clj (js/JSON.parse json))
          block (first tree)
          closed-value (get-in block ["children" 0])
          reference-eids #{(:db/id target) (:db/id alias-property)}
          contains-reference-eid? (fn contains-reference-eid? [value]
                                    (cond
                                      (map? value) (some contains-reference-eid? (vals value))
                                      (coll? value) (some contains-reference-eid? value)
                                      (number? value) (contains? reference-eids value)
                                      :else false))]
      (is (not (re-find #"db/id|parent|cljs\\$lang\\$protocol_mask" json))
          "Schema reference targets must not expose internal ids, ancestry entities, or entity protocols")
      (is (not (contains-reference-eid? tree))
          "Reference target numeric db ids must not survive the JSON round trip")
      (is (= {"title" "Reference Target Page" "uuid" (str (:block/uuid target))}
             (get block "link"))
          "Single-valued block/link preserves the target's title and uuid")
      (is (= [{"title" "Reference Target Page" "uuid" (str (:block/uuid target))}]
             (get block "alias"))
          "Many-valued block/alias preserves each target's title and uuid")
      (is (= [{"title" "Alias"
               "uuid" (str (:block/uuid alias-property))
               "ident" "block/alias"}]
             (get closed-value "closed-value-property"))
          "Built-in closed-value property retains stable title, uuid, and fully qualified ident"))))

(deftest get-page-data-include-children-serializes-page-entity
  (test-helper/load-test-files
   [{:page {:block/title "Page Entity Source"}
     :blocks [{:block/title "page entity block"
               :build/children [{:block/title "page entity child"}]}]}
    {:page {:block/title "Page Entity Alias Target"}}])
  (let [db (conn/get-db)
        page (ldb/get-page db "Page Entity Source")
        target (ldb/get-page db "Page Entity Alias Target")
        page-tag (d/entity db :logseq.class/Page)]
    (conn/transact! nil [[:db/add (:db/id page) :block/alias (:db/id target)]])
    (let [db (conn/get-db)
          default (api-tools/get-page-data db "Page Entity Source" {})
          complete (api-tools/get-page-data db "Page Entity Source"
                                            {:include-children? true :max-blocks 2})
          json (js/JSON.stringify (clj->js complete))
          parsed (js->clj (js/JSON.parse json))
          serialized-entity (get parsed "entity")
          contains-db-id? (fn contains-db-id? [value]
                            (cond
                              (map? value) (or (contains? value "db/id")
                                               (some contains-db-id? (vals value)))
                              (coll? value) (some contains-db-id? value)
                              :else false))]
      (is (some? (:block/alias (:entity default)))
          "Default top-level getPage retains its established page alias attribute")
      (is (not (contains? (:entity default) :block/tags))
          "Default top-level getPage continues omitting page tags")
      (is (not (contains? (:entity default) :block/refs))
          "Default top-level getPage continues omitting page refs")
      (is (= "Page Entity Source" (get serialized-entity "title")))
      (is (= (str (:block/uuid page)) (get serialized-entity "uuid"))
          "The page uuid remains a stable JSON string")
      (is (not (contains? serialized-entity "db/id"))
          "Opted-in page entity must omit its internal DataScript id")
      (is (= [{"title" "Page Entity Alias Target"
               "uuid" (str (:block/uuid target))}]
             (get serialized-entity "alias"))
          "Page aliases retain stable identity without exposing entity references")
      (is (= [{"title" (:block/title page-tag)
               "uuid" (str (:block/uuid page-tag))
               "ident" "logseq.class/Page"}]
             (get serialized-entity "tags"))
          "Page tags are projected to stable class identity")
      (is (not (contains? serialized-entity "children"))
          "The page entity must not acquire a synthetic children field")
      (is (not (contains-db-id? parsed))
          "No internal db/id key survives anywhere in the complete page response")
      (is (not (re-find #"cljs\\$lang\\$protocol_mask" json))
          "The entire getPage result must not contain leaked entity protocols")
      (is (= "page entity child" (get-in parsed ["blocks" 0 "children" 0 "title"]))
          "Page-entity projection must not change nested block children"))))

(deftest get-page-data-dynamic-node-property-refs-are-json-safe
  (sqlite-build/create-blocks
   (conn/get-db nil false)
   {:properties {:related {:logseq.property/type :node}}
    :pages-and-blocks
    [{:page {:block/title "Dynamic Property Source"
             :build/properties {:related [:build/page {:block/title "Dynamic Property Target"}]}}
      :blocks [{:block/title "dynamic property block"
                :build/children [{:block/title "dynamic property child"
                                  :build/properties {:related [:build/page {:block/title "Dynamic Property Target"}]}}]}]}
     {:page {:block/title "Dynamic Property Target"}}]})
  (let [db (conn/get-db)
        source (ldb/get-page db "Dynamic Property Source")
        property-ident (some #(when (= "user.property" (namespace (:a %))) (:a %))
                             (d/datoms db :eavt (:db/id source)))
        property-key (name property-ident)
        target (ldb/get-page db "Dynamic Property Target")]
    (let [result (api-tools/get-page-data db "Dynamic Property Source"
                                          {:include-children? true :max-blocks 2})
          json (js/JSON.stringify (clj->js result))
          parsed (js->clj (js/JSON.parse json))
          contains-db-id? (fn contains-db-id? [value]
                            (cond
                              (map? value) (or (contains? value "db/id")
                                               (some contains-db-id? (vals value)))
                              (coll? value) (some contains-db-id? value)
                              :else false))]
      (is (= :db.type/ref (:db/valueType (d/entity db property-ident)))
          "The fixture's real user property is schema-declared as a ref")
      (is (= :node (:logseq.property/type (d/entity db property-ident)))
          "The fixture's real user property uses the node property type")
      (is (= :db.cardinality/one (:db/cardinality (d/entity db property-ident)))
          "The fixture's real user property has single cardinality")
      (is (= (:db/id target) (:db/id (get source property-ident)))
          "The page stores a real DataScript entity under its dynamic property")
      (is (not (re-find #"cljs\\$lang\\$protocol_mask" json))
          "The complete JSON payload must not contain leaked DataScript entity protocols")
      (is (not (contains-db-id? parsed))
          "The complete JSON payload must not contain DataScript ids")
      (is (= {"title" "Dynamic Property Target"
              "uuid" (str (:block/uuid target))}
             (get-in parsed ["entity" property-key]))
          "Page node-property refs retain stable target title and uuid")
      (is (= {"title" "Dynamic Property Target"
              "uuid" (str (:block/uuid target))}
             (get-in parsed ["blocks" 0 "children" 0 property-key]))
          "Nested-block node-property refs retain stable target title and uuid"))))

(deftest get-page-data-include-children-requires-valid-block-budget
  (test-helper/load-test-files nested-page-fixture)
  (let [db (conn/get-db)]
    (doseq [[options required-fragment]
            [[{:include-children? true} "maxBlocks"]
             [{:include-children? true :max-blocks 0} "positive integer"]
             [{:include-children? true :max-blocks -1} "positive integer"]
             [{:include-children? true :max-blocks 2.5} "positive integer"]
             [{:include-children? true :max-blocks "6"} "positive integer"]
             [{:include-children? true :max-blocks 5} "requires 6 blocks"]]]
      (let [result (api-tools/get-page-data db "Nested Tree Page" options)]
        (is (string? (:error result)) (str "Expected error for " options))
        (is (re-find (re-pattern required-fragment) (:error result))
            (str "Error should explain how to satisfy the budget: " options))
        (is (re-find #"requires 6 blocks" (:error result))
            "Every budget error reports the actual complete-tree count")
        (is (re-find #"maxBlocks" (:error result))
            "Every budget error tells the caller which budget to supply")
        (is (re-find #"at least 6" (:error result))
            "Every budget error gives a retry budget that will return the full tree")
        (is (not (contains? result :blocks))
            (str "An invalid/insufficient budget must not return partial content: " options))))))

(deftest get-page-data-include-children-accepts-exact-block-budget
  (test-helper/load-test-files nested-page-fixture)
  (let [db (conn/get-db)
        result (api-tools/get-page-data db "Nested Tree Page"
                                        {:include-children? true :max-blocks 6})]
    (is (nil? (:error result)))
    (is (= 6 (count (titles-at-depth (:blocks result) 5)))
        "An exact budget returns the complete tree")))

(deftest get-page-data-include-children-on-leaf-page
  (test-helper/load-test-files
   [{:page {:block/title "Leaf Page"}
     :blocks [{:block/title "lonely block"}]}])
  (let [db (conn/get-db)
        opt-in (api-tools/get-page-data db "Leaf Page" {:include-children? true :max-blocks 1})
        default (api-tools/get-page-data db "Leaf Page" {})]
    (is (= ["lonely block"] (map :block/title (:blocks opt-in))))
    (is (= [] (:block/children (first (:blocks opt-in))))
        "A block with no children reports an empty children vector")
    (is (nil? (get-in opt-in [:block/tree-has-more?]))
        "A page with no nested blocks is complete even by default")
    (is (nil? (get-in default [:block/tree-has-more?]))
        "The default read of a page without nested blocks must not claim truncation")))

(deftest get-page-data-include-children-on-empty-page
  (test-helper/load-test-files
   [{:page {:block/title "Empty Tree Page"}}])
  (let [db (conn/get-db)
        default (api-tools/get-page-data db "Empty Tree Page" {})
        opt-in (api-tools/get-page-data db "Empty Tree Page" {:include-children? true :max-blocks 1})]
    (is (empty? (:blocks default)) "An empty page has no blocks")
    (is (nil? (get-in default [:block/tree-has-more?]))
        "An empty page is a complete tree, not a truncated one")
    (is (empty? (:blocks opt-in)) "An empty page has no blocks with opt-in either")))

(deftest get-page-data-recycled-page-gates-descendants
  (test-helper/load-test-files nested-page-fixture)
  (let [uuid (recycle-page! "Nested Tree Page")
        db (conn/get-db)
        default (api-tools/get-page-data db "Nested Tree Page" {})
        opt-in (api-tools/get-page-data db (str uuid) {:include-recycled? true
                                                       :include-children? true
                                                       :max-blocks 6})]
    (is (some? (:error default))
        "A recycled page is still refused by default with a tree option available")
    (is (not (re-find #"grandchild" (pr-str default)))
        "The default refusal must not leak descendant titles")
    (is (= ["top one" "top two"] (map :block/title (:blocks opt-in)))
        "Opt-in read of a recycled page returns its top-level blocks")
    (is (= ["child a" "child b"]
           (map :block/title (:block/children (first (:blocks opt-in)))))
        "Opt-in read of a recycled page returns its descendants")
    (is (nil? (get-in opt-in [:block/tree-has-more?]))
        "The opt-in recycled read is a complete tree")))

;; getBlock UUID lookup (#8 narrow direct-read slice)
;; =================================================

(deftest get-block-resolves-one-block-by-uuid
  (test-helper/load-test-files
   [{:page {:block/title "Same title"}
     :blocks [{:block/title "parent"
               :build/children [{:block/title "target child"
                                 :build/children [{:block/title "grandchild"}]}]}]}
    {:page {:block/title "Second block page"}
     :blocks [{:block/title "Same title"}]}])
  (let [db (conn/get-db)
        target (first (filter #(= "target child" (:block/title %))
                              (map #(d/entity db (:e %))
                                   (d/datoms db :avet :block/title "target child"))))
        parent (:block/parent target)
        page (:block/page target)
        result (api-tools/get-block db (str (:block/uuid target)) {})]
    (is (= "target child" (:block/title result))
        "UUID lookup returns the selected child even when another page has duplicate titles")
    (is (= (str (:block/uuid target)) (:block/uuid result)))
    (is (= (str (:block/uuid parent)) (:block/parent result))
        "Parent identity is a stable UUID, not a DataScript ref")
    (is (= (str (:block/uuid page)) (:block/page result))
        "Page identity is a stable UUID, not a DataScript ref")
    (is (not (contains? result :db/id))
        "Internal DataScript ids are not returned")
    (is (not (contains? result :block/children))
        "A direct block read does not claim to return descendants")
    (is (re-find #"not a block"
                 (:error (api-tools/get-block db (str (:block/uuid page)) {})))
        "A page entity is not accepted as a block")))

(deftest get-block-rejects-invalid-and-missing-uuids
  (let [db (conn/get-db)]
    (is (re-find #"valid uuid" (:error (api-tools/get-block db "not-a-uuid" {})))
        "Malformed UUIDs fail visibly")
    (is (re-find #"not found" (:error (api-tools/get-block db "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa" {})))
        "Unknown valid UUIDs return a clear not-found error")
    (is (re-find #"valid uuid" (:error (api-tools/get-block db 42 {})))
        "Numeric DataScript ids are not treated as UUIDs")))

(deftest get-block-rejects-parent-cycles-without-leaking-content
  (test-helper/load-test-files
   [{:page {:block/title "Parent Cycle Page"}
     :blocks [{:block/title "cycle first"}
              {:block/title "cycle second"}]}])
  (let [db (conn/get-db)
        first-block (d/entity db (:e (first (d/datoms db :avet :block/title "cycle first"))))
        second-block (d/entity db (:e (first (d/datoms db :avet :block/title "cycle second"))))
        cyclic-db (d/db-with db [[:db/add (:db/id first-block) :block/parent (:db/id second-block)]
                                 [:db/add (:db/id second-block) :block/parent (:db/id first-block)]])
        result (api-tools/get-block cyclic-db (str (:block/uuid first-block)) {})]
    (is (re-find #"parent cycle" (:error result))
        "Malformed parent cycles return a clear error")
    (is (not (contains? result :block/title))
        "A cyclic block is denied without exposing its content")))

(deftest get-block-rejects-broken-parent-chain
  (test-helper/load-test-files
   [{:page {:block/title "Broken Parent Page"}
     :blocks [{:block/title "orphan block"}]}])
  (let [db (conn/get-db)
        block (d/entity db (:e (first (d/datoms db :avet :block/title "orphan block"))))
        broken-db (d/db-with db [[:db/retract (:db/id block) :block/parent (:db/id (:block/page block))]])
        result (api-tools/get-block broken-db (str (:block/uuid block)) {})]
    (is (re-find #"parent chain that does not reach its page" (:error result))
        "A chain that terminates before the claimed page returns a clear error")
    (is (not (contains? result :block/title))
        "A block with a broken parent chain is denied without exposing its content")))

(deftest get-block-hidden-ancestor-remains-denied
  (test-helper/load-test-files
   [{:page {:block/title "Hidden Ancestor Page"}
     :blocks [{:block/title "hidden parent"
               :build/children [{:block/title "visible-looking child"}]}]}])
  (let [db (conn/get-db)
        parent (d/entity db (:e (first (d/datoms db :avet :block/title "hidden parent"))))
        child (d/entity db (:e (first (d/datoms db :avet :block/title "visible-looking child"))))]
    (conn/transact! nil [[:db/add (:db/id parent) :logseq.property/hide? true]])
    (let [result (api-tools/get-block (conn/get-db) (str (:block/uuid child)) {})]
      (is (re-find #"hidden" (:error result))
          "The visited-id guard must preserve hidden-ancestor denial")
      (is (not (contains? result :block/title))
          "A block under a hidden ancestor does not expose content"))))

(deftest get-block-recycled-page-gate-and-opt-in
  (test-helper/load-test-files
   [{:page {:block/title "Recycled Block Page"}
     :blocks [{:block/title "recycled target"}]}])
  (let [page (ldb/get-page (conn/get-db) "Recycled Block Page")
        block-datom (first (d/datoms (conn/get-db) :avet :block/title "recycled target"))
        block (d/entity (conn/get-db) (:e block-datom))]
    (conn/transact! nil [[:db/add (:db/id page) :logseq.property/deleted-at 123]])
    (let [db (conn/get-db)
          default (api-tools/get-block db (str (:block/uuid block)) {})
          opt-in (api-tools/get-block db (str (:block/uuid block)) {:include-recycled? true})]
      (is (re-find #"recycled page" (:error default))
          "Blocks on recycled pages are denied by default")
      (is (not (contains? default :block/title))
          "The denial does not leak block content")
      (is (= "recycled target" (:block/title opt-in)))
      (is (= 123 (:logseq.property/deleted-at opt-in))
          "Opt-in results carry the recycled page's deleted-at marker"))))

(deftest get-block-rejects-hidden-property-value-pseudochildren
  (test-helper/load-test-files
   [{:page {:block/title "Pseudochild Page"}
     :blocks [{:block/title "ordinary block"}]}])
  (let [db (conn/get-db)
        block (d/entity db (:e (first (d/datoms db :avet :block/title "ordinary block"))))
        property (d/entity db :block/alias)
        pseudochild (property-build/build-property-value-block
                     block property "alias value")]
    (conn/transact! nil [pseudochild])
    (let [db (conn/get-db)
          pseudochild (d/entity db [:block/uuid (:block/uuid pseudochild)])
          result (api-tools/get-block db (str (:block/uuid pseudochild)) {})]
      (is (re-find #"pseudochild" (:error result))
          "Property-value pseudochildren have an explicit unsupported-entity response")
      (is (nil? (:block/title result))
          "Pseudochild content is not exposed"))))

(deftest get-block-json-round-trips-dynamic-node-property-reference
  (sqlite-build/create-blocks
   (conn/get-db nil false)
   {:properties {:related {:logseq.property/type :node}}
    :pages-and-blocks
    [{:page {:block/title "Direct Reference Source"}
      :blocks [{:block/title "direct reference block"
                :build/properties {:related [:build/page {:block/title "Direct Reference Target"}]}}]}
     {:page {:block/title "Direct Reference Target"}}]})
  (let [db (conn/get-db)
        source-page (ldb/get-page db "Direct Reference Source")
        block (d/entity db (:e (first (d/datoms db :avet :block/title "direct reference block"))))
        target (ldb/get-page db "Direct Reference Target")
        related (some #(when (= "user.property" (namespace (:a %))) (:a %))
                      (d/datoms db :eavt (:db/id block)))
        result (api-tools/get-block db (str (:block/uuid block)) {})
        json (js/JSON.stringify (clj->js result))
        parsed (js->clj (js/JSON.parse json))]
    (is (= :db.type/ref (:db/valueType (d/entity db related)))
        "Fixture is a genuine schema-declared dynamic ref")
    (is (= {"title" "Direct Reference Target" "uuid" (str (:block/uuid target))}
           (get parsed (name related)))
        "Dynamic property refs are projected to stable title and uuid")
    (is (= (str (:block/uuid source-page)) (get parsed "page")))
    (is (not (re-find #"db/id|cljs\\$lang\\$protocol_mask" json))
        "The complete single-block result serializes without entity internals")
    (is (not (contains? parsed "children"))
        "The direct lookup does not claim to include descendants")))

(deftest read-upsert-blocks-validates-expected-page-and-title
  (test-helper/load-test-files
   [{:page {:block/title "Receipt Readback Page"}
     :blocks [{:block/title "receipt target"}]}])
  (let [db (conn/get-db)
        page (ldb/get-page db "Receipt Readback Page")
        block (first (ldb/get-page-blocks db (:db/id page)))
        uuid (str (:block/uuid block))
        page-uuid (str (:block/uuid page))
        [result] (api-tools/read-upsert-blocks
                  db [{:uuid uuid :page-uuid page-uuid :title "receipt target"}])
        [wrong-title] (api-tools/read-upsert-blocks
                       db [{:uuid uuid :page-uuid page-uuid :title "not the stored title"}])
        [wrong-page] (api-tools/read-upsert-blocks
                      db [{:uuid uuid :page-uuid "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
                           :title "receipt target"}])]
    (is (= {:block/uuid uuid :block/page page-uuid :block/parent page-uuid
            :block/title "receipt target"}
           result)
        "A receipt readback returns actual block and page identities with title")
    (is (some? (:error wrong-title))
        "A title mismatch is not accepted as a verified receipt")
    (is (some? (:error wrong-page))
        "A page identity mismatch is not accepted as a verified receipt")))

(deftest read-upsert-blocks-rejects-ineligible-blocks
  (test-helper/load-test-files
   [{:page {:block/title "Receipt Eligibility Page"}
     :blocks [{:block/title "ordinary receipt block"}]}])
  (let [db (conn/get-db)
        page (ldb/get-page db "Receipt Eligibility Page")
        block (first (ldb/get-page-blocks db (:db/id page)))
        property (d/entity db :block/alias)
        pseudochild (property-build/build-property-value-block block property "value")
        _ (conn/transact! nil [pseudochild])
        db-after-pseudochild (conn/get-db)
        pseudochild (d/entity db-after-pseudochild [:block/uuid (:block/uuid pseudochild)])
        [pseudochild-result] (api-tools/read-upsert-blocks
                              db-after-pseudochild [{:uuid (str (:block/uuid pseudochild))}])]
    (is (re-find #"pseudochild" (:error pseudochild-result))
        "Property-value pseudochildren cannot be accepted as receipt blocks")
    (conn/transact! nil [[:db/add (:db/id page) :logseq.property/deleted-at 1]])
    (let [[recycled-result] (api-tools/read-upsert-blocks
                             (conn/get-db) [{:uuid (str (:block/uuid block))}])]
      (is (re-find #"recycled page" (:error recycled-result))
          "Blocks on recycled pages cannot be accepted as verified receipts"))))
