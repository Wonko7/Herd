(ns aqua-node.core
  (:require [cljs.core :as cljs]
            [cljs.nodejs :as node]
            [aqua-node.dtls :as dtls]
            [aqua-node.socks :as socks]
            [aqua-node.config :as config]))

(defn -main [& args]
  (let [config (config/read-config)]
    (dtls/create-aqua-listening-socket (:server config) #(println "new dtls conn on: " (.remoteAddress %) ":" (.remotePort %)))
    (socks/create-socks-server (:server config))))

(set! *main-cli-fn* #(try
                       (apply -main %&)
                       (catch js/Object e (println "/!\\  I don't know what I excepted: " e))))
