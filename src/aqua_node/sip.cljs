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


(defn create-server [config geo-db things]
  (let [sip         (node/require "sip")
        sip-circ    (path/get-path config :single)
        process     (fn [rq]
                      (let [nrq  (-> rq cljs/js->clj walk/keywordize-keys)
                            name (-> nrq :headers :contact first :name)]
                        (println)
                        (println :nrq nrq)
                        (condp = (:method nrq)
                          "REGISTER"  (do ;(send reg w/ rdv to sip dir)
                                          ;(<! get ack)
                                          (.send sip (.makeResponse sip rq 200 "OK")))
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
                                              (println 1)
                                              (println (-> (<! xml) cljs/js->clj walk/keywordize-keys))
                                              (println 2)
                                              (.send sip (.makeResponse sip rq 200 "OK")))
                                            (do (log/error "SIP: Unsupported PUBLISH event:" (-> nrq :headers :event))
                                                (.send sip (.makeResponse sip rq 501 "Not Implemented")))))
                          "OPTIONS"   (.send sip (.makeResponse sip rq 200 "OK"))
                          "INVITE"    (do (.send sip (.makeResponse sip rq 100 "TRYING"))
                                          ;(<! send-invite), create rtp circ to dest, continue;
                                          (.send sip (.makeResponse sip rq 180 "RINGING")))
                          nil)))]
    ;; create path to sip dir.
    ;; create rdv. now, or on register?
    (.start sip (cljs/clj->js {:protocol "UDP"}) process)
    (log/info "SIP proxy listening on default UDP SIP port")))



(def dir (atom {}))

(defn process-dir [config info]
  (comment
    (when is-sip-register
      (swap! dir update entry {key {:name aoeu :timeout (use expire info)}}))))


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
