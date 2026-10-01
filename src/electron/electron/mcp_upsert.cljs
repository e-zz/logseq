(ns electron.mcp-upsert)

(defn api-upsert-nodes
  [call-api-fn args]
  (call-api-fn "logseq.cli.upsertNodes"
               [(aget args "operations")
                #js {:dry-run (aget args "dry-run")
                     :receipt (aget args "receipt")}]))
