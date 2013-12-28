(ns aqua-node.rtpp
  (:require [cljs.core :as cljs]
            [cljs.nodejs :as node]
            [cljs.core.async :refer [chan <! >!]]
            [clojure.string :as str]
            [aqua-node.log :as log]
            [aqua-node.buf :as b]
            [aqua-node.conns :as c])
  (:require-macros [cljs.core.async.macros :as m :refer [go]]))

(defn err []
  (println "error on rtpp server"))

(defn process [config socket message rinfo]
  (log/debug "RTP-Proxy: from:" (.-address rinfo) (.-port rinfo) :msg (.toString message "ascii"))
  (let [bcode        (node/require "node-bencode")
        [cookie msg] (-> message (.toString "ascii") (str/split #" " 2))
        msg          (-> (.decode bcode msg "ascii") cljs/js->clj)
        cmd          (.toString (msg "command"))
        mk-reply     #(do (log/debug "RTP-Proxy" cookie ":" cmd "->" %)
                          (b/new (str cookie " " (.encode bcode (cljs/clj->js %)))))
        send         #(.send socket % 0 (.-length %) (.-port rinfo) (.-address rinfo))]
    (log/debug :cookie cookie :cmd (println (keys msg)))
    (condp = cmd
      "ping"  (-> {:result "pong"} mk-reply send)
      "offer" (let [sdp (.toString (msg "sdp"))
                    cnt (.match sdp #"m\=")]
                (println cnt)
                (-> {:result "ok" :sdp sdp} mk-reply send))
      "delete" (-> {:result "ok"} mk-reply send)
      (log/error "RTP-Proxy: unsupported command" cmd))))

(defn create-server [{host :host port :port} config]
  (let [socket (.createSocket (node/require "dgram") "udp4")] ;; FIXME hardcoded to ip4 for now.
    (.bind socket port host #(log/info "RTP-Proxy listening on:" (-> socket .address .-ip) (-> socket .address .-port)))
    (c/add socket {:type :udp-rtpp :cs :server})
    (c/add-listeners socket {:message  (partial process config socket)
                             :error    err
                             :close    err})
    socket))
