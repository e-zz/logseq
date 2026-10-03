(ns electron.embedding-server-test
  (:require ["path" :as node-path]
            [cljs.test :refer [async deftest is]]
            [clojure.string :as string]
            [electron.embedding-server :as embedding-server]
            [promesa.core :as p]))

;; Every fixture path is assembled with node:path/join so the expected values
;; match the host-native output of the production `node-path/join` calls on all
;; platforms (POSIX "/" vs Windows "\"). Base directories are relative on
;; purpose: production only ever joins them, so their separators are what we
;; assert, not their absoluteness.
(def ^:private user-data-dir (node-path/join "users" "me" "logseq"))
(def ^:private runtime-dir (node-path/join user-data-dir "embedding-server"))
(def ^:private venv-dir (node-path/join runtime-dir ".venv"))
(def ^:private venv-python (node-path/join venv-dir "bin" "python"))
(def ^:private deps-stamp (node-path/join runtime-dir "deps-v2.ok"))
(def ^:private old-deps-stamp (node-path/join runtime-dir "deps-v1.ok"))
(def ^:private dirname (node-path/join "repo" "static"))
(def ^:private resources-path (node-path/join "app" "Contents" "Resources"))
(def ^:private sidecar-dir (node-path/join dirname ".." "sidecar"))
(def ^:private script (node-path/join sidecar-dir "embedding_server.py"))
(def ^:private packaged-sidecar-dir (node-path/join resources-path "sidecar"))
(def ^:private packaged-script (node-path/join packaged-sidecar-dir "embedding_server.py"))

(defn- fake-app
  [packaged?]
  #js {:isPackaged packaged?
       :getPath (fn [path-name]
                  (case path-name
                    "userData" user-data-dir))})

