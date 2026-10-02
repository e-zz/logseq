(ns electron.mcp-search)

(defn search-call-args
  [args]
  (let [options #js {:enable-snippet? false}
        page-uuid (aget args "pageUuid")
        block-uuid (aget args "blockUuid")
        limit (aget args "limit")]
    (when page-uuid
      (aset options "page-uuid" page-uuid))
    (when block-uuid
      (aset options "block-uuid" block-uuid))
    (when limit
      (aset options "limit" limit))
    [(aget args "searchTerm") options]))
