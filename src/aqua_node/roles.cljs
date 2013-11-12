(ns aqua-node.roles
  (:require [cljs.core :as cljs]
            [cljs.nodejs :as node]
            [aqua-node.buf :as b]
            [aqua-node.ntor :as hs]
            [aqua-node.circ :as circ]
            [aqua-node.conns :as c]
            [aqua-node.conn-mgr :as conn]))


;; FIXME: the following are placeholders.
(defn forward [b]
  (let [[c cdata] (first (filter (fn [[c data]] (and (= (:type data) :aqua) (= (:cs data) :client))) (c/get-all)))]
    (b/print b "recv:")
    (.write c b)))

(defn new-dtls-conn [config s]
  (println "---  new dtls conn on:" (-> s .-socket .-_destIP) ":" (-> s .-socket .-_destPort)) ;; FIXME: investigate nil .-remote[Addr|Port]
  (c/add-listeners s {:data #(circ/process config s %)}))

(def i (atom 0))

(defn conn-to-dtls [config s]
  (c/add-listeners s {:data #(circ/process config s %)})
  (circ/mk-path config s {:srv-id (js/Buffer. "h00z6mIWXCPWK4Pp1AQh+oHoHs8=" "base64")
                          :pub-B  (js/Buffer. "KYi+NX2pCOQmYnscN0K+MB+NO9A6ynKiIp41B5GlkHc=" "base64")}))
;; FIXME: end placeholders.

(defn is? [role roles] ;; FIXME -> when needed elsewhere move to roles
  (some #(= role %) roles))

(defn bootstrap [{roles :roles ap :app-proxy-conn aq :aqua-conn ds :dir-server :as config}]
  (let [is?   #(is? % roles)]
    (println "###  Bootstrapping as" roles)
    (when (some is? [:mix :entry :exit])
      (conn/new :aqua :server aq config new-dtls-conn))
    (when (is? :app-proxy)
      (conn/new :socks :server ap config forward)
      ;; the following will be covered by conn-to all known nodes --> sooooon
      (conn/new :aqua  :client ds config conn-to-dtls))
    (when (and false (not (is? :dir-server))) ;; dir-servs also need to connect to other dir-sers, we'll see about that when we get there.
      (conn/new :aqua  :client ds config get-dir-info))))
