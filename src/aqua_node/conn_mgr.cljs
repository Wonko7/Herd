(ns aqua-node.conn-mgr
  (:require [cljs.core :as cljs]
            [cljs.nodejs :as node]
            [aqua-node.log :as log]
            [aqua-node.dtls :as dtls]
            [aqua-node.socks :as socks]
            [aqua-node.conns :as c]))


(defn new [type cs conn {auth :auth :as config} data-handle new-handle error-cb]
  (let [conn-info   (merge conn {:type type :cs cs})
        is?         #(and (= %2 cs) (= %1 type))
        data-handle (partial data-handle config)
        new-tcp-c   (fn [] (let [socket (.connect (node/require "net") (cljs/clj->js (select-keys conn [:host :port])))]
                             (.on socket "data" (partial data-handle socket))
                             (c/add socket {:type :tcp-exit :cs :client})
                             socket))]
    (cond (is? :socks :server) (socks/create-server conn data-handle (partial new-handle config) (partial error-cb config))
          (is? :aqua  :server) (dtls/create-server conn config data-handle)
          (is? :aqua  :client) (dtls/connect conn config data-handle)
          (is? :tcp   :client) (new-tcp-c)
          :else                (log/error "Unsupported connection type:" type "as" cs))))
