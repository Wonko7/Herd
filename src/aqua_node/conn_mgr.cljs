(ns aqua-node.conn-mgr
  (:require [cljs.core :as cljs]
            [cljs.nodejs :as node]
            [aqua-node.log :as log]
            [aqua-node.dtls :as dtls]
            [aqua-node.socks :as socks]
            [aqua-node.conns :as c]))


(defn new [type cs conn {auth :auth :as config} {connect :connect data :data udp-data :udp-data init :init err :error}]
  (let [conn-info   (merge conn {:type type :cs cs})
        is?         #(and (= %2 cs) (= %1 type))
        data        (partial data config)
        udp-data    (partial udp-data config)
        new-tcp-c   (fn [] (let [socket (.connect (node/require "net") (cljs/clj->js (select-keys conn [:host :port])))]
                             (c/add socket {:ctype :tcp :type :tcp-exit :cs :client})
                             (c/add-listeners socket {:data      (partial data socket)
                                                      :connect   #(connect socket)
                                                      :error     err
                                                      :end       err})
                             socket))
        new-udp-c   (fn [type] (let [socket (.createSocket (node/require "dgram") (if (= :ip6 (:ip conn)) "udp6" "udp4"))]
                                 (.bind socket 0)
                                 (c/add socket {:ctype :udp :type type :cs :client :send #(.send socket % 0 (.-length %) (:port conn) (:host conn))})
                                 (c/add-listeners socket {:message   (partial data socket)
                                                          :listening #(connect socket)
                                                          :error     err
                                                          :close     err})
                                 socket))]
    (cond (is? :socks :server) (socks/create-server conn data udp-data (partial init config) (partial err config))
          (is? :aqua :server)  (dtls/create-server conn config data)
          (is? :aqua :client)  (dtls/connect conn config data)
          (is? :dir :server)   (tls/create-server conn config connect)
          (is? :dir :client)   (tls/connect conn config connect)
          (is? :tcp :client)   (new-tcp-c)
          (is? :udp :client)   (new-udp-c :udp-exit)
          (is? :rtp :client)   (new-udp-c :rtp-exit)
          :else                (log/error "Unsupported connection type:" type "as" cs))))
