(ns logseq.api.db-based.tools
  "Shared helpers for db-based API calls."
  (:require [clojure.string :as string]
            [datascript.core :as d]
            [logseq.api.db-based.util :as api-util]
            [logseq.common.util :as common-util]
            [logseq.common.util.date-time :as date-time-util]
            [logseq.db :as ldb]
            [logseq.db.frontend.class :as db-class]
            [logseq.db.frontend.content :as db-content]
            [logseq.db.frontend.entity-util :as entity-util]
            [logseq.db.frontend.property :as db-property]
            [logseq.db.frontend.property.type :as db-property-type]
            [logseq.db.frontend.schema :as db-schema]
            [logseq.outliner.tree :as otree]
            [logseq.outliner.validate :as outliner-validate]
            [malli.core :as m]
            [malli.error :as me]))

(defn list-properties
  "Main fn for ListProperties tool"
  [db {:keys [expand]}]
  (->> (d/datoms db :avet :block/tags :logseq.class/Property)
       (map #(d/entity db (:e %)))
       #_((fn [x] (prn :prop-keys (distinct (mapcat keys x))) x))
       (map (fn [e]
              (if expand
                (cond-> (into {} e)
                  true
                  (dissoc :block/tags :block/order :block/refs :block/name :db/index
                          :logseq.property/default-value)
                  true
                  (update :block/uuid str)
                  (:logseq.property/classes e)
                  (update :logseq.property/classes #(mapv :db/ident %))
                  (:logseq.property/description e)
                  (update :logseq.property/description db-property/property-value-content))
                {:block/title (:block/title e)
                 :block/uuid (str (:block/uuid e))})))))

(defn list-tags
  "Main fn for ListTags tool"
  [db {:keys [expand]}]
  (->> (d/datoms db :avet :block/tags :logseq.class/Tag)
       (map #(d/entity db (:e %)))
       (map (fn [e]
              (if expand
                (cond-> (into {} e)
                  true
                  (dissoc :block/tags :block/order :block/refs :block/name)
                  true
                  (update :block/uuid str)
                  (:logseq.property.class/extends e)
                  (update :logseq.property.class/extends #(mapv :db/ident %))
                  (:logseq.property.class/properties e)
                  (update :logseq.property.class/properties #(mapv :db/ident %))
                  (:logseq.property.view/type e)
                  (assoc :logseq.property.view/type (:db/ident (:logseq.property.view/type e)))
                  (:logseq.property/description e)
                  (update :logseq.property/description db-property/property-value-content))
                {:block/title (:block/title e)
                 :block/uuid (str (:block/uuid e))})))))

(def remove-hidden-properties api-util/remove-hidden-properties)

(def ^:private serializable-block-drop-keys
  "Attrs that are internal entity identifiers rather than content."
  #{:db/id :block/page :block/parent})

(defn- reference->serializable
  [db reference]
  (let [reference (if (number? reference)
                    (d/entity db reference)
                    reference)]
    (cond-> {}
      (:block/title reference)
      (assoc :block/title (:block/title reference))

      (:block/uuid reference)
      (assoc :block/uuid (str (:block/uuid reference)))

      (:db/ident reference)
      (assoc :db/ident (subs (str (:db/ident reference)) 1)))))

(defn- dynamic-ref-attributes
  [db attrs]
  (let [schema (d/schema db)]
    (keep (fn [attr]
            (when (and (not (contains? db-schema/ref-type-attributes attr))
                       (contains? #{"user.property" "logseq.property"} (namespace attr))
                       (= :db.type/ref (get-in schema [attr :db/valueType])))
              [attr (get-in schema [attr :db/cardinality])]))
          attrs)))

(defn- entity->serializable
  "Projects an entity without its internal ids or raw schema-declared refs."
  [db entity]
  (let [base (-> (into {} (remove (fn [[k _]] (contains? serializable-block-drop-keys k))
                                  (remove-hidden-properties entity)))
                 (update :block/uuid str))
        with-many-refs (reduce (fn [clean attr]
                                 (if (contains? clean attr)
                                   (update clean attr #(mapv (partial reference->serializable db) %))
                                   clean))
                               base
                               db-schema/card-many-ref-type-attributes)
        with-schema-refs (reduce (fn [clean [attr cardinality]]
                                   (if (contains? clean attr)
                                     (update clean attr
                                             (if (= :db.cardinality/many cardinality)
                                               #(mapv (partial reference->serializable db) %)
                                               #(reference->serializable db %)))
                                     clean))
                                 with-many-refs
                                 (dynamic-ref-attributes db (keys base)))]
    (reduce (fn [clean attr]
              (if (contains? clean attr)
                (update clean attr #(reference->serializable db %))
                clean))
            with-schema-refs
            db-schema/card-one-ref-type-attributes)))

(defn- block-tree->serializable
  "Recursively makes a block tree safe to serialize for API clients.

  Every level gets a string `:block/uuid`. Parent links are omitted because
  children already encode ancestry. Schema-declared reference attributes are
  projected to stable title/uuid/ident data instead of leaking DataScript entities."
  [db block]
  (assoc (entity->serializable db block)
         :block/children (mapv #(block-tree->serializable db %) (:block/children block))))

(defn- page-block-tree
  "Raw tree of every block on the page, before any depth is dropped."
  [db page-id]
  (let [datoms (d/datoms db :avet :block/page page-id)
        block-eids (mapv :e datoms)
        block-ents (map #(d/entity db %) block-eids)
        blocks (map #(assoc % :block/title (db-content/recur-replace-uuid-in-block-title %)) block-ents)]
    (otree/blocks->vec-tree db blocks page-id)))

(defn- get-page-blocks
  "Serializes the block `tree` of a page for API callers. Without
   `include-children?` only top-level blocks are returned and their children are
   dropped, so callers that need the omitted count must measure the tree first."
  [db tree {:keys [include-children?]}]
  (if include-children?
    (mapv #(block-tree->serializable db %) tree)
    (map #(-> %
              remove-hidden-properties
              (dissoc :block/children :block/page)
              (update :block/uuid str))
         tree)))

(defn- count-tree-blocks
  [blocks]
  (reduce (fn [n block] (+ n 1 (count-tree-blocks (:block/children block)))) 0 blocks))

(defn- page-uuid-string
  [page-id-name-or-uuid]
  (when-let [id (if (uuid? page-id-name-or-uuid)
                  page-id-name-or-uuid
                  (parse-uuid (str page-id-name-or-uuid)))]
    (str id)))

(defn- recycled-page?
  [page]
  (some? (:logseq.property/deleted-at page)))

(defn- generations-by-name
  "All page generations sharing a name, split into active and recycled."
  [db page-name]
  (let [all (->> (entity-util/get-pages-by-name db page-name)
                 (map #(d/entity db (:e %)))
                 (remove nil?))
        {recycled true active false} (group-by recycled-page? all)]
    {:active (vec active) :recycled (vec recycled)}))

(defn- candidate-uuids
  [pages]
  (mapv #(str (:block/uuid %)) pages))

(defn- resolve-page-for-read
  "Resolves a page for MCP reads, returning `{:page page}` or `{:error msg}`.

  A recycled generation is only returned when `include-recycled?` is true and no
  active generation with that name exists. Ambiguous names report candidate uuids
  instead of silently picking one generation."
  [db page-id-name-or-uuid include-recycled?]
  (if (some? (page-uuid-string page-id-name-or-uuid))
    (when-let [page (ldb/get-page db page-id-name-or-uuid)]
      (when (or include-recycled? (not (recycled-page? page)))
        {:page page}))
    (let [{:keys [active recycled]} (generations-by-name db page-id-name-or-uuid)
          name-str (pr-str (name page-id-name-or-uuid))]
      (cond
        (= 1 (count active))
        {:page (first active)}

        (> (count active) 1)
        {:error (str "Page name " name-str " is ambiguous: " (count active)
                     " active pages share it. Use one of these uuids: "
                     (pr-str (candidate-uuids active)))}

        (empty? recycled)
        {:error (str "Page " name-str " not found")}

        (not include-recycled?)
        {:error (str "Page " name-str " is in the recycle bin and is not returned by"
                     " default. Pass includeRecycled=true to read it; if several generations"
                     " share this name, use includeRecycled=true with a page uuid.")}

        (= 1 (count recycled))
        {:page (first recycled)}

        :else
        {:error (str "Page name " name-str " is ambiguous: " (count recycled)
                     " recycled pages share it. Use one of these uuids: "
                     (pr-str (candidate-uuids recycled)))}))))

(defn get-page-data
  "Get page data for GetPage tool including the page's entity and its blocks.
   Recycled pages are only returned when `:include-recycled?` is true.

   Blocks are top-level only by default. Pass `:include-children?` true to get
   the complete nested tree; this requires a positive integer `:max-blocks` at
   least as large as the complete tree. Oversized reads fail without returning
   partial content."
  [db page-name-or-uuid {:keys [include-recycled? include-children? max-blocks]}]
  (let [{:keys [page error]} (resolve-page-for-read db page-name-or-uuid include-recycled?)]
    (cond
      error
      {:error error}

      (nil? page)
      nil

      :else
      (let [page-id (:db/id page)
            ;; One tree build serves both the depth measurement and the payload.
            ;; The count has to come from the full tree, because the default
            ;; serialization drops :block/children.
            tree (page-block-tree db page-id)
            total-blocks (count-tree-blocks tree)
            omitted (when-not include-children?
                      (- total-blocks (count tree)))
            invalid-budget? (and include-children?
                                 (not (and (integer? max-blocks) (pos? max-blocks))))]
        (cond
          invalid-budget?
          {:error (str "This page requires " total-blocks
                       " blocks. Pass includeChildren=true with a positive integer maxBlocks "
                       "of at least " total-blocks " to return the complete tree.")}

          (and include-children? (> total-blocks max-blocks))
          {:error (str "This page requires " total-blocks " blocks, exceeding maxBlocks="
                       max-blocks ". Retry with maxBlocks at least " total-blocks
                       " to return the complete tree.")}

          :else
          (cond-> {:entity (if include-children?
                             (entity->serializable db page)
                             (-> (remove-hidden-properties page)
                                 (dissoc :block/tags :block/refs)
                                 (update :block/uuid str)))
                   :blocks (get-page-blocks db tree {:include-children? include-children?})}
            (pos? (or omitted 0))
            (assoc :block/tree-has-more? true :block/tree-omitted-count omitted)))))))

(defn get-block
  "Gets exactly one visible block by UUID. Property-value pseudochildren and
   page entities are not blocks exposed by this endpoint. Blocks on recycled
   pages require `:include-recycled?` and carry the page's deleted-at marker."
  [db uuid-string {:keys [include-recycled?]}]
  (cond
    (not (and (string? uuid-string)
              (common-util/uuid-string? uuid-string)))
    {:error "Block uuid must be a valid uuid string"}

    :else
    (if-let [block (d/entity db [:block/uuid (uuid uuid-string)])]
      (let [page (:block/page block)
            recycled-at (:logseq.property/deleted-at page)
            parent-chain-state (loop [entity block
                                      seen #{}]
                                 (cond
                                   (nil? entity) :broken
                                   (= (:db/id entity) (:db/id page)) :reaches-page
                                   (contains? seen (:db/id entity)) :cycle
                                   (or (:logseq.property/hide? entity)
                                       (:logseq.property/deleted-at entity)) :hidden
                                   :else (recur (:block/parent entity)
                                                (conj seen (:db/id entity)))))]
        (cond
          (entity-util/page? block)
          {:error (str "Entity uuid " uuid-string " is a page, tag, or property, not a block")}

          (nil? page)
          {:error (str "Entity uuid " uuid-string " is not a block")}

          (or (:block/closed-value-property block)
              (:logseq.property/created-from-property block))
          {:error (str "Block uuid " uuid-string
                       " identifies a property-value pseudochild and is not returned by getBlock")}

          (and recycled-at (not include-recycled?))
          {:error (str "Block uuid " uuid-string
                       " belongs to a recycled page and is not returned by default. "
                       "Pass includeRecycled=true to read it")}

          (or (:logseq.property/hide? page) (= :hidden parent-chain-state))
          {:error (str "Block uuid " uuid-string " is hidden and is not returned by getBlock")}

          (= :cycle parent-chain-state)
          {:error (str "Block uuid " uuid-string " has a parent cycle and is not returned by getBlock")}

          (= :broken parent-chain-state)
          {:error (str "Block uuid " uuid-string
                       " has a parent chain that does not reach its page and is not returned by getBlock")}

          :else
          (cond-> (-> (entity->serializable db block)
                      (assoc :block/parent (some-> (:block/parent block) :block/uuid str)
                             :block/page (some-> page :block/uuid str)))
            recycled-at
            (assoc :logseq.property/deleted-at recycled-at))))
      {:error (str "Block uuid " uuid-string " not found")})))

(defn list-pages
  "Main fn for ListPages tool. Recycled pages are excluded unless
   `include-recycled?` is true, in which case they are listed with their
   `:logseq.property/deleted-at` marker. Non-recycled hidden entities are always
   excluded."
  [db {:keys [expand include-recycled?]}]
  (->> (d/datoms db :avet :block/name)
       (map #(d/entity db (:e %)))
       (remove (if include-recycled?
                 (fn [e] (and (entity-util/hidden? e)
                              (nil? (:logseq.property/deleted-at e))))
                 entity-util/hidden?))
       (map (fn [e]
              (if expand
                (-> e
                    ;; Until there are options to limit pages, return minimal info to avoid
                    ;; exceeding max payload size
                    (select-keys [:block/uuid :block/title :block/created-at :block/updated-at
                                  :logseq.property/deleted-at])
                    (update :block/uuid str))
                (cond-> {:block/title (:block/title e)
                         :block/uuid (str (:block/uuid e))}
                  (:logseq.property/deleted-at e)
                  (assoc :logseq.property/deleted-at (:logseq.property/deleted-at e))))))))

;; upsert-nodes tool
;; =================
(defn- resolve-numeric-properties
  "Narrow contract: UUID-keyed existing single-valued user number properties.
   Resolve and validate the whole batch before constructing the importer data."
  [db operations receipt?]
  (mapv
   (fn [{:keys [operation entityType data id] :as op}]
     (if-not (contains? data :properties)
       op
       (do
         (when receipt?
           (throw (ex-info "Property writes do not support receipt mode yet" {})))
         (when-not (and (= "block" entityType) (contains? #{"add" "edit"} operation))
           (throw (ex-info "Properties are supported only on add/edit blocks" {})))
         (when (and (= "edit" operation) (:error (get-block db id {})))
           (throw (ex-info "Property edit requires an ordinary visible block" {:id id})))
         (assoc op ::numeric-properties
                (into {}
                      (map (fn [[key value]]
                             (let [property-id (if (keyword? key)
                                                 (when-not (namespace key) (name key))
                                                 key)
                                   prop (when (and (string? property-id)
                                                   (common-util/uuid-string? property-id))
                                          (d/entity db [:block/uuid (uuid property-id)]))
                                   ident (:db/ident prop)]
                               (when-not (and (entity-util/property? prop)
                                              (= "user.property" (namespace ident))
                                              (= :number (:logseq.property/type prop))
                                              (= :db.cardinality/one (:db/cardinality prop))
                                              (not (seq (:property/closed-values prop)))
                                              (not (entity-util/hidden? prop)))
                                 (throw (ex-info "Property must be an existing single-valued user number property UUID"
                                                 {:property property-id})))
                               (when-not (and (number? value) (js/Number.isFinite value))
                                 (throw (ex-info "Numeric property value must be a finite JSON number"
                                                 {:property property-id})))
                               [ident value])))
                      (:properties data))))))
   operations))

(defn- get-ident [idents title]
  (or (get idents title)
      (throw (ex-info (str "No ident found for " (pr-str title)) {}))))

(defn- add-block-op?
  [op]
  (and (= "block" (:entityType op)) (= "add" (:operation op))))

(defn- batch-parent-index
  "Resolves batch parents to operation indices; existing UUID parents are
   validated separately. Named add ids must be unambiguous."
  [operations]
  (let [add-indices (filter #(= "add" (:operation (nth operations %)))
                            (range (count operations)))
        ids (group-by #(get-in operations [% :id])
                      (filter #(some? (get-in operations [% :id])) add-indices))]
    (doseq [[id indices] ids]
      (when (> (count indices) 1)
        (throw (ex-info "Duplicate add id makes parent references ambiguous"
                        {:id id :indices indices}))))
    (reduce (fn [result i]
              (let [op (nth operations i)
                    parent-id (get-in op [:data :parent-id])]
                (if (and (add-block-op? op) parent-id)
                  (if-let [parent-index (first (get ids parent-id))]
                    (do
                      (when-not (add-block-op? (nth operations parent-index))
                        (throw (ex-info "Block parent-id must identify a block, not a page, tag or property"
                                        {:index i :parent-id parent-id})))
                      (assoc result i parent-index))
                    (do
                      (when-not (common-util/uuid-string? parent-id)
                        (throw (ex-info "Block parent-id is not a batch block id or an existing block UUID"
                                        {:index i :parent-id parent-id})))
                      result))
                  result)))
            {}
            (range (count operations)))))

(defn- assert-parent-ids!
  "Validates same-page parent eligibility and batch cycles before importing."
  [db operations]
  (let [index->parent (batch-parent-index operations)]
    (doseq [[i op] (map-indexed vector operations)
            :let [parent-id (get-in op [:data :parent-id])]
            :when (and (add-block-op? op) parent-id)]
      (if-let [parent-index (get index->parent i)]
        (when-not (= (get-in op [:data :page-id])
                     (get-in operations [parent-index :data :page-id]))
          (throw (ex-info "Block parent-id names a block on a different page"
                          {:index i :parent-id parent-id})))
        (let [parent (get-block db parent-id {})]
          (when (or (:error parent)
                    (not= (get-in op [:data :page-id]) (:block/page parent)))
            (throw (ex-info "Block parent-id must identify an ordinary visible block on the same active page"
                            {:index i :parent-id parent-id :error (:error parent)}))))))
    (doseq [i (keys index->parent)]
      (loop [n i seen #{}]
        (when (contains? seen n)
          (throw (ex-info "Block parent-id references form a cycle" {:index i})))
        (when-let [parent (get index->parent n)]
          (recur parent (conj seen n)))))
    index->parent))

(defn- build-block-tree
  "Encodes batch children through recursive :build/children; existing-parent
   roots carry an explicit UUID lookup ref."
  [operations index->parent page-add-indices {:keys [class-idents]}]
  (let [children (group-by index->parent (filter #(contains? index->parent %) page-add-indices))
        build-node (fn build-node [i]
                     (let [op (nth operations i)
                           data (:data op)]
                       (cond-> {:block/title (:title data)}
                         (seq (::numeric-properties op))
                         (assoc :build/properties (::numeric-properties op))
                         (::receipt-uuid op)
                         (assoc :block/uuid (::receipt-uuid op) :build/keep-uuid? true)
                         (:tags data)
                         (assoc :build/tags (mapv #(get-ident class-idents %) (:tags data)))
                         (and (:parent-id data) (not (contains? index->parent i)))
                         (assoc :block/parent {:db/id [:block/uuid (uuid (:parent-id data))]})
                         (seq (get children i))
                         (assoc :build/children (mapv build-node (get children i))))))]
    (mapv build-node (remove #(contains? index->parent %) page-add-indices))))

(defn- ops->new-page-ids
  "Local :id's of pages added in the same call"
  [operations]
  (->> operations
       (filter #(and (= "page" (:entityType %)) (= "add" (:operation %))))
       (keep :id)
       set))

(defn- ops->existing-pages-and-blocks
  "Groups existing-page adds and edits together, preserving nested add trees."
  [db operations idents index->parent adds-by-page]
  (let [new-page-ids (ops->new-page-ids operations)
        edits-by-page (->> operations
                           (filter #(and (= "block" (:entityType %)) (= "edit" (:operation %))))
                           (group-by (fn [op]
                                       (let [block (d/entity db [:block/uuid (uuid (:id op))])]
                                         (when-not (:block/page block)
                                           (throw (ex-info "Block edit operation requires a block to have a page." {})))
                                         (str (get-in block [:block/page :block/uuid]))))))
        page-ids (distinct (concat (remove new-page-ids (keys adds-by-page))
                                   (keys edits-by-page)))]
    (mapv (fn [page-id]
            {:page {:block/uuid (uuid page-id)}
             :blocks (into (build-block-tree operations index->parent (get adds-by-page page-id) idents)
                           (map (fn [op]
                                  (cond-> {:block/uuid (uuid (:id op))
                                           :block/title (if (contains? (:data op) :title)
                                                          (get-in op [:data :title])
                                                          (:block/title (d/entity db [:block/uuid (uuid (:id op))])))}
                                    (seq (::numeric-properties op))
                                    (assoc :build/properties (::numeric-properties op))))
                                (get edits-by-page page-id)))})
          page-ids)))

(defn- ops->pages-and-blocks
  [db operations* idents]
  (let [operations (vec operations*)
        adds-by-page (group-by #(get-in operations [% :data :page-id])
                              (filter #(add-block-op? (nth operations %))
                                      (range (count operations))))
        new-pages (filter #(and (= "page" (:entityType %)) (= "add" (:operation %))) operations)
        index->parent (assert-parent-ids! db operations)]
    (into (mapv (fn [op]
                  (cond-> {:page (if-let [journal-day (date-time-util/journal-title->int
                                                       (get-in op [:data :title])
                                                       (date-time-util/safe-journal-title-formatters nil))]
                                   {:build/journal journal-day}
                                   {:block/title (get-in op [:data :title])})}
                    (seq (get adds-by-page (:id op)))
                    (assoc :blocks (build-block-tree operations index->parent
                                                     (get adds-by-page (:id op)) idents))))
                new-pages)
          (ops->existing-pages-and-blocks db operations idents index->parent adds-by-page))))

(defn- ops->classes
  [operations {:keys [property-idents class-idents existing-classes]}]
  (let [new-classes (filter #(and (= "tag" (:entityType %)) (= "add" (:operation %))) operations)
        classes (merge
                 (into {} (keep (fn [[k v]]
                                  ;; Removing existing until edits are supported
                                  (when-not (existing-classes v) [v {:block/title k}]))
                                class-idents))
                 (->> new-classes
                      (map (fn [{:keys [data] :as op}]
                             (let [title (get-in op [:data :title])
                                   class-m (cond-> {:block/title title}
                                             (:class-extends data)
                                             (assoc :build/class-extends (mapv #(get-ident class-idents %) (:class-extends data)))
                                             (:class-properties data)
                                             (assoc :build/class-properties (mapv #(get-ident property-idents %) (:class-properties data))))]
                               [(get-ident class-idents title) class-m])))
                      (into {})))]
    classes))

(defn- ops->properties
  [operations {:keys [property-idents class-idents existing-properties]}]
  (let [new-properties (filter #(and (= "property" (:entityType %)) (= "add" (:operation %))) operations)
        properties
        (merge
         existing-properties
         (->> new-properties
              (map (fn [{:keys [data] :as op}]
                     (let [title (get-in op [:data :title])
                           prop-m (cond-> {:block/title title}
                                    (some->> (:property-type data) keyword (contains? (set db-property-type/user-built-in-property-types)))
                                    (assoc :logseq.property/type (keyword (:property-type data)))
                                    (= "many" (:property-cardinality data))
                                    (assoc :db/cardinality :db.cardinality/many)
                                    (:property-classes data)
                                    (assoc :build/property-classes
                                           (mapv #(get-ident class-idents %) (:property-classes data))
                                           :logseq.property/type :node))]
                       [(get-ident property-idents title) prop-m])))
              (into {})))]
    properties))

(defn- operations->idents
  "Creates property and class idents from all uses of them in operations"
  [db operations]
  (let [existing-classes (atom #{})
        existing-properties (atom {})
        property-idents
        (->> (filter #(and (= "property" (:entityType %)) (= "add" (:operation %)))
                     operations)
             (map #(get-in % [:data :title]))
             (into (mapcat #(get-in % [:data :class-properties])
                           (filter #(and (= "tag" (:entityType %)) (= "add" (:operation %)))
                                   operations)))
             distinct
             (map #(vector % (if (common-util/uuid-string? %)
                               (let [ent (d/entity db [:block/uuid (uuid %)])
                                     ident (:db/ident ent)]
                                 (when-not (entity-util/property? ent)
                                   (throw (ex-info (str (pr-str (:block/title ent))
                                                        " is not a property and can't be used as one")
                                                   {})))
                                 (swap! existing-properties assoc ident (select-keys ent [:db/cardinality :logseq.property/type]))
                                 ident)
                               (db-property/create-user-property-ident-from-name %))))
             (into {}))
        class-idents
        (->> (filter #(and (= "tag" (:entityType %)) (= "add" (:operation %))) operations)
             (mapcat (fn [op]
                       (into [(get-in op [:data :title])] (get-in op [:data :class-extends]))))
             (into (mapcat #(get-in % [:data :property-classes])
                           (filter #(and (= "property" (:entityType %)) (= "add" (:operation %)))
                                   operations)))
             (into (mapcat #(get-in % [:data :tags])
                           (filter #(and (= "block" (:entityType %)) (= "add" (:operation %)))
                                   operations)))
             distinct
             (map #(vector % (if (common-util/uuid-string? %)
                               (let [ent (d/entity db [:block/uuid (uuid %)])
                                     ident (:db/ident ent)]
                                 (when-not (entity-util/class? ent)
                                   (throw (ex-info (str (pr-str (:block/title ent))
                                                        " is not a tag and can't be used as one")
                                                   {})))
                                 (swap! existing-classes conj ident)
                                 ident)
                               (db-class/create-user-class-ident-from-name db %))))
             (into {}))]
    {:property-idents property-idents
     :class-idents class-idents
     :existing-classes @existing-classes
     :existing-properties @existing-properties}))

(def ^:private add-non-block-schema
  [:map
   [:data [:map
           [:title :string]]]])

(def ^:private uuid-string
  [:and :string [:fn {:error/message "Must be a uuid string"} common-util/uuid-string?]])

(def ^:private upsert-nodes-operation-schema
  [:and
   ;; Base schema. Has some overlap with inputSchema
   [:map
    {:closed true}
    [:operation [:enum "add" "edit"]]
    [:entityType [:enum "block" "page" "tag" "property"]]
    [:id {:optional true} [:or :string :nil]]
    [:data [:map
            [:properties {:optional true} [:map-of [:or :keyword :string] :any]]
            [:title {:optional true} :string]
            [:page-id {:optional true} :string]
            [:tags {:optional true} [:sequential uuid-string]]
            [:property-type {:optional true} :string]
            [:property-cardinality {:optional true} [:enum "many" "one"]]
            [:property-classes {:optional true} [:sequential :string]]
            [:class-extends {:optional true} [:sequential :string]]
            [:class-properties {:optional true} [:sequential :string]]]]]
   ;; Validate special cases of operation and entityType e.g. required keys and uuid strings
   [:multi {:dispatch (juxt :operation :entityType)}
    [["add" "block"] [:map
                      [:data [:map {:closed true}
                              [:tags {:optional true} [:sequential uuid-string]]
                              [:properties {:optional true} [:map-of [:or :keyword :string] :any]]
                              [:title :string]
                              [:page-id :string]
                              ;; Optional parent reference (issue #6): a unique
                              ;; string temp id of another block add in the same
                              ;; call, or an existing ordinary block uuid.
                              [:parent-id {:optional true} :string]]]]]
    [["add" "page"] add-non-block-schema]
    [["add" "tag"] add-non-block-schema]
    [["add" "property"] add-non-block-schema]
    [["edit" "block"] [:map
                       [:id uuid-string]
                       ;; :tags not supported yet
                       [:data [:map {:closed true}
                               [:title {:optional true} :string]
                               [:properties {:optional true} [:map-of [:or :keyword :string] :any]]]]]]
    ;; other edit's
    [::m/default [:map [:id uuid-string]]]]])

(def ^:private Upsert-nodes-operations-schema
  [:sequential upsert-nodes-operation-schema])

(defn- assert-add-block-page-ids!
  "page-id must be the :id of a page added in the same call or the uuid of an
   existing page. A page name is not resolved and would otherwise be silently dropped."
  [db operations]
  (let [new-page-ids (ops->new-page-ids operations)]
    (doseq [op (filter #(and (= "block" (:entityType %)) (= "add" (:operation %))) operations)]
      (let [page-id (get-in op [:data :page-id])]
        (when-not (contains? new-page-ids page-id)
          (when-not (common-util/uuid-string? page-id)
            (throw (ex-info (str "Block page-id " (pr-str page-id)
                                 " must be a page uuid or the id of a page added in the same call")
                            {:page-id page-id})))
          (when-not (entity-util/page? (d/entity db [:block/uuid (uuid page-id)]))
            (throw (ex-info (str "Block page-id " (pr-str page-id) " is not an existing page")
                            {:page-id page-id}))))))))

(defn- validate-import-edn
  "Validates everything as coming from add operations, failing fast on first invalid
  node. Will need to adjust add operation assumption when supporting editing pages"
  [{:keys [pages-and-blocks properties classes]}]
  (try
    (doseq [{:block/keys [title] :as m}
            ;; Only validate new properties
            (filter :block/title (vals properties))]
      (outliner-validate/validate-property-title title {:entity-type :property :title title :entity-map m})
      (outliner-validate/validate-page-title-characters title {:entity-type :property :title title :entity-map m})
      (outliner-validate/validate-page-title title {:entity-type :property :title title :entity-map m}))
    (doseq [{:block/keys [title] :as m} (vals classes)]
      (outliner-validate/validate-page-title-characters title {:entity-type :tag :title title :entity-map m})
      (outliner-validate/validate-page-title title {:entity-type :tag :title title :entity-map m}))
    (doseq [{:block/keys [title] :as m} (map :page pages-and-blocks)]
      ;; title is only present for new pages
      (when title
        (outliner-validate/validate-page-title-characters title {:entity-type :page :title title :entity-map m})
        (outliner-validate/validate-page-title title {:entity-type :page :title title :entity-map m})))
    (catch :default e
      (js/console.error e)
      (throw (ex-info (str (string/capitalize (name (get (ex-data e) :entity-type :page)))
                           " " (pr-str (:title (ex-data e))) " is invalid: " (ex-message e))
                      (ex-data e))))))

(defn- validate-receipt-operations!
  [db operations]
  (doseq [[index {:keys [operation entityType data id]}] (map-indexed vector operations)]
    (when-not (and (= "block" entityType)
                   (contains? #{"add" "edit"} operation))
      (throw (ex-info "Receipt mode supports only add/edit block operations on existing pages"
                      {:operation-index index
                       :operation operation
                       :entity-type entityType})))
    (case operation
      "add"
      (let [page-id (:page-id data)
            page (when (common-util/uuid-string? page-id)
                   (d/entity db [:block/uuid (uuid page-id)]))]
        (when-not (entity-util/page? page)
          (throw (ex-info "Receipt mode block adds require an existing page UUID"
                          {:operation-index index :page-id page-id}))))

      "edit"
      (let [block (when (common-util/uuid-string? id)
                    (d/entity db [:block/uuid (uuid id)]))]
        (when-not (:block/page block)
          (throw (ex-info "Receipt mode block edits require an existing block UUID"
                          {:operation-index index :block-id id})))))))

(defn read-upsert-blocks
  "Reads and validates receipt blocks by UUID, preserving request order. Each
  expectation may include `:page-uuid`, `:title` and `:parent-uuid`; only
  ordinary visible blocks on active pages are eligible. `:parent-uuid` checks
  the block's :block/parent so a wrong parent cannot claim a verified
  hierarchy. Returns errors in-place and never exposes EIDs."
  [db expected-blocks]
  (mapv (fn [{:keys [uuid page-uuid parent-uuid] :as expected}]
          (let [block (get-block db uuid {})]
            (cond
              (:error block)
              block

              (and page-uuid (not= page-uuid (:block/page block)))
              {:error (str "Receipt block " uuid " belongs to a different page")}

              (and parent-uuid (not= parent-uuid (:block/parent block)))
              {:error (str "Receipt block " uuid " does not have the requested parent " parent-uuid)}

              (and (contains? expected :title)
                   (not= (:title expected) (:block/title block)))
              {:error (str "Receipt block " uuid " title does not match the requested title")}

              :else
              (select-keys block [:block/uuid :block/page :block/parent :block/title]))))
        expected-blocks))

(defn ^:api build-upsert-nodes-edn
  "Given llm generated operations, builds the import EDN, validates it and returns it. It fails
   fast on anything invalid. Receipt options are internal: `:receipt?` restricts operations to
   existing-page block writes, and `:receipt-uuids` supplies UUIDs for add correlation."
  ([db operations*]
   (build-upsert-nodes-edn db operations* {}))
  ([db operations* {:keys [receipt? receipt-uuids]}]
   ;; Only support these operations with appropriate outliner validations
   (when (seq (filter #(and (= "page" (:entityType %)) (= "edit" (:operation %))) operations*))
     (throw (ex-info "Editing a page, tag or property isn't supported yet" {})))
   (let [operations
         (->> operations*
              ;; normalize classes as they sometimes have titles in :name
              (map #(if (and (= "tag" (:entityType %)) (= "add" (:operation %)))
                      (assoc-in % [:data :title]
                                (or (get-in % [:data :name]) (get-in % [:data :title])))
                      %)))
         _ (when-let [errors (m/explain Upsert-nodes-operations-schema operations)]
             (throw (ex-info (str "Tool arguments are invalid:\n" (me/humanize errors))
                             {:errors errors})))
         operations (resolve-numeric-properties db operations receipt?)
         _ (doseq [op operations
                   :when (and (= "edit" (:operation op)) (= "block" (:entityType op)))]
             (when-not (or (contains? (:data op) :title) (seq (::numeric-properties op)))
               (throw (ex-info "Block edit requires a title or non-empty properties" {}))))
         _ (when receipt?
             (validate-receipt-operations! db operations))
         _ (when (and receipt?
                      (not= (count operations) (count receipt-uuids)))
             (throw (ex-info "Receipt UUID mapping must match operation count" {})))
         operations (if receipt?
                      (map-indexed (fn [index op]
                                     (cond-> op
                                       (and (= "add" (:operation op))
                                            (= "block" (:entityType op)))
                                       (assoc ::receipt-uuid (nth receipt-uuids index))))
                                   operations)
                      operations)
         _ (assert-add-block-page-ids! db operations)
         idents (operations->idents db operations)
         pages-and-blocks (ops->pages-and-blocks db operations idents)
         classes (ops->classes operations idents)
         properties (merge (ops->properties operations idents)
                           (into {} (map (fn [ident]
                                           [ident (select-keys (d/entity db ident)
                                                               [:block/uuid :logseq.property/type :db/cardinality])]))
                                 (mapcat #(keys (::numeric-properties %)) operations)))
         import-edn
         (cond-> {}
           (seq pages-and-blocks)
           (assoc :pages-and-blocks pages-and-blocks)
           (seq classes)
           (assoc :classes classes)
           (seq properties)
           (assoc :properties properties))]
     (validate-import-edn import-edn)
     import-edn)))
