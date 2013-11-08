(ns aqua-node.roles
  (:require [cljs.core :as cljs]
            [cljs.nodejs :as node]
            [aqua-node.buf :as b]
            [aqua-node.circ :as circ]
            [aqua-node.conns :as c]
            [aqua-node.conn-mgr :as conn]))


;; FIXME: the following are placeholders.
(defn forward [b]
  (let [[c cdata] (first (filter (fn [[c data]] (and (= (:type data) :aqua) (= (:cs data) :client))) (c/get-all)))]
    (b/print b "recv:")
    (.write c b)))

(defn new-dtls-conn [s]
  (println "---  new dtls conn on:" (-> s .-socket .-_destIP) ":" (-> s .-socket .-_destPort)) ;; FIXME: investigate nil .-remote[Addr|Port]
  (c/add-listeners s {:data (fn [b]
                              (circ/process s b)
                              (.write s (str "polly wants a cracker! " (.toString b))))}))

(def i (atom 0))

(defn conn-to-dtls [s]
  (c/add-listeners s {:data #(b/print % "recv:")})
  ;(js/setInterval #(.write s (str "hello" (swap! i inc))) 1000)
  )
;; FIXME: end placeholders.

(defn is? [role roles] ;; FIXME -> when needed elsewhere move to roles
  (some #(= role %) roles))

(defn bootstrap [{roles :roles ap :app-proxy-conn aq :aqua-conn ds :dir-server :as config}]
  (let [is?   #(is? % roles)]
    (println "###  Bootstrapping as" roles)
    (when
      (conn/new :aqua :server aq config new-dtls-conn))
    (when (is? :app-proxy)
      (conn/new :socks :server ap config forward)
      ;; the following will be covered by conn-to all known nodes --> sooooon
      (conn/new :aqua  :client ds config conn-to-dtls))
    (when (and false (not (is? :dir-server))) ;; dir-servs also need to connect to other dir-sers, we'll see about that when we get there.
      (conn/new :aqua  :client ds config get-dir-info))))
