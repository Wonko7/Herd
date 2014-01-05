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

(defn err [] ;; Obviously a place holder.
  (println "error on rtpp server"))

(def calls (atom {}))

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
                       (go (let [sdp          (.toString (msg "sdp"))
                                 sdp-ch       (chan)
                                 ip           (second (re-find #"(?m)c\=IN IP4 ((\d+\.){3}\d+)" sdp))
                                 ports        (map next (re-seq #"(?m)m\=(\w+)\s+(\d+)" sdp))
                                 circs        (repeatedly path/get-path)
                                 change-port  (fn [sdp old new]
                                                (str/replace sdp (re-pattern (str "(?m)(m\\=\\w+\\s+)" old)) #(str %2 new)))
                                 assoc-circ   (fn [cid [media old]]
                                                (go (let [[_ local-port] (<! (path/attach-local-udp4 config cid {:type :ip4 :proto :udp :host ip :port old}))
                                                          dist-port      (-> cid circ/get-data :path-dest :port)]
                                                      (swap! calls assoc-in [(msg "call-id") media] {:local-circ-port local-port
                                                                                                     :distant-circ-port dist-port
                                                                                                     :local-port old})
                                                      [old dist-port])))
                                 nsdp         (reduce #(go (let [sdp       (<! %1) 
                                                                 [old new] (<! %2)]
                                                             (change-port sdp old new)))
                                                      sdp-ch (map assoc-circ circs ports))]
                             (go (>! sdp-ch (str/replace sdp ip (-> (first circs) circ/get-data :path-dest :host))))
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
