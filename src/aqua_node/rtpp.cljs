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


(def calls (atom {}))

(defn get-call-circs [id]
  (let [ms (@calls id)]
    (for [m (keys ms)]
      (:circuit (ms m)))))

(defn destroy [config id]
  (let [circs (get-call-circs id)]
    (log/info "Closing circuits" circs "for call ID" id)
    (doseq [c circs]
      (circ/destroy config c))))

(defn process [config socket message rinfo]
  (log/debug "RTP-Proxy: from:" (.-address rinfo) (.-port rinfo))
  (let [bcode        (node/require "node-bencode")
        [cookie msg] (-> message (.toString "ascii") (str/split #" " 2))
        msg          (-> (.decode bcode msg "ascii") cljs/js->clj)
        cmd          (.toString (msg "command"))
        mk-reply     #(b/new (str cookie " " (.encode bcode (cljs/clj->js %))))
        send         #(.send socket % 0 (.-length %) (.-port rinfo) (.-address rinfo))
        ;; SDP & circ glue:
        external-ip  (:external-ip config)
        local-ip     (:local-ip config)
        call-id      (.toString (or (msg "call-id") ""))
        parse-sdp    #(let [sdp (.toString (msg "sdp"))]
                        [sdp
                         (second (re-find #"(?m)c\=IN IP4 ((\d+\.){3}\d+)" sdp))
                         (map next (re-seq #"(?m)m\=(\w+)\s+(\d+)" sdp))])
        change-port  (fn [sdp old new]
                       (str/replace sdp (re-pattern (str "(?m)(m\\=\\w+\\s+)" old)) #(str %2 new)))
        sdp-exiting  (fn [sdp ip ports]
                       (-> {:result "ok" :sdp sdp} mk-reply send))
        sdp-entering (fn [sdp distant-ip ports]
                       (let [sdp-ch         (chan)
                             local-ip       (:local-ip config)
                             circs          (repeatedly #(path/get-path :rt)) ;; won't work because we changed get-path, not fixing because this code will be remodeled in sip.cljs
                             assoc-circ     (fn [cid [media distant-port]]
                                              (assert cid "no circuits available. how quaint.")
                                              (let [circ           (circ/get-data cid)
                                                    state          (chan)]
                                                (circ/update-data cid [:state-ch] state)
                                                (go (>! (:dest-ctrl circ) {:host distant-ip :port distant-port :proto :udp :type :ip4}))
                                                (go (let [state          (<! state)
                                                          [_ local-port] (<! (path/attach-local-udp4 config cid {:host local-ip} path/app-proxy-forward-udp))]
                                                      (swap! calls assoc-in [call-id media] {:circuit cid})
                                                      [distant-port local-port]))))
                             replace-sdp    #(go (let [sdp       (<! %1)
                                                       [old new] (<! %2)]
                                                   (change-port sdp old new)))
                             nsdp           (reduce replace-sdp sdp-ch (map assoc-circ circs ports))]
                         (log/info "RTP-Proxy: Adding call ID [offer]" call-id)
                         (go (>! sdp-ch (str/replace sdp distant-ip local-ip))) ;; FIXME: this means get path returns paths having the same dest.
                         (go (-> {:result "ok" :sdp (<! nsdp)} mk-reply send))))
        process-sdp  (fn []
                       (let [[sdp ip ports]     (parse-sdp)]
                         (if (or (= ip external-ip) (= ip local-ip))
                           (sdp-exiting sdp ip ports)
                           (when-not (@calls call-id)
                             (sdp-entering sdp ip ports)))))]
    (condp = cmd
      "ping"   (-> {:result "pong"} mk-reply send)
      "offer"  (process-sdp)
      "answer" (process-sdp)
      "delete" (do (destroy config call-id)
                   (-> {:result "ok"} mk-reply send))
      (log/error "RTP-Proxy: unsupported command" cmd))))

(defn create-server [{host :host port :port} config]
  (let [socket (.createSocket (node/require "dgram") "udp4") ;; FIXME hardcoded to ip4 for now.
        err    #(log/error "RTP-Proxy: server down.")]
    (.bind socket port host #(log/info "RTP-Proxy listening on:" (-> socket .address .-ip) (-> socket .address .-port)))
    (c/add socket {:ctype :udp :type :udp-rtpp :cs :server})
    (c/add-listeners socket {:message  (partial process config socket)
                             :error    err
                             :close    err})
    socket))
