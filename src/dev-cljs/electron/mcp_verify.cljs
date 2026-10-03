(ns electron.mcp-verify
  "Opt-in verification harness for the desktop MCP server.

  Boots the real `electron.mcp-server` registration (real @modelcontextprotocol/sdk
  + real StreamableHTTP transport) behind a real Node HTTP listener using the
  production request handlers, and drives it with the real MCP client SDK.

  The MCP api-fn is wired through the same production method resolution and
  renderer dispatch used by `electron.server`/`electron.listener`
  (`electron.api-method`), calling the same fns the renderer exposes as
  `window.logseq.api`. The API layer routes through `state/<invoke-db-worker`
  into the outliner/recycle implementation. NOTE: `logseq.api.test-helper`
  installs a local-db worker stub (no worker thread, IPC, or SQLite); this
  harness therefore verifies the MCP protocol + API semantics, not worker
  transport or on-disk durability.

  The harness asserts the recycle/restore contract and exits nonzero on any
  mismatch. A negative control re-runs the assertions against a deliberately
  corrupted result and requires them to fail, so a silently-passing assertion
  set is itself a failure.

  Run:
    NODE_PATH=<repo>/resources/node_modules \\
      node static/mcp-http-verify.js

  This namespace is intentionally NOT named `*-test` so the main `:test` build
  never imports it (the SDK is not resolvable from the repo root node_modules)."
  (:require ["@modelcontextprotocol/sdk/client/index.js" :refer [Client]]
            ["@modelcontextprotocol/sdk/client/streamableHttp.js" :refer [StreamableHTTPClientTransport]]
            ["http" :as http]
            [clojure.string :as string]
            [datascript.core :as d]
            [electron.api-method :as api-method]
            [electron.mcp-server :as mcp-server]
            [frontend.db.conn :as conn]
            [logseq.common.util :as common-util]
            [frontend.test.helper :as test-helper]
            [logseq.api.db-based.cli :as cli-api]
            [logseq.api.test-helper :as api-test]
            [logseq.db :as ldb]
            [logseq.db.sqlite.build :as sqlite-build]
            [promesa.core :as p]))

;; API boundary -----------------------------------------------------------------
;; `electron.api-method` owns the production `logseq.<ns>.<method>` ->
;; `ns@snake_case` resolution + renderer dispatch. `real-api` mirrors the
;; `^:export` cli entries in `src/main/logseq/api.cljs`; keep them in sync.

