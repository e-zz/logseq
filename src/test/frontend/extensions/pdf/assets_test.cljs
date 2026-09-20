(ns frontend.extensions.pdf.assets-test
  (:require [cljs.test :as test :refer [are async deftest testing]]
            [frontend.db.async :as db-async]
            [frontend.extensions.pdf.assets :as pdf-assets]
            [frontend.extensions.pdf.utils :as pdf-utils]
            [frontend.handler.assets :as assets-handler]
            [frontend.handler.editor :as editor-handler]
            [frontend.handler.page :as page-handler]
            [frontend.state :as state]
            [frontend.util :as util]
            [promesa.core :as p]))

(deftest highlight-color-id-loads-closed-values-through-worker-test
  (async done
    (let [calls (atom [])
          original-get-property-closed-values db-async/<get-property-closed-values]
      (set! db-async/<get-property-closed-values
            (fn [repo property-id]
              (swap! calls conj [repo property-id])
              (p/resolved [{:db/id 1 :block/title "blue"}
                           {:db/id 2 :block/title "yellow"}])))
      (-> (pdf-assets/<highlight-color-id "test" "yellow")
          (p/then
           (fn [color-id]
             (test/is (= 2 color-id))
             (test/is (= [["test" :logseq.property.pdf/hl-color]] @calls))))
          (p/catch
           (fn [error]
             (test/is false (str error))))
          (p/finally
           (fn []
             (set! db-async/<get-property-closed-values original-get-property-closed-values)
             (done)))))))

(deftest fix-local-asset-pagename
  (testing "matched filenames"
    (are [x y] (= y (pdf-utils/fix-local-asset-pagename x))
      "2015_Book_Intertwingled_1659920114630_0" "2015 Book Intertwingled"
      "hls__2015_Book_Intertwingled_1659920114630_0" "2015 Book Intertwingled"
      "hls/2015_Book_Intertwingled_1659920114630_0" "hls/2015 Book Intertwingled"
      "hls__sicp__-1234567" "sicp"))
  (testing "non matched filenames"
    (are [x y] (= y (pdf-utils/fix-local-asset-pagename x))
      "foo" "foo"
      "foo_bar" "foo_bar"
      "foo__bar" "foo__bar"
      "foo_bar.pdf" "foo_bar.pdf")))

