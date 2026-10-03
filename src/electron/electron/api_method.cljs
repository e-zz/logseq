(ns electron.api-method
  "Production method resolution and renderer dispatch shared by the desktop
   API/MCP server (`electron.server`) and the renderer IPC listener
   (`electron.listener`).

   Kept free of electron/renderer requires so node verification harnesses can
   exercise the exact same `logseq.<ns>.<method>` -> `ns@snake_case` naming and
   dispatch path the desktop uses."
  (:require [camel-snake-kebab.core :as csk]
            [clojure.string :as string]
            [promesa.core :as p]))

(defn type-proxy-api?
  [s]
  (when (string? s)
    (string/starts-with? s "logseq.")))

(defn resolve-real-api-method
  [s]
  (when-not (string/blank? s)
    (if (type-proxy-api? s)
      (let [s' (string/split (string/trim s) ".")
            ns (some-> (second s') str (string/lower-case))
            method (some-> (last s') str)]
        (csk/->snake_case (str ns "@" method)))
      (string/trim s))))

(defn dispatch-real-api-method
  "Invokes a resolved `ns@snake_case_method` on the renderer-exposed API objects,
   matching `js/window.logseq.api` for app/editor/db/cli methods and
   `js/window.logseq.sdk` for other namespaces. `args` is a js array. Returns a
   promise resolving to the method's return value, rejecting on failure."
  [api sdk resolved-method args]
  (p/create
   (fn [resolve reject]
     (try
       (let [ns-method (some-> resolved-method (string/split "@"))
             ns' (first ns-method)
             method' (last ns-method)
             app? (contains? #{"app" "editor" "db" "cli"} ns')
             target (if app? api (aget sdk ns'))]
         (when-not target
           (throw (js/Error. (str "MethodNotExist: " resolved-method))))
         (-> (p/promise (apply js-invoke target method' args))
             (p/then resolve)
             (p/catch reject)))
       (catch :default e (reject e))))))
