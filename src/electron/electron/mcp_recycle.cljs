(ns electron.mcp-recycle)

(defn api-recycle-block
  [call-api-fn args]
  (call-api-fn "logseq.cli.recycleBlock"
               [(aget args "blockUuid")]))

(defn api-restore-block
  [call-api-fn args]
  (call-api-fn "logseq.cli.restoreBlock"
               [(aget args "blockUuid")]))

(defn api-get-recycled-block
  [call-api-fn args]
  (call-api-fn "logseq.cli.getRecycledBlock"
               [(aget args "blockUuid")]))
