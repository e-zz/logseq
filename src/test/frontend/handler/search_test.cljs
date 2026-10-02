(ns frontend.handler.search-test
  (:require [cljs.test :refer [async deftest is]]
            [frontend.handler.search :as search-handler]
            [frontend.search :as search]
            [frontend.state :as state]
            [logseq.common.util :as common-util]
            [promesa.core :as p]))

(deftest search-resolves-string-page-filter-through-worker-test
  (async done
    (let [worker-call (atom nil)
          block-search-call (atom nil)
          state-update (atom nil)]
      (-> (p/with-redefs [state/<invoke-db-worker
                          (fn [& args]
                            (reset! worker-call args)
                            (p/resolved {:db/id 42}))
                          search/block-search
                          (fn [repo q opts]
                            (reset! block-search-call {:repo repo
                                                       :q q
                                                       :opts opts})
                            (p/resolved [{:block/uuid "block-1"}]))
                          search/file-search
                          (fn [_q]
                            (p/resolved ["file.md"]))
                          state/swap-state!
                          (fn [_f & args]
                            (reset! state-update args)
                            nil)]
            (search-handler/search "logseq_db_test" "needle" {:page-db-id "page-name"
                                                              :limit 5}))
          (.then (fn [result]
                   (is (= [:thread-api/pull "logseq_db_test" [:db/id] [:block/name "page-name"]]
                          @worker-call))
                   (is (= {:repo "logseq_db_test"
                           :q "needle"
                           :opts {:page-db-id "page-name"
                                  :limit 5
                                  :page "42"}}
                          @block-search-call))
                   (is (= [:search/result {:blocks [{:block/uuid "block-1"}]
                                            :has-more? false}]
                          @state-update))
                   (is (= {:blocks [{:block/uuid "block-1"}]
                           :has-more? false}
                          result))
                   (done)))
          (.catch (fn [error]
                    (is false (str error))
                    (done)))))))

(deftest search-rejects-invalid-explicit-limits
  (doseq [limit [0 -1 1.5 101 nil]]
    (is (thrown? js/Error
                 (search-handler/search "repo" "needle" {:limit limit}))))
  (is (nil? (search-handler/search "repo" "" {:limit 100}))))

(deftest search-passes-default-limit-to-worker-when-options-omit-it
  (async done
    (let [search-options (atom nil)]
      (-> (p/with-redefs [search/block-search (fn [_repo _query options]
                                                (reset! search-options options)
                                                (p/resolved []))
                          search/file-search (fn [_query]
                                               (p/resolved []))
                          state/swap-state! (fn [& _] nil)]
            (search-handler/search "repo" "needle" {:enable-snippet? false}))
          (.then (fn [_result]
                   (is (= {:enable-snippet? false :limit 10} @search-options))
                   (done)))
          (.catch (fn [error]
                    (is false (str error))
                    (done)))))))

(deftest search-keeps-unscoped-result-shape
  (async done
    (let [search-options (atom nil)]
      (-> (p/with-redefs [search/block-search (fn [_repo _query options]
                                                (reset! search-options options)
                                                (p/resolved [{:block/uuid "block-1"}]))
                          search/file-search (fn [_query]
                                               (p/resolved ["file.md"]))
                          state/swap-state! (fn [& _] nil)]
            (search-handler/search "repo" "needle"))
          (.then (fn [result]
                   (is (= {:limit 10} @search-options))
                   (is (= {:blocks [{:block/uuid "block-1"}]
                           :has-more? false
                           :files ["file.md"]}
                          result))
                   (done)))
          (.catch (fn [error]
                    (is false (str error))
                    (done)))))))

(deftest search-rejects-malformed-page-uuid-before-search
  (is (false? (common-util/uuid-string? "not-a-uuid")))
  (is (true? (common-util/uuid-string? "67e55044-10b1-426f-9247-bb680e5fe0c8")))
  (is (thrown? js/Error
               (search-handler/search "repo" "needle" {:page-uuid "not-a-uuid"}))))

(deftest search-blocks-by-exact-block-uuid-passes-single-block-scope
  (async done
    (let [page-uuid "67e55044-10b1-426f-9247-bb680e5fe0c8"
          block-uuid "67e55044-10b1-426f-9247-bb680e5fe0c9"
          search-options (atom nil)
          file-search? (atom false)]
      (-> (p/with-redefs [state/<invoke-db-worker
                          (fn [api _repo _uuid]
                            (is (= :thread-api/search-block-uuid api))
                            (p/resolved {:block-uuid block-uuid :page-uuid page-uuid}))
                          search/block-search (fn [_repo _query options]
                                                (reset! search-options options)
                                                (p/resolved [{:block/uuid block-uuid}]))
                          search/file-search (fn [& _]
                                               (reset! file-search? true)
                                               (p/resolved []))
                          state/swap-state! (fn [& _] nil)]
            (search-handler/search "repo" "needle" {:block-uuid block-uuid :limit 1}))
          (.then (fn [result]
                   (is (= {:limit 1 :block block-uuid} @search-options))
                   (is (not @file-search?))
                   (is (not (contains? result :files)))
                   (done)))
          (.catch (fn [error]
                    (is false (str "expected exact-block search, got " error))
                    (done)))))))

