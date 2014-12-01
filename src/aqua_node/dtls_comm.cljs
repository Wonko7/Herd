(ns aqua-node.dtls-comm
  (:require [cljs.core :as cljs]
            [cljs.nodejs :as node]
            [cljs.core.async :refer [chan <! >! sub pub unsub close!] :as a]
            [aqua-node.sip-helpers :as h]
            [aqua-node.parse :as conv]
            [aqua-node.log :as log]
            [aqua-node.buf :as b]
            [aqua-node.conns :as c])
  (:require-macros [cljs.core.async.macros :as m :refer [go-loop go]]))

;; globals:

(def dtls-handler-socket-data (atom nil))
(def dispatch-pub (atom nil))

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
   7  :ack})

(def from-cmd
  (apply merge (for [k (keys to-cmd)]
                 {(to-cmd k) k})))

(defn process [socket buf rinfo dispatch-rq]
  (let [cmd (-> buf (.readUInt8 0) to-cmd)]
    (println "recvd" cmd)
    (condp = cmd
      :ack (go (>! dispatch-rq buf))
      (let [socket-id (.readUInt32BE buf 1)]
        (if (nil? (c/get-data socket-id))
          (log/error "Got data for an invalid/unknown DTLS socket id" socket-id))
        (circ/process config socket-id (.slice buf 5))
        (println (h/to-clj rinfo))
        ;(println (.toString buf))
        ))))

(defn send-to-dtls [buf]
  "send to dtls"
  (let [[soc soc-ctrl port] @dtls-handler-socket-data]
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
    (println key-file)
    (println cert-file)
    (b/print-x aqua-pub)
    (b/print-x aqua-sec)
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
                       (-> dest :id))))

(defn connect [dest]
  (let [c         (node/require "crypto")
        cookie    (.readUInt32BE (.randomBytes c 4) 0) ;; cookie used to identify transaction
        ctrl      (chan)]
    (println "sending connect with cookie" cookie "id length" (.-length (:id dest)))
    (sub @dispatch-pub cookie ctrl)
    (go (send-connect dest cookie)
        (let [answer (<! ctrl) ;; also allow for timeout...
              state  (.readUInt32BE answer 5)
              id     (.readUInt32BE answer 9)]
          (unsub @dispatch-pub cookie ctrl)
          (close! ctrl)
          (if (not= 0 state)
            (do (println "got fail on" cookie)
                :fail)
            (do (log/debug "got dtls-handler ok on cookie" cookie "given node id =" id)
                (c/add id (merge {:id id :cs :client :type :aqua :host (:host dest) :port (:port dest)
                                  :send-fn #(do (.writeUInt32BE % id 0)
                                                (send-to-dtls %))}))))))))

;  (defn connect [dest config conn-info conn-handler err]

(defn init [{port :dtls-handler-port fixme :files-for-certs :as config}]
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
    (reset! dispatch-pub (pub dispatch-rq #(.readUInt32BE %1 1)))
    (.bind soc 0 "127.0.0.1")
    (c/add-listeners soc {:message   #(process soc %1 %2 dispatch-rq)
                          :listening #(go (>! soc-ctrl :listening))
                          :error     #(log/error "DTLS control socket error")
                          :close     #(log/error "DTLS control socket closed")})

    (log/info "Started dtls handler, PID:" (.-pid dtls-handler) "Port:" port)
    (reset! dtls-handler-socket-data [soc soc-ctrl port])
    (go (<! soc-ctrl)
        (send-to-dtls (b/cat (b/new1 3) (b/new "Hellololololol!")))
        (send-to-dtls (b/cat (b/new1 1) (-> {:host "123.124.125.126" :port 12345 :type :ip :proto :udp} conv/dest-to-tor-str b/new)))
        (send-init config)
        (send-to-dtls (b/cat (b/new1 3) (b/new "Hellololololol again!")))
        (connect {:host "127.0.0.1" :port 12345 :id (-> config :auth :aqua-id :id)})
        )))

(defn test []
  (go (let [c (chan)
            p (pub c #(if (= (:top %) 1)
                        :lol
                        :mdr))
            a (chan 3 (map #(merge % {:lol "aaaa"})))
            b (chan 3 (map #(merge % {:lol "bbbb"})))]
        (sub p :lol a)
        (sub p :mdr b)
        (>! c {:top 1 :rofl 5})
        (>! c {:top 5 :rofl 6})
        (>! c {:top 1 :rofl 7})
        (>! c {:top 5 :rofl 8})
        (println (<! a))
        (println (<! b))
        (println (<! a))
        (println (<! b))
        )))
