(ns aqua-node.dtls
  (:require [cljs.core :as cljs]
            [cljs.nodejs :as node]
            [aqua-node.conns :as c]))

(defn mk-dtls [auth addr port]
  (let [fs          (node/require "fs")
        cat         (fn [k] (try {k (.readFileSync fs (auth k) "utf8")}
                              (catch js/Object e (println "/!\\  could not load auth info: " e))))]
    [(node/require "nodedtls") (cljs/clj->js (merge auth (if addr {:host addr} nil) {:port port} (cat :key) (cat :cert)))]))

(defn create-server [{addr :addr port :port} auth new-conn-handler]
  (let [[dtls opts] (mk-dtls auth addr port)
        srv         (.createServer dtls port opts new-conn-handler)] ;; FIXME: based on tls api, this is not what a nice dtls api should look like.
    (println "###  Aqua listening on:" addr ":" port)
    (c/add srv)))

(defn connect [{addr :addr port :port} auth conn-handler]
  (let [[dtls opts] (mk-dtls auth addr port)
        c           (.connect dtls opts)]
    (c/add-listeners c {:secureConnect #(conn-handler c)})
    (c/add c)))
