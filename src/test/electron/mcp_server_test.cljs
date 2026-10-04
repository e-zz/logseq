(ns electron.mcp-server-test
  (:require [cljs.test :refer [deftest is]]
            [clojure.string]
            [electron.mcp-recycle :as mcp-recycle]
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
            :publish-result? false
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
            :publish-result? false
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

(deftest upsert-nodes-description-matches-supported-property-contract
  (let [description mcp-upsert/upsert-nodes-description
        includes? (fn [s] (boolean (clojure.string/includes? description s)))]
    (is (includes? "Blocks and pages"))
    (is (includes? "stable string uuid or exact qualified ident"))
    (is (includes? "a bare title is rejected"))
    (is (includes? "cardinality-many properties take a non-empty JSON array"))
    (is (includes? "existing values are kept, not replaced"))
    (is (includes? "null removal and empty arrays are rejected"))
    (is (includes? "{\"uuid\": \"...\"}"))
    (is (includes? "recycled references are rejected"))
    (is (includes? "Property-only edits preserve the title"))
    (is (includes? "Pass receipt=true"))
    (is (not (includes? "Blocks only"))
        "the stale blocks-only property claim must be gone")
    (is (not (includes? "cannot be combined with receipt=true"))
        "property receipts are now supported")
    (is (not (includes? "non-numeric, many-valued or reference properties"))
        "the stale rejection list must be gone")
    (is (not (includes? ":entity :page"))
        "the invalid example key was corrected to :entityType")))

(deftest recycle-restore-get-recycled-adapters-forward-block-uuid
  (let [uuid "67e55044-10b1-426f-9247-bb680e5fe0c8"
        calls (atom [])
        call-api-fn (fn [api args]
                      (swap! calls conj [api (js->clj args)])
                      nil)]
    (doseq [adapter [mcp-recycle/api-recycle-block
                     mcp-recycle/api-restore-block
                     mcp-recycle/api-get-recycled-block]]
      (adapter call-api-fn #js {:blockUuid uuid}))
    (is (= [["logseq.cli.recycleBlock" [uuid]]
            ["logseq.cli.restoreBlock" [uuid]]
            ["logseq.cli.getRecycledBlock" [uuid]]]
           @calls)
        "Each tool adapter forwards the blockUuid to its camelCase public CLI method")))
