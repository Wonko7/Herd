(ns aqua-node.core
  (:require [cljs.core :as cljs]
            [cljs.nodejs :as node]
            [aqua-node.dtls :as dtls]
            [aqua-node.socks :as socks]
            [aqua-node.config :as config]))

(defn -main [& args]
  (let [config (config/read-config)]
    (if (some #(not= % :app-proxy) (-> config :server :type))
      (dtls/create-aqua-listening-socket (:server config) #(println "new dtls conn on: " (.-remoteAddress %) ":" (.-remotePort %)))
      (do
        (println "app-proxy only")
        (socks/create-socks-server (:server config))
        (dtls/connect-to-aqua-node (:dir-server config) (:server config) #(println "conn?"))))))

;(set! *main-cli-fn* -main)
(set! *main-cli-fn* #(try
                       (apply -main %&)
                       (catch js/Object e (println "/!\\  I don't know what I excepted: " e))))
