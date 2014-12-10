(ns aqua-node.dtls-comm
  (:require [cljs.core :as cljs]
            [cljs.nodejs :as node]
            [cljs.core.async :refer [chan <! >! sub pub unsub close!] :as a]
            [aqua-node.parse :as conv]
            [aqua-node.log :as log]
            [aqua-node.buf :as b]
            [aqua-node.conns :as c])
  (:require-macros [cljs.core.async.macros :as m :refer [go-loop go]]))

;; globals:
(declare circ-process dispatch-pub dtls-handler-socket-data send-to-dtls)

;; command definitions:

;;  :init             -> give ntor certs, paths to dtls certs.
;;  :connect-to-node  -> give tor-dest
;;  :add-hop          -> add hop to a circuit, we send created ntor shared secret to enable fastpath.
;;  :open-local-udp   -> for fast path between c layer & sip client
;;  :new-circuit      -> for fast path relay
;;  :forward          -> give a packet to forward on a dtls link
;;  :data             -> c layer sends us data it couldn't process in a fast path

(def to-cmd
  {0  :init
   1  :connect-to-node
   2  :open-local-udp
   3  :local-udp-port
   4  :forward
   5  :data
   6  :new-circuit
   7  :ack
   8  :new-client
   })

(def from-cmd
  (apply merge (for [k (keys to-cmd)]
                 {(to-cmd k) k})))

;; helpers:
(defn- mk-send-fn [socket-id]
  (let [header    (b/new 5)]
    (.writeUInt8 header (from-cmd :forward) 0)
    (.writeUInt32BE header socket-id 1)
    #(do (.copy header %)
         (send-to-dtls %))))

;; sending to dtls-handler:
(defn send-to-dtls [buf]
  "send to dtls"
  (let [[soc soc-ctrl port] dtls-handler-socket-data]
    (println :fwd port (.-length buf))
    (.send soc buf 0 (.-length buf) port "127.0.0.1")))

(defn send-init [config]
  (let [key-file          (-> config :auth-files :openssl :key)
        cert-file         (-> config :auth-files :openssl :cert)
        aqua-pub          (-> config :auth :aqua-id :pub)
        aqua-sec          (-> config :auth :aqua-id :sec)
        aqua-id           (-> config :auth :aqua-id :id)
        mk-size-and-buf   #(let [buf (b/new %)]
                             (b/cat (b/new2 (.-length buf)) buf))]
    (send-to-dtls (b/cat (-> :init from-cmd b/new1)
                         (mk-size-and-buf cert-file)
                         (mk-size-and-buf key-file)
                         (mk-size-and-buf aqua-pub)
                         (mk-size-and-buf aqua-sec)
                         (mk-size-and-buf aqua-id)
                         (-> config :aqua :port b/new2)))))

(defn send-connect [dest cookie]
  (send-to-dtls (b/cat (-> :connect-to-node from-cmd b/new1)
                       (b/new4 cookie)
                       (-> dest conv/dest-to-tor-str b/new)
                       b/zero
                       (-> dest :auth :srv-id))))

;; connect to a new node:
(defn connect [dest conn-info conn-handler err]
  (let [c         (node/require "crypto")
        cookie    (.readUInt32BE (.randomBytes c 4) 0) ;; cookie used to identify transaction
        ctrl      (chan)]
    (log/info "Connecting to" (select-keys dest [:host :port :role]))
    (println "sending connect with cookie" cookie "id length" (-> dest :auth :srv-id .-length))
    (sub dispatch-pub cookie ctrl)
    (go (send-connect dest cookie)
        (let [answer (<! ctrl) ;; also allow for timeout...
              state  (.readUInt32BE answer 5)
              id     (.readUInt32BE answer 9)]
          (unsub dispatch-pub cookie ctrl)
          (close! ctrl)
          (if (not= 0 state)
            (do (log/error "got fail on" cookie)
                (when err
                  (err))
                :fail)
            (do (when conn-handler
                  (conn-handler))
                (log/debug "got dtls-handler ok on cookie" cookie "given node id =" id)
                (c/add id (merge conn-info
                                 {:id id :cs :client :type :aqua :host (:host dest) :port (:port dest)
                                  :send-fn (mk-send-fn id)}))))))))

;; process messages from dtls-handler:
(defn process [socket config buf rinfo dispatch-rq]
  (let [[r1 r2 r4]  (b/mk-readers buf)
        cmd         (to-cmd (r1 0))]
    (println "recvd" cmd)
    (condp = cmd
      :ack        (go (>! dispatch-rq buf))
      :data       (let [socket-id (r4 1)]
                    (if (nil? (c/get-data socket-id))
                      (log/error "Got data for an invalid/unknown DTLS socket id" socket-id)
                      (circ-process config socket-id (.slice buf 5))))
      :new-client (let [socket-id (r4 1)]
                    (log/info "New client on socket-id:" socket-id)
                    (c/add socket-id {:id socket-id :cs :server :type :aqua ;; FIXME can we get rid of :cs? that was old...
                                      :send-fn (mk-send-fn socket-id)}))
      (log/error "DTLS comm: unsupported command" cmd (r1 0)))))

;; start dtls-handler & create listening socket:
(defn init [{port :dtls-handler-port fixme :files-for-certs :as config} circ-process] ; FIXME:also others
  (let [exec          (.-exec (node/require "child_process"))
        dtls-handler  (exec (str (:dtls-handler-path config) " " port)
                            nil
                            #(do (log/error "dtls-handler exited with" %1)
                                 (log/error %&)
                                 ;(init config)
                                 ))
        soc           (.createSocket (node/require "dgram") "udp4")
        soc-ctrl      (chan)
        dispatch-rq   (chan)]
    ;; yerk, define globals:
    (def circ-process circ-process)
    (def dispatch-pub (pub dispatch-rq #(.readUInt32BE %1 1)))
    (.bind soc 0 "127.0.0.1")
    (c/add-listeners soc {:message   #(process soc config %1 %2 dispatch-rq)
                          :listening #(go (>! soc-ctrl :listening))
                          :error     #(log/error "DTLS control socket error")
                          :close     #(log/error "DTLS control socket closed")})

    (log/info "Started dtls handler, PID:" (.-pid dtls-handler) "Port:" port)
    ;; yerk, define global:
    (def dtls-handler-socket-data [soc soc-ctrl port])
    (go (<! soc-ctrl)
        (send-init config))))
