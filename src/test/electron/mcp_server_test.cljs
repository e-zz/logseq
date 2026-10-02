(ns electron.mcp-server-test
  (:require [cljs.test :refer [deftest is]]
            [electron.mcp-search :as mcp-search]
            [electron.mcp-upsert :as mcp-upsert]))

(deftest search-blocks-forwards-page-block-uuids-and-limit
  (let [args #js {:searchTerm "needle"
                  :pageUuid "67e55044-10b1-426f-9247-bb680e5fe0c8"
                  :blockUuid "67e55044-10b1-426f-9247-bb680e5fe0c9"
                  :limit 7}
        [query options] (mcp-search/search-call-args args)]
    (is (= "needle" query))
    (is (= {:enable-snippet? false
            :page-uuid "67e55044-10b1-426f-9247-bb680e5fe0c8"
            :block-uuid "67e55044-10b1-426f-9247-bb680e5fe0c9"
            :limit 7}
           (js->clj options :keywordize-keys true)))))

(deftest search-blocks-forwards-existing-page-scope
  (let [args #js {:searchTerm "needle"
                  :pageUuid "67e55044-10b1-426f-9247-bb680e5fe0c8"
                  :limit 7}
        [query options] (mcp-search/search-call-args args)]
    (is (= "needle" query))
    (is (= {:enable-snippet? false
            :page-uuid "67e55044-10b1-426f-9247-bb680e5fe0c8"
            :limit 7}
           (js->clj options :keywordize-keys true)))))

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
