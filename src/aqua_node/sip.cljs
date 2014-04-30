(ns aqua-node.sip
  (:require [cljs.core :as cljs]
            [cljs.nodejs :as node]
            [cljs.core.async :refer [chan <! >!]]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [aqua-node.log :as log]
            [aqua-node.buf :as b]
            [aqua-node.parse :as conv]
            [aqua-node.conns :as c]
            [aqua-node.conn-mgr :as conn]
            [aqua-node.circ :as circ]
            [aqua-node.path :as path]
            [aqua-node.dir :as dir]
            [aqua-node.sip-dir :as sd]
            [aqua-node.sip-helpers :as s])
  (:require-macros [cljs.core.async.macros :as m :refer [go-loop go]]))


;; Call management ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; call data:
;; {:sip-ctrl      sip channel dedicated to that call
;;  :sip-call-id   sip id we're feeding to our SIP client
;;  :state         state of the call}

(def calls (atom {}))
(def sip-to-call-id (atom {}))

(defn add-sip-call [sip-id call-id]
  (swap! sip-to-call-id merge {sip-id call-id}))

(defn add-call [call-id data]
  (when (:sip-call-id data)
    (add-sip-call (:sip-call-id data) call-id))
  (swap! calls merge {call-id data}))

(defn update-data [call-id keys data]
  (swap! calls assoc-in (cons call-id keys) data))

(defn rm-call [call-id]
  (when-let [sip-id (-> call-id (@calls) :sip-call-id)]
    (swap! sip-to-call-id dissoc call-id))
  (swap! calls dissoc call-id))

(defn mk-call-id []
  (-> (node/require "crypto") (.randomBytes 16) (.toString "hex")))


