(ns electron.mcp-server
  "MCP server routes for the desktop API server."
  (:require ["@modelcontextprotocol/sdk/server/mcp.js" :refer [McpServer]]
            ["@modelcontextprotocol/sdk/server/streamableHttp.js" :refer [StreamableHTTPServerTransport]]
            ["@modelcontextprotocol/sdk/types.js" :refer [isInitializeRequest]]
            ["zod/v3" :as z] ;; zod 4 doesn't work w/ mcp - https://github.com/modelcontextprotocol/typescript-sdk/issues/925
            [electron.mcp-search :as mcp-search]
            [electron.mcp-recycle :as mcp-recycle]
            [electron.mcp-transport :as mcp-transport]
            [electron.mcp-upsert :as mcp-upsert]
            [promesa.core :as p]))

;; Server util fns
;; ===============
;; "Stores transports by session ID"
(defonce ^:private transports
  (atom {}))

(declare create-mcp-api-server)

;; See https://modelcontextprotocol.io/specification/2025-03-26/basic/transports#streamable-http
;; for how to respond to different MCP requests
(defn handle-post-request [api-fn {:keys [port host]} req res]
  (let [session-id (aget (.-headers req) "mcp-session-id")
        existing-transport (and session-id (@transports session-id))]
    (js/console.log "POST /mcp request" session-id (pr-str (.-body req)))
    (cond
      existing-transport
      (mcp-transport/handle-request! existing-transport req res (.-body req))

      (and (not session-id)
           (isInitializeRequest (.-body req)))
      (let [transport (StreamableHTTPServerTransport.
                       #js {:sessionIdGenerator (comp str random-uuid)
                            :enableDnsRebindingProtection true
                            :allowedHosts #js [(str host ":" port)]})
            mcp-server (create-mcp-api-server api-fn)]
        (set! (.-onclose transport)
              (fn []
                (js/console.log "Transport closed" (.-sessionId transport))
                (swap! transports dissoc (.-sessionId transport))))
        (.connect mcp-server transport)
        (mcp-transport/handle-request! transport req res (.-body req))
        (js/console.log "Initialize sessionId" (.-sessionId transport))
        (if (.-sessionId transport)
          (swap! transports assoc (.-sessionId transport) transport)
          (js/console.error "No sessionId to initialize!"))
        res)

      :else
      (do
        (.code res 400)
        (.send res #js {:jsonrpc "2.0"
                        :error #js {:code -32000
                                    :message "Bad Request: No valid session ID provided"}
                        :id nil})))))

(defn handle-get-request
  [req res]
  (let [session-id (aget (.-headers req) "mcp-session-id")]
    (js/console.log "GET /mcp" session-id)
    (if-let [transport (and session-id (@transports session-id))]
      (mcp-transport/handle-request! transport req res)
      (-> res (.code 400) (.send "Invalid or missing session ID")))))

(defn handle-delete-request
  [req res]
  (let [session-id (aget (.-headers req) "mcp-session-id")]
    (js/console.log "DELETE /mcp" session-id)
    (if-let [transport (and session-id (@transports session-id))]
      (do
        (.close transport)
        (-> res (.code 200) (.send #js {:ok true})))
      (-> res (.code 400) (.send "Invalid or missing session ID")))))

(defn mcp-error-response [msg]
  #js {:content
       #js [#js {:type "text"
                 :text msg}]})

(defn mcp-success-response [data]
  (clj->js {:content
            [{:type "text"
              :text (js/JSON.stringify (clj->js data))}]}))

;; API tool fns
;; ============
(defn- unexpected-api-error [error]
  #js {:content
       #js [#js {:type "text"
                 :text (str "Unexpected API error: " (.-message error))}]})

(defn- api-tool
  "Calls API method w/ args and returns a MCP response"
  [api-fn api-method method-args]
  (-> (p/let [body (api-fn api-method method-args)]
        (if-let [error (and body (aget body "error"))]
          (mcp-error-response (str "API Error: " error))
          (mcp-success-response body)))
      (p/catch unexpected-api-error)))

(defn- api-get-page
  [call-api-fn args]
  (call-api-fn "logseq.cli.getPageData"
               [(aget args "pageName")
                #js {:include-recycled? (aget args "includeRecycled")
                     :include-children? (aget args "includeChildren")
                     :max-blocks (aget args "maxBlocks")}]))

