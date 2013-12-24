(ns aqua-node.rtpp
  (:require [cljs.core :as cljs]
            [cljs.nodejs :as node]
            [cljs.core.async :refer [chan <! >!]]
            [aqua-node.log :as log]
            [aqua-node.buf :as b]
            [aqua-node.conns :as c])
  (:require-macros [cljs.core.async.macros :as m :refer [go]]))

(defn err []
  (println "error on rtpp server"))

(defn process [config socket message rinfo]
  (log/debug "RTP-Proxy: from:" (.-address rinfo) (.-port rinfo))
  (let [bcode        (node/require "node-bencode")
        [cookie cmd] (-> message (.toString "ascii") (.split " "))
        cmd          (cljs/js->clj (.decode bcode cmd))]
    (log/debug :cookie cookie :cmd (for [k (keys cmd)]
                                     (list k (.toString (cmd k)))))))

(defn create-server [{host :host port :port} config]
  (let [socket (.createSocket (node/require "dgram") "udp4")] ;; FIXME hardcoded to ip4 for now.
    (.bind socket port host #(log/info "RTP-Proxy listening on:" (-> socket .address .-ip) (-> socket .address .-port)))
    (c/add socket {:type :udp-rtpp :cs :server})
    (c/add-listeners socket {:message  (partial process config socket)
                             :error    err
                             :close    err})
    socket))
