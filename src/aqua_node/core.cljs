(ns aqua-node.core
  (:require [cljs.core :as cljs]
            [cljs.nodejs :as node]
            [aqua-node.socks :as socks]
            [aqua-node.config :as config]))

(defn -main [& args]
  (let [config (config/read-config)]
    (socks/create-socks-server (:server config))))

(set! *main-cli-fn* -main)
