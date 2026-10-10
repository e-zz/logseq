(ns electron.mcp-upsert)

(def upsert-nodes-description
  "The shipped upsertNodes tool description. Kept here (not in `electron.mcp-server`)
   so the description contract can be asserted by tests without loading the MCP SDK."
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
              * :properties - Blocks and pages: a map keyed by an existing property's stable string uuid or exact qualified ident (e.g. \"logseq.property/status\", \"user.property/orcid\"); a bare title is rejected. Writable types are text, finite number, URL, checkbox, datetime (epoch milliseconds), date (a journal page uuid), node and asset references (value: {\"uuid\": \"...\"}), and closed-value properties such as Task status (value: its stable uuid, exact identity, or display value). cardinality-many properties take a non-empty JSON array and are additive: existing values are kept, not replaced. null removal and empty arrays are rejected. Unknown, hidden, recycled or unsupported-type properties, ambiguous/duplicate closed-value display matches, wrong-class references and recycled references are rejected before any write. The built-in query property (\"logseq.property/query\") is writable only on add/edit blocks and only through the explicit envelope {\"mode\": \"simple\"|\"advanced\", \"content\": string}: simple stores the query text; advanced stores query EDN (validated by parsing, never evaluated) and marks the value node as code. Mode switches rewrite the value node in place, keeping its stable uuid, and drop stale advanced code metadata atomically. Property-only edits preserve the title. Pass receipt=true to combine property writes with observed post-transaction readback. Call listProperties with expand=true to discover each property's type, cardinality, uuid and, for closed-value properties, the allowed closed-values list with stable uuids. dry-run validates without writing.
              * :tags - A list of tags as string uuids
              * :property-type - A property's type
              * :property-cardinality - A property's cardinality. Must be :one or :many
              * :property-classes - A property's list of allowed tags, each being a uuid string or a tag's name
              * :class-extends - List of parent tags, each being a uuid string or a tag's name
              * :class-properties - A tag's list of properties, each eing a uuid string or a property's name

         Example inputs with their prompt, description and data as clojure EDN:

         Description: This input adds a new block to page with id '119268a6-704f-4e9e-8c34-36dfc6133729' and sets properties on a person page with uuid '119268a6-704f-4e9e-8c34-36dfc6133729':

         {:operations
          [{:operation :add
            :entityType :block
            :id nil
            :data {:page-id \"119268a6-704f-4e9e-8c34-36dfc6133729\"
                   :title \"New block text\"}}
           {:operation :edit
            :entityType :page
            :id \"119268a6-704f-4e9e-8c34-36dfc6133729\"
            :data {:properties {\"user.property/orcid\" \"0000-0002-1825-0097\"
                                \"user.property/topics\" [\"pedagogy\"]}}}]}

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
         * Before creating any page, tag or property, check that it exists with getPage")

(defn api-upsert-nodes
  [call-api-fn args]
  (call-api-fn "logseq.cli.upsertNodes"
               [(aget args "operations")
                #js {:dry-run (aget args "dry-run")
                     :receipt (aget args "receipt")}]))
