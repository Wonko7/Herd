(ns aqua-node.dtls
  (:require [cljs.core :as cljs]
            [cljs.nodejs :as node]))

(defn mk-dtls [auth addr port]
  (let [fs          (node/require "fs")
        cat         (fn [k] (try {k (.readFileSync fs (auth k) "utf8")}
                              (catch js/Object e (println "/!\\  could not load auth info: " e))))]
    [(node/require "nodedtls") (cljs/clj->js (merge auth {:host addr} {:port port} (cat :key) (cat :cert)))]))

(defn create-aqua-listening-socket [{auth :auth addr :addr port :port}
                                    new-conn-handler]
  (let [[dtls opts] (mk-dtls auth addr port)]
    (.createServer dtls port opts new-conn-handler))) ;; FIXME: based on tls api, this is not what a nice dtls api should look like.

(defn connect-to-aqua-node [{addr :addr port :port} {auth :auth} conn-handler]
  (let [[dtls opts] (mk-dtls auth addr port)]
    (.connect dtls opts conn-handler)))
