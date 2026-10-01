(ns electron.mcp-server
  "MCP server routes for the desktop API server."
  (:require ["@modelcontextprotocol/sdk/server/mcp.js" :refer [McpServer]]
            ["@modelcontextprotocol/sdk/server/streamableHttp.js" :refer [StreamableHTTPServerTransport]]
            ["@modelcontextprotocol/sdk/types.js" :refer [isInitializeRequest]]
            ["zod/v3" :as z] ;; zod 4 doesn't work w/ mcp - https://github.com/modelcontextprotocol/typescript-sdk/issues/925
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
  (call-api-fn "logseq.app.search" [(aget args "searchTerm") #js {:enable-snippet? false}]))

(def ^:large-vars/data-var api-tools
  "MCP Tools when calling API server"
  {:getBlock
   {:fn api-get-block
    :config #js {:title "Get Block"
                 :description
                 "Get exactly one block by its stable UUID. Pages, tags, properties, and property-value pseudochildren are not blocks returned by this tool. Parent and page identities are UUID strings. Descendants are not returned. Blocks on recycled pages are excluded by default; pass includeRecycled=true to read one, with the page's deleted-at value included as a marker."
                 :inputSchema #js {:uuid (-> (z/string) .uuid (.describe "The block's stable uuid string"))
                                   :includeRecycled (-> (z/boolean) .optional (.describe "Read a block on a recycled page and include its deleted-at marker. Defaults to false"))}}}
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
         :description
         "This tool must be called at most once per user request. Never re-call it unless explicitly asked.
          It takes an object with field :operations, which is an array of operation objects.
          Each operation creates or edits a page, block, tag or property. Each operation is a object
          that must have :operation, :entityType and :data fields. More about fields in an operation object:
            * :operation  - Either :add or :edit
            * :entityType - What type of node, e.g. :block, :page, :tag or :property
            * :id - For :edit, this _must_ be a string uuid. For :add, use a temporary unique string if the new page is referenced by later operations e.g. add blocks
            * :data - A map of fields to set or update. This map can have the following keys:
              * :title - A page/tag/property's name or a block's content
              * :page-id - A page string uuid of a block. Required when adding a block.
              * :parent-id - (blocks only, optional) Put this new block under an existing parent in the same call. Set it to either the unique temporary :id of another block add in this same batch, or the string uuid of an existing ordinary visible block on the same page. The parent and child must resolve to the same page; missing, duplicate, cross-page, hidden, recycled, tag/property, self-referential or cyclic parents are rejected before any write. Sibling order follows the batch order and appends after the parent's existing children.
              * :tags - A list of tags as string uuids
              * :property-type - A property's type
              * :property-cardinality - A property's cardinality. Must be :one or :many
              * :property-classes - A property's list of allowed tags, each being a uuid string or a tag's name
              * :class-extends - List of parent tags, each being a uuid string or a tag's name
              * :class-properties - A tag's list of properties, each eing a uuid string or a property's name

         Example inputs with their prompt, description and data as clojure EDN:

         Description: This input adds a new block to page with id '119268a6-704f-4e9e-8c34-36dfc6133729' and update the title of a page with uuid '119268a6-704f-4e9e-8c34-36dfc6133729':

         {:operations
          [{:operation :add
            :entityType :block
            :id nil
            :data {:page-id \"119268a6-704f-4e9e-8c34-36dfc6133729\"
                   :title \"New block text\"}}
           {:operation :edit
            :entity :page
            :id \"119268a6-704f-4e9e-8c34-36dfc6133729\"
            :data {:title \"Revised page title\"}}]}

        Prompt: Add task 't1' to new page 'Inbox'
        Description: This input creates a page 'Inbox' and adds a 't1' block with tag \"00000002-1282-1814-5700-000000000000\" (task) to it:

        {:operations
          [{:operation :add
            :entityType :page
            :id \"temp-Inbox\"
            :data {:title \"Inbox\"}}
           {:operation :add
            :entityType :block
            :data {:page-id \"temp-Inbox\"
                   :title \"t1\"
                   :tags [\"00000002-1282-1814-5700-000000000000\"]}}]}

         Additional advice for building operations:
         * Before creating any page, tag or property, check that it exists with getPage"
         :inputSchema
         #js {:operations
              (z/array
               (z/object
                #js {:operation   (z/enum #js ["add" "edit"])
                     :entityType  (z/enum #js ["block" "page" "tag" "property"])
                     :id          (.optional (z/union #js [(z/string) (z/number) (z/null)]))
                     :data        (-> (z/object #js {}) (.passthrough))}))
              :dry-run (-> (z/boolean) .optional (.describe "Pretend to do batch update. Does everything except actually commit change to db e.g. validation."))
              :receipt (-> (z/boolean) .optional (.describe "Return an opt-in per-block receipt verified by post-transaction worker DB readback. Does not claim SQLite crash durability."))}}}
   :searchBlocks
   {:fn api-search-blocks
    :config #js {:title "Search Blocks"
                 :description "Search graph for blocks containing search term"
                 :inputSchema #js {:searchTerm (z/string)}}}
   :listTags
   {:fn api-list-tags
    :config #js {:title "List Tags"
                 :description "List all tags in a graph"
                 :inputSchema
                 #js {:expand (-> (z/boolean) .optional (.describe "Provide additional detail on each tag e.g. their parents (extends) and tag properties"))}}}
   :listProperties
   {:fn api-list-properties
    :config #js {:title "List Properties"
                 :description "List all properties in a graph"
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
