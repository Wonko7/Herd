(ns aqua-node.dtls-comm
  (:require [cljs.core :as cljs]
            [cljs.nodejs :as node]
            [cljs.core.async :refer [chan <! >! ] :as a]
            [aqua-node.sip-helpers :as h]
            [aqua-node.parse :as conv]
            [aqua-node.log :as log]
            [aqua-node.buf :as b]
            [aqua-node.conns :as c]
            [aqua-node.circ :as circ])
  (:require-macros [cljs.core.async.macros :as m :refer [go-loop go]]))


(def dtls-handler-socket-data (atom nil))

(defn fwd-to-dtls [buf]
  (let [[soc soc-ctrl port] @dtls-handler-socket-data]
    (println :fwd port buf (.-length buf))
    (.send soc buf 0 (.-length buf) port "127.0.0.1")))

(defn process [socket buf rinfo]
  (println (h/to-clj rinfo))
  (println (.toString buf)))

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
   6  :new-circuit})

(defn init [{port :dtls-handler-port fixme :files-for-certs :as config}]
  (let [exec          (.-exec (node/require "child_process"))
        dtls-handler  (exec (str "./dtls-handler " port)
                            nil
                            #(do (log/error "dtls-handler exited with" %1)
                                 (log/error %&)
                                 ;(init config)
                                 ))
        soc           (.createSocket (node/require "dgram") "udp4")
        soc-ctrl      (chan)]
    (.bind soc 0 "127.0.0.1")
    (c/add-listeners soc {:message   #(process soc %1 %2)
                          :listening #(go (>! soc-ctrl :listening))
                          :error     #(log/error "DTLS control socket error")
                          :close     #(log/error "DTLS control socket closed")})

    (log/info "Started dtls handler, PID:" (.-pid dtls-handler) "Port:" port)
    (reset! dtls-handler-socket-data [soc soc-ctrl port])
    (go (<! soc-ctrl)
        (println :sent)
        (fwd-to-dtls (b/cat (b/new1 3) (b/new "Hellololololol!")))
        ;;
        (fwd-to-dtls (b/cat (b/new1 1) (-> {:host "123.124.125.126" :port 12345 :type :ip :proto :udp} conv/dest-to-tor-str b/new ))))))

(defn create-server [{host :host port :port :as dest} config conn-info new-conn-handler err]
  )

(defn connect [dest config conn-info conn-handler err]
  )
