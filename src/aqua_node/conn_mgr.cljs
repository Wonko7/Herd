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
                             (c/add socket {:type :tcp-exit :cs :client})
                             (c/add-listeners socket {:data     (partial data-handle socket)
                                                      :error    error-cb
                                                      :end      error-cb})
                             socket))
        new-udp-c   (fn [] (let [socket (.createSocket (node/require "dgram") (if (= :ip6 (:ip conn)) "udp6" "udp4"))]
                             (c/add socket {:type :tcp-exit :cs :client :send #(do (println :sending-udp 0 (.-length %) (:port conn) (:host conn))
                                                                                   (.send socket % 0 (.-length %) (:port conn) (:host conn)))})
                             (c/add-listeners socket {:message  (partial data-handle socket)
                                                      :error    error-cb
                                                      :close    error-cb})
                             socket))]
    (println type cs conn)
    (cond (is? :socks :server) (socks/create-server conn data-handle (partial new-handle config) (partial error-cb config))
          (is? :aqua  :server) (dtls/create-server conn config data-handle)
          (is? :aqua  :client) (dtls/connect conn config data-handle)
          (is? :tcp   :client) (new-tcp-c)
          (is? :udp   :client) (new-udp-c)
          :else                (log/error "Unsupported connection type:" type "as" cs))))
