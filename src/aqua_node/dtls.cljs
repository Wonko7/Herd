(ns aqua-node.dtls
  (:require [cljs.core :as cljs]
            [cljs.nodejs :as node]))

(defn create-aqua-listening-socket [{auth :auth
                                     {addr :addr
                                      port :port} :listen}
                                    new-conn-handler]
  (let [dtls       (node/require "nodedtls")
        fs         (node/require "fs")
        cat        (fn [k] (try {k (.readFileSync fs (auth k) "utf8")}
                             (catch js/Object e (println "/!\\  could not load auth info: " e)))) 
        auth       (merge auth (cat :key) (cat :cert))]
    (.createServer dtls (cljs/clj->js auth) new-conn-handler)))
