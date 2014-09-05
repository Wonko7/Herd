(ns aqua-node.dtls
  (:require [cljs.core :as cljs]
            [cljs.nodejs :as node]
            [aqua-node.log :as log]
            [aqua-node.conns :as c]))

(defn mk-dtls [config dest]
  "Helper to get DTLS module & options (host/port, keys) ready to use."
  [(node/require "nodedtls") (cljs/clj->js (merge (-> config :auth :openssl) (select-keys dest [:host :port])))])

(defn create-server [{host :host port :port :as dest} config conn-info new-conn-handler err]
  "Create DTLS server. Only used for aqua, so hardcoded for now."
  (let [[dtls opts] (mk-dtls config dest)
        srv         (.createServer dtls port opts new-conn-handler)] ;; FIXME: based on tls api, this is not what a nice dtls api should look like.
    (log/info "Aqua listening on:" host port)
    (c/add-listeners srv {:error err})
    (c/add srv (merge conn-info {:cs :server :type :aqua}))))

(defn connect [dest config conn-info conn-handler err]
  "Connect to a DTLS socket."
  (let [[dtls opts] (mk-dtls config dest)
        c           (.connect dtls opts)]
    (c/add-listeners c {:secureConnect #(conn-handler c) :error err})
    (c/add c (merge conn-info {:cs :client :type :aqua :host (:host dest) :port (:port dest)})))) ;; FIXME doing this because for some reason .-remote[Addr|Port] end up nil.
