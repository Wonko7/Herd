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
        external-ip  (:extenal-ip config)
        local-ip     (:local-ip config)
        call-id      (.toString (or (msg "call-id") ""))
        parse-sdp    #(let [sdp (.toString (msg "sdp"))]
                        [sdp
                         (second (re-find #"(?m)c\=IN IP4 ((\d+\.){3}\d+)" sdp))
                         (map next (re-seq #"(?m)m\=(\w+)\s+(\d+)" sdp))])
        change-port  (fn [sdp old new]
                       (str/replace sdp (re-pattern (str "(?m)(m\\=\\w+\\s+)" old)) #(str %2 new)))
        offer-sdp         (fn []
                            (let [[sdp ip ports]     (parse-sdp)]
                              (if (or (= ip external-ip) (= ip local-ip))
                                (offer-sdp-exiting sdp ip ports)
                                (offer-sdp-entering sdp ip ports))))
        offer-exiting  (fn [sdp ip ports]
                         (log/info "RTP-Proxy: Adding call ID [offer]" call-id)
                         (-> {:result "ok" :sdp sdp} mk-reply send))
        offer-entering (fn [sdp distant-ip ports]
                         (let [sdp-ch         (chan)
                               circs          (repeatedly #(path/get-path config))
                               assoc-circ     (fn [cid [media distant-port]]
                                                (go (let [circ           (circ/get-data cid)]
                                                      (go (>! (:dest-ctrl circ) {:host ip :port port :proto :udp :type :ip4}))
                                                      ;; FIXME need to attach local udp...
                                                      (swap! calls assoc-in [call-id media] {:circuit cid})
                                                      [distant-port (<! )])))
                               assoc-circ     (fn [cid [media port]]
                                                (let [circ           (circ/get-data cid)]
                                                  (swap! calls assoc-in [call-id media] {:circuit cid})
                                                  [old new]))
                               replace-sdp    #(go (let [sdp       (<! %1)
                                                         [old new] (<! %2)]
                                                     (change-port sdp old new)))
                               nsdp           (reduce replace-sdp sdp-ch (map assoc-circ circs ports))]
                           (doall (map assoc-circ ))
                           (log/info "RTP-Proxy: Adding call ID [offer]" call-id)
                           (go (>! sdp-ch (str/replace sdp ip (-> (first circs) circ/get-data :path-dest :host)))) ;; FIXME: this means get path returns paths having the same dest.
                           (go (-> {:result "ok" :sdp (<! nsdp)} mk-reply send))))
        answer-sdp   (fn []
                       (let [[sdp ip ports]     (parse-sdp)
                             call-data          (@calls call-id)]
                         (if (= ip local-ip)
                           (do (log/info "RTP-Proxy: ignoring answer from local" call-id)
                               (-> {:result "ok" :sdp sdp} mk-reply send))
                           (let [circs          (for [[media port] ports
                                                      :let [med   (merge (call-data media) {:distant-port port :distant-ip ip})
                                                            cid   (:circuit med)
                                                            b-hop (-> cid circ/get-data :backward-hop)]]
                                                  (do (swap! calls assoc-in [call-id media] med)
                                                      (circ/update-data cid [:dist-dest] {:host ip :port port})
                                                      (c/update-data b-hop [:type] :rtp-ap)
                                                      [port (:local-circ-port med)]))
                                 sdp            (str/replace sdp ip local-ip)
                                 sdp            (reduce #(change-port %1 (first %2) (second %2)) sdp circs)]
                             (log/info "RTP-Proxy: Adding call ID [answer]" call-id "using circuits:" (get-call-circs call-id)) 
                             (-> {:result "ok" :sdp sdp} mk-reply send)))))]
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
    (c/add socket {:ctype :udp :type :udp-rtpp :cs :server})
    (c/add-listeners socket {:message  (partial process config socket)
                             :error    err
                             :close    err})
    socket))
