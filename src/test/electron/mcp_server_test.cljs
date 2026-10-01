(ns electron.mcp-server-test
  (:require [cljs.test :refer [deftest is]]
            [electron.mcp-upsert :as mcp-upsert]))

(deftest upsert-nodes-forwards-receipt-option-as-json-safe-options
  (let [calls (atom nil)
        args #js {:operations #js []
                  :dry-run false
                  :receipt true}
        adapter mcp-upsert/api-upsert-nodes]
    (adapter (fn [api api-args]
               (reset! calls [api api-args])
               #js {:mode "no-op" :operations #js []})
             args)
    (let [[api api-args] @calls
          options (second api-args)
          json (js/JSON.stringify options)]
      (is (= "logseq.cli.upsertNodes" api))
      (is (true? (aget options "receipt"))
          "The opt-in receipt flag reaches the public CLI API")
      (is (= {"dry-run" false "receipt" true}
             (js->clj (js/JSON.parse json)))
          "Adapter options remain JSON serializable and preserve falsey/default fields"))))
