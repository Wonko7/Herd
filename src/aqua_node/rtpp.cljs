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
        call-id      (.toString (or (msg "call-id") ""))
        parse-sdp    #(let [sdp (.toString (msg "sdp"))]
                        [sdp
                         (second (re-find #"(?m)c\=IN IP4 ((\d+\.){3}\d+)" sdp))
                         (map next (re-seq #"(?m)m\=(\w+)\s+(\d+)" sdp))])
        change-port  (fn [sdp old new]
                       (str/replace sdp (re-pattern (str "(?m)(m\\=\\w+\\s+)" old)) #(str %2 new)))
        offer-sdp    (fn []
                       (let [[sdp ip ports] (parse-sdp)        
                             sdp-ch         (chan)
                             circs          (repeatedly path/get-path)
                             assoc-circ     (fn [cid [media old]]
                                              (go (let [f-dest         {:type :ip4, :proto :udp, :host ip :port old}
                                                        [_ local-port] (<! (path/attach-local-udp4 config cid f-dest path/forward-udp))
                                                        dist-port      (-> cid circ/get-data :path-dest :port)]
                                                    (swap! calls assoc-in [call-id media] {:local-circ-port local-port
                                                                                           :distant-circ-port dist-port
                                                                                           :local-port old
                                                                                           :circuit cid})
                                                    [old dist-port])))
                             replace-sdp    #(go (let [sdp       (<! %1)
                                                       [old new] (<! %2)]
                                                   (change-port sdp old new)))
                             nsdp           (reduce replace-sdp sdp-ch (map assoc-circ circs ports))]
                         (log/info "RTP-Proxy: Adding call ID [offer]" call-id)
                         (go (>! sdp-ch (str/replace sdp ip (-> (first circs) circ/get-data :path-dest :host)))) ;; FIXME: this means get path returns paths having the same dest.
                         (go (-> {:result "ok" :sdp (<! nsdp)} mk-reply send))))
        answer-sdp   (fn []
                       (let [[sdp ip ports] (parse-sdp)
                             call-data      (@calls call-id)
                             circs          (for [[media port] ports
                                                  :let [med   (merge (call-data media) {:distant-port port :distant-ip ip})
                                                        cid   (:circuit med)
                                                        b-hop (-> cid circ/get-data :backward-hop)]]
                                              (do (swap! calls assoc-in [call-id media] med)
                                                  (circ/update-data cid [:dist-dest] {:host ip :port port})
                                                  (c/update-data b-hop [:type] :rtp-ap)
                                                  [port (:local-circ-port med)]))
                             sdp            (str/replace sdp ip (-> config :aqua :host))
                             sdp            (reduce #(change-port %1 (first %2) (second %2)) sdp circs)]
                         (log/info "RTP-Proxy: Adding call ID [answer]" call-id "using circuits:" (get-call-circs call-id)) 
                         (-> {:result "ok" :sdp sdp} mk-reply send)))]
    (condp = cmd
      "ping"   (-> {:result "pong"} mk-reply send)
      "offer"  (offer-sdp)
      "delete" (do (destroy config call-id)
                   (-> {:result "ok"} mk-reply send))
      "answer" (answer-sdp)
      (log/error "RTP-Proxy: unsupported command" cmd))))

(defn create-server [{host :host port :port} config]
  (let [socket (.createSocket (node/require "dgram") "udp4") ;; FIXME hardcoded to ip4 for now.
        err    #(log/error "RTP-Proxy: server down.")]
    (.bind socket port host #(log/info "RTP-Proxy listening on:" (-> socket .address .-ip) (-> socket .address .-port)))
    (c/add socket {:type :udp-rtpp :cs :server})
    (c/add-listeners socket {:message  (partial process config socket)
                             :error    err
                             :close    err})
    socket))
