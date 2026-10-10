(ns logseq.api.db-based.tools
  "Shared helpers for db-based API calls."
  (:require [clojure.set :as set]
            [clojure.string :as string]
            [cljs.reader :as reader]
            [datascript.core :as d]
            [logseq.api.db-based.util :as api-util]
            [logseq.common.util :as common-util]
            [logseq.common.util.date-time :as date-time-util]
            [logseq.common.util.page-ref :as page-ref]
            [logseq.db :as ldb]
            [logseq.db.frontend.class :as db-class]
            [logseq.db.frontend.content :as db-content]
            [logseq.db.frontend.db :as db-db]
            [logseq.db.frontend.entity-util :as entity-util]
            [logseq.db.frontend.property :as db-property]
            [logseq.db.frontend.property.type :as db-property-type]
            [logseq.db.frontend.schema :as db-schema]
            [logseq.outliner.recycle :as outliner-recycle]
            [logseq.outliner.tree :as otree]
            [logseq.outliner.validate :as outliner-validate]
            [malli.core :as m]
            [malli.error :as me]))

(defn- ->closed-value-info
  "Stable, JSON-safe discovery info for one closed value. The uuid is the
   canonical value an agent writes back; title/ident are for humans and prompts."
  [cv]
  (let [title (or (:block/title cv) (:logseq.property/value cv))]
    (cond-> {:block/uuid (str (:block/uuid cv))}
      (some? title) (assoc :block/title title)
      (:db/ident cv) (assoc :db/ident (subs (str (:db/ident cv)) 1)))))

(defn- allowed-closed-values
  "The closed values an agent may read or write: dropped when recycled or hidden.
   Shared by discovery and write resolution so both accept the same set."
  [property]
  (remove #(or (entity-util/recycled? %) (:logseq.property/hide? %))
          (:property/closed-values property)))

(defn- property-closed-values
  "Allowed choices of a closed-value property. Exposed only for properties that
   actually have closed values so hidden metadata stays out."
  [property]
  (mapv ->closed-value-info (allowed-closed-values property)))