;; SIP sdp creation ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn mk-headers [call-id caller headers uri-to {ip :host}]
  "create headers for generating an invite. Uses the headers that we saved during register."
  (let [method "INVITE"]
    {:uri     uri-to
     :method  method
     :headers {:to               {:uri uri-to}
               :from             {:uri (str/replace (-> headers :from :uri) #"sip:\w+@" (str "sip:" caller "@")) :name caller}
               :call-id          call-id
               ;:via             ; thankfully, sip.js takes care of this one.
               :contact [{:name nil
                          :uri (str "sip:" caller "@" ip ":5060;transport=UDP;ob")
                          :params {}}]
               :content-type     "application/sdp"
               :cseq             {:seq (rand-int 888888), :method method}}})) ;; FIXME (rand-int 0xFFFFFFFF) is what we'd want.

(defn mk-sdp [{ip :host port :port} method & [sdp]] ;; FIXME: ignoring rtpc for now.
  "generates SDP for invite or 200/ok. codec choice is hardcoded for now."
  (let [to-string   #(apply str (interleave % (repeat "\r\n")))]
    (if sdp
      (let [[owner-sess-id owner-sess-version] (next (re-find #"o=.*\b(\d+) (\d+) IN IP" sdp))
            sdp         (str/replace sdp #"o=.*" (str "o=- " owner-sess-id " " (inc (js/parseInt owner-sess-version)) " IN IP4 " ip)) ;; should completely generate these, inc of that thing is only needed on re-offer/re-negotiation.
            sdp         (str/replace sdp #"c=.*" (str "c=IN IP4 " ip))
            sdp         (str/replace sdp #"(a=rtcp:.* IN IP4).*" (str "$1 " ip))
            sdp         (str/replace sdp #"m=audio \d+ .*" (str "m=audio " port " RTP/AVP 98"))
            sdp         (->> sdp str/split-lines (filter #(or (not= "a" (first %))
                                                              (re-find #"X-nat|sendrecv|rtpmap:98|rtcp" %))))]
        {:content (to-string sdp)})
      {:content (to-string ["v=0"
                            (str "o=- 3607434973 3607434973 IN IP4 " ip)
                            "s=pjmedia"
                            (str "c=IN IP4 " ip)
                            "t=0 0"
                            "a=X-nat:0"
                            (str "m=audio " port " RTP/AVP 98 97 99 104 3 0 8 9 96")
                            (str "a=rtcp:4001 IN IP4 " ip) ;; FIXME nothing open for that yet.
                            "a=rtpmap:98 speex/16000"
                            "a=rtpmap:97 speex/8000"
                            "a=rtpmap:99 speex/32000"
                            "a=rtpmap:104 iLBC/8000"
                            "a=fmtp:104 mode=30"
                            "a=rtpmap:3 GSM/8000"
                            "a=rtpmap:0 PCMU/8000"
                            "a=rtpmap:8 PCMA/8000"
                            "a=rtpmap:9 G722/8000"
                            "a=sendrecv"
                            "a=rtpmap:96 telephone-event/8000"
                            "a=fmtp:96 0-15"])})))


;; Manage local SIP client requests ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn create-server [config net-info]
  "Creates the listening service that will process the connected SIP client's requests.
  Application Proxies start this service."
  ;; assuming only one client
  (let [incoming-sip          (chan)
        node-id-len           (-> config :ntor-values :node-id-len)] ;; Constant we'll be using often.
    (go (let [sip             (node/require "sip")
              headers         (atom {})
              uri-to          (atom "")
              ;; Prepare RDV:
              rdv-id          (<! (path/get-path :single)) ;; FIXME we should specify what zone we want our rdv in.
              rdv-data        (circ/get-data rdv-id)
              rdv-ctrl        (:dest-ctrl rdv-data)
              rdv-notify      (:notify rdv-data)
              ;; Outgoinp RDV:
              out-rdv-id      (<! (path/get-path :single)) ;; FIXME we should specify what zone we want our rdv in.
              out-rdv-data    (circ/get-data out-rdv-id)
              out-rdv-ctrl    (:dest-ctrl out-rdv-data)
              out-rdv-notify  (:notify out-rdv-data)
              ;; Prepare MIX SIG:
              mix-id          (<! (path/get-path :one-hop))
              ;; SDP parsing:
              get-sdp-dest    (fn [rq]
                                {:port (->> (:content rq) (re-seq #"(?m)m\=(\w+)\s+(\d+)") first last)
                                 :host (second (re-find #"(?m)c\=IN IP4 ((\d+\.){3}\d+)" (:content rq)))})

              ;; Process SIP logic:
              process     (fn process [rq]
                            (let [nrq          (-> rq cljs/js->clj walk/keywordize-keys)
                                  contact      (-> nrq :headers :contact first)
                                  name         (or (-> contact :name)
                                                   (->> contact :uri (re-find #"sip:(.*)@") second))]
                              ;; debug <--
                              (println)
                              (println :nrq nrq)
                              (println :cid (-> nrq :headers :call-id (@sip-to-call-id) (@calls)))
                              (println :cid @sip-to-call-id (-> nrq :headers :call-id ))
                              ;; debug -->

                              (cond

                                ;; if call is recognised:
                                (-> nrq :headers :call-id (@sip-to-call-id))
                                (go (>! (-> nrq :headers :call-id (@sip-to-call-id) (@calls) :sip-ctrl)
                                        {:nrq nrq :rq rq}))

                                (= (:method nrq) "REGISTER")
                                (let [rdv-data     (circ/get-data out-rdv-id)
                                      select       #(->> net-info seq (map second) (filter %) shuffle)                ;; FIXME -> this should be shared by path.
                                      sip-dir-dest (first (select #(= (:role %) :sip-dir)))
                                      sip-dir-dest (merge sip-dir-dest {:dest sip-dir-dest})                          ;; FIXME will get rid of :dest someday.
                                      ack          (.makeResponse sip rq 200 "OK")]                                   ;; prepare sip successful answer
                                  (if (:auth sip-dir-dest)
                                    (go (>! out-rdv-ctrl sip-dir-dest)                                                ;; --- RDV: connect to sip dir to send register
                                        (<! out-rdv-notify)                                                           ;; wait until connected to send
                                        (sd/register config name out-rdv-id rdv-id (-> rdv-data :rdv :auth :srv-id))  ;; send register to dir, ack to sip client:
                                        (sd/register-to-mix config name mix-id)                                       ;; register our sip user name (needed for last step of incoming rt circs, without giving our ip to caller)
                                        (.send sip ack)                                                               ;; --- SIP: answer sip client, successfully registered.
                                        (reset! uri-to  (-> contact :uri))                                            ;; save uri & headers for building invite later:
                                        (reset! headers (-> ack cljs/js->clj walk/keywordize-keys :headers)))
                                    (do (log/error "Could not find SIP DIR in aqua network")
                                        ;; debug <--
                                        (doall (->> net-info seq (map second) (map #(dissoc % :auth)) (map println)))
                                        ;; debug -->
                                        (.send sip (.makeResponse sip rq "404" "NOT FOUND")))))

                                (= (:method nrq) "SUBSCRIBE")
                                (condp = (-> nrq :headers :event)
                                  "presence.winfo"  (do (println (:event nrq))
                                                        ;; and register the gringo.
                                                        (.send sip (.makeResponse sip rq 200 "OK")))
                                  "message-summary" (do (println :200 :OK) (.send sip (.makeResponse sip rq 200 "OK")))
                                  (.send sip (.makeResponse sip rq 501 "Not Implemented")))

                                (= (:method nrq) "PUBLISH")
                                (go (if (= "presence" (-> nrq :headers :event))
                                      (let [parse-xml (-> (node/require "xml2js") .-parseString)
                                            xml       (chan)]
                                        ;; debug <--
                                        (parse-xml (:content nrq) #(go (println %2) (>! xml %2)))
                                        (println (-> (<! xml) cljs/js->clj walk/keywordize-keys))
                                        ;; debug -->
                                        (.send sip (.makeResponse sip rq 200 "OK")))
                                      (do (log/error "SIP: Unsupported PUBLISH event:" (-> nrq :headers :event))
                                          (.send sip (.makeResponse sip rq 501 "Not Implemented")))))

                                (= (:method nrq) "OPTIONS")
                                (.send sip (.makeResponse sip rq 200 "OK"))

                                ;; Take care of invite: SIP client sent an invite.
                                ;; this means we are the caller. The following will find the callee & initiate call:
                                (= (:method nrq) "INVITE")
                                (go (let [sip-call-id      (-> nrq :headers :call-id)
                                          call-id          (mk-call-id)
                                          sip-ctrl         (chan)
                                          callee-name      (second (re-find #"sip:(.*)@" (:uri nrq)))                     ;; get callee name
                                          sdp              (:content nrq)]
                                      (add-call call-id {:sip-ctrl sip-ctrl :sip-call-id sip-call-id :state :ringing})
                                      (sd/query config callee-name out-rdv-id call-id)                                    ;; query for callee's rdv
                                      (log/info "SIP:" "intiating call" call-id "to" callee-name)
                                      (let [query-reply-rdv      (<! sip-ctrl)]                                           ;; get query reply
                                        (if (= :error (-> query-reply-rdv :sip-rq (.readUInt8 0) s/to-cmd))               ;; FIXME: assert this instead.
                                          (do (log/error "Query for" callee-name "failed.")
                                              (.send sip (.makeResponse sip rq 404 "NOT FOUND")))
                                          (let [callee-rdv       (:data query-reply-rdv)
                                                callee-rdv-cid   (.readUInt32BE callee-rdv 0)
                                                callee-rdv-id    (.slice callee-rdv 4 (+ 4 node-id-len))
                                                ;;j callee-rdv       (net-info [(:host callee-rdv-dst) (:port callee-rdv-dst)])             ;; FIXME: this is fine for PoC, but in the future net-info will be a atom and will be updated regularly.
                                                sdp-dest         (get-sdp-dest nrq)]                                                    ;; parse sdp to find where the SIP client expects to receive incoming RTP.
                                            (assert callee-rdv-id (str "SIP: Could not find callee's mix:" name))
                                            (update-data call-id [:peer-rdv] callee-rdv-cid)
                                            (.send sip (.makeResponse sip rq 100 "TRYING"))                                             ;; inform the SIP client we have initiated the call.
                                            (when (not= callee-rdv-id (-> out-rdv-id circ/get-data :rdv :auth :srv-id)) ;; if we are using the same RDV we don't extend to it, it would fail (can't reuse the same node in a circ)
                                              (>! out-rdv-ctrl callee-rdv-id)
                                              (<! out-rdv-notify)
                                              (log/debug "Extended to callee's RDV"))
                                            ;; FIXME: once this works we'll add relay-sip extend to callee so rdv can't read demand,
                                            ;; and client can match our HS against the keys he has for his contacts.
                                            (circ/relay-sip config out-rdv-id :f-enc (b/cat (-> :invite s/from-cmd b/new1)              ;; Send invite to callee. include our rdv-id so callee can send sig to us.
                                                                                            (b/new call-id)
                                                                                            b/zero
                                                                                            (b/new4 callee-rdv-cid)
                                                                                            (-> @path/chosen-mix :auth :srv-id)
                                                                                            (b/new name)
                                                                                            b/zero
                                                                                            (-> config :auth :aqua-id :id)
                                                                                            (-> config :auth :aqua-id :pub)))
                                            (let [reply                  (<! sip-ctrl)]                                                 ;; and now we wait for ack
                                              (assert (= (:cmd reply) :ack) (str "Something went wrong with call" call-id))
                                              (.send sip (.makeResponse sip rq 180 "RINGING"))                                          ;; we received an answer (non error) from callee, inform our SIP client that callee's phone is ringing
                                              (let [[mix-id id pub]      (b/cut (:data reply) node-id-len (* 2 node-id-len))
                                                    rtp-circ             (<! (path/get-path :rt))
                                                    rtp-data             (circ/get-data rtp-circ)
                                                    rtp-ctrl             (:dest-ctrl rtp-data)
                                                    rtp-notify           (:notify rtp-data)
                                                    [_ local-port]       (<! (path/attach-local-udp-to-simplex-circs config             ;; create local udp socket. in-circ will be sent to sdp-dest, the SIP client's RTP media. out-circ is where data from the sip client will be sent through to callee.
                                                                                                                     (go (:circ-id reply))
                                                                                                                     (go rtp-circ)
                                                                                                                     (go sdp-dest)))]
                                                (>! rtp-ctrl [mix-id {:auth {:pub-B pub :srv-id id} :name callee-name}])   ;; connect to callee's mix & then to callee.
                                                (<! rtp-notify)                                                                                          ;; wait until ready.
                                                (log/info "SIP: RT circuit ready for outgoing data on:" call-id)
                                                (update-data call-id [:rt] {:in (:circ-id reply) :out rtp-circ}) ;; FIXME if needed add chans.
                                                (circ/relay-sip config rtp-circ :f-enc (b/cat (-> :ackack s/from-cmd b/new1)                             ;; send final ack to callee, with call-id so it knows that this circuit will be used for our outgoing (its incoming) RTP.
                                                                                              (b/new call-id)
                                                                                              b/zero))
                                                (log/info "SIP: sent ackack, ready for relay on" call-id)
                                                ;; debug <--
                                                (println (merge (assoc-in (s/to-clj (.makeResponse sip rq 200 "OK")) [:headers :content-type] "application/sdp")
                                                                (mk-sdp {:host (:local-ip config) :port local-port} :ack sdp)))
                                                ;; debug -->
                                                (.send sip (s/to-js (merge (assoc-in (s/to-clj (.makeResponse sip rq 200 "OK"))                          ;; Send our client a 200 OK, with out-circ's listening udp as "callee's" dest (what caller thinks is the callee actually is aqua).
                                                                                     [:headers :content-type]
                                                                                     "application/sdp") ;; inelegant, testing.
                                                                           (mk-sdp {:host (:local-ip config) :port local-port} :ack sdp)))
                                                       process)
                                                (loop [{nrq :nrq rq :rq}  (<! sip-ctrl)]                                                                 ;; loop on sip messages with this call-id until we receive a BYE.
                                                  (cond (= "ACK" (:method nrq)) (println "request ack, ok, cool")
                                                        (= "BYE" (:method nrq)) (println "bye bye.") ;; and tear down. and 200 OK. and don't recur.
                                                        (< 200 (:status nrq))   (println "error.")
                                                        :else                   (println "what?"))
                                                  (recur (<! sip-ctrl))))))))))

                                :else (log/error "Unsupported sip method" (:method nrq)))))]

          ;; Initialisation of create-server: prepare RDV, sip signalisation incoming channel.
          (>! rdv-ctrl :rdv)
          (>! out-rdv-ctrl :rdv)
          (circ/update-data rdv-id [:sip-chan] incoming-sip)
          (circ/update-data out-rdv-id [:sip-chan] incoming-sip)
          (.start sip (cljs/clj->js {:protocol "UDP"}) process)

          ;; FIXME: sip-ch is general and dispatches according to call-id to sub channels.
          (go-loop [query (<! incoming-sip)]
            (let [cmd           (-> query :sip-rq (.readUInt8 0) s/to-cmd)
                  [call-id msg] (-> query :sip-rq s/get-call-id)
                  call-chan     (-> call-id (@calls) :sip-ctrl)]
              (log/info "SIP: call-id:" call-id "-" cmd)
              (cond
                ;; try to dispatch to an existing call:
                call-chan
                (go (>! call-chan (merge query {:data msg :call-id call-id :cmd cmd})))

                ;; if it's an invite, initiate call. We are the callee.
                (= cmd :invite)
                (go (let [caller-rdv-id  (.readUInt32BE msg 0)
                          [_ mix-id msg] (b/cut msg 4 (+ 4 node-id-len))
                          [caller msg]   (b/cut-at-null-byte msg)
                          [id pub]       (b/cut msg node-id-len)
                          caller         (.toString caller)
                          sip-ctrl       (chan)
                          rtp-circ       (<! (path/get-path :rt))
                          rtp-data       (circ/get-data rtp-circ)
                          rtp-ctrl       (:dest-ctrl rtp-data)
                          rtp-notify     (:notify rtp-data)
                          rtp-incoming   (chan)
                          sdp-dest       (chan)
                          [_ local-port] (<! (path/attach-local-udp-to-simplex-circs config                  ;; our local udp socket for exchanging RTP with local sip client. rtp-incoming is caller's RTP which we'll route to the @/port which will be given in 200/OK after sending invite to it.
                                                                                     rtp-incoming
                                                                                     (go rtp-circ)           ;; The invite we'll send will have our local sockets @/port as media, so sip client sends us RTP, we'll route it through rtp-circ.
                                                                                     sdp-dest))
                          local-dest     {:host (:local-ip config) :port local-port}]
                      (log/info "SIP: invited by" caller "- Call-ID:" call-id "Rdv" caller-rdv-id)
                      (add-call call-id {:sip-ctrl sip-ctrl, :sip-call-id call-id, :state :ringing, :peer-rdv caller-rdv-id :rt {:out rtp-circ}}) ;; FIXME add rtp rt
                      (.send sip (s/to-js (merge (mk-headers call-id caller @headers @uri-to local-dest)     ;; Send our crafted invite with local udp port as "caller's" media session
                                                 (mk-sdp local-dest :invite)))
                             process)
                      (loop [{user-answer :nrq rq :rq}  (<! sip-ctrl)]                                       ;; loop until we receive a 200/OK with SDP
                      (println 0.5)
                        (if (not= (:status user-answer) 200)
                          (recur (<! sip-ctrl)) ;; FIXME should only loop if status < 200, and destroy session if >.
                          (go (>! sdp-dest (get-sdp-dest user-answer)))))
                      (println :out-of-loop)
                      (>! rtp-ctrl [mix-id {:auth {:pub-B pub :srv-id id} :name caller}])   ;; connect to caller's mix & then to caller.
                      (println 1)
                      (<! rtp-notify)                                                                                     ;; wait for answer.
                      (println 2)
                      (log/info "SIP: RT circuit ready for call" call-id)
                      (circ/relay-sip config rtp-circ :f-enc (b/cat (-> :ack s/from-cmd b/new1)               ;; Send ack to caller, with our mix's coordinates so he can create an rt-path to us to send rtp.
                                                                    (b/new call-id)
                                                                    b/zero
                                                                    (-> @path/chosen-mix :auth :srv-id)
                                                                    (-> config :auth :aqua-id :id)
                                                                    (-> config :auth :aqua-id :pub)))
                      (let [{cmd :cmd, rtp-in :circ-id}    (<! sip-ctrl)]                                     ;; Wait for caller's rt path's first message.
                        (assert (= :ackack cmd) "Something went wrong")
                        (>! rtp-incoming rtp-in)                                                              ;; inform attach-local-udp-to-simplex-circs that we have incoming-rtp to attach to socket.
                        (update-data call-id [:rt :in] rtp-in))
                      (log/info "SIP: got ackack, ready for relay on" call-id)
                      ;; loop waiting for bye.
                      ))

                :else
                (log/info "SIP: incoming message with unknown call id:" call-id "-- dropping."))
              (recur (<! incoming-sip))))

            (log/info "SIP proxy listening on default UDP SIP port")))
    incoming-sip))


;; replace all uris, tags, ports by hc defaults.
;; {method REGISTER
;;  uri sip:localhost                                                                                  ; URI
;;  version 2.0
;;  headers {contact [{name "aqua"
;;                     uri sip:aqua@127.0.0.1:18750;transport=udp;registering_acc=localhost            ; URI
;;                     params {expires 600}}]
;;           user-agent Jitsi2.5.5104Linux                                                             ; becomes aqua-version.
;;           call-id 659987c14fca0876dc89d5fa4ec715e5@0:0:0:0:0:0:0:0                                  ; this changes.
;;           from {name "aqua"
;;                 uri sip:aqua@localhost                                                              ; URI
;;                 params {tag 81429e45}}                                                              ; tag.
;;           via [{version 2.0
;;                 protocol UDP
;;                 host 127.0.0.1                                                                      ; remove this. remove via entirely?
;;                 port 18750
;;                 params {branch z9hG4bK-313432-de5cc56153489d6de96fa6deeabaab8f
;;                         received 127.0.0.1}}]                                                       ; and this
;;           expires 600
;;           max-forwards 70
;;           content-length 0
;;           to {name "aqua"
;;               uri sip:aqua@localhost
;;               params {}}
;;           cseq {seq 1
;;                 method REGISTER}}
;;  content }

;; media session.
;;                         B2BUA
;;    Ann                  Server                 Bob
;;     |                    | |                    |
;;     |      INVITE     F1 | |                    |
;;     |------------------->| |                    |
;;     |    100 Trying   F2 | |                    |
;;     |<-------------------| |       INVITE    F3 |
;;     |                    | |------------------->|
;;     |                    | |    100 Trying   F4 |
;;     |                    | |<-------------------|
;;     |                    | |    180 Ringing  F5 |
;;     |   180 Ringing   F6 | |<-------------------|
;;     |<-------------------| |                    |
;;     |                    | |       200 OK    F7 |
;;     |      200 OK     F8 | |<-------------------|
;;     |<-------------------| |         ACK     F9 |
;;     |         ACK    F10 | |------------------->|
;;     |------------------->| |                    |
;;     |      RTP Media     | |      RTP Media     |
;;     |<==================>| |<==================>|
;;     |        BYE     F11 | |                    |
;;     |------------------->| |        BYE     F12 |
;;     |      200 OK    F13 | |------------------->|
;;     |<-------------------| |       200 OK   F14 |
;;     |                    | |<-------------------|
;;     |                    | |                    |


;; presence stuff

;; (comment
;; {:method PUBLISH
;;  :uri sip:me@localhost
;;  :version 2.0
;;  :headers {:via [{:version 2.0 :protocol UDP :host 127.0.0.1 :port 9669
;;                   :params {:branch z9hG4bK-373037-96f223ef93a23586ffc02df09af2cc53
;;                            :received 127.0.0.1}}]
;;            :content-type application/pidf+xml
;;            :expires 3600
;;            :max-forwards 70
;;            :event presence
;;            :content-length 401
;;            :to {:name "me"
;;                 :uri sip:me@localhost
;;                 :params {}}
;;            :cseq {:seq 2
;;                   :method PUBLISH}
;;            :contact [{:name "me"
;;                       :uri sip:me@127.0.0.1:9669;transport=udp;registering_acc=localhost
;;                       :params {}}]
;;            :user-agent Jitsi2.5.5104Linux
;;            :call-id a091313efa9d8c5f2c7471c2952d21de@0:0:0:0:0:0:0:0
;;            :from {:name "me"
;;                   :uri sip:me@localhost
;;                   :params {:tag c4c41a24}}}
;;  :content <?xml version="1.0" encoding="UTF-8" standalone="no"?><presence xmlns="urn:ietf:params:xml:ns:pidf" xmlns:dm="urn:ietf:params:xml:ns:pidf:data-model" xmlns:rpid="urn:ietf:params:xml:ns:pidf:rpid" entity="sip:me@localhost"><dm:personid="p2856"><rpid:activities/></dm:person><tuple id="t5430"><status><basic>open</basic></status><contact>sip:me@localhost</contact><note>Online</note></tuple></presence>})
;;
;;
;; (comment
;;   {:method SUBSCRIBE
;;    :uri sip:me@localhost
;;    :version 2.0
;;    :headers {:via [{:version 2.0
;;                     :protocol UDP
;;                     :host 127.0.0.1
;;                     :port 55590
;;                     :params {:branch z9hG4bK-373037-cae0467c2ff1881dad572e4d6c2c8c93
;;                              :received 127.0.0.1}}]
;;              :expires 3600
;;              :max-forwards 70
;;              :event message-summary
;;              :content-length 0
;;              :to {:name "me"
;;                   :uri sip:me@localhost
;;                   :params {}}
;;              :cseq {:seq 1
;;                     :method SUBSCRIBE}
;;              :contact [{:name "me"
;;                         :uri sip:me@127.0.0.1:55590;transport=udp;registering_acc=localhost
;;                         :params {}}]
;;              :user-agent Jitsi2.5.5104Linux
;;              :accept application/simple-message-summary
;;              :call-id 9fe891081a73da36fd0d1984409fedb5@0:0:0:0:0:0:0:0
;;              :from {:name "me"
;;                     :uri sip:me@localhost
;;                     :params {:tag ba13e9ef}}}
;;

;; {:method INVITE
;;  :uri sip:lol@172.17.0.7
;;  :version 2.0
;;  :headers {:supported " replaces, 100rel, timer, norefersub,"
;;            :via [{:version 2.0
;;                   :protocol UDP
;;                   :host 172.17.42.1
;;                   :port 5555
;;                   :params {:rport 5555
;;                            :branch z9hG4bKPjb3bfc8f5-ced1-42ce-ade2-495d7bad0c60
;;                            :received 172.17.42.1}}]
;;            :content-type "application/sdp"
;;            :max-forwards 70
;;            :content-length 230
;;            :to {:name nil
;;                 :uri "sip:lol@172.17.0.7"
;;                 :params {}}
;;            :cseq {:seq 9058
;;                   :method INVITE}
;;            :session-expires 1800
;;            :contact [{:name nil
;;                       :uri "sip:aoeu1@172.17.42.1:5555;transport=UDP;ob"
;;                       :params {}}]
;;            :user-agent "PJSUA v1.14.0 Linux-3.13.5/x86_64/glibc-2.17 "
;;            :allow " PRACK, INVITE, ACK, BYE, CANCEL, UPDATE, SUBSCRIBE, NOTIFY, REFER, MESSAGE, OPTIONS,"
;;            :call-id "4e6eb96d-c8e5-482b-ac12-f0cb9076655b"
;;            :from {:name nil
;;                   :uri "sip:aoeu1@172.17.0.7"
;;                   :params {:tag 676d64bf-a738-48fe-9b6b-6c108f484edd}}
;;            :min-se 90}
;;  :content "v=0
;;           o=- 3606712585 3606712585 IN IP4 139.19.186.120
;;           s=pjmedia
;;           c=IN IP4 139.19.186.120
;;           t=0 0
;;           a=X-nat:0
;;           m=audio 4000 RTP/AVP 96
;;           a=rtcp:4001 IN IP4 139.19.186.120
;;           a=sendrecv
;;           a=rtpmap:96 telephone-event/8000
;;           a=fmtp:96 0-15" }

