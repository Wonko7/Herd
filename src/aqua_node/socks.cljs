(ns aqua-node.socks
  (:require [cljs.core :as cljs]
            [cljs.nodejs :as node]
            [cljs.core.async :refer [chan <! >!]]
            [aqua-node.log :as log]
            [aqua-node.buf :as b]
            [aqua-node.conns :as c])
  (:require-macros [cljs.core.async.macros :as m :refer [go]]))


(defn kill-conn [conn & [err]]
  (log/debug "App-Proxy: killing a conn:" (or err "unknown"))
  (try (-> conn c/rm .destroy)
       (catch js/Object e identity nil)))

;; FIXME: most kill-conns should be wait for more data.
(defn socks-recv [host c data-handler udp-data-handler init-handle close-cb]
  "Parses messages received on socks5 socket."
  (let [data       (.read c)
        len        (.-length data)
        [r8 r16]   (b/mk-readers data)
        ;; only parse the socks version. The rest of the header parsing will be done by
        ;; the appropriate functions.
        socks-vers (r8 0)
        ;; get current socks state: from handshake, to request, to relay.
        state      (-> c c/get-data :socks :state)
        ;; error handling:
        kill-conn  (fn [conn & [err]]
                     (log/error "App-Proxy: killing a conn:" (or err "unknow"))
                     (close-cb conn)
                     (-> conn c/rm .destroy))
        ;; handle socks states:
        handshake  (fn [c data]
                     (if (> len 2)
                       (let [nb-auth-methods (r8 1)
                             no-auth? (some zero? (map r8 (range 2 (min len (+ 2 nb-auth-methods)))))]
                         ;; we only support no-auth authentication method: send ack if its the case.
                         (if no-auth?
                           (-> c
                               (c/update-data [:socks :state] :request)
                               (.write (js/Buffer. (cljs/clj->js [0x05, 0x00]))))
                           (kill-conn c "bad auth method")))
                       (kill-conn c "too small")))
        request    (fn [c data]
                     (if (> len 4)
                       (let [ctrl      (chan)
                             cmd       (r8 1) ;; 1 for tcp, 3 for udp.
                             host-type (r8 3) ;; 1: ip4, 4: ip6, 3 dns.
                             ;; parse destination host to which to connect, according to host-type.
                             [too-short? type to-port to-ip] (condp = host-type ;; to-[ip/port] are functions to avoid executing the code if not enough data
                                                               1 [(< len 10) :ip4 #(r16 8)  #(->> (range 4 8) (map r8) (interpose ".") (apply str))]
                                                               4 [(< len 5)  :ip6 #(r16 20) #(->> (.toString data "hex" 4 20) (partition 4) (interpose [\:]) (apply concat) (apply str))]
                                                               3 (let [ml?  (>= len 5)
                                                                       alen (when ml? (r8 4))
                                                                       aend (when ml? (+ alen 5))]
                                                                   [(or (not ml?) (< len (+ 2 aend))) :dns #(r16 aend) #(.toString data "utf8" 5 aend)])
                                                               (repeat false))] ;; return false to all if unknow host-type.
                         ;; UDP: for udp we create a dedicated socket.
                         ;;      Each data message on that socket will have a destination header.
                         (cond (= cmd 3) (go (if too-short?
                                               (kill-conn c (str "not enough data. conn type: " type))
                                               (let [from      {:proto :udp :type type :host (to-ip) :port (to-port)}
                                                     udp-sock  (.createSocket (node/require "dgram") "udp4") ;; FIXME should not be hardcoded to ip4
                                                     port      (do (.bind udp-sock 0 host #(go (>! ctrl (-> udp-sock .address .-port))))
                                                                   (<! ctrl))
                                                     reply     (b/new 6)
                                                     dest      {:proto :udp :host "0.0.0.0" :port 0}]
                                                 ;; write ip & port of the newly created udp socket.
                                                 (doseq [[v i] (map list (.split host ".") (range))]
                                                   (.writeUInt8 reply v i))
                                                 (.writeUInt16BE reply port 4)
                                                 ;; add socket to conns map, with metadata.
                                                 (-> udp-sock 
                                                     (c/add {:ctype :udp :from from :ctrl ctrl :type :udp-ap :socks {:control-tcp c :dest dest}})
                                                     (c/add-listeners {:message (partial udp-data-handler udp-sock)})
                                                     (init-handle dest))
                                                 (-> c
                                                     (.removeAllListeners "readable")
                                                     (c/add-listeners {:error #(.close udp-sock)
                                                                       :close #(.close udp-sock)}))
                                                 ;; wait for the socket to be marked as :relay in state before sending OK reply.
                                                 (when (= :relay (<! ctrl))
                                                   (.write c (b/cat (b/new (cljs/clj->js [5 0 0 1])) reply))))))
                               ;; TCP: the same socket will be kept for relaying data.
                               (= cmd 1) (if too-short?
                                           (kill-conn c (str "not enough data. conn type: " type))
                                           ;; get destination and prepare reply with OK status.
                                           (let [dest   {:proto :tcp :type type :host (to-ip) :port (to-port)}
                                                 reply  (js/Buffer. (cljs/clj->js [5 0 0 1 0 0 0 0 0 0]))]
                                             (init-handle c dest)
                                             ;; update socket metadata, remove our listeners and add the user's
                                             ;; callbacks for processing incoming data to relay.
                                             (-> c
                                                 (c/update-data [:socks] {:dest dest, :state :relay})
                                                 (c/update-data [:ctrl] ctrl)
                                                 (.removeAllListeners "readable")
                                                 (c/add-listeners {:readable (partial data-handler c)}))
                                             ;; wait for the socket to be marked as :relay in state before sending OK reply.
                                             (go (when (= :relay (<! ctrl))
                                                   (.write c reply)))))
                               :else     (kill-conn c "bad request command")))))]
    (if (not= socks-vers 5)
      (kill-conn c "bad socks version")
      (condp = state
        :handshake   (handshake c data)
        :request     (request c data)
        (kill-conn c)))))

(defn create-server [{host :host port :port} data-handler udp-data-handler init-handle close-cb]
  "Create a tcp server (on given host/port) and add listeners to handle clients."
  (let [error   #(do (close-cb %) (c/rm %))
        net     (node/require "net")
        srv     (.createServer net (fn [c]
                                     (log/debug "App-Proxy: new connection on:" (-> c .address .-ip) (-> c .address .-port))
                                     (-> c
                                         (c/add {:cs :remote-client :type :socks :socks {:state :handshake}})
                                         (c/add-listeners {:end      #(error c)
                                                           :error    #(error c)
                                                           :readable #(socks-recv host c data-handler udp-data-handler init-handle close-cb)}))))
        new-srv #(log/info "App-Proxy listening on:" (-> srv .address .-ip) (-> srv .address .-port))]
    (if host
      (.listen srv port host new-srv)
      (.listen srv port new-srv))
    (c/add srv {:ctype :tcp :cs :server :type :socks})))
