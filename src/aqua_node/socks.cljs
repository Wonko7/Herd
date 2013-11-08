(ns aqua-node.socks
  (:require [cljs.core :as cljs]
            [cljs.nodejs :as node]
            [aqua-node.buf :as b]
            [aqua-node.conns :as c]))


(defn kill-conn [conn & [err]]
  (println (str "###  App-Proxy: killing a conn: " (or err "unknow")))
  (-> conn c/rm .destroy))

;; FIXME: most kill-conns should be wait for more data.
(defn socks-recv [c new-conn-handler data] ;; FIXME: either call data-handler if socks still needs processing when forwarding data, or remove all listeners when connection is ready and add the given listener.
  (let [len        (.-length data)
        [r8 r16]   (b/mk-readers buff)
        socks-vers (r8 0)
        state      (-> c c/get-data :socks :state)
        ;; handle socks states:
        handshake  (fn [c data]
                     (if (> len 2)
                       (let [nb-auth-methods (r8 1)
                             no-auth? (some zero? (map r8 (range 2 (min len (+ 2 nb-auth-methods)))))]
                         (if no-auth?
                           (-> c
                               (c/update-data [:socks :state] :request)
                               (.write (js/Buffer. (cljs/clj->js [0x05, 0x00]))))
                           (kill-conn c "bad auth method")))
                       (kill-conn c "too small")))
        request    (fn [c data]
                     (if (> len 4)
                       (let [cmd       (r8 1)
                             addr-type (r8 3)
                             reply     (js/Buffer. len)
                             [too-short? type to-port to-ip] (condp = addr-type
                                                               1 [(< len 10) :ipv4 #(r16 8)  #(->> (range 4 8) (map r8) (interpose ".") (apply str))]
                                                               4 [(< len 5)  :ipv6 #(r16 20) #(->> (.toString data "hex" 4 20) (partition 4) (interpose [\:]) (apply concat) (apply str))]
                                                               3 (let [ml?  (>= len 5)
                                                                       alen (when ml? (r8 4))
                                                                       aend (when ml? (+ alen 5))]
                                                                   [(or (not ml?) (< len (+ 2 aend))) :dns #(r16 aend) #(.toString data "utf8" 5 aend)])
                                                               (repeat false))]
                         (.copy data reply)
                         (.writeUInt8 reply 0 1)
                         (if (not= cmd 1) ;; FIXME: Add udp here.
                           (kill-conn c "bad request command")
                           (if too-short? ;; to-[ip/port] are functions to avoid executing the code if not enough data
                             (kill-conn c (str "not enough data. conn type: " type))
                             (-> c
                                 (c/update-data [:socks :dest] {:type type :addr (to-ip) :port (to-port)})
                                 (c/update-data [:socks :state] :relay)
                                 (.removeAllListeners "data")
                                 (c/add-listeners {:data new-conn-handler})
                                 (.write reply)))))))]
    (if (not= socks-vers 5)
      (kill-conn c "bad socks version")
      (condp = state
        :handshake   (handshake c data)
        :request     (request c data)
        (kill-conn c)))))

(defn create-server [{addr :addr port :port} new-conn-handler]
  (let [net     (node/require "net")
        srv     (.createServer net (fn [c]
                                     (println (str "###  App-Proxy: new connection on: " (-> c .address .-ip) ":" (-> c .address .-port)))
                                     (-> c
                                         (c/add {:cs :remote-client :type :socks :socks {:state :handshake}})
                                         (c/add-listeners {:end   #(println "###  App-Proxy: connection end")
                                                           :error kill-conn
                                                           :data  #(socks-recv c new-conn-handler %)}))))
        new-srv #(println "###  App-Proxy listening on:" (-> srv .address .-ip) ":" (-> srv .address .-port))]
    (if addr
      (.listen srv port addr new-srv)
      (.listen srv port new-srv))
    (c/add srv {:cs :server :type :socks})))
