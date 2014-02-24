(ns aqua-node.core
  (:require [cljs.core :as cljs]
            [cljs.nodejs :as node]
            [aqua-node.log :as log]
            [aqua-node.roles :as roles]
            [aqua-node.config :as config]))


(defn -main [& args]
  (let [config (config/read-config (merge {:rate {:period (-> args first js/parseInt)}}
                                          (when (= "--debug" (second args)) {:debug true})))]
    (roles/bootstrap config)))

(set! *main-cli-fn* #(do (enable-console-print!)
                         (apply -main %&)))
;(set! *main-cli-fn* #(try (enable-console-print!)
;                          (apply -main %&)
;                          (catch js/Object e (log/c-error "No one expects the Spanish Inquisition." e))))