(deftest zotero-linked-source-uses-configured-relative-path
  (with-redefs [state/get-config
                (constantly {:zotero/settings-v2
                             {"default"
                              {:zotero-linked-attachment-base-directory "D:/library"}}})]
    (are [source expected]
         (= expected (#'pdf-assets/zotero-linked-source source))
      "file:///D:/library/qn/paper.pdf"
      "zotero-link://qn/paper.pdf"
      "file:///D:/library/it/Understanding%20EXPLAIN.pdf"
      "zotero-link://it/Understanding EXPLAIN.pdf"
      "file:///D:/library/it/100%paper.pdf"
      "zotero-link://it/100%paper.pdf")))

(deftest find-zotero-asset-by-source-uses-exact-query
  (async done
    (let [query-call (atom nil)
          imported-block {:block/uuid #uuid "1c6e0f0d-dc5f-46d1-9f4a-bf6f4f5fc885"}]
      (with-redefs [state/get-config
                    (constantly {:zotero/settings-v2
                                 {"default"
                                  {:zotero-linked-attachment-base-directory "D:/library"}}})
                    db-async/<q
                    (fn [& args]
                      (reset! query-call args)
                      (p/resolved imported-block))]
        (-> (#'pdf-assets/<find-zotero-asset-by-source
             "repo"
             "file:///D:/library/it/Understanding%20EXPLAIN.pdf")
            (p/then (fn [result]
                      (test/is (= imported-block result))
                      (test/is (= ["repo" {:transact-db? false}
                                   '[:find (pull ?b [:block/uuid
                                                      :logseq.property.asset/external-file-name]) .
                                     :in $ ?source
                                     :where
                                     [?b :logseq.property.asset/external-file-name ?source]]
                                   "zotero-link://it/Understanding EXPLAIN.pdf"]
                                 @query-call))))
            (p/finally done))))))

(deftest ensure-db-asset-shares-in-flight-creation-and-retries
  (async done
    (let [first-resolve (atom nil)
          save-calls (atom 0)
          first-save (js/Promise.
                      (fn [resolve _reject]
                        (reset! first-resolve resolve)))
          first-create (fn []
                         (swap! save-calls inc)
                         first-save)
          key ["repo" "zotero-link://it/paper.pdf"]]
      (reset! @#'pdf-assets/*asset-creation-in-flight {})
      (let [first-result (#'pdf-assets/<share-db-asset-creation! key first-create)
            second-result (#'pdf-assets/<share-db-asset-creation! key first-create)
            asset-block {:block/uuid #uuid "2e7d9a76-76df-4db1-b5e9-1b6cfaa2a6d5"}]
        (test/is (identical? first-result second-result))
        (test/is (= 1 @save-calls))
        (@first-resolve asset-block)
        (-> (p/all [first-result second-result])
            (p/then
             (fn [results]
               (test/is (= [asset-block asset-block] results))
               (let [failure (#'pdf-assets/<share-db-asset-creation!
                             key
                             (fn []
                               (swap! save-calls inc)
                               (throw (js/Error. "save failed"))))]
                 (p/catch
                  failure
                  (fn [_]
                    (let [retry (#'pdf-assets/<share-db-asset-creation!
                                 key
                                 (fn []
                                   (swap! save-calls inc)
                                   (p/resolved asset-block)))]
                      (p/then retry
                               (fn [result]
                                 (test/is (= asset-block result))
                                 (test/is (= 3 @save-calls))))))))))
            (p/finally done))))))

(deftest inflate-asset-normalizes-local-assets-url-on-windows
  (with-redefs [util/electron? (constantly true)
                util/win32? true]
    (test/is (= "assets:///C/logseq__colon/Users/charlie/sicp.pdf"
                (:url (pdf-assets/inflate-asset
                       "C:/Users/charlie/sicp.pdf"
                       {:href "assets:///C:/Users/charlie/sicp.pdf"}))))))

(deftest normalize-asset-resource-url-normalizes-file-protocols
  (with-redefs [util/electron? (constantly true)]
    (are [input expected]
         (= expected (assets-handler/normalize-asset-resource-url input))
      "file:///C:/library/paper.pdf"
      "assets:///C/logseq__colon/library/paper.pdf"
      "file://C:/library/paper.pdf"
      "assets:///C/logseq__colon/library/paper.pdf"
      "file:///tmp/paper.pdf"
      "assets:///tmp/paper.pdf"
      "assets:///C/logseq__colon/library/paper.pdf"
      "assets:///C/logseq__colon/library/paper.pdf")))

(deftest ensure-db-asset-creates-record-for-external-pdf
  (async done
    (let [pdf-current {:key            "paper"
                       :filename       "paper.pdf"
                       :original-path  "file:///C:/library/paper.pdf"
                       :url            "assets:///C/logseq__colon/library/paper.pdf"}
          asset-block {:block/uuid #uuid "8d6c5f58-5fa8-4d8a-a6ee-c8f7c6b1c3af"}
          hls-page {:block/uuid #uuid "c99a82f5-fb96-4e9a-a11f-2edab6b34ed1"}
          created (atom nil)]
      (with-redefs [state/get-current-repo (constantly "repo")
                    page-handler/<create! (fn [title opts]
                                            (test/is (= "hls__paper" title))
                                            (test/is (= {:redirect? false :edit? false} opts))
                                            (p/resolved hls-page))
                    editor-handler/db-based-save-assets!
                    (fn [repo files & opts]
                      (reset! created {:repo repo :files files :opts opts})
                      (p/resolved [asset-block]))]
        (-> (pdf-assets/ensure-db-asset! pdf-current)
            (p/then (fn [result]
                      (test/is (= {:repo "repo"
                                   :files [{:title "paper.pdf"
                                            :src "file:///C:/library/paper.pdf"}]
                                   :opts [:save-to-page hls-page]}
                                  @created))
                      (test/is (= asset-block (:block result)))
                      (test/is (= (:url pdf-current) (:url result)))))
            (p/finally done))))))

(deftest ensure-db-asset-rejects-when-pdf-has-no-source-path
  (async done
    (let [page-called? (atom false)
          query-called? (atom false)
          checksum-called? (atom false)
          hls-page {:block/uuid #uuid "8d32f065-b381-4f2e-8c8b-f0f11c506af0"}]
      (with-redefs [state/get-current-repo (constantly "repo")
                    page-handler/<create! (fn [& _]
                                            (reset! page-called? true)
                                            (p/resolved hls-page))
                    db-async/<q (fn [& _]
                                  (reset! query-called? true)
                                  (p/resolved []))
                    assets-handler/get-file-checksum (fn [_source]
                                                       (reset! checksum-called? true)
                                                       (p/resolved nil))
                    editor-handler/db-based-save-assets! (fn [& _] (p/resolved []))]
        (-> (pdf-assets/ensure-db-asset! {:key "paper" :filename "paper.pdf"})
            (p/then (fn [_]
                      (test/is false "expected missing PDF source path to reject")))
            (p/catch (fn [error]
                       (test/is (= "PDF asset has no source path" (ex-message error)))))
            (p/finally (fn []
                         (test/is (false? @page-called?))
                         (test/is (false? @query-called?))
                         (test/is (false? @checksum-called?))
                         (done))))))))

(deftest ensure-db-asset-reuses-existing-record
  (async done
    (let [pdf-current {:key "paper"
                       :filename "paper.pdf"
                       :original-path "assets:///D/logseq__colon/library/paper.pdf"
                       :url "assets:///D/logseq__colon/library/paper.pdf"}
          existing-block {:block/uuid #uuid "1c6e0f0d-dc5f-46d1-9f4a-bf6f4f5fc885"}
          hls-page {:block/uuid #uuid "c99a82f5-fb96-4e9a-a11f-2edab6b34ed1"}
          moved (atom nil)]
      (with-redefs [state/get-current-repo (constantly "repo")
                    page-handler/<create! (constantly (p/resolved hls-page))
                    assets-handler/get-file-checksum (constantly "checksum")
                    db-async/<get-asset-with-checksum (fn [repo checksum]
                                                         (test/is (= "repo" repo))
                                                         (test/is (= "checksum" checksum))
                                                         (p/resolved existing-block))
                    editor-handler/move-blocks! (fn [blocks target opts]
                                                  (reset! moved {:blocks blocks
                                                                 :target target
                                                                 :opts opts})
                                                  (p/resolved true))
                    editor-handler/db-based-save-assets!
                    (fn [& _]
                      (test/is false "duplicate asset must not be created")
                      (p/resolved nil))]
        (-> (pdf-assets/ensure-db-asset! pdf-current)
            (p/then (fn [result]
                      (test/is (= existing-block (:block result)))
                      (test/is (= {:blocks [existing-block]
                                   :target hls-page
                                   :opts {:sibling? false :bottom? true}}
                                  @moved))))
            (p/finally done))))))

(deftest persist-area-image-reuses-existing-asset
  (async done
    (let [existing-image {:block/uuid #uuid "7f34842f-a734-4f2e-9fd2-8c7ac1b5fd0"}
          file #js {:name "pdf area highlight.png"}]
      (with-redefs [editor-handler/db-based-save-assets!
                    (fn [_repo _files & _opts] (p/resolved []))
                    assets-handler/get-file-checksum
                    (fn [_file] (p/resolved "image-checksum"))
                    db-async/<get-asset-with-checksum
                    (fn [_repo checksum]
                      (test/is (= "image-checksum" checksum))
                      (p/resolved existing-image))]
        (-> (#'pdf-assets/db-based-persist-hl-area-image "repo" file)
            (p/then (fn [result]
                      (test/is (= [existing-image] result))))
            (p/finally done))))))

(deftest resolve-external-pdf-url-centralizes-zotero-protocols
  (with-redefs [pdf-assets/get-zotero-local-pdf-path
                (fn [path & opts] {:path path :opts opts})]
    (test/is (= {:path "zotero-link://qn/paper.pdf"
                 :opts '(:id "paper.pdf")}
                (pdf-assets/resolve-external-pdf-url
                 "zotero-link://qn/paper.pdf"
                 "zotero-link://qn/paper.pdf")))
    (test/is (= "assets:///D/logseq__colon/library/paper.pdf"
                (pdf-assets/resolve-external-pdf-url
                 "assets:///D/logseq__colon/library/paper.pdf"
                 nil)))
    (test/is (= {:path "zotero-link://qn/paper.pdf"
                 :opts '(:id "paper.pdf")}
                (pdf-assets/resolve-external-pdf-url
                 "zotero-link://qn/paper.pdf"
                 nil)))))

(deftest ensure-db-asset-prefers-imported-zotero-record
  (async done
    (let [pdf-current {:key "paper"
                       :filename "paper.pdf"
                       :original-path "assets:///D/logseq__colon/library/qn/paper.pdf"
                       :url "assets:///D/logseq__colon/library/qn/paper.pdf"}
          imported-block {:block/uuid #uuid "1c6e0f0d-dc5f-46d1-9f4a-bf6f4f5fc885"
                          :logseq.property.asset/external-file-name
                          "zotero-link://qn/paper.pdf"}
          checksum-block {:block/uuid #uuid "2d7f1e1e-e7f7-4aa9-bf0a-5aa5e20a4b02"}
          hls-page {:block/uuid #uuid "c99a82f5-fb96-4e9a-a11f-2edab6b34ed1"}
          moved (atom nil)]
      (with-redefs [state/get-current-repo (constantly "repo")
                    state/get-config (constantly {:zotero/settings-v2
                                                  {"default"
                                                   {:zotero-linked-attachment-base-directory "D:/library"}}})
                    page-handler/<create! (constantly (p/resolved hls-page))
                    db-async/<q (fn [& _]
                                  (p/resolved imported-block))
                    assets-handler/get-file-checksum (constantly (p/resolved "checksum"))
                    db-async/<get-asset-with-checksum (constantly (p/resolved checksum-block))
                    editor-handler/move-blocks! (fn [blocks target opts]
                                                  (reset! moved {:blocks blocks
                                                                 :target target
                                                                 :opts opts})
                                                  (p/resolved true))
                    editor-handler/db-based-save-assets!
                    (fn [& _]
                      (test/is false "an imported Zotero Asset should win")
                      (p/resolved nil))]
        (-> (pdf-assets/ensure-db-asset! pdf-current)
            (p/then (fn [result]
                      (test/is (= imported-block (:block result)))
                      (test/is (= [imported-block] (:blocks @moved)))))
            (p/finally done))))))
