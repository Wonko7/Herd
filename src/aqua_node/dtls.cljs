(ns aqua-node.dtls
  (:require [cljs.core :as cljs]
            [cljs.nodejs :as node]
            [aqua-node.conns :as c]))

(defn mk-dtls [auth addr port]
  (let [fs          (node/require "fs")
        cat         (fn [k] (try {k (.readFileSync fs (auth k) "utf8")}
                              (catch js/Object e (println "/!\\  could not load auth info: " e))))]
    [(node/require "nodedtls") (cljs/clj->js (merge auth (if addr {:host addr} nil) {:port port} (cat :key) (cat :cert)))]))

(defn create-server [{addr :addr port :port} auth new-conn-handler] ;; FIXME listeners aren't used here for now. might remove from the api.
  (let [[dtls opts] (mk-dtls auth addr port)
        srv         (.createServer dtls port opts #(-> % c/add new-conn-handler))] ;; FIXME: based on tls api, this is not what a nice dtls api should look like.
    (c/add srv)))

(defn connect [{addr :addr port :port} auth conn-handler]
  (let [[dtls opts] (mk-dtls auth addr port)
        c           (.connect dtls opts conn-handler)]
    (c/add c)))
