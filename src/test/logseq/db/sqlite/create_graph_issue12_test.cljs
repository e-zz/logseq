(ns logseq.db.sqlite.create-graph-issue12-test
  (:require [cljs.test :refer [deftest is]]
            [datascript.core :as d]
            [logseq.db.frontend.schema :as db-schema]
            [logseq.db.sqlite.create-graph :as sqlite-create-graph]))

(deftest initial-files-are-created-and-existing-content-is-preserved
  (let [paths #{"logseq/config.edn"
                "logseq/custom.css"
                "logseq/custom.js"
                "logseq/publish.css"
                "logseq/publish.js"}
        sentinels (zipmap paths (map #(str "sentinel-" %) paths))
        conn (d/create-conn db-schema/schema)]
    (d/transact! conn (sqlite-create-graph/build-db-initial-data "fresh-config"))
    (is (= paths
           (set (d/q '[:find [?path ...]
                       :where [_ :file/path ?path]]
                     @conn)))
        "Fresh graphs receive all five built-in files")
    (is (= "fresh-config"
           (:file/content (d/entity @conn [:file/path "logseq/config.edn"]))))
    (is (every? empty?
                (map #(-> (d/entity @conn [:file/path %]) :file/content)
                     (disj paths "logseq/config.edn")))
        "Fresh custom and publish files start empty")
    (d/transact! conn (map (fn [[path content]]
                             {:file/path path :file/content content})
                           sentinels))
    (let [unrelated-block {:block/uuid #uuid "11111111-1111-4111-8111-111111111111"
                           :block/title "unrelated open sentinel"}
          _ (d/transact! conn [unrelated-block])
          existing-paths (set (d/q '[:find [?path ...]
                                     :where [_ :file/path ?path]]
                                   @conn))]
      (d/transact! conn (sqlite-create-graph/build-db-initial-data
                         "replacement-config"
                         :existing-file-paths existing-paths))
      (is (= sentinels
             (into {}
                   (map (fn [path]
                          [path (:file/content (d/entity @conn [:file/path path]))]))
                   paths))
          "Opening does not replace any of the five stored files")
      (is (= unrelated-block
             (select-keys (d/entity @conn [:block/uuid (:block/uuid unrelated-block)])
                          [:block/uuid :block/title]))
          "Opening leaves unrelated graph data intact"))))
