(ns aqua-node.tls
  (:require [cljs.core :as cljs]
            [cljs.nodejs :as node]
            [aqua-node.log :as log]
            [aqua-node.conns :as c]))

(defn mk-tls [config dest]
  "Helper to get TLS module & options (host/port, keys) ready to use."
  [(node/require "tls") (cljs/clj->js (merge (-> config :auth :openssl) (select-keys dest [:host :port]) {:rejectUnauthorized false}))])

(defn create-server [{host :host port :port :as dest} config new-conn-handler]
  "Create TLS server. Only used for aqua-dir, so hardcoded for now."
  (let [[tls opts] (mk-tls config dest)
        srv        (.createServer tls opts new-conn-handler)]
    (log/info "Aqua-Dir listening on:" host port)
    (.listen srv port host)
    (c/add srv {:cs :server :type :aqua-dir})))

(defn connect [dest config conn-handler]
  "Connect to a TLS socket."
  (let [[tls opts] (mk-tls config dest)
        c          (.connect tls opts)]
    (c/add-listeners c {:secureConnect #(conn-handler c)})
    (c/add c {:cs :client :type :aqua-dir :host (:host dest) :port (:port dest)}))) ;; FIXME doing this because for some reason .-remote[Addr|Port] end up nil.