(defn- api-get-block
  [call-api-fn args]
  (call-api-fn "logseq.cli.getBlockByUuid"
               [(aget args "uuid")
                #js {:include-recycled? (aget args "includeRecycled")}]))

(defn- api-list-pages
  [call-api-fn args]
  (call-api-fn "logseq.cli.listPages"
               [#js {:expand (aget args "expand")
                     :include-recycled? (aget args "includeRecycled")}]))

(defn- api-list-tags
  [call-api-fn args]
  (call-api-fn "logseq.cli.listTags" [#js {:expand (aget args "expand")}]))

(defn- api-list-properties
  [call-api-fn args]
  (call-api-fn "logseq.cli.listProperties" [#js {:expand (aget args "expand")}]))

(defn- api-search-blocks
  [call-api-fn args]
  (call-api-fn "logseq.app.search" (mcp-search/search-call-args args)))

(def ^:large-vars/data-var api-tools
  "MCP Tools when calling API server"
  {:getBlock
   {:fn api-get-block
    :config #js {:title "Get Block"
                 :description
                 "Get exactly one block by its stable UUID. Pages, tags, properties, and property-value pseudochildren are not blocks returned by this tool. Parent and page identities are UUID strings. Descendants are not returned. Blocks on recycled pages are excluded by default; pass includeRecycled=true to read one, with the page's deleted-at value included as a marker."
                 :inputSchema #js {:uuid (-> (z/string) .uuid (.describe "The block's stable uuid string"))
                                   :includeRecycled (-> (z/boolean) .optional (.describe "Read a block on a recycled page and include its deleted-at marker. Defaults to false"))}}}
   :recycleBlock
   {:fn mcp-recycle/api-recycle-block
    :config #js {:title "Recycle Block"
                 :description
                 "Soft-delete exactly one ordinary block root and its subtree into the recycle bin. The root must be an ordinary block: pages, tags, properties, built-in blocks, hidden blocks, property-value pseudochildren, and any subtree containing a page are rejected before any write. Recycling an already-recycled root is an explicit no-op that keeps the existing deleted-at timestamp and original location. Returns the recycled root uuid, its page uuid, the affected subtree uuids root-first, the affected count, and the deleted-at timestamp."
                 :inputSchema #js {:blockUuid (-> (z/string) .uuid (.describe "Stable uuid string of the ordinary block root to recycle"))}}}
   :restoreBlock
   {:fn mcp-recycle/api-restore-block
    :config #js {:title "Restore Block"
                 :description
                 "Restore exactly one previously recycled ordinary block root and its retained subtree to its original parent and order. Restoring a block that is not recycled is an actionable error, never a silent no-op. Returns the restored root uuid, its page and parent uuids, the affected subtree uuids and count, and whether the original position and order were reused or regenerated."
                 :inputSchema #js {:blockUuid (-> (z/string) .uuid (.describe "Stable uuid string of the recycled block root to restore"))}}}
   :getRecycledBlock
   {:fn mcp-recycle/api-get-recycled-block
    :config #js {:title "Get Recycled Block"
                 :description
                 "Read exactly one recycled ordinary block root: its deleted-at timestamp, its stored original page/parent/order, and its retained subtree as a root-first, ordered list of stable block uuids. Pages, tags, properties, property-value pseudochildren, and blocks that are not recycled are rejected."
                 :inputSchema #js {:blockUuid (-> (z/string) .uuid (.describe "Stable uuid string of the recycled block root to inspect"))}}}
   :listPages
   {:fn api-list-pages
    :config #js {:title "List Pages"
                 :description
                 "List all pages in a graph. Pages in the recycle bin (trashed pages)
                  are excluded by default; pass includeRecycled to list them, in which
                  case each recycled page is marked with a deleted-at value"
                 :inputSchema
                 #js {:expand (-> (z/boolean) .optional (.describe "Provide additional detail on each page"))
                      :includeRecycled (-> (z/boolean) .optional (.describe "Include pages in the recycle bin, each marked with a deleted-at value. Defaults to false"))}}}
   :getPage
   {:fn api-get-page
    :config #js {:title "Get Page"
                 :description
                 "Get a page's content including its blocks. A property and a tag are pages.
                 A page in the recycle bin is not returned by default: looking one up by
                 name or uuid reports an error instead. Pass includeRecycled to read a
                 recycled page, which is then marked with a deleted-at value.
                 When several pages share a name the active one wins; if that is still
                 ambiguous the error lists the candidate uuids to disambiguate with.
                 Blocks are returned top-level only by default, because a page can be
                 arbitrarily deep. Such a response is marked tree-has-more? true with
                 tree-omitted-count giving how many descendant blocks were left out, so a
                 partial read is never mistaken for the whole page. Pass includeChildren
                 to get every descendant nested under children, in order, with levels and
                 string uuids; that read is complete and carries no omitted count. A full-tree read requires maxBlocks: a positive integer node budget. If omitted, invalid, or smaller than the page's block count, the tool returns an error without partial blocks; the error reports the required count. maxBlocks is a block-count bound, not a byte-size bound."
                 :inputSchema #js {:pageName (-> (z/string) (.describe "The page's name or uuid"))
                                   :includeRecycled (-> (z/boolean) .optional (.describe "Read a page that is in the recycle bin. Defaults to false"))
                                   :includeChildren (-> (z/boolean) .optional (.describe "Return the full nested block tree with every descendant. Requires a positive integer maxBlocks at least as large as the complete tree; errors rather than returning partial content when missing or too small."))
                                   :maxBlocks (-> (z/number) .int .positive .optional (.describe "Required when includeChildren=true. Caller-selected positive integer maximum number of blocks; must be at least the page's full block count. Bounds block count, not response bytes."))}}}
   :upsertNodes
   {:fn mcp-upsert/api-upsert-nodes
    :config
    #js {:title "Upsert Nodes"
         :description mcp-upsert/upsert-nodes-description
         :inputSchema
         #js {:operations
              (z/array
               (z/object
                #js {:operation   (z/enum #js ["add" "edit"])
                     :entityType  (z/enum #js ["block" "page" "tag" "property"])
                     :id          (.optional (z/union #js [(z/string) (z/number) (z/null)]))
                     :data        (-> (z/object #js {}) (.passthrough))}))
              :dry-run (-> (z/boolean) .optional (.describe "Pretend to do batch update. Does everything except actually commit change to db e.g. validation."))
              :receipt (-> (z/boolean) .optional (.describe "Return an opt-in per-operation (page or block) receipt verified by post-transaction worker DB readback, including requested typed property values. Does not claim SQLite crash durability."))}}}
   :searchBlocks
   {:fn api-search-blocks
    :config #js {:title "Search Blocks"
                 :description "Search graph for blocks containing search term. Optionally scope to an active page by stable page UUID or exactly one active block by stable block UUID; pageUuid and blockUuid must agree when both are set. Exact-block scope uses indexed text search and excludes descendants and semantic/vector results. Limit defaults to the API default and must be between 1 and 100."
                 :inputSchema #js {:searchTerm (z/string)
                                   :pageUuid (-> (z/string) .uuid .optional (.describe "Stable UUID of an existing active visible page. Invalid or recycled pages fail instead of triggering a global search."))
                                   :blockUuid (-> (z/string) .uuid .optional (.describe "Stable UUID of exactly one active visible block. Its descendants are excluded. Invalid, hidden, recycled, pseudochild, cyclic, or broken-chain blocks fail visibly."))
                                   :limit (-> (z/number) .int .positive (.max 100) .optional (.describe "Maximum number of results, from 1 through 100. Defaults to the existing search API default."))}}}
   :listTags
   {:fn api-list-tags
    :config #js {:title "List Tags"
                 :description "List all tags in a graph"
                 :inputSchema
                 #js {:expand (-> (z/boolean) .optional (.describe "Provide additional detail on each tag e.g. their parents (extends) and tag properties"))}}}
   :listProperties
   {:fn api-list-properties
    :config #js {:title "List Properties"
                 :description "List all properties in a graph. Pass expand=true to include each property's type, cardinality and, for closed-value properties such as status, the allowed choices with their display values, fully-qualified idents and stable uuids to write back with upsertNodes."
                 :inputSchema
                 #js {:expand (-> (z/boolean) .optional (.describe "Provide additional detail on each property e.g. property type, cardinality"))}}}})

(defn call-api-tool [tool-fn api-fn args]
  (tool-fn (partial api-tool api-fn) args))

;; Server fns
;; ==========
(defn create-mcp-server []
  (McpServer. #js {:name "Logseq MCP Server"
                   :version "0.1.0"}))

(defn create-mcp-api-server [api-fn]
  (let [mcp-server (create-mcp-server)]
    (doseq [[k v] api-tools]
      (.registerTool mcp-server
                     (name k)
                     (:config v)
                     (partial call-api-tool (:fn v) api-fn)))
    mcp-server))
