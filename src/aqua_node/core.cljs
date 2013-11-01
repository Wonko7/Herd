(ns aqua-node.core
  (:require [cljs.core :as cljs]
            [cljs.nodejs :as node]
            [aqua-node.dtls :as dtls]
            [aqua-node.socks :as socks]
            [aqua-node.config :as config]))

(defn new-dtls-conn [s]
  (println "new dtls conn on: " (.-remoteAddress s) ":" (.-remotePort s))
  (.on s "data" (fn [b]
                  (println "recv: " (.toString b))
                  (.write s (str "polly wants a cracker! " (.toString b))))))

(def i (atom 0))
(defn write [s]
  (js/setInterval #(.write s (str "hello" (swap! i inc))) 1000))

(defn -main [& args]
  (let [config (config/read-config)]
    (if (some #(not= % :app-proxy) (-> config :server :type))
      (dtls/create-aqua-listening-socket (:server config) new-dtls-conn)
      (do
        (println "app-proxy only")
        (socks/create-socks-server (:server config))
        (let [s (dtls/connect-to-aqua-node (:dir-server config) (:server config) identity)]
          (write s)
          (.on s "data" #(println (.toString %))))))))

;(set! *main-cli-fn* -main)
(set! *main-cli-fn* #(try
                       (apply -main %&)
                       (catch js/Object e (println "/!\\  I don't know what I excepted: " e))))
