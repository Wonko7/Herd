(ns aqua-node.sp
  (:require [cljs.core :as cljs]
            [cljs.nodejs :as node]
            [cljs.core.async :refer [chan <! >! sub pub unsub close!] :as a]
            [aqua-node.parse :as conv]
            [aqua-node.dtls-comm :as dtls]
            [aqua-node.conn-mgr :as conn]
            [aqua-node.circ :as circ]
            [aqua-node.path :as path]
            [aqua-node.ntor :as hs]
            [aqua-node.conns :as c]
            [aqua-node.log :as log]
            [aqua-node.dir :as dir]
            [aqua-node.buf :as b])
  (:require-macros [cljs.core.async.macros :as m :refer [go-loop go]]))


(def to-cmd
  {0  :register-to-sp
   1  :mk-secret
   2  :ack-secret
   3  :register-id-to-sp
   })

(def from-cmd
  (apply merge (for [k (keys to-cmd)]
                 {(to-cmd k) k})))


;; sent by mix:

(defn send-client-sp-id [config socket client-index sp-id]
  "send a sp id & its client-index on the channel to a client"
  (circ/send-sp config socket (b/cat (-> :register-to-sp from-cmd b/new1)
                                     (b/new4 client-index)
                                     sp-id)))

(defn mk-secret-from-create [config payload]
  (log/debug ":aoeu" (.-length payload) (+ (:node-id-len config) (:h-len config) (:h-len config)))
  (comment (let [{pub-B :pub node-id :id sec-b :sec} (-> config :auth :aqua-id)
        client-id                           (.readUInt32BE payload 0)
        hs-type                             (.readUInt16BE payload 4)
        len                                 (.readUInt16BE payload 6)
        [shared-sec created]                (hs/server-reply config {:pub-B pub-B :node-id node-id :sec-b sec-b} (.slice payload 8) (-> config :enc :key-len))]
    (assert (= hs-type 2) "unsupported handshake type")
    [client-id shared-sec created])))

;(defn recv-r)


;; sent by client:

(defn send-mk-secret [config mix-socket client-id mix-auth]
  (let [[auth create]   (hs/client-init config mix-auth)]
    (circ/send-sp config mix-socket (b/cat (-> :mk-secret from-cmd b/new1)
                                           (b/new4 client-id)
                                           (b/new2 2) ;; type of hs
                                           (-> create .-length b/new2)
                                           create))
    auth))


;; init:

(defn init [config]
  (let [[sp-ctrl sp-notify] (:sp-chans config)
        mix-answer (chan)
        config     (merge config [sp-ctrl sp-notify])
        process   (fn [{cmd :cmd data :data socket :socket}]
                    (let [cmd (if (number? cmd) (to-cmd cmd) cmd)]
                      (log/info "Recvd" cmd)
                      (condp = cmd
                        ;;;; recvd by mix:
                        :new-client       (let [conns               (c/get-all)
                                                sps                 (for [k (keys conns)
                                                                          :let [data (conns k)]
                                                                          :when (= :super-peer (:role data))]
                                                                      [k  data])
                                                [sp-socket sp-data] (first sps)
                                                sp-id               (-> sp-data :auth :srv-id)
                                                sp-clients          (-> sp-data :client-secrets)
                                                sp-clients          (or sp-clients {}) 
                                                client-id           (first (filter #(not (sp-clients %)) (range (:max-clients-per-channel config))))]
                                            (assert (= 1 (count sps)) "wrong number of superpeers")
                                            (assert client-id "could not add client, channel full")
                                            (log/debug "Sending SP id" (b/hx sp-id) "to client" client-id)
                                            (c/update-data sp-socket [:client-secrets] (merge sp-clients {client-id {:secret nil}}))
                                            (c/update-data socket [:future-sp] sp-socket)
                                            (send-client-sp-id config socket client-id sp-id))
                        :mk-secret        (let [[client-id shared-sec created]  (mk-secret-from-create config data)
                                                sp-socket                       (-> socket c/get-data :future-sp)
                                                client-secrets                  (-> sp-socket c/get-data :client-secrets)]
                                            (c/update-data sp-socket [:client-secrets]
                                                           (merge client-secrets {client-id {:secret shared-sec}}))
                                            (dtls/send-node-secret {:index client-id} shared-sec)
                                            ;; send ack to client:
                                            (circ/send-sp config socket (b/cat (-> :ack-secret from-cmd b/new1)
                                                                               (-> created .-length b/new2)
                                                                               created)))
                        ;;;; recvd by client:
                        :register-to-sp   (let [client-id (.readUInt32BE data 0)
                                                sp-id     (.slice data 4)]
                                           (go (>! mix-answer [client-id sp-id]))) ;; :connect function is waiting for this.
                        :ack-secret       (go (>! mix-answer data))
                        ;; internal commands (not from the network)
                        :connect          (let [zone          (-> config :geo-info :zone)
                                                net-info      (dir/get-net-info)
                                                select-mixes  #(->> net-info seq (map second) (filter %) shuffle) ;; FIXME make this a function
                                                mix           (first (select-mixes #(and (= (:role %) :mix) (= (:zone %) zone))))
                                                mk-path       (fn [] ;; change (take n) for a path of n+1 nodes.
                                                                (->> (select-mixes #(and (= (:role %) :mix) (not= mix %))) (take 0) (cons mix))) ;; use same mix as entry point for single & rt. ; not= mix
                                                rdvs          (select-mixes #(and (= (:role %) :rdv) (= (:zone %) zone)))
                                                socket        (conn/new :aqua :client mix config  {:connect identity})]
                                            ;; 1/ connect to mix, wait for client-id & sp-id
                                            (go (let [mix-socket (<! socket)]
                                                  (circ/send-id config mix-socket)
                                                  (log/debug :FIXME "sent id")
                                                  (let [[client-id sp-id] (<! mix-answer)
                                                        sp                (first (select-mixes #(b/b= sp-id (-> % :auth :srv-id))))]
                                                    (log/debug "Will connect to SP" (b/hx sp-id))
                                                    (assert sp "Could not find SP")
                                                    ;; 2/ connect to SP:
                                                    (let [socket     (conn/new :aqua :client sp config {:connect identity})
                                                          auth       (send-mk-secret config mix-socket client-id (:auth mix))
                                                          payload    (<! mix-answer)
                                                          shared-sec (hs/client-finalise auth (.slice payload 2) (-> config :enc :key-len))
                                                          sp-socket  (<! socket)]
                                                      (circ/send-sp config sp-socket (b/cat (-> :register-id-to-sp from-cmd b/new1)
                                                                                            (b/new4 client-id)))
                                                      ;; 3/ create circuits:
                                                      (dtls/send-node-secret sp-socket shared-sec)
                                                      (c/update-data sp-socket [:auth] (-> mix-socket c/get-data :auth)) ;; FIXME: not sure if we'll keep this, but for now it'll do
                                                      (path/init-pool config sp-socket :rt mix)
                                                      (path/init-pool config sp-socket :single #(concat (mk-path) (->> rdvs shuffle (take 1)))) ;; for now all single circuits are for rdvs, if this changes this'll have to change too.
                                                      (path/init-pool config sp-socket :one-hop mix)
                                                      (>! sp-notify [mix sp])))))))))]
    (go-loop [msg (<! sp-ctrl)]
      (process msg)
      (recur (<! sp-ctrl)))
    (log/info "Superpeer signaling initialised")))
