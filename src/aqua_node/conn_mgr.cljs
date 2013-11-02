(ns aqua-node.conn-mgr
  (:require [cljs.core :as cljs]
            [cljs.nodejs :as node]
            [aqua-node.dtls :as dtls]
            [aqua-node.socks :as socks]
            [aqua-node.conns :as c]))


(defn new [type cs conn {auth :auth} handle]
  (let [conn-info (merge conn {:type type :cs cs})
        is?       #(and (= %2 cs) (= %1 type))]
    (cond (is? :socks :server) (socks/create-server conn handle)
          (is? :aqua  :server) (dtls/create-server conn auth handle)
          (is? :aqua  :client) (dtls/connect conn auth handle)
          :else                (println "/!\\  Unsupported connection type:" type "as" cs))))
