(ns aqua-node.dtls
  (:require [cljs.core :as cljs]
            [cljs.nodejs :as node]
            [aqua-node.log :as log]
            [aqua-node.conns :as c]))

(defn mk-dtls [config addr port]
  [(node/require "nodedtls") (cljs/clj->js (merge (-> config :auth :openssl) (if addr {:host addr} nil) {:port port}))])

(defn create-server [{addr :addr port :port} config new-conn-handler]
  (let [[dtls opts] (mk-dtls config addr port)
        srv         (.createServer dtls port opts new-conn-handler)] ;; FIXME: based on tls api, this is not what a nice dtls api should look like.
    (log/info "Aqua listening on:" addr port)
    (c/add srv {:cs :server :type :aqua})))

(defn connect [{addr :addr port :port} config conn-handler]
  (let [[dtls opts] (mk-dtls config addr port)
        c           (.connect dtls opts)]
    (c/add-listeners c {:secureConnect #(conn-handler c)}) ;; FIXME; do this on connect?
    (c/add c {:cs :client :type :aqua})))
