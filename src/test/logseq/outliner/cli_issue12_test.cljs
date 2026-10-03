(ns logseq.outliner.cli-issue12-test
  (:require ["fs" :as fs]
            ["os" :as os]
            ["path" :as node-path]
            [cljs.test :refer [deftest is testing]]
            [datascript.core :as d]
            [logseq.outliner.cli :as outliner-cli]))

(defn- disposable-dir
  []
  (let [base-dir (or js/process.env.TMPDIR
                     js/process.env.TEMP
                     (os/tmpdir))]
    (fs/mkdtempSync (node-path/join base-dir "logseq-issue12-"))))

(defn- file-contents
  [conn paths]
  (into {}
        (map (fn [path]
               [path (:file/content (d/entity @conn [:file/path path]))]))
        paths))

(deftest init-conn-preserves-files-on-reopen-and-resolves-windows-classpath
  (let [dir (disposable-dir)
        graph-path (node-path/join dir "graph.sqlite")
        templates-dir (node-path/join dir "templates")
        config-path (node-path/join templates-dir "config.edn")
        config-content "{:classpath-template true}"
        classpath (str dir (.-delimiter node-path) "Z:/nonexistent")
        paths ["logseq/config.edn"
               "logseq/custom.css"
               "logseq/custom.js"
               "logseq/publish.css"
               "logseq/publish.js"]
        sentinels (zipmap paths (map #(str "sentinel-" %) paths))]
    (fs/mkdirSync templates-dir #js {:recursive true})
    (fs/writeFileSync config-path config-content)
    (testing "fresh graph creation installs defaults and reads a Windows path"
      (let [conn (outliner-cli/init-conn graph-path {:classpath classpath})]
        (is (= (set paths)
               (set (d/q '[:find [?path ...]
                           :where [_ :file/path ?path]]
                         @conn))))
        (is (= config-content
               (:file/content (d/entity @conn [:file/path "logseq/config.edn"]))))
        (is (= "" (:file/content (d/entity @conn [:file/path "logseq/custom.css"]))))))
    (let [conn (outliner-cli/init-conn graph-path {:classpath ""})
          unrelated-block {:block/uuid #uuid "11111111-1111-4111-8111-111111111111"
                           :block/title "unrelated open sentinel"}]
      (d/transact! conn (map (fn [[path content]]
                              {:file/path path :file/content content})
                            sentinels))
      (d/transact! conn [unrelated-block])
      (testing "repeated CLI opens preserve all files and unrelated data"
        (let [reopened-conn (outliner-cli/init-conn graph-path {:classpath ""})
              reopened-again (outliner-cli/init-conn graph-path {:classpath classpath})]
          (is (= sentinels (file-contents reopened-conn paths)))
          (is (= sentinels (file-contents reopened-again paths)))
          (is (= unrelated-block
                 (select-keys (d/entity @reopened-again
                                        [:block/uuid (:block/uuid unrelated-block)])
                              [:block/uuid :block/title]))))))))

(deftest init-conn-partial-graph-preserves-existing-and-recreates-missing-defaults
  ;; Policy under test (issue #12): on CLI reopen, file entities that already exist are
  ;; never rewritten; default files absent from the graph are recreated with their
  ;; creation-time defaults (config template content for logseq/config.edn, "" for the
  ;; css/js files). Opening a partial graph must not touch any pre-existing content.
  (let [dir (disposable-dir)
        graph-path (node-path/join dir "partial.sqlite")
        templates-dir (node-path/join dir "templates")
        config-path (node-path/join templates-dir "config.edn")
        config-content "{:partial-graph-config true}"
        classpath (str templates-dir (.-delimiter node-path) "Z:/nonexistent")
        missing-paths ["logseq/custom.css" "logseq/custom.js"]
        kept-paths ["logseq/config.edn" "logseq/publish.css" "logseq/publish.js"]
        sentinels {"logseq/config.edn" "sentinel-logseq/config.edn-partial"
                   "logseq/publish.css" "sentinel-logseq/publish.css-partial"
                   "logseq/publish.js" "sentinel-logseq/publish.js-partial"}
        unrelated-block {:block/uuid #uuid "22222222-2222-4222-8222-222222222222"
                         :block/title "partial graph sentinel"}]
    (fs/mkdirSync templates-dir #js {:recursive true})
    (fs/writeFileSync config-path config-content)
    (outliner-cli/init-conn graph-path {:classpath classpath})
    (let [conn (outliner-cli/init-conn graph-path {:classpath ""})]
      ;; Build the partial graph: retract some default file entities, sentinel the rest.
      (d/transact! conn (map (fn [path] [:db.fn/retractEntity [:file/path path]])
                             missing-paths))
      (d/transact! conn (map (fn [[path content]]
                               {:file/path path :file/content content})
                             sentinels))
      (d/transact! conn [unrelated-block])
      (let [reopened (outliner-cli/init-conn graph-path {:classpath ""})]
        (testing "reopening a partial graph preserves existing content"
          (is (= sentinels (file-contents reopened kept-paths))
              "Pre-existing files are not rewritten on reopen")
          (is (= unrelated-block
                 (select-keys (d/entity @reopened
                                        [:block/uuid (:block/uuid unrelated-block)])
                              [:block/uuid :block/title]))
              "Unrelated graph data survives reopen"))
        (testing "missing default files are recreated with creation-time defaults"
          (is (every? #(contains? (set (d/q '[:find [?path ...]
                                              :where [_ :file/path ?path]]
                                            @reopened))
                          %)
                  missing-paths)
              "Absent default files are recreated on reopen")
          (is (every? empty?
                      (map (fn [path] (:file/content (d/entity @reopened [:file/path path])))
                           missing-paths))
              "Recreated css/js files get their default empty content"))
        (testing "a further reopen keeps the completed set intact"
          (let [reopened-again (outliner-cli/init-conn graph-path {:classpath classpath})]
            (is (= sentinels (file-contents reopened-again kept-paths)))
            (is (every? empty?
                        (map (fn [path] (:file/content (d/entity @reopened-again [:file/path path])))
                             missing-paths)))))))))

(deftest init-conn-fails-if-existing-file-query-fails
  (let [dir (disposable-dir)
        graph-path (node-path/join dir "query-failure.sqlite")]
    (with-redefs [d/q (fn [& _]
                        (throw (js/Error. "injected file-path query failure")))]
      (is (thrown-with-msg? js/Error
                            #"injected file-path query failure"
                            (outliner-cli/init-conn graph-path {:classpath ""}))))))