(defn list-properties
  "Main fn for ListProperties tool"
  [db {:keys [expand]}]
  (->> (d/datoms db :avet :block/tags :logseq.class/Property)
       (map #(d/entity db (:e %)))
       #_((fn [x] (prn :prop-keys (distinct (mapcat keys x))) x))
        (map (fn [e]
               (if expand
                 (let [closed-values (property-closed-values e)]
                   (cond-> (into {} e)
                     true
                     ;; `:property/closed-values` is a virtual entity-plus
                     ;; attribute (deps/db/.../entity_plus.cljc): never trust a
                     ;; raw value from the realized map. Drop it and re-add only
                     ;; the filtered choices below so hidden/recycled protected
                     ;; metadata cannot surface when the allowed set is empty.
                     (dissoc :block/tags :block/order :block/refs :block/name :db/index
                             :property/closed-values
                             :logseq.property/default-value)
                     true
                     (update :block/uuid str)
                     (:logseq.property/classes e)
                     (update :logseq.property/classes #(mapv :db/ident %))
                     (:logseq.property/description e)
                     (update :logseq.property/description db-property/property-value-content)
                     (seq closed-values)
                     (assoc :property/closed-values closed-values)))
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

      (some? (:logseq.property/value reference))
      (assoc :logseq.property/value (:logseq.property/value reference))

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

(defn- page-own-property-value-block?
  "True for a property-value pseudochild that stores one of the page's own
   property values (its parent is the page). Those are metadata, not page
   content, so they are excluded even from the include-children tree. Property
   value blocks owned by descendant blocks are content of those blocks and are
   left untouched."
  [page-id block]
  (and (or (:block/closed-value-property block)
           (:logseq.property/created-from-property block))
       (= page-id (:db/id (:block/parent block)))))

(defn- page-block-tree
  "Raw tree of every content block on the page, before any depth is dropped."
  [db page-id]
  (let [datoms (d/datoms db :avet :block/page page-id)
        block-eids (mapv :e datoms)
        block-ents (->> block-eids
                        (map #(d/entity db %))
                        (remove #(page-own-property-value-block? page-id %)))
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
          (cond-> {                   :entity (if include-children?
                             (entity->serializable db page)
                             (-> (entity->serializable db page)
                                 (dissoc :block/tags :block/refs)))
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

(defn get-recycled-block
  "Reads the retained subtree of an explicitly recycled ordinary block root,
   including its stored original location. Property-value pseudochildren, pages,
   tags and properties are rejected. `:subtree` is root-first and ordered and
   `:page-uuid` is the current (Recycle) location page.

   With `:classify?` an active ordinary block returns `{:state \"active\"}`
   instead of an error so the recycle tool can distinguish no-op from invalid
   before transacting."
  [db uuid-string {:keys [classify?]}]
  (cond
    (not (and (string? uuid-string)
              (common-util/uuid-string? uuid-string)))
    {:error "Block uuid must be a valid uuid string"}

    :else
    (if-let [root (d/entity db [:block/uuid (uuid uuid-string)])]
      (cond
        (entity-util/page? root)
        {:error (str "Entity uuid " uuid-string
                     " is a page, tag, or property; getRecycledBlock reads recycled blocks only")}

        (or (:block/closed-value-property root)
            (:logseq.property/created-from-property root))
        {:error (str "Block uuid " uuid-string
                     " identifies a property-value pseudochild and is not returned by getRecycledBlock")}

        (nil? (:logseq.property/deleted-at root))
        (if classify?
          {:state "active" :root-uuid (str (:block/uuid root))}
          {:error (str "Block uuid " uuid-string " is not recycled")})

        :else
        (let [subtree (outliner-recycle/subtree-uuids db root)]
          {:operation "get-recycled"
           :state "recycled"
           :root-uuid (str (:block/uuid root))
           :deleted-at (:logseq.property/deleted-at root)
           :page-uuid (some-> (:block/page root) :block/uuid str)
           :original-page-uuid (some-> (:logseq.property.recycle/original-page root) :block/uuid str)
           :original-parent-uuid (some-> (:logseq.property.recycle/original-parent root) :block/uuid str)
           :original-order (:logseq.property.recycle/original-order root)
           :subtree subtree
           :subtree-count (count subtree)}))
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
(defn- closed-value-title
  "Display value of a closed-value entity (title for text types, :logseq.property/value otherwise)."
  [ent]
  (or (:block/title ent) (:logseq.property/value ent)))

(def ^:private supported-typed-property-types
  "User property types with a supported generic write path. Internal-only types
   (map/json/string/entity/class/page/property/keyword/coll/any) are rejected."
  #{:default :number :url :checkbox :datetime :date :node :asset})

(defn- property-key->entity
  "Resolves a property identifier to its existing property entity. Accepts only a
   property UUID (the identity listProperties returns) or an exact qualified db
   ident (e.g. `logseq.property/status`, `user.property/foo`). A bare title is
   rejected so a name collision can never silently pick the wrong property."
  [db key]
  (let [id (cond
             (keyword? key) (if (namespace key) key (name key))
             (string? key) (if (string/includes? key "/") (keyword key) key)
             :else nil)
        ent (cond
              (and (string? id) (common-util/uuid-string? id))
              (d/entity db [:block/uuid (uuid id)])

              (qualified-keyword? id)
              (d/entity db id)

              :else nil)]
    (when-not (and ent (entity-util/property? ent))
      (throw (ex-info
              (str "Property " (pr-str key)
                   " must be an existing property UUID or exact qualified ident "
                   "(e.g. \"logseq.property/status\", \"user.property/foo\"); a bare title is not accepted")
              {:property key})))
    ent))

(defn- assert-writable-property!
  [prop property-id]
  (when (:logseq.property/deleted-at prop)
    (throw (ex-info (str "Property " (pr-str property-id) " is recycled and is not writable")
                    {:property property-id})))
  (when (and (entity-util/hidden? prop)
             (not= (:db/ident prop) :logseq.property/order-list-type)
             (not= (:db/ident prop) :logseq.property/query))
    (throw (ex-info (str "Property " (pr-str property-id) " is hidden and is not writable")
                    {:property property-id})))
  (when (:logseq.property/created-from-property prop)
    (throw (ex-info (str "Property " (pr-str property-id) " is a property-value entity and is not writable")
                    {:property property-id}))))

;; ====================
;; Controlled query property (issue #22): the built-in hidden
;; :logseq.property/query property is writable through an explicit
;; {"mode" "simple"|"advanced", "content" string} envelope only. Simple mode
;; stores the query text as the property value node's title and guarantees the
;; node carries no advanced-mode code metadata. Advanced mode stores EDN query
;; text (validated by parsing only, never eval) plus the code metadata the UI
;; expects on the value node. Mode switches retract stale metadata atomically
;; via :build/retract-attributes on the property value node.
(def ^:private query-advanced-config
  "The exact advanced-query code metadata the UI writes (frontend.commands
   advanced-query-steps sets BOTH). A node is advanced-mode only when it
   carries exactly these values; any other metadata state is not valid
   advanced query configuration and reads back as inconsistent."
  {:logseq.property.node/display-type :code
   :logseq.property.code/lang "clojure"})

(def ^:private query-property-advanced-code-metadata
  "Advanced queries render through code metadata on the property value node.
  A simple-mode query node must carry none of these."
  [:logseq.property.node/display-type :logseq.property.code/lang])

(defn- query-node-mode
  "Reads the observed query mode off a property value node: \"advanced\" only
   for the exact :code + clojure metadata pair, \"simple\" only when BOTH
   metadata keys are absent. Partial or foreign metadata (a lang without
   display-type, a non-clojure lang, a foreign display-type) is not valid
   advanced query configuration and reads back as \"inconsistent\", which can
   never verify a receipt claim."
  [node]
  (let [observed (select-keys node [:logseq.property.node/display-type
                                    :logseq.property.code/lang])]
    (cond
      (= query-advanced-config observed) "advanced"
      (empty? observed) "simple"
      :else "inconsistent")))

(defn- resolve-query-property-value
  "Validates the controlled query envelope and returns the importer property
   value map. Reuses the existing structured property-value import
   (`:build/property-value :block`): the value node is titled with the query
   content, its :block/uuid is preserved across edits, and advanced code
   metadata lives on the value node only. `block` is the entity whose query
   value is being replaced (nil for block adds)."
  [_db _prop property-id value block]
  (when-not (and (map? value)
                 (= #{:mode :content} (set (keys value)))
                 (contains? #{"simple" "advanced"} (:mode value))
                 (string? (:content value)))
    (throw (ex-info (str "Query property " (pr-str property-id)
                         " requires a {\"mode\" \"simple\"|\"advanced\", \"content\" string} envelope")
                    {:property property-id :value value})))
  (let [content (:content value)
        mode (:mode value)]
    (when (string/blank? content)
      (throw (ex-info (str "Query property " (pr-str property-id)
                           " content must be a non-empty string; removal is not supported")
                      {:property property-id :value value})))
    (let [;; The CURRENT value node of the edited block (nil for block adds).
          ;; Both modes reuse it: its :block/uuid rides along so the importer
          ;; upserts the existing node instead of creating a new one, keeping
          ;; the node's position and creation stamp stable across mode switches.
          ;; The ref may resolve to a datascript Entity, so read fields through
          ;; lookups rather than map destructuring.
          current-value (when block
                          (:logseq.property/query block))
          current-value-uuid (when current-value
                               (:block/uuid current-value))
          preserved (cond-> {}
                       current-value-uuid
                       (merge (select-keys current-value [:block/uuid :block/order :block/created-at])))]
      (if (= mode "simple")
        ;; Mode switch retraction: drop stale advanced code metadata in the
        ;; same tx as the content write. Always present in simple mode —
        ;; retracting an absent attribute is a no-op.
        (cond-> {:build/property-value :block
                 :block/title content
                 :build/retract-attributes query-property-advanced-code-metadata}
          current-value-uuid
          (merge preserved))
        (let [;; Parse-only validation: a malformed advanced query must be rejected
              ;; before any write, and never eval'd.
              form (try
                     (reader/read-string
                      {:readers {'tag page-ref/->page-ref}
                       :read-eval false}
                      content)
                     (catch :default e
                       (throw (ex-info (str "Query property " (pr-str property-id)
                                            " advanced content is not valid query EDN: "
                                            (ex-message e))
                                       {:property property-id :value value}))))
              _ (when-not (and (map? form)
                               (contains? form :query))
                  (throw (ex-info (str "Query property " (pr-str property-id)
                                       " advanced content must be a map with a :query key")
                                  {:property property-id :value value})))
              ;; Advanced mode ALWAYS ensures the exact UI contract metadata
              ;; (frontend.commands advanced-query-steps sets BOTH display-type
              ;; :code and lang clojure). A node whose existing lang is absent
              ;; or anything but clojure is not valid advanced query
              ;; configuration, so an advanced write normalizes it instead of
              ;; preserving it.
              advanced-metadata query-advanced-config]
          (merge {:build/property-value :block
                  :block/title content}
                 preserved
                 {:build/properties advanced-metadata}))))))

(defn- uuid-envelope->uuid
  "A stable reference value is an unambiguous `{:uuid \"...\"}` envelope, never a
   raw string guessed into a link."
  [value]
  (when (and (map? value)
             (= #{:uuid} (set (keys value)))
             (common-util/uuid-string? (:uuid value)))
    (uuid (:uuid value))))

(defn- ref-target
  "Resolves and validates the target entity a `{:uuid \"...\"}` reference envelope
   points at. Recycled targets, hidden targets and targets with a recycled or
   hidden ancestor are rejected."
  [db property-id value]
  (let [id (uuid-envelope->uuid value)]
    (when-not id
      (throw (ex-info (str "Reference property " (pr-str property-id)
                           " requires an unambiguous {\"uuid\" \"...\"} reference envelope")
                      {:property property-id :value value})))
    (let [target (d/entity db [:block/uuid id])]
      (when-not target
        (throw (ex-info (str "Reference property " (pr-str property-id)
                             " points to unknown uuid " (pr-str (:uuid value)))
                        {:property property-id :value value})))
      (when (entity-util/recycled? target)
        (throw (ex-info (str "Reference property " (pr-str property-id)
                             " points to a recycled entity")
                        {:property property-id :value value})))
      (when (entity-util/hidden? target)
        (throw (ex-info (str "Reference property " (pr-str property-id)
                             " points to a hidden entity or one with a hidden or recycled ancestor")
                        {:property property-id :value value})))
      [id target])))

(defn- class-label
  [class]
  (or (:db/ident class) (:block/title class) (some-> (:block/uuid class) str)))

(defn- assert-allowed-target-class!
  "Requires the target to be an instance of one of the property's allowed classes.
   Uses the shared `class-instance?` identity/inheritance check so ident-less user
   classes and class inheritance are honored instead of a private ident rule."
  [prop property-id target]
  (when-let [classes (seq (:logseq.property/classes prop))]
    (when-not (some #(db-db/class-instance? % target) classes)
      (throw (ex-info (str "Reference property " (pr-str property-id)
                           " requires a target tagged with one of "
                           (pr-str (mapv class-label classes)))
                      {:property property-id
                       :target-classes (mapv class-label (:block/tags target))})))))

(defn- resolve-typed-scalar
  "Validates and encodes one value for a non-closed property of a supported type."
  [db prop property-id type value]
  (case type
    :default
    (if (string? value)
      value
      (throw (ex-info (str "Text property " (pr-str property-id) " value must be a string")
                      {:property property-id :value value})))

    :number
    (if (and (number? value) (js/Number.isFinite value))
      value
      (throw (ex-info (str "Numeric property " (pr-str property-id) " value must be a finite JSON number")
                      {:property property-id :value value})))

    :url
    (if (and (string? value) (or (db-property-type/url? value) (db-property-type/macro-url? value)))
      value
      (throw (ex-info (str "URL property " (pr-str property-id) " value must be a URL string")
                      {:property property-id :value value})))

    :checkbox
    (if (boolean? value)
      value
      (throw (ex-info (str "Checkbox property " (pr-str property-id) " value must be a boolean")
                      {:property property-id :value value})))

    :datetime
    (if (and (number? value) (js/Number.isFinite value))
      value
      (throw (ex-info (str "Datetime property " (pr-str property-id) " value must be a finite epoch-milliseconds number")
                      {:property property-id :value value})))

    :date
    (let [[id target] (ref-target db property-id value)]
      (when-not (entity-util/journal? target)
        (throw (ex-info (str "Date property " (pr-str property-id) " must reference a journal page uuid")
                        {:property property-id :value value})))
      [:block/uuid id])

    :node
    (let [[id target] (ref-target db property-id value)]
      (when (or (nil? (:block/title target))
                (:block/closed-value-property target)
                (:logseq.property/created-from-property target))
        (throw (ex-info (str "Node property " (pr-str property-id) " must reference a block-shaped node with a title")
                        {:property property-id :value value})))
      (assert-allowed-target-class! prop property-id target)
      [:block/uuid id])

    :asset
    (let [[id target] (ref-target db property-id value)]
      (when-not (and (:block/title target)
                     (contains? (set (map :db/ident (:block/tags target))) :logseq.class/Asset))
        (throw (ex-info (str "Asset property " (pr-str property-id) " must reference an uploaded Asset block")
                        {:property property-id :value value})))
      [:block/uuid id])

    (throw (ex-info (str "Property " (pr-str property-id) " has unsupported type " (pr-str type))
                    {:property property-id :type type}))))

(defn- closed-choice-matches
  "All allowed closed values `value` could mean. An exact stable uuid or keyword
   identity is canonical; display-value strings also match the title and, when a
   db ident exists, its short forms. The caller decides on zero/one/many matches,
   so a duplicate display value fails visibly instead of silently picking one."
  [choices value]
  (let [ident-forms (fn [cv]
                      (when-let [ident (:db/ident cv)]
                        #{(name ident) (subs (str ident) 1)}))]
    (cond
      (keyword? value)
      (filter #(= value (:db/ident %)) choices)

      (number? value)
      (filter #(= value (:logseq.property/value %)) choices)

      (string? value)
      (filter (fn [cv]
                (or (= value (str (:block/uuid cv)))
                    (= value (closed-value-title cv))
                    (contains? (ident-forms cv) value)))
              choices)

      :else [])))

(defn- resolve-closed-choice
  "Selects a real allowed closed value by stable uuid, exact identity, or display
   value, and returns its lookup ref. Membership in the allowed (non-hidden,
   non-recycled) set is required; choices are never invented. An ident-less user
   closed value is selectable by uuid or display value. A value that matches more
   than one allowed choice fails as ambiguous rather than picking the first."
  [prop property-id value]
  (when-not (contains? db-property-type/closed-value-property-types (:logseq.property/type prop))
    (throw (ex-info (str "Closed-value property " (pr-str property-id)
                         " has unsupported type " (pr-str (:logseq.property/type prop)))
                    {:property property-id})))
  (let [choices (allowed-closed-values prop)
        matches (closed-choice-matches choices value)]
    (when (empty? matches)
      (throw (ex-info (str (:block/title prop) " value must be an existing closed value: "
                           "its stable uuid, its identity (e.g. \"logseq.property/status.done\"), "
                           "or its display value (e.g. \"Done\")")
                      {:property property-id :value value})))
    (when (> (count matches) 1)
      (throw (ex-info (str (:block/title prop) " value " (pr-str value)
                           " is ambiguous: it matches " (count matches)
                           " allowed closed values. Use the stable uuid instead.")
                      {:property property-id :value value
                       :candidate-uuids (mapv #(str (:block/uuid %)) matches)})))
    (let [cv (first matches)]
      (when-not (and (:block/closed-value-property cv) (:block/uuid cv))
        (throw (ex-info (str (:block/title prop) " value must be an existing closed value: "
                             "its stable uuid, its identity (e.g. \"logseq.property/status.done\"), "
                             "or its display value (e.g. \"Done\")")
                        {:property property-id :value value})))
      [:block/uuid (:block/uuid cv)])))

(defn- resolve-property-value
  "Resolve and validate one property pair against the real property metadata.
   Returns [ident encoded-value] for the importer, or throws. The value contract
   is driven entirely by the property's existing type/cardinality/closed values;
   the property type is never guessed from the key. `block` (optional) is the
   entity whose property is being replaced; the controlled query contract uses
   it to preserve advanced metadata on the current value node."
  ([db prop property-id value]
   (resolve-property-value db prop property-id value nil))
  ([db prop property-id value block]
   (let [type (:logseq.property/type prop)
         ident (:db/ident prop)]
     (cond
       (= ident :logseq.property/query)
       [ident (resolve-query-property-value db prop property-id value block)]

      (= ident :logseq.property/order-list-type)
      (do
        (when-not (and (string? value) (= (string/lower-case value) "number"))
          (throw (ex-info
                  "List-type value must be the string \"number\" (a numbered/ordered list). \"bullet\" requires removal, which the import path does not support yet."
                  {:property property-id :value value})))
        [ident "number"])

      (seq (:property/closed-values prop))
      (do
        (when (= :db.cardinality/many (:db/cardinality prop))
          (throw (ex-info (str "Many-valued closed-value property " (pr-str property-id)
                               " is not supported by this API")
                          {:property property-id})))
        (when (nil? value)
          (throw (ex-info (str "Closed-value property " (pr-str property-id)
                               " does not accept null; removal is not supported")
                          {:property property-id})))
        [ident (resolve-closed-choice prop property-id value)])

      (= :db.cardinality/many (:db/cardinality prop))
      (do
        (when-not (contains? db-property-type/cardinality-property-types type)
          (throw (ex-info (str "Property " (pr-str property-id) " of type " (pr-str type)
                               " cannot be many-valued")
                          {:property property-id :type type})))
        (when-not (sequential? value)
          (throw (ex-info (str "Many-valued property " (pr-str property-id) " requires a JSON array")
                          {:property property-id :value value})))
        (when (empty? value)
          (throw (ex-info (str "Many-valued property " (pr-str property-id)
                               " requires a non-empty JSON array; removal is not supported")
                          {:property property-id :value value})))
        (let [encoded (mapv #(resolve-typed-scalar db prop property-id type %) value)]
          ;; The importer unions cardinality-many values on edit; return a set so
          ;; the additive semantics are explicit rather than accidental.
          [ident (set encoded)]))

      :else
      (do
        (when (sequential? value)
          (throw (ex-info (str "Single-valued property " (pr-str property-id) " does not accept an array")
                          {:property property-id :value value})))
        (when-not (contains? supported-typed-property-types type)
          (throw (ex-info (str "Property " (pr-str property-id) " has unsupported type " (pr-str type)
                               "; supported types are "
                               (pr-str (vec (sort-by name supported-typed-property-types))))
                          {:property property-id :type type})))
        (when (nil? value)
          (throw (ex-info (str "Property " (pr-str property-id)
                               " does not accept null; removal is not supported")
                          {:property property-id})))
        [ident (resolve-typed-scalar db prop property-id type value)])))))

(defn- visible-page
  "Resolves an existing, non-recycled page by uuid for a property write."
  [db id]
  (let [page (when (common-util/uuid-string? id)
               (d/entity db [:block/uuid (uuid id)]))]
    (when-not (and (entity-util/page? page) (not (recycled-page? page)))
      (throw (ex-info "Page property edit requires an existing ordinary page"
                      {:id id})))
    page))

(defn- resolve-typed-properties
  "Stage-1/2 widened contract: existing schema-driven typed properties on
   add/edit blocks and pages. Every property in `data.properties` is located by
   UUID or exact qualified ident and validated against its real metadata before
   any write."
  [db operations]
  (mapv
   (fn [{:keys [operation entityType data id] :as op}]
     (if-not (contains? data :properties)
       op
       (let [query-edit-ids (atom #{})]
         (when-not (and (contains? #{"block" "page"} entityType)
                        (contains? #{"add" "edit"} operation))
           (throw (ex-info "Properties are supported only on add/edit blocks and pages" {})))
         (when (and (= "page" entityType) (= "edit" operation))
           (visible-page db id))
         (when (and (= "block" entityType) (= "edit" operation)
                    (:error (get-block db id {})))
           (throw (ex-info "Property edit requires an ordinary visible block" {:id id})))
         (assoc op
                ::typed-properties
                (into {}
                      (map (fn [[key value]]
                             (let [prop (property-key->entity db key)
                                   ;; The controlled query contract resolves advanced
                                   ;; metadata against the edited block's current value node.
                                   block (when (and (= "block" entityType) (= "edit" operation))
                                           (d/entity db [:block/uuid (uuid id)]))]
                               (assert-writable-property! prop key)
                               (when (and (= (:db/ident prop) :logseq.property/query)
                                          (not= entityType "block"))
                                 (throw (ex-info (str "Query property " (pr-str key)
                                                      " is supported only on add/edit block operations")
                                                 {:property key :entity-type entityType})))
                               (when (= (:db/ident prop) :logseq.property/query)
                                 (swap! query-edit-ids conj id))
                               (resolve-property-value db prop key value block)))
                           (:properties data)))
                ::query-tag? (seq @query-edit-ids)))))
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
                         (seq (::typed-properties op))
                         (assoc :build/properties (::typed-properties op))
                         (::receipt-uuid op)
                         (assoc :block/uuid (::receipt-uuid op) :build/keep-uuid? true)
                         (or (:tags data) (::query-tag? op))
                         (assoc :build/tags (cond-> (mapv #(get-ident class-idents %) (:tags data))
                                              (::query-tag? op)
                                              (conj :logseq.class/Query)))
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
  "Groups existing-page adds, block edits and page property edits together,
   preserving nested add trees."
  [db operations idents index->parent adds-by-page]
  (let [new-page-ids (ops->new-page-ids operations)
        edits-by-page (->> operations
                           (filter #(and (= "block" (:entityType %)) (= "edit" (:operation %))))
                           (group-by (fn [op]
                                       (let [block (d/entity db [:block/uuid (uuid (:id op))])]
                                         (when-not (:block/page block)
                                           (throw (ex-info "Block edit operation requires a block to have a page." {})))
                                         (str (get-in block [:block/page :block/uuid]))))))
        page-edits (->> operations
                        (filter #(and (= "page" (:entityType %)) (= "edit" (:operation %)))))
        page-edit-props (into {} (map (fn [op] [(:id op) (::typed-properties op)])) page-edits)
        page-ids (distinct (concat (remove new-page-ids (keys adds-by-page))
                                   (keys edits-by-page)
                                   (keys page-edit-props)))]
    (mapv (fn [page-id]
            {:page (cond-> {:block/uuid (uuid page-id)}
                     (seq (get page-edit-props page-id))
                     (assoc :build/properties (get page-edit-props page-id)))
             :blocks (into (build-block-tree operations index->parent (get adds-by-page page-id) idents)
                           (map (fn [op]
                                  (cond-> {:block/uuid (uuid (:id op))
                                             :block/title (if (contains? (:data op) :title)
                                                            (get-in op [:data :title])
                                                            (:block/title (d/entity db [:block/uuid (uuid (:id op))])))}
                                      (seq (::typed-properties op))
                                      (assoc :build/properties (::typed-properties op))
                                      ;; Tags are cardinality-many; this assertion adds
                                      ;; Query without resolving or replacing other tags.
                                      (::query-tag? op)
                                      (assoc :build/tags [:logseq.class/Query])))
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
                  (cond-> {:page (cond-> (if-let [journal-day (date-time-util/journal-title->int
                                                              (get-in op [:data :title])
                                                              (date-time-util/safe-journal-title-formatters nil))]
                                            {:build/journal journal-day}
                                            {:block/title (get-in op [:data :title])})
                                    (seq (::typed-properties op))
                                    (assoc :build/properties (::typed-properties op))
                                    (::receipt-uuid op)
                                    (assoc :block/uuid (::receipt-uuid op) :build/keep-uuid? true))}
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
    ;; Page edits carry properties only; title/tags/outline edits are out of scope.
    [["edit" "page"] [:map
                      [:id uuid-string]
                      [:data [:map {:closed true}
                              [:properties [:map-of [:or :keyword :string] :any]]]]]]
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
    (when-not (and (contains? #{"block" "page"} entityType)
                   (contains? #{"add" "edit"} operation))
      (throw (ex-info "Receipt mode supports only add/edit block and page operations"
                      {:operation-index index
                       :operation operation
                       :entity-type entityType})))
    (case [operation entityType]
      ["add" "page"] nil

      ["edit" "page"]
      (let [page (when (common-util/uuid-string? id)
                   (d/entity db [:block/uuid (uuid id)]))]
        (when-not (and (entity-util/page? page) (not (recycled-page? page)))
          (throw (ex-info "Receipt mode page edits require an existing page UUID"
                          {:operation-index index :page-id id}))))

      ["add" "block"]
      (let [page-id (:page-id data)
            page (when (common-util/uuid-string? page-id)
                   (d/entity db [:block/uuid (uuid page-id)]))]
        (when-not (entity-util/page? page)
          (throw (ex-info "Receipt mode block adds require an existing page UUID"
                          {:operation-index index :page-id page-id}))))

      ["edit" "block"]
      (let [block (when (common-util/uuid-string? id)
                    (d/entity db [:block/uuid (uuid id)]))]
        (when-not (:block/page block)
          (throw (ex-info "Receipt mode block edits require an existing block UUID"
                          {:operation-index index :block-id id})))))))

(def ^:private ref-property-types
  "Types stored as a direct `[:block/uuid ..]` ref (node/date/asset). Properties
   with closed values are refs too and are detected separately."
  #{:date :node :asset})

(defn- canonical-expected-property
  "Canonical comparison form for a requested property value. Reuses the write
   resolver so the receipt measures exactly what the importer stores: ref values
   become uuid strings and many values stay sets. The controlled query property
   canonicalizes to its mode/content envelope so the receipt verifies both."
  [db key value]
  (let [prop (property-key->entity db key)
        _ (assert-writable-property! prop key)
        [_ encoded] (resolve-property-value db prop key value)]
    (if (= (:db/ident prop) :logseq.property/query)
      {:mode (:mode value)
       :content (:block/title encoded)}
      (let [canonical (fn canonical [v]
                        (cond
                          (and (vector? v) (= :block/uuid (first v))) (str (second v))
                          (set? v) (set (map canonical v))
                          ;; Structured property values (the controlled query) store
                          ;; their content as the value node's title.
                          (and (map? v) (:build/property-value v)) (:block/title v)
                          :else v))]
        (canonical encoded)))))

(defn- observed-property-values
  "Canonical observed value(s) for one property on an entity, matching the shape
   produced by `canonical-expected-property`. The controlled query property
   observes the full mode/content envelope read back from actual storage:
   content is the value node's title and mode is derived from whether the node
   carries advanced code metadata."
  [db entity prop]
  (let [ident (:db/ident prop)
        type (:logseq.property/type prop)
        ref? (or (contains? ref-property-types type)
                 (boolean (seq (:property/closed-values prop))))
        value-ref? (and (not ref?) (contains? db-property-type/value-ref-property-types type))
        one (fn [v]
              (let [e (if (and (map? v) (:db/id v)) (d/entity db (:db/id v)) v)]
                (cond
                  (nil? e) nil
                  ref? (str (:block/uuid e))
                  value-ref? (if (contains? e :logseq.property/value)
                               (:logseq.property/value e)
                               (:block/title e))
                  :else e)))
        observed (if (= :db.cardinality/many (:db/cardinality prop))
                   (set (map one (get entity ident)))
                   (one (get entity ident)))]
    (if (= ident :logseq.property/query)
      (when-let [node (get entity ident)]
        (let [node (if (and (map? node) (:db/id node)) (d/entity db (:db/id node)) node)]
          {:mode (query-node-mode node)
           :content (:block/title node)}))
      observed)))

(defn- verify-requested-properties
  "Compares each requested property value against observed post-write state.
   Single values must match exactly; many values are additive, so the requested
   values must be a subset of the observed set. Returns `{:values {ident observed}}`
   or `{:error ..}` with the expected/observed pair."
  [db entity properties]
  (reduce-kv
   (fn [acc key value]
     (if (:error acc)
       acc
       (let [prop (property-key->entity db key)
             expected (canonical-expected-property db key value)
             observed (observed-property-values db entity prop)
             many? (= :db.cardinality/many (:db/cardinality prop))
             ok? (if many? (set/subset? expected observed) (= expected observed))]
         (if ok?
           (update acc :values assoc (:db/ident prop) observed)
           (cond-> {:error (str "Receipt property " (pr-str key)
                                " does not match the observed value")}
             true (assoc :property key :expected expected :observed observed))))))
   {:values {}}
   (or properties {})))

(defn read-upsert-blocks
  "Reads and validates receipt entities by UUID, preserving request order. Each
  expectation may include `:entity-type` (\"block\" default), `:page-uuid`,
  `:parent-uuid`, `:title` and `:properties` (the requested raw property map).
  Ordinary visible blocks and existing pages are eligible. `:parent-uuid` checks
  the block's :block/parent so a wrong parent cannot claim a verified hierarchy.
  Requested property values are compared against observed post-write state; the
  response carries the observed values. Returns errors in-place and never exposes
  EIDs."
  [db expected-blocks]
  (mapv (fn [{:keys [page-uuid parent-uuid entity-type properties] :as expected}]
          (let [uuid-str (:uuid expected)
                page? (= "page" entity-type)
                entity (d/entity db [:block/uuid (uuid uuid-str)])]
            (cond
              (nil? entity)
              {:error (str (if page? "Receipt page " "Receipt block ") uuid-str " not found")}

              page?
              (if-not (entity-util/page? entity)
                {:error (str "Receipt page " uuid-str " is not a page")}
                (let [prop-result (verify-requested-properties db entity properties)]
                  (cond
                    (:error prop-result) prop-result
                    (and (contains? expected :title)
                         (not= (:title expected) (:block/title entity)))
                    {:error (str "Receipt page " uuid-str " title does not match the requested title")}
                    :else
                    (cond-> {:block/uuid (str uuid-str) :block/title (:block/title entity)}
                      (contains? expected :properties)
                      (assoc :properties (:values prop-result))))))

              :else
              (let [block (get-block db uuid-str {})]
                (cond
                  (:error block) block
                  (and page-uuid (not= page-uuid (:block/page block)))
                  {:error (str "Receipt block " uuid-str " belongs to a different page")}
                  (and parent-uuid (not= parent-uuid (:block/parent block)))
                  {:error (str "Receipt block " uuid-str " does not have the requested parent " parent-uuid)}
                  (and (contains? expected :title)
                       (not= (:title expected) (:block/title block)))
                  {:error (str "Receipt block " uuid-str " title does not match the requested title")}
                  :else
                  (let [prop-result (verify-requested-properties db entity properties)]
                    (if (:error prop-result)
                      prop-result
                      (cond-> (select-keys block [:block/uuid :block/page :block/parent :block/title])
                        (contains? expected :properties)
                        (assoc :properties (:values prop-result))))))))))
        expected-blocks))

(defn ^:api build-upsert-nodes-edn
  "Given llm generated operations, builds the import EDN, validates it and returns it. It fails
   fast on anything invalid. Receipt options are internal: `:receipt?` restricts operations to
   add/edit block and page writes, and `:receipt-uuids` supplies UUIDs for add correlation."
  ([db operations*]
   (build-upsert-nodes-edn db operations* {}))
  ([db operations* {:keys [receipt? receipt-uuids]}]
   ;; Tag/property edits and non-property page edits remain unsupported.
   (when (seq (filter #(and (contains? #{"tag" "property"} (:entityType %))
                            (= "edit" (:operation %)))
                      operations*))
     (throw (ex-info "Editing a tag or property isn't supported yet" {})))
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
         operations (resolve-typed-properties db operations)
         _ (doseq [op operations
                   :when (and (= "edit" (:operation op)) (= "block" (:entityType op)))]
             (when-not (or (contains? (:data op) :title) (seq (::typed-properties op)))
               (throw (ex-info "Block edit requires a title or non-empty properties" {}))))
         _ (doseq [op operations
                   :when (and (= "edit" (:operation op)) (= "page" (:entityType op)))]
             (when-not (seq (::typed-properties op))
               (throw (ex-info "Page property edit requires non-empty properties" {}))))
         _ (when receipt?
             (validate-receipt-operations! db operations))
         _ (when (and receipt?
                      (not= (count operations) (count receipt-uuids)))
             (throw (ex-info "Receipt UUID mapping must match operation count" {})))
         operations (if receipt?
                       (map-indexed (fn [index op]
                                      (cond-> op
                                        (and (= "add" (:operation op))
                                             (contains? #{"block" "page"} (:entityType op)))
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
                                 ;; Built-in properties (e.g. :logseq.property/status,
                                 ;; :logseq.property/order-list-type) already exist in the
                                 ;; graph and must NOT be passed to the importer, which
                                 ;; would try to create them and fail on the internal
                                 ;; namespace. Only user property idents need a config entry.
                                 (filter #(= "user.property" (namespace %))
                                         (mapcat #(keys (::typed-properties %)) operations))))
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
