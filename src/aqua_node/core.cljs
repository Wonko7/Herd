(ns aqua-node.core
  (:require [cljs.core :as cljs]
            [cljs.nodejs :as node]
            [aqua-node.roles :as roles]
            [aqua-node.ntor :as hs]
            [aqua-node.config :as config]))


(defn -main [& args]
  (let [config (config/read-config)]
    ;; This has nothing to do here. But I like seeing that it works...
    (let [srv-id                   (js/Buffer. "waaaaaaaaaaoooooooow" "ascii")
          [srv-sec-b srv-pub-B]    (hs/gen-keys)
          [auth-info create]       (hs/client-init  {:srv-id srv-id :pub-B srv-pub-B})
          [srv-shared-sec created] (hs/server-reply {:node-id srv-id :pub-B srv-pub-B :sec-b srv-sec-b} create 72)
          cli-shared-sec           (hs/client-finalise auth-info created 72)]
      (println :cli (.toString cli-shared-sec "hex"))
      (println :srv (.toString srv-shared-sec "hex")))
    (roles/bootstrap config)))

;(set! *main-cli-fn* -main)
(set! *main-cli-fn* #(try
                       (apply -main %&)
                       (catch js/Object e (println "/!\\  I don't know what I excepted:" e))))
