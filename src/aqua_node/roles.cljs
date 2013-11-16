(ns aqua-node.roles
  (:require [cljs.core :as cljs]
            [cljs.nodejs :as node]
            [aqua-node.log :as log]
            [aqua-node.buf :as b]
            [aqua-node.ntor :as hs]
            [aqua-node.circ :as circ]
            [aqua-node.conns :as c]
            [aqua-node.conn-mgr :as conn]))


(defn app-proxy-init [config socket dest]
  (let [[circ-id circ-data] (first (circ/get-all))] ;; FIXME -> choose (based on...?) or create circuit
    (c/update-data socket [:circuit] circ-id)
    (circ/relay-begin config circ-id dest)
    (circ/update-data circ-id [:exit-hop :conn] socket)))

(defn app-proxy-forward [config s b]
  (let [circ-id   (:circuit (c/get-data s))
        circ-data (circ/get-data circ-id)]
    (if (-> circ-data :circuit :state :relay)
      (circ/relay-data config circ-id b)
      (log/info "not ready for data, dropping on circuit" circ-id))))

(defn aqua-server-recv [config s]
  (log/debug "new dtls conn on:" (-> s .-socket .-_destIP) (-> s .-socket .-_destPort)) ;; FIXME: investigate nil .-remote[Addr|Port]
  (c/add-listeners s {:data #(circ/process config s %)}))

(defn aqua-client-recv [config s]
  (c/add-listeners s {:data #(circ/process config s %)})
  ;; this is temporary:
  (circ/mk-hop config s {:srv-id (js/Buffer. "h00z6mIWXCPWK4Pp1AQh+oHoHs8=" "base64")
                         :pub-B  (js/Buffer. "KYi+NX2pCOQmYnscN0K+MB+NO9A6ynKiIp41B5GlkHc=" "base64")}))

;(js/setInterval#(circ/relay config s 42 :data "If at first you don't succeed, you fail.")  1000)

(defn is? [role roles] ;; FIXME -> when needed elsewhere move to roles
  (some #(= role %) roles))

(defn bootstrap [{roles :roles ap :app-proxy-conn aq :aqua-conn ds :dir-server :as config}]
  (let [is?   #(is? % roles)]
    (log/info "Bootstrapping as" roles)
    (when (some is? [:mix :entry :exit])
      (conn/new :aqua :server aq config aqua-server-recv))
    (when (is? :app-proxy)
      (conn/new :socks :server ap config app-proxy-forward app-proxy-init)
      ;; the following will be covered by conn-to all known nodes --> sooooon
      (conn/new :aqua  :client ds config aqua-client-recv))
    (when (and false (not (is? :dir-server))) ;; dir-servs also need to connect to other dir-sers, we'll see about that when we get there.
      (conn/new :aqua  :client ds config get-dir-info))))
