(ns aqua-node.core
  (:require [cljs.core :as cljs]
            [cljs.nodejs :as node]
            [aqua-node.roles :as roles]
            [aqua-node.config :as config]))


(defn -main [& args]
  (let [config (config/read-config)]
    (roles/bootstrap config)))

;(set! *main-cli-fn* -main)
(set! *main-cli-fn* #(try
                       (apply -main %&)
                       (catch js/Object e (println "/!\\  I don't know what I excepted:" e))))
