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

(declare register query dir mk-invite)

(defn create-server [{sip-dir :remote-sip-dir :as config} net-info things]
  "Creates the listening service that will process the connected SIP client's requests"
  (go (let [sip         (node/require "sip")
            ;; Prepare RDV:
            rdv-id      (<! (path/get-path :single)) ;; FIXME we should specify what zone we want our rdv in.
            rdv-ctrl    (-> rdv-id circ/get-data :dest-ctrl)
            rdv-notify  (-> rdv-id circ/get-data :notify)
            ;; Process logic:
            process     (fn [rq]
                          (let [nrq  (-> rq cljs/js->clj walk/keywordize-keys)
                                name (-> nrq :headers :contact first :name)]
                            (println)
                            (println :nrq nrq)
                            (condp = (:method nrq)

                              "REGISTER"  (let [contact  (-> nrq :headers :contact first)
                                                name     (or (-> contact :name)
                                                             (->> contact :uri (re-find #"sip:(.*)@") second))
                                                rdv-data (circ/get-data rdv-id)
                                                sip-dir-dest (net-info [(:host sip-dir) (:port sip-dir)])
                                                sip-dir-dest (merge sip-dir-dest {:dest sip-dir-dest})] ;; FIXME; we'll get rid of :dest in circ someday.
                                            (if (:auth sip-dir-dest)
                                              (go (>! rdv-ctrl sip-dir-dest) ;; connect to sip dir to send register
                                                  (<! rdv-notify)            ;; wait until connected to send it
                                                  (register config name rdv-id (:rdv rdv-data))
                                                  (.send sip (.makeResponse sip rq 200 "OK")))
                                              (do (log/error "Could not find SIP DIR" sip-dir)
                                                  (doall (->> net-info seq (map second) (map #(dissoc % :auth)) (map println)))
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
                                                  (parse-xml (:content nrq) #(go (println %2) (>! xml %2)))
                                                  (println (-> (<! xml) cljs/js->clj walk/keywordize-keys))
                                                  (.send sip (.makeResponse sip rq 200 "OK")))
                                                (do (log/error "SIP: Unsupported PUBLISH event:" (-> nrq :headers :event))
                                                    (.send sip (.makeResponse sip rq 501 "Not Implemented")))))

                              "OPTIONS"   (.send sip (.makeResponse sip rq 200 "OK"))

                              "INVITE"    (go (let [;; get callee name, query for it
                                                    name           (second (re-find #"sip:(.*)@" (:uri nrq)))
                                                    callee-rdv     (:sip-rq (<! (query config name rdv-id)))
                                                    callee-rdv-id  (.readUInt32BE callee-rdv 1)
                                                    callee-rdv-dst (conv/parse-addr (.slice callee-rdv 5))
                                                    callee-rdv     (@dir [(:host callee-rdv-dst) (:port callee-rdv-dst)])]
                                                (if callee-rdv
                                                  (do (println :got callee-rdv-id callee-rdv-dst)
                                                      (.send sip (.makeResponse sip rq 100 "TRYING"))
                                                      (>! rdv-ctrl callee-rdv)
                                                      (<! rdv-notify)
                                                      ;; (circ/relay-sip config rdv-id :f-enc demand-mix)
                                                      ;; (rt-path-add (<! callee-mix))
                                                      ;; (rt-path-ask-mix to connect with callee) ; -> mix must keep a dir of its own
                                                      ;; (mk invite for local rt)
                                                      (.send sip (.makeResponse sip rq 180 "RINGING")))
                                                  (do (log/error "SIP: Could not find callee's mix:" name)
                                                      (.send sip (.makeResponse sip rq 404 "NOT FOUND"))))))

                              nil)))]
        (>! rdv-ctrl :rdv)
        (circ/update-data rdv-id [:sip-chan] (chan))
        (println (keys (second (first (seq net-info)))))
        (.start sip (cljs/clj->js {:protocol "UDP"}) process)
        (log/info "SIP proxy listening on default UDP SIP port"))))


;; Sip dir & register:


(def dir (atom {}))

(def from-cmd {:register    0
               :query       1
               :query-reply 2
               :error       9})
(def to-cmd
  (apply merge (for [k (keys from-cmd)]
                 {(from-cmd k) k})))

(defn rm [name]
  "Remove name from dir"
  (swap! dir dissoc name))

(defn create-dir [config]
  "Wait for sip requests on the sip channel and process them.
  Returns the SIP channel it is listening on."
  (println (mk-invite))
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
                       ;; if the user is renewing its registration, remove rm timeout:
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
  - sip name."
  (let [cmd         (-> [(from-cmd :register)] cljs/clj->js b/new)
        rdv-b       (b/new4 rdv-id)
        name-b      (b/new name)
        rdv-dest    (b/new (conv/dest-to-tor-str {:type :ip4 :proto :udp :host (:host rdv-data) :port (:port rdv-data)})) ]
    (log/debug "SIP: registering" name "on RDV" rdv-id)
    (circ/relay-sip config rdv-id :f-enc (b/cat cmd rdv-b rdv-dest b/zero name-b))))

(defn query [config name rdv-id]
  (let [cmd         (-> [(from-cmd :query)] cljs/clj->js b/new)
        name-b      (b/new name)]
    (log/debug "SIP: querying for" name "on RDV" rdv-id)
    (circ/relay-sip config rdv-id :f-enc (b/cat cmd name-b))
    (-> rdv-id circ/get-data :sip-chan)))

(defn mk-invite []
  (cljs/clj->js {"method"  "INVITE"
                 "uri"     "sip:aoeu1@172.17.0.7"
                 "headers" {"to"      {"uri" "aoeu1@172.17.0.7"}
                            "from"    {"uri" "from-uri"}
                            "contact" [{"uri" "sip:aoeu1@172.17.42.1"}]
                            "content" (apply str (interleave ["v=0"
                                                              "o=- 3606192961 3606192961 IN IP4 139.19.186.120"
                                                              "s=pjmedia"
                                                              "c=IN IP4 139.19.186.120"
                                                              "t=0 0"
                                                              "a=X-nat:0"
                                                              "m=audio 4000 RTP/AVP 96"
                                                              "a=rtcp:4001 IN IP4 139.19.186.120"
                                                              "a=sendrecv"
                                                              "a=rtpmap:96 telephone-event/8000"
                                                              "a=fmtp:96 0-15"]
                                                             (repeat "\r\n")))}}))

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
