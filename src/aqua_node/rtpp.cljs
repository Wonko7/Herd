(ns aqua-node.rtpp
  (:require [cljs.core :as cljs]
            [cljs.nodejs :as node]
            [cljs.core.async :refer [chan <! >!]]
            [clojure.string :as str]
            [aqua-node.log :as log]
            [aqua-node.buf :as b]
            [aqua-node.conns :as c]
            [aqua-node.circ :as circ]
            [aqua-node.path :as path])
  (:require-macros [cljs.core.async.macros :as m :refer [go]]))

(defn err []
  (println "error on rtpp server"))

(defn process [config socket message rinfo]
  (log/debug "RTP-Proxy: from:" (.-address rinfo) (.-port rinfo))
  (let [bcode        (node/require "node-bencode")
        [cookie msg] (-> message (.toString "ascii") (str/split #" " 2))
        msg          (-> (.decode bcode msg "ascii") cljs/js->clj)
        cmd          (.toString (msg "command"))
        mk-reply     #(do (log/debug "RTP-Proxy reply to" cookie ":" cmd "<" %)
                          (b/new (str cookie " " (.encode bcode (cljs/clj->js %)))))
        send         #(.send socket % 0 (.-length %) (.-port rinfo) (.-address rinfo))
        replace-sdp  (fn []
                       (go (let [sdp         (.toString (msg "sdp"))
                                 ip          (second (re-find #"(?m)c\=IN IP4 ((\d+\.){3}\d+)" sdp))
                                 ports       (map second (re-seq #"(?m)m\=\w+\s+(\d+)" sdp))
                                 circs       (repeatedly path/get-path)
                                 ports-chs   (map #(go (<! (path/attach-local-udp4 config %1 {:type :ip4 :proto :udp :host ip :port %2})))
                                                  circs ports)
                                 change-port (fn [sdp [old new]]
                                               (go (let [[_ local-port {dist-host :host dist-port :port}] (<! new)]
                                                     (log/debug "RTP-Proxy replacing" ip ":" old "->" dist-port)
                                                     (str/replace (<! sdp) (re-pattern (str "(?m)(m\\=\\w+\\s+)" old)) #(str %2 dist-port)))))
                                 sdp-ch      (chan)
                                 nsdp        (reduce change-port sdp-ch (map list ports ports-chs))
                                 nsdp        (go (str/replace (<! nsdp) ip (-> (first circs) circ/get-data :path-dest :host)))]
                             (go (>! sdp-ch sdp))
                             (go (-> {:result "ok" :sdp (<! nsdp)} mk-reply send)))))]
    (condp = cmd
      "ping"   (-> {:result "pong"} mk-reply send)
      "offer"  (replace-sdp)
      "delete" (-> {:result "ok"} mk-reply send) ;; FIXME -> keep track of call ids so we can kill circs on delete.
      "answer" (-> {:result "ok" :sdp (.toString (msg "sdp"))} mk-reply send)
      (log/error "RTP-Proxy: unsupported command" cmd))))

(defn create-server [{host :host port :port} config]
  (let [socket (.createSocket (node/require "dgram") "udp4")] ;; FIXME hardcoded to ip4 for now.
    (.bind socket port host #(log/info "RTP-Proxy listening on:" (-> socket .address .-ip) (-> socket .address .-port)))
    (c/add socket {:type :udp-rtpp :cs :server})
    (c/add-listeners socket {:message  (partial process config socket)
                             :error    err
                             :close    err})
    socket))
