(ns frontend.extensions.pdf.core-test
  (:require [cljs.test :as test :refer [async deftest]]
            [frontend.db.async :as db-async]
            [frontend.extensions.pdf.assets :as pdf-assets]
            [frontend.extensions.pdf.core :as pdf-core]
            [frontend.extensions.pdf.windows :as pdf-windows]
            [frontend.handler.editor :as editor-handler]
            [frontend.handler.notification :as notification]
            [frontend.state :as state]
            [frontend.util :as util]
            [promesa.core :as p]))

(deftest async-asset-completion-does-not-restore-a-left-pdf
  (let [set-state-calls (atom [])
        highlight-calls (atom [])
        pdf-a {:identity "A"}
        pdf-b {:identity "B"}
        created-a (assoc pdf-a :block {:db/id 42})]
    (test/is (nil?
              (#'pdf-core/complete-asset-creation-for-current-pdf!
               pdf-a
               created-a
               pdf-b
               #(swap! set-state-calls conj %)
               #(swap! highlight-calls conj %))))
    (test/is (empty? @set-state-calls))
    (test/is (empty? @highlight-calls))))

(deftest async-asset-completion-updates-the-current-pdf
  (let [set-state-calls (atom [])
        highlight-calls (atom [])
        pdf-a {:identity "A"}
        created-a (assoc pdf-a :block {:db/id 42})]
    (test/is (true?
              (#'pdf-core/complete-asset-creation-for-current-pdf!
               pdf-a
               created-a
               pdf-a
               #(swap! set-state-calls conj %)
               #(swap! highlight-calls conj %))))
    (test/is (= [created-a] @set-state-calls))
    (test/is (= [created-a] @highlight-calls))))

(deftest resize-area-highlight-cleans-up-on-rejection
  (async done
    (let [cleaned? (atom false)
          notified? (atom false)]
      (-> (#'pdf-core/<persist-resized-area-highlight!
           #(p/rejected (js/Error. "save failed"))
           (fn [_] (test/is false "update must not run after save failure"))
           #(reset! cleaned? true)
           (fn [& _] (reset! notified? true)))
          (p/catch (fn [_]
                     (test/is @cleaned?)
                     (test/is @notified?)))
          (p/finally done)))))

(deftest new-area-highlight-rolls-back-after-image-save-failure
  (async done
    (let [original-highlights [{:id "old"}]
          optimistic-highlights (conj original-highlights {:id "new"})
          restored (atom nil)
          notified? (atom false)]
      (-> (#'pdf-core/<persist-new-area-highlight!
           #(p/rejected (js/Error. "save failed"))
           original-highlights
           {:id "new"}
           optimistic-highlights
           #(reset! restored %)
           (fn [& _] (reset! notified? true)))
          (p/catch (fn [_]
                     (test/is (= original-highlights @restored))
                     (test/is @notified?)))
          (p/finally done)))))

(deftest copy-hl-ref-uses-explicit-pdf-current
  (async done
    (let [inserted (atom nil)
          get-block-calls (atom 0)
          explicit-pdf-current {:block {:db/id 42
                                        :block/uuid #uuid "b1cb39b2-08f1-407f-9a36-9cc9c224d54f"}}
          global-pdf-current {:block {:db/id 7
                                      :block/uuid #uuid "36a89faa-27f0-4b60-8132-61a2e514f342"}}
          highlight {:id "highlight"
                     :page 1
                     :content {:text "selected text"}
                     :properties {:color "yellow"}}
          viewer #js {}
          original-get-current-repo state/get-current-repo
          original-get-current-pdf state/get-current-pdf
          original-get-block db-async/<get-block
          original-get-closed-values db-async/<get-property-closed-values
          original-insert-block editor-handler/api-insert-new-block!
          original-copy-to-clipboard util/copy-to-clipboard!
          original-show-notification notification/show!
          original-resolve-own-window pdf-windows/resolve-own-window]
      (set! state/get-current-repo (constantly "repo"))
      (set! state/get-current-pdf (constantly global-pdf-current))
      (set! db-async/<get-block
            (fn [& _]
              (if (= 1 (swap! get-block-calls inc))
                (p/resolved {})
                (p/resolved {:block/title "selected text"
                             :block/uuid #uuid "bc5cec0b-e225-4dc3-82e4-f7a998d1fd14"}))))
      (set! db-async/<get-property-closed-values
            (fn [& _] (p/resolved [{:db/id 99 :block/title "yellow"}])))
      (set! editor-handler/api-insert-new-block!
            (fn [_text opts]
              (reset! inserted opts)
              (p/resolved {:block/uuid #uuid "bc5cec0b-e225-4dc3-82e4-f7a998d1fd14"})))
      (set! util/copy-to-clipboard! (fn [& _]))
      (set! notification/show! (fn [& _]))
      (set! pdf-windows/resolve-own-window (fn [_] nil))
      (-> (pdf-assets/copy-hl-ref! highlight viewer explicit-pdf-current)
          (p/then (fn [_]
                    (test/is (= 42
                                (get-in @inserted [:properties :logseq.property/asset])))))
          (p/catch (fn [error]
                     (test/is false (str "unexpected error: " error))))
          (p/finally (fn []
                       (set! state/get-current-repo original-get-current-repo)
                       (set! state/get-current-pdf original-get-current-pdf)
                       (set! db-async/<get-block original-get-block)
                       (set! db-async/<get-property-closed-values original-get-closed-values)
                       (set! editor-handler/api-insert-new-block! original-insert-block)
                       (set! util/copy-to-clipboard! original-copy-to-clipboard)
                       (set! notification/show! original-show-notification)
                       (set! pdf-windows/resolve-own-window original-resolve-own-window)
                       (done)))))))
