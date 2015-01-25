(ns aqua-node.sp
  (:require [cljs.core :as cljs]
            [cljs.nodejs :as node]
            [cljs.core.async :refer [chan <! >! sub pub unsub close!] :as a]
            [aqua-node.parse :as conv]
            [aqua-node.dtls-comm :as dtls]
            [aqua-node.circ :as circ]
            [aqua-node.ntor :as hs]
            [aqua-node.conns :as c]
            [aqua-node.log :as log]
            [aqua-node.buf :as b])
  (:require-macros [cljs.core.async.macros :as m :refer [go-loop go]]))


(def to-cmd
  {0  :register-to-sp
   1  :mk-secret
   2  :ack-secret
   })

(def from-cmd
  (apply merge (for [k (keys to-cmd)]
                 {(to-cmd k) k})))


;; sent by mix:

(defn send-client-sp-id [client-index sp-id]
  "send a sp id & its client-index on the channel to a client"
  (circ/relay-sp config (b/cat (-> :register-to-sp from-cmd b/new1)
                               sp-id)))

(defn recv-mk-secret [payload]
  (let [{pub-B :pub node-id :id sec-b :sec} (-> config :auth :aqua-id)
        client-id                           (.readUInt16BE payload 0)
        hs-type                             (.readUInt16BE payload 0)
        len                                 (.readUInt16BE payload 2)
        [shared-sec created]                (hs/server-reply config {:pub-B pub-B :node-id node-id :sec-b sec-b} (.slice payload 4) (-> config :enc :key-len))]
    (assert (= hs-type 2) "unsupported handshake type")
    [client-id shared-sec created]))

;(defn recv-r)


;; sent by client:

(defn send-mk-secret [config mix-socket mix-auth]
  (let [[auth create]   (hs/client-init config mix-auth)]
    (c/update-data mix-s)
    (circ/relay-sp config (b/cat (-> :register-to-sp from-cmd b/new1)
                                 (b/new1 client-id)
                                 (b/new2 2) ;; type of hs
                                 (-> create .-length b/new2)
                                 create))
    auth))


;; init:

(defn init [config]
  (let [sp-ctrl   (chan)
        sp-notify (chan)
        process   (fn [{cmd :cmd data :data socket :socket}]
                    (log/info "Recvd" cmd)
                    (condp = cmd
                      :register-to-sp  (let [{id :id socket :socket} data]
                                         ;; FIXME as a client, we need to connect to sp instead
                                         )
                      :mk-secret       (let [[client-id shared-sec created] (mk-secret-from-create data)
                                             client-secrets                 (-> sp-sock c/get-data :client-secrets)]
                                         (c/update  ;; find sp
                                                   (merge client-secrets {client-id shared-sec}))
                                         ;; send ack to client:
                                         (circ/relay-sp config (b/cat (-> :ack-secret from-cmd b/new1)
                                                                      (b/new2 2) ;; type of hs
                                                                      (-> create .-length b/new2)
                                                                      created)))
                      :ack-secret      (let [[client-id shared-sec] (recv-mk-secret data)
                                             client-secrets         (-> sp-sock c/get-data :client-secrets)]
                                         (c/update  ;; find sp
                                                   (merge client-secrets {client-id shared-sec})
                                                   )
                                         (dtls/update-id)
                                         )))]
    (go-loop [msg (<! sp-ctrl)]
      (process msg)
      (recur (<! sp-ctrl)))
    [sp-ctrl sp-notify]))
