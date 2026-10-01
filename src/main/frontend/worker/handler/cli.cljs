(ns frontend.worker.handler.cli
  "CLI and API server operations for the db worker."
  (:require [frontend.common.thread-api :refer [def-thread-api]]
            [frontend.worker.state :as worker-state]
            [logseq.api.db-based.tools :as api-tools]
            [logseq.cli.common.db-worker :as cli-db-worker]))

(def-thread-api :thread-api/cli-list-properties
  [repo options]
  (let [conn (worker-state/get-datascript-conn repo)]
    (cli-db-worker/list-properties @conn options)))

(def-thread-api :thread-api/cli-list-tags
  [repo options]
  (let [conn (worker-state/get-datascript-conn repo)]
    (cli-db-worker/list-tags @conn options)))

(def-thread-api :thread-api/cli-list-pages
  [repo options]
  (let [conn (worker-state/get-datascript-conn repo)]
    (cli-db-worker/list-pages @conn options)))

(def-thread-api :thread-api/cli-list-tasks
  [repo options]
  (let [conn (worker-state/get-datascript-conn repo)]
    (cli-db-worker/list-tasks @conn options)))

(def-thread-api :thread-api/cli-list-nodes
  [repo options]
  (let [conn (worker-state/get-datascript-conn repo)]
    (cli-db-worker/list-nodes @conn options)))

(def-thread-api :thread-api/api-get-page-data
  [repo page-title options]
  (let [conn (worker-state/get-datascript-conn repo)]
    (api-tools/get-page-data @conn page-title options)))

(def-thread-api :thread-api/api-get-block
  [repo uuid-string options]
  (let [conn (worker-state/get-datascript-conn repo)]
    (api-tools/get-block @conn uuid-string options)))

(def-thread-api :thread-api/api-list-properties
  [repo options]
  (let [conn (worker-state/get-datascript-conn repo)]
    (api-tools/list-properties @conn options)))

(def-thread-api :thread-api/api-list-tags
  [repo options]
  (let [conn (worker-state/get-datascript-conn repo)]
    (api-tools/list-tags @conn options)))

(def-thread-api :thread-api/api-list-pages
  [repo options]
  (let [conn (worker-state/get-datascript-conn repo)]
    (api-tools/list-pages @conn options)))

(def-thread-api :thread-api/api-build-upsert-nodes-edn
  [repo ops options]
  (let [conn (worker-state/get-datascript-conn repo)]
    (api-tools/build-upsert-nodes-edn @conn ops options)))

(def-thread-api :thread-api/api-read-upsert-blocks
  [repo expected-blocks]
  (let [conn (worker-state/get-datascript-conn repo)]
    (api-tools/read-upsert-blocks @conn expected-blocks)))