(def ^:private real-api
  (let [api #js {}]
    (aset api "recycle_block" cli-api/recycle-block)
    (aset api "restore_block" cli-api/restore-block)
    (aset api "get_recycled_block" cli-api/get-recycled-block)
    (aset api "get_block_by_uuid" cli-api/get-block)
    (aset api "get_page_data" cli-api/get-page-data)
    (aset api "list_pages" cli-api/list-pages)
    (aset api "list_tags" cli-api/list-tags)
    (aset api "list_properties" cli-api/list-properties)
    (aset api "upsert_nodes" cli-api/upsert-nodes)
    api))

(def ^:private real-sdk #js {})

(defn- api-fn
  [meth args]
  (if-let [resolved (api-method/resolve-real-api-method meth)]
    (api-method/dispatch-real-api-method real-api real-sdk resolved args)
    (p/resolved #js {:error (str "No method found for " (pr-str meth))})))

;; HTTP shims ------------------------------------------------------------------
;; The production handlers expect a fastify request/reply. We only need the
;; fields they touch: req.headers/req.body/req.raw and res.getHeaders/res.raw,
;; plus res.code/res.send for the error branches.

(defn- shim-res
  [raw-res]
  (let [res #js {}]
    (aset res "raw" raw-res)
    (aset res "getHeaders" (fn [] #js {}))
    (aset res "code" (fn [c] (aset res "__code" c) res))
    (aset res "send"
          (fn [payload]
            (.writeHead raw-res (or (aget res "__code") 200)
                        #js {"content-type" "application/json"})
            (.end raw-res (if (string? payload) payload (js/JSON.stringify payload)))
            res))
    res))

(defn- shim-req
  [raw-req body]
  (let [req #js {}]
    (aset req "headers" (.-headers raw-req))
    (aset req "body" body)
    (aset req "raw" raw-req)
    req))

(defn- read-body
  [raw-req cb]
  (let [chunks (atom [])]
    (.on raw-req "data" (fn [c] (swap! chunks conj (.toString c "utf8"))))
    (.on raw-req "end" (fn []
                         (let [s (apply str @chunks)]
                           (cb (when (seq s) (js/JSON.parse s))))))))

(defn- route!
  [host port raw-req raw-res]
  (let [url (or (.-url raw-req) "")]
    (if-not (string/starts-with? url "/mcp")
      (do (.writeHead raw-res 404) (.end raw-res "not found"))
      (case (.-method raw-req)
        "POST" (read-body raw-req
                          (fn [body]
                            (mcp-server/handle-post-request
                             api-fn {:host host :port port}
                             (shim-req raw-req body)
                             (shim-res raw-res))))
        "GET" (mcp-server/handle-get-request (shim-req raw-req nil) (shim-res raw-res))
        "DELETE" (mcp-server/handle-delete-request (shim-req raw-req nil) (shim-res raw-res))
        (do (.writeHead raw-res 405) (.end raw-res))))))

(defn- start-server!
  []
  (p/create
   (fn [resolve _]
     (let [holder (atom nil)
           server (http/createServer
                   (fn [raw-req raw-res]
                     (let [port (some-> @holder .address .-port)]
                       (route! "127.0.0.1" port raw-req raw-res))))]
       (reset! holder server)
       (.listen server 0 "127.0.0.1"
                (fn [] (resolve server)))))))

;; Scenario --------------------------------------------------------------------

(def ^:private required-tools
  #{"getBlock" "recycleBlock" "restoreBlock" "getRecycledBlock"
    "getPage" "listPages" "listTags" "listProperties" "searchBlocks" "upsertNodes"})

(defn- entity-by-title
  [db title]
  (d/entity db (:e (first (d/datoms db :avet :block/title title)))))

(defn- parse-text
  [result]
  (let [text (.-text (aget (.-content result) 0))]
    (try
      (js->clj (js/JSON.parse text) :keywordize-keys true)
      (catch :default _
        {:raw text}))))

(defn- call-tool-text
  "Calls a tool and returns the raw content text, tolerating SDK-level
   JSON-RPC rejections so schema failures can be asserted instead of thrown."
  [client name args]
  (-> (.callTool client #js {:name name :arguments args})
      (p/then (fn [result] (.-text (aget (.-content result) 0))))
      (p/catch (fn [error] (str error)))))

(defn- call-tool-json
  [client name args]
  (-> (call-tool-text client name args)
      (p/then (fn [text]
                (try
                  (js->clj (js/JSON.parse text) :keywordize-keys true)
                  (catch :default _
                    {:raw text}))))))

(defn- call-tool-parsed
  "Calls a tool and parses its content text as JSON after the SDK promise
   resolves (unlike `call-tool-json`, SDK-level rejections are not swallowed)."
  [client name args]
  (p/then (.callTool client #js {:name name :arguments args}) parse-text))

(defn- upsert-args
  [ops {:keys [receipt dry-run]}]
  (let [args #js {}]
    (aset args "operations" (js/JSON.parse (js/JSON.stringify (clj->js ops))))
    (when receipt (aset args "receipt" true))
    (when dry-run (aset args "dry-run" true))
    args))

(def ^:private user-property-specs
  "Genuine user properties created through the production outliner/import schema
   path, mirroring what `logseq.db.sqlite.build/create-blocks` produces for
   imported graphs. `topics` is a real cardinality-many node ref so the many
   additive contract is exercised, not simulated."
  {:user.property/cv {:block/title "CV" :logseq.property/type :url}
   :user.property/orcid {:block/title "ORCID" :logseq.property/type :default}
   :user.property/description {:block/title "Description" :logseq.property/type :default}
   :user.property/topics {:block/title "Topics" :logseq.property/type :node
                          :db/cardinality :db.cardinality/many}
   :user.property/score {:block/title "Score" :logseq.property/type :number}
   :user.property/enabled {:block/title "Enabled" :logseq.property/type :checkbox}})

(defn- seed-user-properties!
  "Creates the user-property metadata fixtures plus two active page targets via
   the production import schema path (no made-up maps). Returns stable UUIDs for
   the property entities and the two topic pages so callers can cross-check
   discovery against real entities."
  []
  (sqlite-build/create-blocks
   (conn/get-db nil false)
   {:properties user-property-specs
    :pages-and-blocks [{:page {:block/title "Topic Alpha"}}
                       {:page {:block/title "Topic Beta"}}]})
  (let [db (conn/get-db)]
    {:topic-alpha (str (:block/uuid (ldb/get-page db "Topic Alpha")))
     :topic-beta (str (:block/uuid (ldb/get-page db "Topic Beta")))
     :property-uuids (into {}
                           (map (fn [[ident _]]
                                  [ident (str (:block/uuid (d/entity db ident)))]))
                           user-property-specs)}))

(defn- list-tools-info
  [client]
  (p/let [tools (.listTools client)]
    {:tool-names (->> (.-tools tools) (map #(.-name %)) sort vec)
     :schemas (into {} (map (fn [t] [(.-name t) (js->clj (.-inputSchema t))]) (.-tools tools)))}))

(defn- run-recycle-stage
  "Stage 1: recycle -> inspect -> re-recycle no-op -> restore through the real
   MCP path. Returns the parsed tool payloads keyed by assertion name."
  [client root-uuid]
  (p/let [before (call-tool-parsed client "getBlock" #js {:uuid root-uuid})
          recycle (call-tool-parsed client "recycleBlock" #js {:blockUuid root-uuid})
          hidden (call-tool-parsed client "getBlock" #js {:uuid root-uuid})
          fetched (call-tool-parsed client "getRecycledBlock" #js {:blockUuid root-uuid})
          noop (call-tool-parsed client "recycleBlock" #js {:blockUuid root-uuid})
          restore (call-tool-parsed client "restoreBlock" #js {:blockUuid root-uuid})
          visible (call-tool-parsed client "getBlock" #js {:uuid root-uuid})
          schema-rejection (call-tool-text client "recycleBlock" #js {:blockUuid "not-a-uuid"})]
    {:before before
     :recycle recycle
     :get-block-while-recycled hidden
     :get-recycled fetched
     :recycle-noop noop
     :restore restore
     :get-block-after-restore visible
     :schema-rejection schema-rejection}))

(defn- run-status-property-stage
  "Stage 4: built-in closed-value `status` write/read contract through the real
   MCP path, including dry-run and mixed-batch rejection."
  [client root-uuid page-uuid]
  (p/let [props (call-tool-json client "listProperties" #js {"expand" true})
          status-db-uuid (str (:block/uuid (d/entity (conn/get-db) :logseq.property/status)))
          status-prop (first (filter #(= status-db-uuid (:uuid %)) props))
          status-prop-uuid (:uuid status-prop)
          status-write (call-tool-json
                        client "upsertNodes"
                        (upsert-args [{:operation "edit" :entityType "block" :id root-uuid
                                       :data {:properties {status-prop-uuid "Done"}}}]
                                     {:receipt true}))
          block-after-write (call-tool-json client "getBlock" #js {"uuid" root-uuid})
          page-write (call-tool-json
                      client "upsertNodes"
                      (upsert-args [{:operation "edit" :entityType "page" :id page-uuid
                                     :data {:properties {status-prop-uuid "Doing"}}}]
                                   {:receipt true}))
          page-after-write (call-tool-json client "getPage" #js {"pageName" page-uuid})
          dry-run-write (call-tool-json
                         client "upsertNodes"
                         (upsert-args [{:operation "edit" :entityType "block" :id root-uuid
                                        :data {:properties {status-prop-uuid "Todo"}}}]
                                      {:receipt true :dry-run true}))
          block-after-dry-run (call-tool-json client "getBlock" #js {"uuid" root-uuid})
          malformed-type (call-tool-text
                          client "upsertNodes"
                          (upsert-args [{:operation "edit" :entityType "block" :id root-uuid
                                         :data {:properties {status-prop-uuid true}}}]
                                       {}))
          mixed-batch (call-tool-text
                       client "upsertNodes"
                       (upsert-args [{:operation "add" :entityType "block" :id "temp-partial"
                                      :data {:page-id page-uuid :title "mcp partial block"}}
                                     {:operation "edit" :entityType "block" :id root-uuid
                                      :data {:properties {"user.property/does-not-exist" "x"}}}]
                                    {}))
          page-after-mixed (call-tool-json client "getPage" #js {"pageName" page-uuid})]
    {:list-properties props
     :status-property status-prop
     :status-write status-write
     :block-after-property-write block-after-write
     :page-property-write page-write
     :page-after-property-write page-after-write
     :dry-run-write dry-run-write
     :block-after-dry-run block-after-dry-run
     :malformed-type malformed-type
     :mixed-batch mixed-batch
     :page-after-mixed-batch page-after-mixed}))

(defn- run-user-property-stage
  "Stage 5: genuine user-property metadata through the real MCP path. Discovery
   first: the write calls must use the UUIDs listProperties actually returns,
   never made-up names/UUIDs."
  [client seed props]
  (let [prop-by-uuid (fn [u] (first (filter #(= u (:uuid %)) props)))
        cv (prop-by-uuid (get-in seed [:property-uuids :user.property/cv]))
        orcid (prop-by-uuid (get-in seed [:property-uuids :user.property/orcid]))
        description (prop-by-uuid (get-in seed [:property-uuids :user.property/description]))
        topics-prop (prop-by-uuid (get-in seed [:property-uuids :user.property/topics]))
        score (prop-by-uuid (get-in seed [:property-uuids :user.property/score]))
        enabled (prop-by-uuid (get-in seed [:property-uuids :user.property/enabled]))
        cv-uuid (:uuid cv)
        orcid-uuid (:uuid orcid)
        description-uuid (:uuid description)
        topics-uuid (:uuid topics-prop)
        score-uuid (:uuid score)
        enabled-uuid (:uuid enabled)
        topic-alpha (:topic-alpha seed)
        topic-beta (:topic-beta seed)]
    (p/let [person-add (call-tool-json
                        client "upsertNodes"
                        (upsert-args
                         [{:operation "add" :entityType "page"
                           :data {:title "Person Alpha"
                                  :properties {cv-uuid "https://example.org/people/alpha"
                                               orcid-uuid "0000-0002-1825-0097"
                                               description-uuid "Researcher"
                                               topics-uuid [{:uuid topic-alpha}]}}}]
                         {:receipt true}))
            person-uuid (str (get-in person-add [:operations 0 :entity :uuid]))
            person-block-add (call-tool-json
                              client "upsertNodes"
                              (upsert-args
                               [{:operation "add" :entityType "block"
                                 :data {:page-id person-uuid :title "Person launch"
                                        :properties {score-uuid 0
                                                     enabled-uuid false
                                                     description-uuid "Block level description"
                                                     topics-uuid [{:uuid topic-alpha}
                                                                  {:uuid topic-beta}]}}}]
                               {:receipt true}))
            person-block-uuid (str (get-in person-block-add [:operations 0 :entity :uuid]))
            person-block-read (call-tool-json client "getBlock" #js {"uuid" person-block-uuid})
            page-union (call-tool-json
                        client "upsertNodes"
                        (upsert-args
                         [{:operation "edit" :entityType "page" :id person-uuid
                           :data {:properties {description-uuid "Senior Researcher"
                                               ;; Only topic beta is requested; the
                                               ;; importer unions many refs so both
                                               ;; alpha and beta must survive.
                                               topics-uuid [{:uuid topic-beta}]}}}]
                         {:receipt true}))
            person-page-after (call-tool-json client "getPage" #js {"pageName" person-uuid})
            block-dry-run (call-tool-json
                           client "upsertNodes"
                           (upsert-args
                            [{:operation "edit" :entityType "block" :id person-block-uuid
                              :data {:properties {score-uuid 99}}}]
                            {:receipt true :dry-run true}))
            person-block-after-dry (call-tool-json client "getBlock" #js {"uuid" person-block-uuid})
            bad-scalar (call-tool-text
                        client "upsertNodes"
                        (upsert-args
                         [{:operation "edit" :entityType "block" :id person-block-uuid
                           :data {:properties {score-uuid "not-a-number"}}}]
                         {}))
            bad-ref (call-tool-text
                     client "upsertNodes"
                     (upsert-args
                      [{:operation "edit" :entityType "block" :id person-block-uuid
                        :data {:properties {topics-uuid ["not-a-ref-envelope"]}}}]
                      {}))
            bad-mixed (call-tool-text
                       client "upsertNodes"
                       (upsert-args
                        [{:operation "add" :entityType "block"
                          :data {:page-id person-uuid :title "mcp partial person block"}}
                         {:operation "edit" :entityType "block" :id person-block-uuid
                          :data {:properties {score-uuid true}}}]
                        {}))
            person-page-after-bad (call-tool-json client "getPage" #js {"pageName" person-uuid})
            person-block-after-bad (call-tool-json client "getBlock" #js {"uuid" person-block-uuid})]
      {:person-add person-add
       :person-uuid person-uuid
       :person-block-add person-block-add
       :person-block-uuid person-block-uuid
       :person-block-read person-block-read
       :page-union page-union
       :person-page-after person-page-after
       :block-dry-run block-dry-run
       :person-block-after-dry person-block-after-dry
       :bad-scalar bad-scalar
       :bad-ref bad-ref
       :bad-mixed bad-mixed
       :person-page-after-bad person-page-after-bad
       :person-block-after-bad person-block-after-bad})))

(defn- run-scenario!
  [root-uuid seed]
  (p/let [server (start-server!)
          port (.-port (.address server))
          transport (StreamableHTTPClientTransport.
                     (js/URL. (str "http://127.0.0.1:" port "/mcp")))
          client (Client. #js {:name "mcp-verify-client" :version "0.0.1"})
          _ (.connect client transport)
          tool-info (list-tools-info client)
          recycle-stage (run-recycle-stage client root-uuid)
          page-uuid (:page (:before recycle-stage))
          status-stage (run-status-property-stage client root-uuid page-uuid)
          user-stage (run-user-property-stage client seed (:list-properties status-stage))
          _ (.close client)
          _ (p/create (fn [res _] (.close server (fn [] (res nil)))))]
    (merge {:port port :seed seed} tool-info recycle-stage status-stage user-stage)))

;; Assertions -------------------------------------------------------------------

(defn- check
  [name ok? detail]
  {:name name :ok? (boolean ok?) :detail detail})

(declare property-assertions)
(declare user-property-assertions)

(defn- assertions
  [result]
  (let [{:keys [tool-names schemas before recycle get-block-while-recycled
                get-recycled recycle-noop restore get-block-after-restore
                schema-rejection]} result
        root (:uuid before)
        child (:child-uuid result)
        original-page (:page before)
        original-parent (:parent before)
        recycle-uuids (:affected-uuids recycle)
        recycled-subtree (:subtree get-recycled)
        restored-uuids (:affected-uuids restore)]
    (into
     [(check "all required tool names are registered"
            (every? (set tool-names) required-tools)
            tool-names)
     (check "recycleBlock inputSchema requires blockUuid"
            (= ["blockUuid"] (vec (get-in schemas ["recycleBlock" "required"])))
            (get-in schemas ["recycleBlock" "required"]))
     (check "restoreBlock inputSchema requires blockUuid"
            (= ["blockUuid"] (vec (get-in schemas ["restoreBlock" "required"])))
            (get-in schemas ["restoreBlock" "required"]))
     (check "getRecycledBlock inputSchema requires blockUuid"
            (= ["blockUuid"] (vec (get-in schemas ["getRecycledBlock" "required"])))
            (get-in schemas ["getRecycledBlock" "required"]))
     (check "getBlock inputSchema requires uuid"
            (= ["uuid"] (vec (get-in schemas ["getBlock" "required"])))
            (get-in schemas ["getBlock" "required"]))
     (check "getBlock before recycle is visible"
            (and (= root (:uuid before))
                 (= "mcp root" (:title before)))
            before)
     (check "recycle reports recycled state and no-op false"
            (and (= "recycled" (:state recycle))
                 (false? (:no-op recycle)))
            recycle)
     (check "recycle root uuid matches"
            (= root (:root-uuid recycle))
            (:root-uuid recycle))
     (check "recycle affected root-first root + child"
            (and (= 2 (:affected-count recycle))
                 (= root (first recycle-uuids))
                 (= #{root child} (set recycle-uuids)))
            recycle-uuids)
     (check "recycle stamps deleted-at"
            (some? (:deleted-at recycle))
            (:deleted-at recycle))
     (check "recycled root is hidden from getBlock"
            (and (nil? (:title get-block-while-recycled))
                 (string/includes? (str (:raw get-block-while-recycled)) "getBlock"))
            get-block-while-recycled)
     (check "getRecycledBlock returns recycled root-first subtree"
            (and (= "recycled" (:state get-recycled))
                 (= root (:root-uuid get-recycled))
                 (= 2 (:subtree-count get-recycled))
                 (= root (first recycled-subtree))
                 (= #{root child} (set recycled-subtree)))
            get-recycled)
     (check "getRecycledBlock retains original page location"
            (= original-page (:original-page-uuid get-recycled))
            {:original-page (:original-page-uuid get-recycled) :expected original-page})
     (check "getRecycledBlock retains original parent"
            (= original-parent (:original-parent-uuid get-recycled))
            {:original-parent (:original-parent-uuid get-recycled)
             :expected original-parent})
     (check "re-recycle is a no-op preserving deleted-at"
            (and (true? (:no-op recycle-noop))
                 (= "already-recycled" (:reason recycle-noop))
                 (= (:deleted-at recycle) (:deleted-at recycle-noop)))
            recycle-noop)
     (check "restore reports active state and no-op false"
            (and (= "active" (:state restore))
                 (false? (:no-op restore)))
            restore)
     (check "restore returns to original page and root"
            (and (= root (:root-uuid restore))
                 (= original-page (:page-uuid restore)))
            restore)
     (check "restore reuses original parent, position and order"
            (and (= original-parent (:parent-uuid restore))
                 (= "original" (:position restore))
                 (= "original" (:order restore)))
            (select-keys restore [:parent-uuid :position :order]))
     (check "restore affected root-first root + child"
            (and (= 2 (:affected-count restore))
                 (= root (first restored-uuids))
                 (= #{root child} (set restored-uuids)))
            restored-uuids)
     (check "restored block is visible to getBlock again"
            (and (= root (:uuid get-block-after-restore))
                 (= "mcp root" (:title get-block-after-restore)))
            get-block-after-restore)
     (check "malformed uuid is rejected by the SDK input schema"
            (and (string? schema-rejection)
                 (string/includes? schema-rejection "-32602"))
            schema-rejection)]
     (property-assertions result))))

(defn- property-assertions
  [result]
  (let [{:keys [before list-properties status-property status-write
                block-after-property-write page-property-write page-after-property-write
                dry-run-write block-after-dry-run malformed-type mixed-batch
                page-after-mixed-batch]} result
        page-uuid (:page before)
        status (fn [receipt] (get-in receipt [:operations 0 :entity :properties :logseq.property/status]))
        block-observed (status status-write)
        observed-uuid (fn [block] (str (get-in block [:status :uuid])))
        mixed-titles (map :title (:blocks page-after-mixed-batch))]
    (into
     [(check "listProperties discovers the status property with a writable uuid"
            (and (seq list-properties)
                 (string? (:uuid status-property))
                 (common-util/uuid-string? (:uuid status-property)))
            (select-keys status-property [:ident :uuid :type]))
     (check "upsertNodes block closed-value write returns a verified receipt"
            (= "verified" (:mode status-write))
            (select-keys status-write [:mode :operations]))
     (check "verified block receipt reports the observed closed-value ref"
            (and (string? block-observed)
                 (common-util/uuid-string? block-observed)
                 (not= "Done" block-observed))
            block-observed)
     (check "getBlock exposes the written closed value"
            (and (nil? (:error block-after-property-write))
                 (= block-observed (observed-uuid block-after-property-write)))
            (select-keys block-after-property-write [:status]))
     (check "upsertNodes page property write returns a verified receipt"
            (= "verified" (:mode page-property-write))
            (select-keys page-property-write [:mode :operations]))
     (check "getPage exposes the page property on the entity"
            (and (nil? (:error page-after-property-write))
                 (= page-uuid (str (get-in page-after-property-write [:entity :uuid])))
                 (some? (get (:entity page-after-property-write) :status)))
            (select-keys (:entity page-after-property-write) [:uuid :status]))
     (check "dry-run reports dry-run mode and does not write"
            (and (= "dry-run" (:mode dry-run-write))
                 (= block-observed (observed-uuid block-after-dry-run)))
            (select-keys dry-run-write [:mode :operations]))
     (check "malformed closed-value type is rejected"
            (and (string? malformed-type)
                 (string/includes? malformed-type "existing closed value"))
            malformed-type)
     (check "invalid mixed batch fails without partial writes"
            (and (string? mixed-batch)
                 (string/includes? mixed-batch "existing property")
                 (not (some #(= "mcp partial block" %) mixed-titles)))
            {:error mixed-batch :titles (vec mixed-titles)})]
     (user-property-assertions result))))

(defn- user-property-context
  "Derives every value asserted by the stage-5 checks from the scenario result."
  [{:keys [list-properties person-add person-block-add person-block-uuid
           person-block-read page-union person-page-after block-dry-run
           person-block-after-dry bad-scalar bad-ref bad-mixed
           person-page-after-bad person-block-after-bad seed]}]
  (let [{:keys [property-uuids topic-alpha topic-beta]} seed
        prop-by-uuid (fn [u] (first (filter #(= u (:uuid %)) list-properties)))
        spec (fn [ident] (get user-property-specs ident))
        expected (fn [ident]
                   {:uuid (get property-uuids ident)
                    :ident (name ident)
                    :type (name (get-in (spec ident) [:logseq.property/type]))
                    :cardinality (if (= :db.cardinality/many
                                         (get-in (spec ident) [:db/cardinality]))
                                   "many" "one")})
        discovery
        (reduce
         (fn [acc ident]
           (let [want (expected ident)
                 got (prop-by-uuid (:uuid want))
                 match? (boolean
                         (and got
                              (= (:ident want) (:ident got))
                              (= (:type want) (:type got))
                              (= (:cardinality want) (:cardinality got))))]
             (assoc acc ident
                    (merge want
                           {:found? (some? got)
                            :got-ident (:ident got)
                            :got-type (:type got)
                            :got-cardinality (:cardinality got)
                            :match? match?}))))
         {}
         [:user.property/cv :user.property/orcid :user.property/description
          :user.property/topics :user.property/score :user.property/enabled])
        discovery-ok? (every? :match? (vals discovery))
        receipt-props (fn [receipt] (get-in receipt [:operations 0 :entity :properties]))
        receipt-topics (fn [receipt]
                         (set (map str (get (receipt-props receipt) :user.property/topics))))
        entity (:entity person-page-after)
        page-topics (set (map (comp str :uuid) (:topics entity)))
        page-desc (get-in entity [:description :title])
        page-cv (get-in entity [:cv :title])
        page-orcid (get-in entity [:orcid :title])
        read-topics (set (map (comp str :uuid) (:topics person-block-read)))
        read-desc (get-in person-block-read [:description :title])
        read-score (get-in person-block-read [:score :value])
        read-enabled (:enabled person-block-read)
        dry-score (get-in person-block-after-dry [:score :value])
        bad-page-desc (get-in person-page-after-bad [:entity :description :title])
        bad-page-topics (set (map (comp str :uuid) (get-in person-page-after-bad [:entity :topics])))
        bad-block-score (get-in person-block-after-bad [:score :value])
        bad-block-topics (set (map (comp str :uuid) (:topics person-block-after-bad)))
        bad-block-enabled (:enabled person-block-after-bad)
        partial-block? (boolean (some #(= "mcp partial person block" (:title %))
                                      (get-in person-page-after-bad [:blocks])))
        ref-uuid-strings? (every? #(string? (:uuid %)) (:topics entity))]
    {:discovery-ok? discovery-ok?
     :discovery discovery
     :receipt-props receipt-props
     :receipt-topics receipt-topics
     :entity entity
     :page-topics page-topics
     :page-desc page-desc
     :page-cv page-cv
     :page-orcid page-orcid
     :read-topics read-topics
     :read-desc read-desc
     :read-score read-score
     :read-enabled read-enabled
     :dry-score dry-score
     :bad-page-desc bad-page-desc
     :bad-page-topics bad-page-topics
     :bad-block-score bad-block-score
     :bad-block-topics bad-block-topics
     :bad-block-enabled bad-block-enabled
     :partial-block? partial-block?
     :ref-uuid-strings? ref-uuid-strings?
     :topic-alpha topic-alpha
     :topic-beta topic-beta
     :person-block-uuid person-block-uuid
     :person-add person-add
     :person-block-add person-block-add
     :page-union page-union
     :block-dry-run block-dry-run
     :bad-scalar bad-scalar
     :bad-ref bad-ref
     :bad-mixed bad-mixed}))

(defn- user-property-discovery-assertions
  [{:keys [discovery-ok? discovery]}]
  [(check "listProperties reports real metadata for all six typed user properties"
          discovery-ok? discovery)])

(defn- user-property-write-assertions
  [{:keys [person-add receipt-props receipt-topics topic-alpha topic-beta entity
           page-cv page-orcid page-topics ref-uuid-strings? person-block-add
           person-block-uuid read-desc read-score read-enabled read-topics]}]
  [(check "upsertNodes page add returns a verified receipt with typed user properties"
          (and (= "verified" (:mode person-add))
               (= "Person Alpha" (get-in person-add [:operations 0 :entity :title]))
               (= "https://example.org/people/alpha" (get (receipt-props person-add) :user.property/cv))
               (= "0000-0002-1825-0097" (get (receipt-props person-add) :user.property/orcid))
               (= "Researcher" (get (receipt-props person-add) :user.property/description))
               (= #{topic-alpha} (receipt-topics person-add)))
          (receipt-props person-add))
   (check "getPage exposes the typed user-property values on the entity"
          (and (= "Person Alpha" (:title entity))
               (= "https://example.org/people/alpha" page-cv)
               (= "0000-0002-1825-0097" page-orcid)
               (= #{topic-alpha topic-beta} page-topics)
               ref-uuid-strings?)
          {:title (:title entity) :cv page-cv :orcid page-orcid
           :topics (vec page-topics) :ref-uuid-strings? ref-uuid-strings?})
   (check "upsertNodes block add returns a verified receipt for typed user properties"
          (and (= "verified" (:mode person-block-add))
               (= person-block-uuid (str (get-in person-block-add [:operations 0 :entity :uuid]))))
          (select-keys person-block-add [:mode :operations]))
   (check "getBlock exposes typed description, number, checkbox and many node refs"
          (and (= "Block level description" read-desc)
               (= 0 read-score)
               (false? read-enabled)
               (= #{topic-alpha topic-beta} read-topics))
          {:description read-desc :score read-score :enabled read-enabled
           :topics (vec read-topics)})])

(defn- user-property-union-assertions
  [{:keys [page-union receipt-props receipt-topics topic-alpha topic-beta entity
           page-topics page-desc block-dry-run dry-score]}]
  [(check "many node refs are additive: editing one ref retains existing refs"
          (let [union-topics (receipt-topics page-union)]
            (and (= #{topic-alpha topic-beta} union-topics)
                 (= "Senior Researcher" (get (receipt-props page-union) :user.property/description))))
          (receipt-props page-union))
   (check "property-only page edit unions refs and retains the entity title"
          (and (= "Person Alpha" (:title entity))
               (= #{topic-alpha topic-beta} page-topics)
               (= "Senior Researcher" page-desc))
          {:title (:title entity) :description page-desc :topics (vec page-topics)})
   (check "dry-run typed property edit reports dry-run and does not mutate"
          (and (= "dry-run" (:mode block-dry-run))
               (= 0 dry-score))
          {:mode (:mode block-dry-run) :score dry-score})])

(defn- user-property-rejection-assertions
  [{:keys [bad-scalar bad-ref bad-mixed bad-page-desc bad-page-topics
           partial-block? bad-block-score bad-block-enabled bad-block-topics
           read-desc topic-alpha topic-beta]}]
  [(check "wrong-typed scalar is rejected"
          (and (string? bad-scalar)
               (string/includes? bad-scalar "finite JSON number"))
          bad-scalar)
   (check "malformed node ref is rejected"
          (and (string? bad-ref)
               (string/includes? bad-ref "reference envelope"))
          bad-ref)
   (check "mixed batch with a wrong-typed property is rejected"
          (and (string? bad-mixed)
               (string/includes? bad-mixed "finite JSON number"))
          bad-mixed)
   (check "rejected mixed batch preserves page metadata and creates no partial block"
          (and (= "Senior Researcher" bad-page-desc)
               (= #{topic-alpha topic-beta} bad-page-topics)
               (not partial-block?))
          {:description bad-page-desc :topics (vec bad-page-topics)
           :partial-block? partial-block?})
   (check "rejected scalar/ref writes preserve block metadata"
          (and (= 0 bad-block-score)
               (false? bad-block-enabled)
               (= #{topic-alpha topic-beta} bad-block-topics)
               (= "Block level description" read-desc))
          {:score bad-block-score :enabled bad-block-enabled
           :topics (vec bad-block-topics)})])

(defn- user-property-assertions
  "Exercises genuine user-property metadata through the real HTTP MCP path:
   discovery by real entity UUID, typed write receipts, typed block/page reads,
   additive cardinality-many node refs, dry-run isolation and rejection safety."
  [result]
  (let [ctx (user-property-context result)]
    (into (user-property-discovery-assertions ctx)
          (concat (user-property-write-assertions ctx)
                  (user-property-union-assertions ctx)
                  (user-property-rejection-assertions ctx)))))

(defn- negative-control-failures
  "Re-runs the assertions against a corrupted result. Returns the number that
   now fail; must be positive or the assertions are not actually checking."
  [result]
  (let [corrupted (-> result
                      (assoc-in [:recycle :state] "active")
                      (assoc-in [:recycle :affected-count] 99)
                      (assoc-in [:get-recycled] {:state "active"})
                      (assoc-in [:recycle-noop :no-op] false)
                      (assoc-in [:restore :state] "recycled")
                      (assoc-in [:get-block-after-restore :title] "wrong")
                      (assoc :schema-rejection "no code here")
                      (assoc :list-properties [])
                      (assoc-in [:status-write :mode] "unverified")
                      (assoc-in [:block-after-property-write
                                 :logseq.property/status :block/uuid] nil)
                      (assoc-in [:page-after-property-write :error] "missing")
                      (assoc-in [:dry-run-write :mode] "verified")
                      (assoc :malformed-type "accepted")
                      (assoc-in [:page-after-mixed-batch :blocks]
                                [{:block/title "mcp partial block"}])
                      (assoc-in [:person-add :operations 0 :entity :properties
                                 :user.property/cv] nil)
                      (assoc-in [:person-page-after :entity :topics] [])
                      (assoc-in [:person-block-read :score :value] 99)
                      (assoc-in [:person-block-after-dry :score :value] 99)
                      (assoc-in [:person-page-after-bad :entity :description :title] "Mutated")
                      (assoc-in [:person-block-after-bad :enabled] true)
                      (assoc :bad-scalar "accepted"))]
    (count (remove :ok? (assertions corrupted)))))

(defn- fail! [msg]
  (println "MCP-VERIFY-FAIL" msg)
  (js/process.exit 1))

(defn -main [& _]
  (api-test/start-plugin-api-db!)
  (test-helper/load-test-files
   [{:page {:block/title "MCP HTTP Page"}
     :blocks [{:block/title "mcp root"
               :build/children [{:block/title "mcp child"}]}]}])
  (let [db (conn/get-db)
        root (entity-by-title db "mcp root")
        child (entity-by-title db "mcp child")
        root-uuid (str (:block/uuid root))
        child-uuid (str (:block/uuid child))]
    (-> (api-test/with-plugin-api
          (fn []
            (let [seed (seed-user-properties!)]
              (run-scenario! root-uuid seed))))
        (p/then (fn [result]
                  (let [result (assoc result :child-uuid child-uuid)
                        checks (assertions result)
                        failures (vec (remove :ok? checks))
                        neg-failures (negative-control-failures result)
                        ok? (and (empty? failures) (pos? neg-failures))]
                    (println "MCP-VERIFY-RESULT" (js/JSON.stringify (clj->js result)))
                    (println "MCP-VERIFY-CHECKS"
                             (pr-str (mapv #(select-keys % [:name :ok?]) checks)))
                    (println "MCP-VERIFY-NEGATIVE-CONTROL" neg-failures)
                    (if ok?
                      (do (println "MCP-VERIFY-OK") (js/process.exit 0))
                      (do (println "MCP-VERIFY-FAIL"
                                   (pr-str {:failures failures
                                            :negative-control-failures neg-failures}))
                          (js/process.exit 1))))))
        (p/catch (fn [error]
                   (fail! (str error \newline (.-stack error))))))))