(deftest search-rejects-mismatched-page-and-block-scope
  (async done
    (let [page-uuid "67e55044-10b1-426f-9247-bb680e5fe0c8"
          block-uuid "67e55044-10b1-426f-9247-bb680e5fe0c9"
          search-calls (atom 0)]
      (-> (p/with-redefs [state/<invoke-db-worker
                          (fn [api _repo _uuid]
                            (if (= :thread-api/search-page-uuid api)
                              (p/resolved page-uuid)
                              (p/resolved {:block-uuid block-uuid
                                           :page-uuid "67e55044-10b1-426f-9247-bb680e5fe0ca"})))
                          search/block-search (fn [& _]
                                                (swap! search-calls inc)
                                                (p/resolved []))]
            (search-handler/search "repo" "needle"
                                   {:page-uuid page-uuid :block-uuid block-uuid}))
          (.then (fn [_]
                   (is false "mismatched page and block must reject")
                   (done)))
          (.catch (fn [_]
                    (is (zero? @search-calls))
                    (done)))))))

(deftest search-validates-block-scope-even-for-blank-query
  (async done
    (let [block-uuid "67e55044-10b1-426f-9247-bb680e5fe0c9"
          scope-calls (atom 0)]
      (-> (p/with-redefs [state/<invoke-db-worker (fn [& _]
                                                    (swap! scope-calls inc)
                                                    (p/rejected (js/Error. "Block is hidden")))
                          search/block-search (fn [& _]
                                                (is false "invalid scope must not search")
                                                (p/resolved []))]
            (search-handler/search "repo" "" {:block-uuid block-uuid}))
          (.then (fn [_]
                   (is false "invalid block scope must reject on blank query")
                   (done)))
          (.catch (fn [_]
                    (is (= 1 @scope-calls))
                    (done)))))))

(deftest search-blocks-by-page-uuid-passes-stable-page-scope
  (async done
    (let [page-uuid "67e55044-10b1-426f-9247-bb680e5fe0c8"
          page {:db/id 42
                :block/uuid (uuid page-uuid)
                :block/tags [{:db/ident :logseq.class/Page}]}
          search-options (atom nil)]
      (-> (p/with-redefs [state/<invoke-db-worker (fn [_api _repo _page-uuid]
                                                    (p/resolved page-uuid))
                          search/block-search (fn [_repo _query options]
                                                (reset! search-options options)
                                                (p/resolved [{:block/uuid (uuid page-uuid)}]))
                          search/file-search (fn [& _]
                                               (throw (js/Error. "page-scoped search must not search files")))
                          state/swap-state! (fn [& _] nil)]
            (search-handler/search "repo" "needle" {:page-uuid page-uuid :limit 7}))
          (.then (fn [result]
                   (is (= page-uuid (:page @search-options)))
                   (is (= 7 (:limit @search-options)))
                   (is (not (contains? @search-options :page-uuid)))
                   (is (not (contains? result :files)))
                   (done)))
          (.catch (fn [error]
                    (is false (str "expected scoped search, got " error))
                    (done)))))))

(deftest search-rejects-invalid-page-scope-even-for-blank-query
  (async done
    (let [page-uuid "67e55044-10b1-426f-9247-bb680e5fe0c8"
          page-validation-calls (atom 0)
          global-search-calls (atom 0)]
      (-> (p/let [_ (p/with-redefs [state/<invoke-db-worker (fn [& _]
                                                               (swap! page-validation-calls inc)
                                                               (p/rejected (js/Error. "Page is not active")))
                                    search/block-search (fn [& _]
                                                          (swap! global-search-calls inc)
                                                          (p/resolved []))
                                    search/file-search (fn [& _]
                                                         (swap! global-search-calls inc)
                                                         (p/resolved []))]
                  (search-handler/search "repo" "" {:page-uuid page-uuid}))]
             (is false "invalid page scope must reject before returning blank-query results"))
          (.then (fn [_]
                   (done)))
          (.catch (fn [_]
                    (is (= 1 @page-validation-calls))
                    (is (zero? @global-search-calls))
                    (done)))))))

(deftest search-blocks-by-page-uuid-rejects-recycled-page
  (async done
    (let [page-uuid "67e55044-10b1-426f-9247-bb680e5fe0c8"
          global-search? (atom false)]
      (-> (p/with-redefs [state/<invoke-db-worker (fn [& _]
                                                    (p/rejected (js/Error. "Page is not active")))
                          search/block-search (fn [& _]
                                                (reset! global-search? true)
                                                (p/resolved []))
                          search/file-search (fn [& _]
                                               (reset! global-search? true)
                                               (p/resolved []))
                          state/swap-state! (fn [& _] nil)]
            (search-handler/search "repo" "needle" {:page-uuid page-uuid}))
          (.then (fn [_]
                   (is false "recycled page UUID must reject instead of searching")
                   (done)))
          (.catch (fn [_]
                    (is (false? @global-search?))
                    (done)))))))
