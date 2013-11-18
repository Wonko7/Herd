(ns aqua-node.dtls
  (:require [cljs.core :as cljs]
            [cljs.nodejs :as node]
            [aqua-node.log :as log]
            [aqua-node.conns :as c]))

(defn mk-dtls [config dest]
  [(node/require "nodedtls") (cljs/clj->js (merge (-> config :auth :openssl) (select-keys dest [:host :port])))])

(defn create-server [{host :host port :port :as dest} config new-conn-handler]
  (let [[dtls opts] (mk-dtls config dest)
        srv         (.createServer dtls port opts new-conn-handler)] ;; FIXME: based on tls api, this is not what a nice dtls api should look like.
    (log/info "Aqua listening on:" host port)
    (c/add srv {:cs :server :type :aqua})))

(defn connect [dest config conn-handler]
  (let [[dtls opts] (mk-dtls config dest)
        c           (.connect dtls opts)]
    (c/add-listeners c {:secureConnect #(conn-handler c)}) ;; FIXME; do this on connect?
    (c/add c {:cs :client :type :aqua :host (:host dest) :port (:port dest)})))
