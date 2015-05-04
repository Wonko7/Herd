(ns aqua-node.sp
  (:require [cljs.core :as cljs]
            [cljs.nodejs :as node]
            [cljs.core.async :refer [chan <! >! sub pub unsub close!] :as a]
            [clojure.set :as clj-set]
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
  (:require-macros [cljs.core.async.macros :as m :refer [go-loop go]]
                   [utils.macros :refer [<? <?? go? go-try dprint]]))


(def to-cmd
  {0  :register-to-mix
   1  :ack
   2  :expect-new-client
   3  :register-to-sp
   4  :ack-secret
   5  :register-id-to-sp
   })

(def from-cmd (clj-set/map-invert to-cmd))

(def SP-clients (atom {}))

(defn add-client-to-chan [sp chan client-id client-info]
  (swap! SP-clients assoc-in [sp chan client-id] client-info))

(defn rm-from-SPs [& keys]
  (swap! SP-clients #(apply dissoc % keys)))

(defn get-random-chans [config nb-chans]
  (take nb-chans (shuffle (for [sp      (keys SP-clients)
                                chan-id (range (:max-chans-per-sp config))
                                cl-id   (range (:max-clients-per-chan config))
                                :when   (nil? (get-in sps [sp chan-id cl-id]))]
                            [sp chan-id cl-id]))))


;; sent by AP to mix:

(defn send-register-to-mix [config mix-socket mix-auth]
  (let [[auth create]   (hs/client-init config mix-auth)]
    (log/debug :FIXME :mk-secret (.-length create))
    (circ/send-sp config mix-socket (b/cat (-> :register-to-mix from-cmd b/new1)
                                           (-> config :nb-channels b/new1)
                                           ;(b/new1 client-id)
                                           (b/new2 2) ;; type of hs
                                           (-> create .-length b/new2)
                                           create))
    auth))

(defn send-ack-regesiter-info [config mix-socket cookie]
  (circ/send-sp config mix-socket (b/cat (-> :ack from-cmd b/new1)
                                         (b/new4 cookie))))

;; sent by mix to AP:

(defn send-register-info-to-client [config cookie ap-socket chan-info secret-info]
  (let [message (concat [(-> :register-info-to-client b/new1)
                         (b/new4 cookie)
                         (-> secret-info .-length b/new2)
                         secret-info]
                        ;; [[sp-id, chan-id, client-id] ... ]
                        (mapcat #(list (b/new %1) (b/new1 %2) (b/new1 %3) chan-info)))]
    (circ/send-sp config ap-socket (apply b/cat message))))


;; sent by mix to SP:

(defn send-expect-new-client [config sp-socket cookie {chan-id :chan-id client-id :client-id client-pub-id :client-pub-id}]
  (circ/send-sp config sp-socket (b/cat (-> :expect-new-client from-cmd b/new1)
                                        (b/new4 cookie)
                                        (b/new4 chan-id)
                                        (b/new4 client-id)
                                        client-pub-id)))

;; sent by SP to mix:

(defn send-ack-new-client [config mix-socket cookie ack-value]
  (circ/send-sp config mix-socket (b/cat (-> :ack from-cmd b/new1)
                                         (b/new4 cookie)
                                         (b/new1 ack-value))))

;;;; old:
;;
;;(defn send-client-sp-id [config socket client-index sp-id]
;;  "send a sp id & its client-index on the channel to a client"
;;  (circ/send-sp config socket (b/cat (-> :register-to-sp from-cmd b/new1)
;;                                     (b/new4 client-index)
;;                                     sp-id)))
;;
(defn mk-secret-from-create [config payload]
  (log/debug ":aoeu" (.-length payload))
  (let [{pub-B :pub node-id :id sec-b :sec} (-> config :auth :aqua-id) ;; FIXME: this is the current blocking bug.
        client-id                           (.readUInt32BE payload 0)
        hs-type                             (.readUInt16BE payload 4)
        len                                 (.readUInt16BE payload 6)
        [shared-sec created]                (hs/server-reply config {:pub-B pub-B :node-id node-id :sec-b sec-b} (.slice payload 8) (-> config :enc :key-len))]
    (assert (= hs-type 2) "unsupported handshake type")
    [client-id shared-sec created]))


;; sent by client:


;; init:

(defn init [config]
  (let [[sp-ctrl sp-notify] (:sp-chans config)
        answer     (chan)
        answers    (atom {})
        config     (merge config [sp-ctrl sp-notify])
        process    (fn [{cmd :cmd data :data socket :socket}]
                     (let [cmd     (if (number? cmd) (to-cmd cmd) cmd)
                           fwd-ack (fn [{cmd :cmd data :data socket :socket}]
                                         (let [cookie (.readUInt32BE data 4)]
                                           (go? (swap! dissoc answers cookie)
                                                (>! (@answers cookie) data))))
                           as-mix  (fn []
                                     (condp = cmd
                                       :register-to-mix   
                                       (let [[nb-chans create]   (b/cut data 1)
                                             cookies             (repeatedly #(.readUInt32BE (.randomBytes c 4) 0) nb-chans)
                                             sp-answers          (repeatedly chan nb-chans)
                                             chan-info           (get-random-chans nb-chans)
                                             wait-for-all-SPs    (chan)]
                                         (assert (and (= create-len (.-length create))
                                                      (= 2 hs-type)
                                                      (> nb-chans 0))
                                                 "Bad :register-to-mix request")
                                         (go-try
                                           ;; send a request to each mix:
                                           (doseq [[cookie ans sp chan-id cl-id] (map #(concat [%1] [%2] %3) cookies sp-answer chan-info)]
                                             (swap! merge answers {cookie ans})
                                             (go?
                                               (<?? (send-expect-new-client config (:socket ((c/get-all) sp)) cookie {:chan-id chan-in :client-id cl-id :client-pub-id client-pub})
                                                    {:chan ans}) ;; FIXME also check ack value
                                               (>! wait-for-all-SPs :done)))
                                           ;; wait for all mixes to respond
                                           (go? (doseq [i (range nb-chans)]
                                                  (<! wait-for-all-SPs))
                                                ;; send client chan info
                                                (let [[client-id secret created]  (mk-secret-from-create config create)
                                                      answer (chan)]
                                                  (swap! merge answers {ap-cookie answer})
                                                  (<?? (send-register-info-to-client config ap-cookie socket chan-info secret)
                                                       {:chan answer})
                                                  (doseq [[sp chan-id client-id] chan-info]
                                                    (add-client-to-chan sp chan-id client-id {:secret secret :pub client-pub}))))
                                           ;; on error:
                                           #(do (doseq [cookie (cons ap-cookie cookies)]
                                                  (swap! dissoc answers cookie))
                                                (comment send rm client to all sps)
                                                (throw (str "Could not reach SP " sp)))))
                                       :ack                ()
                                       :ack-secret         ()
                                       nil))
                           as-sp   (fn []
                                     (condp = cmd
                                       :expect-new-client  ()
                                       :register-to-sp     ()
                                       nil))
                           as-ap   (fn []
                                     (condp = cmd
                                       :ack-secret         ()
                                       :connect            (let [zone          (-> config :geo-info :zone)
                                                                 net-info      (dir/get-net-info)
                                                                 select-mixes  #(->> net-info seq (map second) (filter %) shuffle) ;; FIXME make this a function
                                                                 mix           (first (select-mixes #(and (= (:role %) :mix) (= (:zone %) zone))))
                                                                 socket        (conn/new :aqua :client mix config  {:connect identity})]
                                                             ;; 1/ connect to mix, wait for client-id & sp-id
                                                             (go? (let [mix-socket (<! socket)]
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
                                                                        (dtls/send-role sp-socket :super-peer)
                                                                        (dtls/send-node-secret sp-socket shared-sec)
                                                                        (c/update-data sp-socket [:sp-auth] (:auth sp)) ;; FIXME: not sure if we'll keep this, but for now it'll do
                                                                        (c/update-data sp-socket [:auth] (-> mix-socket c/get-data :auth)) ;; FIXME: not sure if we'll keep this, but for now it'll do
                                                                        (c/add-id sp-socket (-> mix :auth :srv-id))
                                                                        ;(circ/send-id config sp-socket)
                                                                        (path/init-pools config net-info (:geo-info config) 2 (c/get-data sp-socket))
                                                                        (>! sp-notify [sp-socket mix]))))))
                                       nil))
                           fs  {:super-peer as-sp :app-proxy as-ap :mix as-mix}
                           ]
                            (log/info "Recvd" cmd)
                            (try (loop [[role & roles] (:roles config)] ;; try each role.
                                   (when (nil? role)
                                     (throw "could not process message"))
                                   (if-let [result ((fs role))]
                                     result
                                     (recur roles)))
                                 (catch js/Object e (log/c-error e "Couldn't process SP signalisation."))))) ]
    (go? (loop [msg (<? sp-ctrl)]
           (process msg)
           (recur (<? sp-ctrl))))
    (log/info "Superpeer signaling initialised")))





(comment (defn init [config]
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
                                                                          :let [conn-data (conns k)]
                                                                          :when (= :super-peer (:role conn-data))]
                                                                      [k  conn-data])
                                                [sp-socket sp-data] (first sps)
                                                sp-id               (-> sp-data :auth :srv-id)
                                                sp-clients          (-> sp-data :client-secrets)
                                                sp-clients          (or sp-clients {})
                                                client-id           (first (filter #(not (sp-clients %)) (range (:max-clients-per-channel config))))
                                                client-ntor-id      data]
                                            (when (not= 1 (count sps))
                                              (log/error "wrong number of superpeers" sps))
                                            (assert client-id "could not add client, channel full")
                                            (log/debug "Sending SP id" (b/hx sp-id) "to client" client-id)
                                            (c/update-data sp-socket [:client-secrets] (merge sp-clients {client-id {:secret nil :srv-id client-ntor-id}}))
                                            (c/update-data socket [:future-sp] sp-socket)
                                            (send-client-sp-id config socket client-id sp-id))
                        :mk-secret        (let [[client-id shared-sec created]  (mk-secret-from-create config data)
                                                on-destroy                      (-> socket c/get-data :on-destroy)
                                                sp-socket                       (-> socket c/get-data :future-sp)
                                                client-secrets                  (-> sp-socket c/get-data :client-secrets)]
                                            (c/update-data sp-socket [:client-secrets]
                                                           (merge client-secrets {client-id {:secret shared-sec}}))
                                            (c/update-data socket [:on-destroy] (cons #(c/add-id sp-socket (:srv-id (client-secrets client-id)))
                                                                                      on-destroy))
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
                                                socket        (conn/new :aqua :client mix config  {:connect identity})]
                                            ;; 1/ connect to mix, wait for client-id & sp-id
                                            (go? (let [mix-socket (<! socket)]
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
                                                       (dtls/send-role sp-socket :super-peer)
                                                       (dtls/send-node-secret sp-socket shared-sec)
                                                       (c/update-data sp-socket [:sp-auth] (:auth sp)) ;; FIXME: not sure if we'll keep this, but for now it'll do
                                                       (c/update-data sp-socket [:auth] (-> mix-socket c/get-data :auth)) ;; FIXME: not sure if we'll keep this, but for now it'll do
                                                       (c/add-id sp-socket (-> mix :auth :srv-id))
                                                       ;(circ/send-id config sp-socket)
                                                       (path/init-pools config net-info (:geo-info config) 2 (c/get-data sp-socket))
                                                       (>! sp-notify [sp-socket mix])))))))))]
    (go-loop [msg (<! sp-ctrl)]
      (process msg)
      (recur (<! sp-ctrl)))
    (log/info "Superpeer signaling initialised"))))
