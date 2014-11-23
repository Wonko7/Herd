(ns aqua-node.dtls-comm
  (:require [cljs.core :as cljs]
            [cljs.nodejs :as node]
            [cljs.core.async :refer [chan <! >! ] :as a]
            [aqua-node.sip-helpers :as h]
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
  (println (h/to-clj rinfo)))

(defn init [{port :dtls-handler-port fixme :files-for-certs :as config}]
  (let [exec          (.-exec (node/require "child_process"))
        dtls-handler  (exec (str "./dtls-handler " port)
                            nil
                            #(do (log/error "dtls-handler exited with" %1)
                                 (log/error %&)
                                 (init config)))
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
        (fwd-to-dtls (b/new "Hellololololol!")))))

(defn create-server [{host :host port :port :as dest} config conn-info new-conn-handler err]
  )



(defn connect [dest config conn-info conn-handler err]
)
