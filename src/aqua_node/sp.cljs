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
   6  :request-active-channel
   7  :activated-channel
   })

(def from-cmd (clj-set/map-invert to-cmd))

(def SP-channel-info (atom {}))

(defn add-client-to-chan [sp chan client-id client-info]
  (swap! SP-channel-info assoc-in [sp chan client-id] client-info))

(defn rm-from-SPs [& keys]
  (swap! SP-channel-info #(apply dissoc % keys)))

(defn get-random-chans [config nb-chans]
  ;; naive, won't scale well. maybe not make things this random?
  (take nb-chans (map #(first (shuffle %))
                      (shuffle (partition-by first
                                             (for [sp      (keys @SP-channel-info)
                                                   chan-id (range (-> config :SP :max-chans-per-sp))
                                                   cl-id   (range (-> config :SP :max-clients-per-channel))
                                                   :when   (nil? (get-in @SP-channel-info [sp chan-id cl-id]))]
                                               [sp chan-id cl-id]))))))

(defn get-inactive-chans-for-client [client-id]
  (for [sp      (keys @SP-channel-info)
        chan-id (-> (@SP-channel-info) sp keys)
        cl-id   (-> (@SP-channel-info) sp chan-id keys)
        :let    [node-id (-> (@SP-channel-info) sp chan-id cl-id :pub)
                 state   (-> (@SP-channel-info) sp chan-id :state)]
        :when   (and (= client-id node-id)
                     (= state :inactive))]
    [sp chan-id cl-id]))

(defn update-chan-info [keys subdata]
  (swap! SP-channel-info assoc-in keys subdata))


;; sent by AP to mix:

(defn send-register-to-mix [config mix-socket auth create]
  (log/debug :FIXME :mk-secret (.-length create))
  (circ/send-sp config mix-socket (b/cat (-> :register-to-mix from-cmd b/new1)
                                         (-> config :SP :nb-channels b/new1)
                                         (-> config :auth :aqua-id b/new)
                                         ;(b/new1 client-id)
                                         (b/new2 2) ;; type of hs
                                         (-> create .-length b/new2)
                                         create)))

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

(defn send-activated-channel [config socket cookie [sp-id chan-id client-id]]
  (if sp-id
    (circ/send-sp config socket (b/cat (-> :actived-channel from-cmd b/new1)
                                       (b/new4 cookie)
                                       (b/new sp-id)
                                       (b/new1 chan-id)
                                       (b/new1 client-id)))
    (circ/send-sp config socket (b/cat (-> :actived-channel from-cmd b/new1)
                                       (b/new4 cookie)))))

;; sent by mix to SP:

(defn send-expect-new-client [config sp-socket cookie {chan-id :chan-id client-id :client-id client-pub-id :client-pub-id}]
  (circ/send-sp config sp-socket (b/cat (-> :expect-new-client from-cmd b/new1)
                                        (b/new4 cookie)
                                        (b/new1 chan-id)
                                        (b/new1 client-id)
                                        client-pub-id)))

;; sent by SP to mix:

(defn send-ack [config socket cookie ack-value]
  (circ/send-sp config socket (b/cat (-> :ack from-cmd b/new1)
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

(defn send-register-to-sp [config sp-socket]
  (circ/send-sp config sp-socket (b/cat (-> :register-to-sp from-cmd b/new1)
                                        (-> config :auth :aqua-id :pub))))

(defn send-request-active-channel [config sp-socket cookie]
  (circ/send-sp config sp-socket (b/cat (-> :request-active-channel from-cmd b/new1)
                                        (-> config :auth :aqua-id :pub)
                                        (b/new4 cookie))))

;; init:

(defn init [config]
  (let [[sp-ctrl sp-notify] (:sp-chans config)
        answer      (chan)
        answers     (atom {})
        config      (merge config [sp-ctrl sp-notify])
        key-len     (-> config :enc :key-len)
        node-id-len (-> config :ntor-values :node-id-len)

        process
        (fn [{cmd :cmd data :data socket :socket}]
          (let [cmd       (if (number? cmd) (to-cmd cmd) cmd)
                mk-cookie #(-> (node/require "crypto") (.randomBytes 4) (.readUInt32BE 0))

                fwd-ack (fn [] ;; {cmd :cmd data :data socket :socket}
                          (let [cookie (.readUInt32BE data 4)]
                            (log/debug "FIXME forwarding cookie" cookie)
                            (go? (swap! dissoc answers cookie)
                                 (>! (@answers cookie) data))))

                as-mix
                (fn []
                  (condp = cmd

                    :register-to-mix
                    (let [[_ client-pub-id create] (b/cut 5 (+ 5 node-id-len))
                          client-pub-id       (b/hx client-pub-id)
                          nb-chans            (.readUInt8 data 0)
                          hs-type             (.readUInt16BE data 1)
                          create-len          (.readUInt16BE data 3)
                          create              (.slice data 5)
                          cookies             (repeatedly mk-cookie nb-chans)
                          ap-cookie           (mk-cookie)
                          sp-answers          (repeatedly chan nb-chans)
                          chan-info           (get-random-chans config nb-chans)
                          wait-for-all-SPs    (chan)]
                      (log/debug "Got register to mix from" client-pub-id)
                      (assert (and ;(= create-len (.-length create))
                                   (= 2 hs-type)
                                   (> nb-chans 0))
                              "Bad :register-to-mix request")
                      (go-try
                        ;; send a request to each mix:
                        (doseq [[cookie ans sp chan-id cl-id] (map #(concat [%1] [%2] %3) cookies sp-answers chan-info)]
                          (swap! merge answers {cookie ans})
                          (go?
                            (log/debug "Sent expect client to" sp "w/ cookie" cookie)
                            (<?? (send-expect-new-client config (:socket ((c/get-all) sp)) cookie {:chan-id chan-id :client-id cl-id :client-pub-id client-pub-id})
                                 {:chan ans :secs 30}) ;; FIXME also check ack value
                            (>! wait-for-all-SPs :done)))
                        ;; wait for all mixes to respond
                        (go? (doseq [i (range nb-chans)]
                               (<! wait-for-all-SPs))
                             (log/debug "got ack from all SPs")
                             ;; send client chan info
                             (let [[client-id secret created]  (mk-secret-from-create config create)
                                   answer     (chan)]
                               (swap! merge answers {ap-cookie answer})
                               (log/debug "sent reg info to client w/ cookie" ap-cookie)
                               (<?? (send-register-info-to-client config ap-cookie socket chan-info secret)
                                    {:chan answer :secs 30})
                               (doseq [[sp chan-id client-id] chan-info]
                                 (add-client-to-chan sp chan-id client-id {:secret secret :node-id client-pub-id}))))
                        ;; on error:
                        #(do (doseq [cookie (cons ap-cookie cookies)]
                               (swap! dissoc answers cookie))
                             (comment send rm client to all sps)
                             (throw "Error registering to mix"))))

                    :request-active-channel
                    (let [client-node-id (b/hx (.slice data 0 node-id-len))
                          cookie         (.readUInt32BE data node-id-len)
                          channel        (first (get-inactive-chans-for-client client-node-id))
                          answer         (chan)]
                      (log/debug "got active chan request")
                      (if channel
                        (go? (swap! merge answers {cookie answer})
                             (update-chan-info (cons (take 2 channel) :state) :active)
                             (log/debug "sending active channel to" client-node-id "w/ cookie" cookie)
                             (dtls/send fixme)
                             (<?? (send-activated-channel config socket cookie channel) 
                                  {:mins 1 :chan answer :on-error #(do (update-chan-info (cons (take 2 channel) :state) :inactive)
                                                                       (swap! dissoc answers cookie)
                                                                       (comment :FIXME dtls/send inactive))}))
                        (go? (send-activated-channel config socket cookie nil))))

                    :ack                (fwd-ack)
                    :ack-secret         (fwd-ack)
                    nil))

                as-sp
                (fn []
                  (condp = cmd
                    :expect-new-client  (let [cookie        (.readUInt32BE data 0)
                                              chan-id       (.readUInt8 data 4)
                                              client-id     (.readUInt8 data 5)
                                              client-pub-id (b/hx (.slice data 6))]
                                          (dtls/send expect client)
                                          ;; we should wait for answer
                                          (log/debug "got expect client" client-pub-id "on" chan-id "/" client-id "w/ cookie" cookie)
                                          (send-ack config socket cookie 1) ;; we don't check ack value for now
                                          (add-client-to-chan :me chan-id client-id {:socket socket}))
                    :register-to-sp     identity ;(let [pub data]) ;; we'll need to auth the client with a HS, for now we don't care
                    nil))

                as-ap  
                (fn as-ap []
                  (condp = cmd

                    :connect
                    (let [zone          (-> config :geo-info :zone)
                          net-info      (dir/get-net-info)
                          select-mixes  #(->> net-info seq (map second) (filter %) shuffle) ;; FIXME make this a function
                          mix           (first (select-mixes #(and (= (:role %) :mix) (= (:zone %) zone))))
                          socket        (conn/new :aqua :client mix config  {:connect identity})
                          mix-answer    (chan)
                          cookie        (mk-cookie)]
                      ;; 1/ connect to mix, wait for sp-ids
                      (go? (let [mix-socket    (<! socket)
                                 [auth create] (hs/client-init config (:auth mix))]
                             ;; this will be replaced with send reg
                             (circ/send-id config mix-socket)
                             (log/debug :FIXME "sent id w/ cookie" cookie)
                             (swap! merge answers {cookie mix-answer})
                             (let [SPs (<?? (send-register-to-mix config mix-socket (:auth mix) create)
                                            {:chan mix-answer :mins 3})
                                   [cookie len secret chans] (b/cut SPs 4 6 (+ 6 key-len))
                                   secret          (hs/client-finalise auth secret key-len)
                                   len-chans       (.-length chans)
                                   read-chan-uint8 (b/mk-readers chans)]
                               (log/debug "got mix answer")
                               (assert (= (/ len-chans (+ 2 node-id-len)) 42)) ;; FIXME: find this in config
                               ;; 2/ connect to each SP
                               (doseq [i (range 0 len-chans (+ 2 node-id-len))
                                       :let [sp-len    (+ i node-id-len)
                                             sp-id     (b/hx (.slice chans i sp-len))
                                             sp-data   (first (select-mixes #(b/b= sp-id (-> % :auth :srv-id))))
                                             chan-id   (read-chan-uint8 sp-len)
                                             client-id (read-chan-uint8 (+ sp-len 1))
                                             sp-socket (conn/new :aqua :client sp-data config {:connect identity})
                                             sp-socket (<! sp-socket)
                                             ]]
                                 (log/info "Connected to SP" sp-id)
                                 (add-client-to-chan sp-id chan-id client-id {:socket  sp-socket
                                                                              :state   :inactive})
                                 (send-register-to-sp config socket) ;; we want an ack here too
                                 ;; update the socket info for dtls-handler:
                                 (dtls/send-role sp-socket :super-peer) ;; FIXME add chan & client IDs.
                                 (dtls/send-node-secret sp-socket secret)
                                 ;; we'll need to check this, there's a hack somewhere.
                                 (c/update-data sp-socket [:sp-auth] (:auth sp-data)) ;; FIXME: not sure if we'll keep this, but for now it'll do
                                 (c/update-data sp-socket [:auth] (-> mix-socket c/get-data :auth)) ;; FIXME: not sure if we'll keep this, but for now it'll do
                                 (c/add-id sp-socket (-> mix :auth :srv-id))
                                 ;;(circ/send-id config sp-socket)
                                 ;(path/init-pools config net-info (:geo-info config) 2 (c/get-data sp-socket))
                                 ;(>! sp-notify [sp-socket mix])
                                 (when (-> config :dummy :active)
                                   (as-ap {:cmd :request-active-channel})))))))

                    :request-active-channel
                    (go-try (let [mix-answer (chan)
                                  cookie     (mk-cookie)]
                              (swap! merge answers {cookie mix-answer})
                              (let [chan-info (loop [[sp-socket & sockets] (shuffle (filter #(:sp-auth %) (c/get-all)))] ;; we're shuffling so clients don't all request on the same channel
                                                (log/debug "Trying to connect to mix through SP" sp-socket)
                                                (if sp-socket
                                                  (let [timeout (chan)]
                                                    (js/setTimeout #(go (>! timeout :timeout)) (* 30 1000))
                                                    (send-request-active-channel config sp-socket cookie)
                                                    (let [chan-info (alts! [mix-answer timeout])]
                                                      (if (= :timeout chan-info)
                                                        (recur sockets)
                                                        chan-info)))))
                                    ;_ (assert (.-length...) "No available channels")
                                    [sp-id chan-info]  (b/hx (b/cut chan-info node-id-len))
                                    chan-id    (.readUInt8 chan-info 0)
                                    client-id  (.readUInt8 chan-info 1)
                                    chan-data  (-> (@SP-channel-info) sp-id chan-id client-id)
                                    sp-socket  (:socket chan-data)]
                                (assert chan-data "Mix tried to use inexistent channel")
                                (update-chan-info [sp-id chan-id client-id :state] :active)
                                (dtls/send fixme)
                                ;; FIXME check on sp-notify --> why does it need mix?
                                (path/init-pools config (dir/get-net-info) (:geo-info config) 2 (c/get-data sp-socket))
                                (send-ack config sp-socket cookie 1) ;; we don't check ack value for now
                                (>! sp-notify [sp-socket mix])))
                            #(log/error "Could not contact mix through available channels"))

                    :activated-channel       (fwd-ack)
                    :register-info-to-client (fwd-ack)

                    nil))

                process-fns  {:super-peer as-sp :app-proxy as-ap :mix as-mix}]

            (log/info "Recvd" cmd)

            ;; this is inelegant, I must have been tired.
            (try (loop [[role & roles] (:roles config)] ;; try to process command with each role.
                   (when (nil? role)
                     (throw "could not process message"))
                   (if-let [result ((process-fns role))]
                     result
                     (recur roles)))
                 (catch js/Object e (log/c-error e "Couldn't process SP signalisation.")))))]

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
                                                           shared-sec (hs/client-finalise auth (.slice payload 2) key-len)
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