(defn- fake-runtime
  [{:keys [existing-paths allocated-port run-command! wait-ready!]}]
  (let [existing-paths* (atom existing-paths)
        ensured-dirs (atom [])
        commands (atom [])
        writes (atom [])
        spawns (atom [])
        events (atom [])
        removed-dirs (atom [])
        env (atom {})
        killed? (atom false)
        proc #js {:kill (fn []
                          (reset! killed? true)
                          true)}]
    {:runtime {:platform "darwin"
               :arch "x64"
               :dirname dirname
               :resources-path resources-path
               :python-command "python3"
               :exists? #(contains? @existing-paths* %)
               :ensure-dir! #(swap! ensured-dirs conj %)
               :find-port! (fn [_host]
                             (p/resolved (or allocated-port 54321)))
               :logger {:debug (fn [& _args])
                        :info (fn [& _args])
                        :warn (fn [& _args])
                        :error (fn [& _args])}
               :remove-dir! (fn [dir]
                              (swap! removed-dirs conj dir)
                              (swap! existing-paths*
                                     (fn [paths]
                                       (set (remove #(string/starts-with? % dir) paths)))))
               :set-env! (fn [k v]
                           (swap! events conj [:set-env k v])
                           (swap! env assoc k v))
               :run-command! (fn [cmd args opts]
                               (swap! commands conj {:cmd cmd
                                                     :args args
                                                     :cwd (:cwd opts)})
                               (or (when run-command!
                                     (run-command! cmd args opts))
                                   (do
                                     (when (= ["-m" "venv" ".venv"] args)
                                       (swap! existing-paths* conj
                                              (node-path/join (:cwd opts)
                                                              ".venv"
                                                              "bin"
                                                              "python")))
                                     (p/resolved nil))))
               :wait-ready! (or wait-ready!
                                (fn [endpoint]
                                  (swap! events conj [:wait-ready endpoint])
                                  (p/resolved nil)))
               :write-file! (fn [file content]
                              (swap! writes conj {:file file
                                                  :content content}))
               :spawn-server! (fn [cfg]
                                (swap! events conj [:spawn-server (:port cfg)])
                                (swap! spawns conj (select-keys cfg [:runtime-dir
                                                                     :venv-dir
                                                                     :venv-python
                                                                     :sidecar-dir
                                                                     :script-path
                                                                     :host
                                                                     :port
                                                                     :model-id]))
                                proc)}
     :ensured-dirs ensured-dirs
     :commands commands
     :writes writes
     :spawns spawns
     :events events
     :removed-dirs removed-dirs
     :env env
     :killed? killed?}))

(deftest start-skips-unsupported-platforms
  (async done
    (let [{:keys [runtime commands spawns]} (fake-runtime {:existing-paths #{}})
          app (fake-app false)]
      (-> (p/let [result (embedding-server/start! app (assoc runtime
                                                              :platform "linux"
                                                              :arch "x64"))]
            (is (= :skipped result))
            (is (empty? @commands))
            (is (empty? @spawns)))
          (p/catch (fn [e]
                     (is false (str "unexpected error: " e))))
          (p/finally done)))))

(deftest python-command-available-detects-missing-python
  (async done
    (let [commands (atom [])
          run-command! (fn [cmd args _opts]
                         (swap! commands conj {:cmd cmd
                                               :args args})
                         (p/rejected (js/Error. "spawn python3 ENOENT")))]
      (-> (p/let [available? (embedding-server/python-command-available!
                              "python3"
                              {:run-command! run-command!})]
            (is (false? available?))
            (is (= [{:cmd "python3"
                     :args ["--version"]}]
                   @commands)))
          (p/catch (fn [e]
                     (is false (str "unexpected error: " e))))
          (p/finally done)))))

(deftest start-allocates-port-creates-local-venv-installs-deps-and-spawns-server
  (async done
    (embedding-server/stop!)
    (let [{:keys [runtime ensured-dirs commands writes spawns env killed?]} (fake-runtime {:existing-paths #{}
                                                                                           :allocated-port 56789})
          app (fake-app false)]
      (-> (p/let [result (embedding-server/start! app runtime)]
            (is (= :started result))
            (is (= [runtime-dir] @ensured-dirs))
            (is (= [{:cmd "python3"
                     :args ["-m" "venv" ".venv"]
                     :cwd runtime-dir}
                    {:cmd venv-python
                     :args ["-c" "import sys"]
                     :cwd runtime-dir}
                    {:cmd venv-python
                     :args ["-m" "pip" "install" "sentence-transformers" "httpx[socks]"]
                     :cwd runtime-dir}]
                   @commands))
            (is (= [{:file deps-stamp
                     :content "sentence-transformers\nhttpx[socks]\n"}]
                   @writes))
            (is (= [{:runtime-dir runtime-dir
                     :venv-dir venv-dir
                     :venv-python venv-python
                     :sidecar-dir sidecar-dir
                     :script-path script
                     :host "127.0.0.1"
                     :port 56789
                     :model-id "all-MiniLM-L6-v2"}]
                   @spawns))
            (is (= {"LOGSEQ_EMBEDDINGS_URL" "http://127.0.0.1:56789/v1/embeddings"}
                   @env))
            (embedding-server/stop!)
            (is @killed?))
          (p/catch (fn [e]
                     (is false (str "unexpected error: " e))))
          (p/finally done)))))

(deftest start-sets-embedding-env-after-server-is-ready
  (async done
    (embedding-server/stop!)
    (let [{:keys [runtime events env]} (fake-runtime {:existing-paths #{venv-python deps-stamp}
                                                      :allocated-port 56789})
          app (fake-app false)]
      (-> (p/let [result (embedding-server/start! app runtime)]
            (is (= :started result))
            (is (= [[:spawn-server 56789]
                    [:wait-ready "http://127.0.0.1:56789/healthz"]
                    [:set-env "LOGSEQ_EMBEDDINGS_URL" "http://127.0.0.1:56789/v1/embeddings"]]
                   @events))
            (is (= {"LOGSEQ_EMBEDDINGS_URL" "http://127.0.0.1:56789/v1/embeddings"}
                   @env))
            (embedding-server/stop!))
          (p/catch (fn [e]
                     (is false (str "unexpected error: " e))))
          (p/finally done)))))

(deftest start-does-not-publish-embedding-env-before-setup-completes
  (async done
    (embedding-server/stop!)
    (let [{:keys [runtime env spawns]} (fake-runtime {:existing-paths #{}
                                                      :allocated-port 56789
                                                      :run-command! (fn [_cmd _args _opts]
                                                                      (p/rejected (js/Error. "venv failed")))})
          app (fake-app false)]
      (-> (embedding-server/start! app runtime)
          (p/then (fn [_]
                    (is false "start should fail")))
          (p/catch (fn [_]
                     (is (= {} @env))
                     (is (empty? @spawns))))
          (p/finally done)))))

(deftest start-upgrades-existing-venv-when-dependency-stamp-is-stale
  (async done
    (embedding-server/stop!)
    (let [{:keys [runtime commands writes]} (fake-runtime {:existing-paths #{venv-python old-deps-stamp}
                                                           :allocated-port 56789})
          app (fake-app false)]
      (-> (p/let [result (embedding-server/start! app runtime)]
            (is (= :started result))
            (is (= [{:cmd venv-python
                     :args ["-c" "import sys"]
                     :cwd runtime-dir}
                    {:cmd venv-python
                     :args ["-m" "pip" "install" "sentence-transformers" "httpx[socks]"]
                     :cwd runtime-dir}]
                   @commands))
            (is (= [{:file deps-stamp
                     :content "sentence-transformers\nhttpx[socks]\n"}]
                   @writes))
            (embedding-server/stop!))
          (p/catch (fn [e]
                     (is false (str "unexpected error: " e))))
          (p/finally done)))))

(deftest start-reuses-existing-venv-and-installed-deps-with-allocated-port
  (async done
    (embedding-server/stop!)
    (let [{:keys [runtime commands spawns env]} (fake-runtime {:existing-paths #{venv-python deps-stamp}
                                                               :allocated-port 45678})
          app (fake-app true)]
      (-> (p/let [result (embedding-server/start! app runtime)]
            (is (= :started result))
            (is (= [{:cmd venv-python
                     :args ["-c" "import sys"]
                     :cwd runtime-dir}]
                   @commands))
            (is (= [{:runtime-dir runtime-dir
                     :venv-dir venv-dir
                     :venv-python venv-python
                     :sidecar-dir packaged-sidecar-dir
                     :script-path packaged-script
                     :host "127.0.0.1"
                     :port 45678
                     :model-id "all-MiniLM-L6-v2"}]
                   @spawns))
            (is (= {"LOGSEQ_EMBEDDINGS_URL" "http://127.0.0.1:45678/v1/embeddings"}
                   @env))
            (embedding-server/stop!))
          (p/catch (fn [e]
                     (is false (str "unexpected error: " e))))
          (p/finally done)))))

(deftest start-recreates-existing-venv-when-python-is-not-usable
  (async done
    (embedding-server/stop!)
    (let [validation-attempts (atom 0)
          {:keys [runtime commands removed-dirs writes spawns]} (fake-runtime
                                                                 {:existing-paths #{venv-python deps-stamp}
                                                                  :allocated-port 45678
                                                                  :run-command! (fn [cmd args _opts]
                                                                                  (when (and (= cmd venv-python)
                                                                                             (= args ["-c" "import sys"])
                                                                                             (= 1 (swap! validation-attempts inc)))
                                                                                    (p/rejected (js/Error. "stale venv python"))))})
          app (fake-app true)]
      (-> (p/let [result (embedding-server/start! app runtime)]
            (is (= :started result))
            (is (= [venv-dir] @removed-dirs))
            (is (= [{:cmd venv-python
                     :args ["-c" "import sys"]
                     :cwd runtime-dir}
                    {:cmd "python3"
                     :args ["-m" "venv" ".venv"]
                     :cwd runtime-dir}
                    {:cmd venv-python
                     :args ["-c" "import sys"]
                     :cwd runtime-dir}
                    {:cmd venv-python
                     :args ["-m" "pip" "install" "sentence-transformers" "httpx[socks]"]
                     :cwd runtime-dir}]
                   @commands))
            (is (= [{:file deps-stamp
                     :content "sentence-transformers\nhttpx[socks]\n"}]
                   @writes))
            (is (= [{:runtime-dir runtime-dir
                     :venv-dir venv-dir
                     :venv-python venv-python
                     :sidecar-dir packaged-sidecar-dir
                     :script-path packaged-script
                     :host "127.0.0.1"
                     :port 45678
                     :model-id "all-MiniLM-L6-v2"}]
                   @spawns))
            (embedding-server/stop!))
          (p/catch (fn [e]
                     (is false (str "unexpected error: " e))))
          (p/finally done)))))

(deftest start-default-port-allocator-uses-node-net
  (async done
    (embedding-server/stop!)
    (let [{:keys [runtime spawns env]} (fake-runtime {:existing-paths #{venv-python deps-stamp}})
          app (fake-app true)
          runtime (dissoc runtime :find-port!)]
      (-> (p/let [result (embedding-server/start! app runtime)
                  port (:port (first @spawns))]
            (is (= :started result))
            (is (integer? port))
            (is (<= 1 port 65535))
            (is (= {"LOGSEQ_EMBEDDINGS_URL" (str "http://127.0.0.1:" port "/v1/embeddings")}
                   @env))
            (embedding-server/stop!))
          (p/catch (fn [e]
                     (is false (str "unexpected error: " e))))
          (p/finally done)))))
