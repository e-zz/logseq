(ns frontend.worker.handler.search-test
  (:require [cljs.test :refer [async deftest is testing]]
            [datascript.core :as d]
            [frontend.common.thread-api :as thread-api]
            [frontend.worker.handler.search :as search-handler]
            [frontend.worker.platform :as platform]
            [frontend.worker.state :as worker-state]
            [logseq.db.test.helper :as db-test]
            [promesa.core :as p]))

(deftest resolve-active-visible-block-uuid-reuses-get-block-eligibility
  (let [conn (db-test/create-conn-with-blocks
              {:pages-and-blocks [{:page {:block/title "Block scope"}
                                   :blocks [{:block/title "Target"
                                             :build/children [{:block/title "Descendant"}]}
                                            {:block/title "Hidden"
                                             :logseq.property/hide? true}]}]})
        target (db-test/find-block-by-content @conn "Target")
        descendant (db-test/find-block-by-content @conn "Descendant")
        page-uuid (str (:block/uuid (:block/page target)))
        target-uuid (str (:block/uuid target))
        hidden (db-test/find-block-by-content @conn "Hidden")
        recycled-db (d/db-with @conn [[:db/add (:db/id (:block/page target))
                                      :logseq.property/deleted-at 1760000000000]])
        broken-db (d/db-with @conn [[:db/retract (:db/id target)
                                     :block/parent (:db/id (:block/page target))]])
        cycle-db (d/db-with @conn [[:db/add (:db/id target) :block/parent (:db/id descendant)]
                                   [:db/add (:db/id descendant) :block/parent (:db/id target)]])]
    (is (= {:block-uuid target-uuid :page-uuid page-uuid}
           (#'search-handler/resolve-active-visible-block @conn target-uuid)))
    (is (thrown? js/Error
                 (#'search-handler/resolve-active-visible-block @conn page-uuid)))
    (is (thrown? js/Error
                 (#'search-handler/resolve-active-visible-block @conn "not-a-uuid")))
    (is (thrown? js/Error
                 (#'search-handler/resolve-active-visible-block
                  @conn "67e55044-10b1-426f-9247-bb680e5fe0ca")))
    (is (thrown? js/Error
                 (#'search-handler/resolve-active-visible-block
                  @conn (str (:block/uuid hidden)))))
    (is (thrown? js/Error
                 (#'search-handler/resolve-active-visible-block recycled-db target-uuid)))
    (is (thrown? js/Error
                 (#'search-handler/resolve-active-visible-block broken-db target-uuid)))
    (is (thrown? js/Error
                 (#'search-handler/resolve-active-visible-block cycle-db target-uuid)))))

(deftest search-block-uuid-thread-api-validates-through-worker-route
  (let [conn (db-test/create-conn-with-blocks
              {:pages-and-blocks [{:page {:block/title "Block route target"}
                                   :blocks [{:block/title "Scoped block"}]}]})
        block (db-test/find-block-by-content @conn "Scoped block")
        block-uuid (str (:block/uuid block))
        search-block-uuid (@thread-api/*thread-apis :thread-api/search-block-uuid)]
    (with-redefs [worker-state/get-datascript-conn (constantly conn)]
      (is (= {:block-uuid block-uuid
              :page-uuid (str (:block/uuid (:block/page block)))}
             (search-block-uuid "repo" block-uuid))))))

(deftest block-scoped-search-declines-semantic-embedding
  (async done
    (let [options {:block "67e55044-10b1-426f-9247-bb680e5fe0c9"
                   :feature/enable-semantic-search? true}
          embedding-calls (atom 0)
          search-options (atom nil)]
      (-> (p/with-redefs [worker-state/get-vector-index (constantly {:query (fn [& _]
                                                                              (throw (js/Error. "vector query must not run")))})
                          platform/current (constantly {:embedding {:embed-texts (fn [_texts]
                                                                                  (swap! embedding-calls inc)
                                                                                  (throw (js/Error. "block-scoped search must not embed")))}})
                          search-handler/search-blocks (fn [_repo _query opts]
                                                         (reset! search-options opts)
                                                         :keyword-only)]
            (#'search-handler/<search-blocks "repo" "needle" options))
          (.then (fn [result]
                   (is (= :keyword-only result))
                   (is (= options @search-options))
                   (is (zero? @embedding-calls) "block scope must skip query embedding")
                   (done)))
          (.catch (fn [error]
                    (is false (str error))
                    (done)))))))

(deftest resolve-active-visible-page-uuid-validates-page-target
  (let [conn (db-test/create-conn-with-blocks
              {:pages-and-blocks [{:page {:block/title "Search target"}
                                   :blocks [{:block/title "A block, not a page"}]}]})
        page (db-test/find-page-by-title @conn "Search target")
        block (db-test/find-block-by-content @conn "A block, not a page")
        page-uuid (str (:block/uuid page))]
    (is (= page-uuid
           (#'search-handler/resolve-active-visible-page-uuid @conn page-uuid)))
    (is (thrown? js/Error
                 (#'search-handler/resolve-active-visible-page-uuid
                  @conn (str (:block/uuid block)))))
    (is (thrown? js/Error
                 (#'search-handler/resolve-active-visible-page-uuid
                  @conn "67e55044-10b1-426f-9247-bb680e5fe0c8")))
    (is (thrown? js/Error
                 (#'search-handler/resolve-active-visible-page-uuid @conn "not-a-uuid")))))

(deftest search-page-uuid-thread-api-validates-through-worker-route
  (let [conn (db-test/create-conn-with-blocks
              {:pages-and-blocks [{:page {:block/title "Thread route target"}}]})
        page (db-test/find-page-by-title @conn "Thread route target")
        page-uuid (str (:block/uuid page))
        search-page-uuid (@thread-api/*thread-apis :thread-api/search-page-uuid)]
    (with-redefs [worker-state/get-datascript-conn (constantly conn)]
      (is (= page-uuid (search-page-uuid "repo" page-uuid))))))

(deftest resolve-active-visible-page-uuid-rejects-recycled-and-hidden-ancestors
  (testing "a recycled page cannot be used as search scope"
    (let [conn (db-test/create-conn-with-blocks
                {:pages-and-blocks [{:page {:block/title "Recycled target"}}]})
          page (db-test/find-page-by-title @conn "Recycled target")
          page-uuid (str (:block/uuid page))]
      (d/transact! conn [[:db/add (:db/id page) :logseq.property/deleted-at 1760000000000]])
      (is (thrown? js/Error
                   (#'search-handler/resolve-active-visible-page-uuid @conn page-uuid)))))

  (testing "a page under a hidden parent is not an active visible search scope"
    (let [conn (db-test/create-conn-with-blocks
                {:pages-and-blocks [{:page {:block/title "Hidden parent"}}
                                    {:page {:block/title "Child target"}}]})
          parent (db-test/find-page-by-title @conn "Hidden parent")
          child (db-test/find-page-by-title @conn "Child target")
          child-uuid (str (:block/uuid child))]
      (d/transact! conn [[:db/add (:db/id parent) :logseq.property/hide? true]
                         [:db/add (:db/id child) :block/parent (:db/id parent)]])
      (is (thrown? js/Error
                   (#'search-handler/resolve-active-visible-page-uuid @conn child-uuid))))))
