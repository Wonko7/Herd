(ns aqua-node.roles
  (:require [cljs.core :as cljs]
            [cljs.nodejs :as node]
            [aqua-node.conns :as c]
            [aqua-node.conn-mgr :as conn]))


;; FIXME: the following are placeholders.
(defn print-buf [b]
  (println "---  recv:" (.toString b)))

(defn new-dtls-conn [s]
  (println "---  new dtls conn on:" (.-remoteAddress s) ":" (.-remotePort s))
  (c/add-listeners s {:data (fn [b]
                              (print-buf b)
                              (.write s (str "polly wants a cracker! " (.toString b))))}))

(def i (atom 0))

(defn conn-to-dtls [s]
  (c/add-listeners s {:data print-buf})
  (js/setInterval #(.write s (str "hello" (swap! i inc))) 1000))
;; FIXME: end placeholders.

(defn is? [role roles]
  (some #(= role %) roles))

(defn bootstrap [{roles :roles ap :app-proxy-conn aq :aqua-conn ds :dir-server :as config}]
  (let [is?   #(is? % roles)]
    (println "###  Bootstrapping as" roles)
    (conn/new :aqua :server aq config new-dtls-conn) ;; in the case of entry only, still needed for NW consensus polling.
    (when (is? :app-proxy)
      (conn/new :socks :server ap config print-buf)
      (conn/new :aqua  :client ds config conn-to-dtls))))
