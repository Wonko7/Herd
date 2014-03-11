(ns aqua-node.core
  (:require [cljs.core :as cljs]
            [cljs.nodejs :as node]
            [clojure.walk :as walk]
            [aqua-node.log :as log]
            [aqua-node.roles :as roles]
            [aqua-node.config :as config]))


(defn -main [& args]
  (let [argv   (-> ((node/require "minimist") (-> js/process .-argv (.slice 2))) cljs/js->clj walk/keywordize-keys)
        config (config/read-config argv)]
    (println argv)
    (roles/bootstrap config)))

;(set! *main-cli-fn* #(do (enable-console-print!) ;; @Nic: sometimes you want to do things outside the try catch for a more complete stacktrace.
;                         (apply -main %&)))
(set! *main-cli-fn* #(try (enable-console-print!)
                          (apply -main %&)
                          (catch js/Object e (log/c-error e "No one expects the Spanish Inquisition."))))
