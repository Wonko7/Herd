(ns aqua-node.sip
  (:require [cljs.core :as cljs]
            [cljs.nodejs :as node]
            [cljs.core.async :refer [chan <! >!]]
            [clojure.walk :as walk]
            [aqua-node.log :as log]
            [aqua-node.buf :as b]
            [aqua-node.parse :as conv]
            [aqua-node.conns :as c]
            [aqua-node.conn-mgr :as conn]
            [aqua-node.circ :as circ]
            [aqua-node.path :as path]
            [aqua-node.dir :as dir])
  (:require-macros [cljs.core.async.macros :as m :refer [go-loop go]]))

(declare register-to-mix register query dir mk-invite)


;; Manage local SIP client requests ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn create-server [{sip-dir :remote-sip-dir :as config} net-info things]
  "Creates the listening service that will process the connected SIP client's requests.
  Application Proxies start this service."
  ;; assuming only one client
  (go (let [sip         (node/require "sip")
            headers     (atom {})
            uri-to      (atom "")
            ;; Prepare RDV:
            rdv-id      (<! (path/get-path :single)) ;; FIXME we should specify what zone we want our rdv in.
            rdv-data    (circ/get-data rdv-id)
            rdv-ctrl    (:dest-ctrl rdv-data)
            rdv-notify  (:notify rdv-data)
            ;; Prepare MIX SIG:
            mix-id      (<! (path/get-path :one-hop))

            ;; Process SIP logic:
            process     (fn [rq]
                          (let [nrq       (-> rq cljs/js->clj walk/keywordize-keys)
                                contact   (-> nrq :headers :contact first)
                                name      (or (-> contact :name)
                                              (->> contact :uri (re-find #"sip:(.*)@") second))]
                            ;; debug <--
                            (println)
                            (println :nrq nrq)
                            ;; debug -->
                            (condp = (:method nrq)

                              "REGISTER"  (let [rdv-data     (circ/get-data rdv-id)
                                                sip-dir-dest (net-info [(:host sip-dir) (:port sip-dir)]) ;; find sip dir from config for now. will change that.
                                                sip-dir-dest (merge sip-dir-dest {:dest sip-dir-dest})    ;; FIXME will get rid of :dest someday.
                                                ack          (.makeResponse sip rq 200 "OK")]             ;; prepare sip successful answer
                                            (if (:auth sip-dir-dest)
                                              (go (>! rdv-ctrl sip-dir-dest)                      ;; --- RDV: connect to sip dir to send register
                                                  (<! rdv-notify)                                 ;; wait until connected to send
                                                  (register config name rdv-id (:rdv rdv-data))   ;; send register to dir, ack to sip client:
                                                  (register-to-mix config name mix-id)            ;; register our sip user name (needed for last step of incoming rt circs, without giving our ip to caller)
                                                  (.send sip ack)                                 ;; --- SIP: answer sip client, successfully registered.
                                                  (reset! uri-to  (-> contact :uri))              ;; save uri & headers for building invite later:
                                                  (reset! headers (-> ack cljs/js->clj walk/keywordize-keys :headers)))
                                              (do (log/error "Could not find SIP DIR" sip-dir)
                                                  ;; debug <--
                                                  (doall (->> net-info seq (map second) (map #(dissoc % :auth)) (map println)))
                                                  ;; debug -->
                                                  (.send sip (.makeResponse sip rq "404" "NOT FOUND")))))

                              "SUBSCRIBE" (condp = (-> nrq :headers :event)
                                            "presence.winfo"  (do (println (:event nrq))
                                                                  ;; and register the gringo.
                                                                  (.send sip (.makeResponse sip rq 200 "OK")))
                                            "message-summary" (do (println :200 :OK) (.send sip (.makeResponse sip rq 200 "OK")))
                                            (.send sip (.makeResponse sip rq 501 "Not Implemented")))

                              "PUBLISH"   (go (if (= "presence" (-> nrq :headers :event))
                                                (let [parse-xml (-> (node/require "xml2js") .-parseString)
                                                      xml       (chan)]
                                                  ;; debug <--
                                                  (parse-xml (:content nrq) #(go (println %2) (>! xml %2)))
                                                  (println (-> (<! xml) cljs/js->clj walk/keywordize-keys))
                                                  ;; debug -->
                                                  (.send sip (.makeResponse sip rq 200 "OK")))
                                                (do (log/error "SIP: Unsupported PUBLISH event:" (-> nrq :headers :event))
                                                    (.send sip (.makeResponse sip rq 501 "Not Implemented")))))

                              "OPTIONS"   (.send sip (.makeResponse sip rq 200 "OK"))

                              "INVITE"    (go (let [callee-name    (second (re-find #"sip:(.*)@" (:uri nrq)))			;; get callee name
                                                    callee-rdv     (:sip-rq (<! (query config callee-name rdv-id)))		;; query for its rdv
                                                    callee-rdv-id  (.readUInt32BE callee-rdv 1)
                                                    callee-rdv-dst (->  callee-rdv (.slice 5) conv/parse-addr first)
                                                    _ (println :rcvd callee-rdv-dst)
                                                    callee-rdv     (net-info [(:host callee-rdv-dst) (:port callee-rdv-dst)])]	;; FIXME: this is fine for PoC, but in the future net-info will be a atom and will be updated regularly.
                                                (if callee-rdv
                                                  (do ;; debug <--
                                                      (println :callee callee-name :caller name :got callee-rdv-id callee-rdv-dst)
                                                      (b/print-x (-> callee-rdv :auth :srv-id))
                                                      (b/print-x (-> rdv-id circ/get-data :rdv :auth :srv-id))
                                                      ;; debug -->
                                                      (.send sip (.makeResponse sip rq 100 "TRYING"))
                                                      (when (not= (-> callee-rdv :auth :srv-id) (-> rdv-id circ/get-data :rdv :auth :srv-id)) ;; if we are using the same RDV we don't extend to it, it would fail (can't reuse the same node in a circ)
                                                        (>! rdv-ctrl (merge callee-rdv {:dest callee-rdv}))
                                                        (<! rdv-notify)
                                                        (log/debug "Extended to callee's RDV"))
                                                      ;; FIXME: once this works we'll add relay-sip extend to callee so rdv can't read demand.
                                                      (circ/relay-sip config rdv-id :f-enc demand-mix)
                                                      (let [reply (<! rdv-notify)]
                                                        (condp = (:status reply)
                                                          :ok     (let [rt       (<! (path/get-path :rt))
                                                                        rt-data  (circ/get-data rt)]
                                                                    (>! (:dest-ctrl rt-data) (:mix reply))
                                                                    (<! (:notiyf rt-data)))
                                                          :busy    (do (log/info "Couldn't complete call," callee-name "is busy")
                                                                       (.send sip "BUSY")) ;; FIXME
                                                          :error   (do (log/info "Couldn't find" callee-name)
                                                                       (.send sip "404 or something."))))
                                                      ;; (circ/relay-sip config rdv-id :f-enc demand-mix)
                                                      ;; (rt-path-add (<! callee-mix))
                                                      ;; (rt-path-ask-mix to connect with callee) ; -> mix must keep a dir of its own
                                                      ;; (mk invite for local rt)
                                                      (.send sip (.makeResponse sip rq 180 "RINGING"))
                                                      ;; debug <--
                                                      (js/console.log (mk-invite @headers uri-to "172.17.42.1"))
                                                      (.send sip (mk-invite @headers uri-to "172.17.42.1") ;; FIXME this will become mk-sdp, and be sent as an ack here.
                                                             (fn [rs] (go (println (-> rs cljs/js->clj walk/keywordize-keys)) (.-status rs)))))
                                                      ;; debug -->
                                                  (do (log/error "SIP: Could not find callee's mix:" name)
                                                      (.send sip (.makeResponse sip rq 404 "NOT FOUND"))))))

                              nil)))]
        (>! rdv-ctrl :rdv)
        (circ/update-data rdv-id [:sip-chan] (chan))
        (.start sip (cljs/clj->js {:protocol "UDP"}) process)
        (log/info "SIP proxy listening on default UDP SIP port"))))


;; Commands used by SIP DIR & sip mix dir ;;;;;;;;;;;;;;;;;;;;;;;;

(def from-cmd {:register        0
               :query           1
               :query-reply     2
               :register-to-mix 3
               :mix-query       4
               :error           9})

(def to-cmd
  (apply merge (for [k (keys from-cmd)]
                 {(from-cmd k) k})))


;; SIP DIR service and associated client functions ;;;;;;;;;;;;;;;

(def dir (atom {}))

(defn rm [name]
  "Remove name from dir"
  (swap! dir dissoc name))

(defn create-dir [config]
  "Wait for sip requests on the sip channel and process them.
  Returns the SIP channel it is listening on.
  SIP directories start this service."
  (let [sip-chan   (chan)
        ;; process register:
        p-register (fn [{rq :sip-rq}]
                     (let [rdv-id        (.readUInt32BE rq 1)
                           [rdv-dest rq] (conv/parse-addr (.slice rq 5))
                           name          (.toString rq)
                           timeout-id    (js/setTimeout #(do (log/debug "SIP DIR: timeout for" name)
                                                             (rm name))
                                                        (:sip-register-interval config))]
                       (log/debug "SIP DIR, registering" name "on RDV:" rdv-id "RDV dest:" rdv-dest)
                       ;; if the user is renewing his registration, remove rm timeout:
                       (when (@dir name)
                         (js/clearTimeout (:timeout (@dir name))))
                       ;; update the global directory:
                       (swap! dir merge {name {:rdv rdv-dest :rdv-id rdv-id :timeout timeout-id}})))
        ;; process query:
        p-query    (fn [{circ :circ-id rq :sip-rq}]
                     (let [name  (.toString (.slice rq 1))
                           reply (when-let [entry   (@dir name)]
                                   (let [cmd      (-> [(from-cmd :query-reply)] cljs/clj->js b/new)
                                         rdv-dest (b/new (conv/dest-to-tor-str (merge {:type :ip4 :proto :udp} (:rdv entry))))
                                         rdv-id   (b/new4 (:rdv-id entry))]
                                     (b/cat cmd rdv-id rdv-dest b/zero)))]
                       ;; debug <--
                       (println :to circ :sending reply)
                       ;; debug -->
                       (if reply
                         (do (log/debug "SIP DIR, query for" name)
                             (circ/relay-sip config circ :b-enc reply))
                         (do (log/debug "SIP DIR, could not find" name)
                             (circ/relay-sip config circ :b-enc
                                             (b/cat (-> [(from-cmd :error)] cljs/clj->js b/new) (b/new "404")))))))]
    ;; dispatch requests to the corresponding functions:
    (go-loop [request (<! sip-chan)]
      (condp = (-> request :sip-rq (.readUInt8 0) to-cmd)
        :register (p-register request)
        :query    (p-query request)
        (log/error "SIP DIR, received unknown command"))
      (recur (<! sip-chan)))
    sip-chan))

(defn register [config name rdv-id rdv-data]
  "Send a register to a sip dir. Format of message:
  - cmd: 0 = register
  - rdv's circuit id
  - rdv ip @ & port
  - sip name.
  Used by application proxies to register the SIP username of a client & its RDV to a SIP DIR."
  (let [cmd         (-> [(from-cmd :register)] cljs/clj->js b/new)
        rdv-b       (b/new4 rdv-id)
        name-b      (b/new name)
        rdv-dest    (b/new (conv/dest-to-tor-str {:host (:host rdv-data) :port (:port rdv-data) :type :ip4 :proto :udp}))]
    (log/debug "SIP: registering" name "on RDV" rdv-id)
    (circ/relay-sip config rdv-id :f-enc (b/cat cmd rdv-b rdv-dest b/zero name-b))))

(defn query [config name rdv-id]
  "Query for the RDV that is used for the given name.
  Used by application proxies to connect to callee."
  (let [cmd         (-> [(from-cmd :query)] cljs/clj->js b/new)
        name-b      (b/new name)]
    (log/debug "SIP: querying for" name "on RDV" rdv-id)
    (circ/relay-sip config rdv-id :f-enc (b/cat cmd name-b))
    (-> rdv-id circ/get-data :sip-chan)))

(defn mk-invite [headers uri-to ipfrom] ;; ip from will become a :dest from a rt circ
  (-> {:method  "INVITE"
       :uri     uri-to
       :headers {:to               {:uri uri-to}
                 :from             (-> headers :to)
                 :call-id          (-> (node/require "crypto") (.randomBytes 16) (.toString "hex"))
                 :via              (-> headers :via)
                 :contact          [{"uri" (str "sip:from@" ipfrom)}]
                 :cseq             {:seq (rand-int 888888) , :method "INVITE"}} ;; FIXME find real cseq max
       :content (apply str (interleave ["v=0"
                                        (str "o=- 3606192961 3606192961 IN IP4 " ipfrom)
                                        "s=pjmedia"
                                        (str "c=IN IP4 " ipfrom)
                                        "t=0 0"
                                        "a=X-nat:0"
                                        "m=audio 4000 RTP/AVP 96"
                                        (str "a=rtcp:4001 IN IP4 " ipfrom)
                                        "a=sendrecv"
                                        "a=rtpmap:96 telephone-event/8000"
                                        "a=fmtp:96 0-15"]
                                       (repeat "\r\n")))}
      ;(update-in [:headers] #(merge % headers))
      walk/stringify-keys
      cljs/clj->js))


;; MIX DIR service and associated client functions ;;;;;;;;;;;;;;;

;; This will also keep track of what clients are active.
;; this might get exported to another module at some point.
;; Either that, or merge with create-dir, while separating functionality to avoid abuse.
;; maybe a different go-loop based on role?

(def mix-dir (atom {}))

(defn create-mix-dir [config]
  "Wait for sip requests on the sip channel and process them.
  Returns the SIP channel it is listening on.
  Mixes start this service to keep track of the state of its users."
  (let [sip-chan   (chan)
        ;; process register:
        p-register (fn [{rq :sip-rq}]
                     (let [[client-dest rq] (conv/parse-addr (.slice rq 1))
                           name             (.toString rq)
                           timeout-id       (js/setTimeout #(do (log/debug "SIP DIR: timeout for" name)
                                                                (rm name))
                                                           (:sip-register-interval config))]
                       (log/debug "SIP MIX DIR, registering" name "dest:" client-dest)
                       ;; if the user is renewing his registration, remove rm timeout:
                       (when (@mix-dir name)
                         (js/clearTimeout (:timeout (@mix-dir name))))
                       ;; update the global directory:
                       (swap! mix-dir merge {name {:dest client-dest :state :inactive}})))
        ;; process extend:
        p-extend   (fn [{circ :circ-id rq :sip-rq}]
                     (let [name  (.toString (.slice rq 1))
                           reply (when-let [entry   (@mix-dir name)]
                                   (let [cmd      (-> [(from-cmd :query-reply)] cljs/clj->js b/new)
                                         rdv-dest (b/new (conv/dest-to-tor-str (merge {:type :ip4 :proto :udp} (:rdv entry))))
                                         rdv-id   (b/new4 (:rdv-id entry))]
                                     (b/cat cmd rdv-id rdv-dest b/zero)))]
                       (println :to circ :sending reply)
                       (if reply
                         (do (log/debug "SIP DIR, query for" name)
                             (circ/relay-sip config circ :b-enc reply))
                         (do (log/debug "SIP DIR, could not find" name)
                             (circ/relay-sip config circ :b-enc
                                             (b/cat (-> [(from-cmd :error)] cljs/clj->js b/new) (b/new "404")))))))]
    ;; dispatch requests to the corresponding functions:
    (go-loop [request (<! sip-chan)]
      (condp = (-> request :sip-rq (.readUInt8 0) to-cmd)
        :register-to-mix (p-register request)
        :extend       (p-extend request)
        (log/error "SIP DIR, received unknown command"))
      (recur (<! sip-chan)))
    sip-chan))

(defn register-to-mix [config name circ-id]
  "Register client's sip username to the mix so people can extend circuits to us.
  Format:
  - dest (tor dest thing)
  - username"
  (let [cmd    (-> [(from-cmd :register-to-mix)] cljs/clj->js b/new)
        dest   (b/new (conv/dest-to-tor-str {:host (:external-ip config) :port 0 :type :ip4 :proto :udp}))
        name-b (b/new name)]
    (log/debug "SIP: registering" name "on mix" circ-id)
    (circ/relay-sip config circ-id :f-enc (b/cat cmd dest b/zero name-b))))



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
;walkiwalki;                 host 127.0.0.1                                                                      ; remove this. remove via entirely?
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

