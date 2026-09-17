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

(deftest inflate-asset-normalizes-local-assets-url-on-windows
  (with-redefs [util/electron? (constantly true)
                util/win32? true]
    (test/is (= "assets:///C/logseq__colon/Users/charlie/sicp.pdf"
                (:url (pdf-assets/inflate-asset
                       "C:/Users/charlie/sicp.pdf"
                       {:href "assets:///C:/Users/charlie/sicp.pdf"}))))))

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
