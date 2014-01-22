(ns aqua-node.core
  (:require [cljs.core :as cljs]
            [cljs.nodejs :as node]
            [aqua-node.log :as log]
            [aqua-node.roles :as roles]
            [aqua-node.config :as config]))


(defn -main [& args]
  (let [config (config/read-config (when (= "--debug" (first args)) {:debug true}))]
    (roles/bootstrap config)))

(set! *main-cli-fn* #(try (apply -main %&)
                          (catch js/Object e (log/c-error "No one expects the Spanish Inquisition." e))))
