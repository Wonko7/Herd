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

;;     var sip = require('sip');
;;   
;;     sip.start({}, function(request) {
;;       var response = sip.makeResponse(request, 302, 'Moved Temporarily');
;; 
;;       var uri = sip.parseUri(request.uri);
;;       uri.host = 'backup.somewhere.net'; 
;;       response.headers.contact = [{uri: uri}];
;;     
;;       sip.send(response);
;;     });

(defn echo-server [config things]
  (let [sip  (node/require "sip")
        echo (fn [rq]
               (println (cljs/js->clj rq))
               (println (cljs/js->clj (.makeResponse sip rq 200 "OK")))
               (let [nrq  (-> rq cljs/js->clj walk/keywordize-keys)
                     name (-> nrq :headers :contact first :name)
                     uri  (str name "@6.6.6.6")
                     nrq  (assoc-in nrq [:headers :contact] [(-> rq :headers :contact first)]) ;; FIXME for now force one contact only.
                     nrq  (reduce #(assoc-in %1 %2 uri) nrq [[:uri] [:headers :contact 0 :uri] [:headers :from :uri]]) ;; just testing, this might change. we'll see.
                     ]
                 (println :nrq nrq))
               (.send sip (.makeResponse sip rq 200 "OK"))
               )]
    (.start sip (cljs/clj->js {:protocol "UDP"}) echo)
    (log/info "SIP proxy listening on default UDP SIP port")))


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
