(ns frontend.extensions.pdf.assets-test
  (:require [cljs.test :as test :refer [are async deftest testing]]
            [frontend.extensions.pdf.assets :as pdf-assets]
            [frontend.extensions.pdf.utils :as pdf-utils]
            [frontend.handler.editor :as editor-handler]
            [frontend.state :as state]
            [frontend.util :as util]
            [promesa.core :as p]))

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
    (let [pdf-current {:filename      "paper.pdf"
                       :original-path "file:///C:/library/paper.pdf"
                       :url           "assets:///C/logseq__colon/library/paper.pdf"}
          asset-block {:block/uuid #uuid "8d6c5f58-5fa8-4d8a-a6ee-c8f7c6b1c3af"}
          created (atom nil)]
      (with-redefs [state/get-current-repo (constantly "repo")
                    editor-handler/db-based-save-assets!
                    (fn [repo files]
                      (reset! created {:repo repo :files files})
                      (p/resolved [asset-block]))]
        (-> (pdf-assets/ensure-db-asset! pdf-current)
            (p/then (fn [result]
                      (test/is (= {:repo "repo"
                                   :files [{:title "paper.pdf"
                                            :src "file:///C:/library/paper.pdf"}]}
                                  @created))
                      (test/is (= asset-block (:block result)))
                      (test/is (= (:url pdf-current) (:url result)))))
            (p/finally done))))))
