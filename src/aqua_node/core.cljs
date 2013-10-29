(ns aqua-node.core
  (:require [cljs.core :as cljs]
            [cljs.nodejs :as node]))

(def connections (atom {}))

(defn add-conn [conn]
  (swap! connections assoc-in [conn :state] :handshake)
  conn)

(defn set-conn-state [conn s]
  (swap! connections assoc-in [conn :state] s)
  conn)

(defn set-conn-dest [conn dest]
  (println dest)
  (swap! connections assoc-in [conn :dest] dest)
  conn)

(defn rm-conn [conn]
  (swap! connections dissoc conn)
  conn)

(defn kill-conn [conn & [err]]
  (println (str "killing a conn: " (or err "unknow")))
  (-> conn rm-conn .destroy))


;; FIXME: most kill-conns should be wait for more data.
;;        also, do ipv4 & dns.
(defn socks-recv [c data]
  (let [len        (.-length data)
        b          #(.readUInt8 data %)
        b16        #(.readUInt16BE data %)
        socks-vers (b 0)       
        state      (:state (@connections c))
        ;; handle socks states:
        handshake  (fn [c data]
                     (println "entered hs")
                     (if (> len 2)
                       (let [nb-auth-methods (b 1)
                             no-auth? (some zero? (map b (range 2 (min len (+ 2 nb-auth-methods)))))]
                         (if no-auth? 
                           (-> c 
                             (set-conn-state :request)
                             (.write (js/Buffer. (cljs/clj->js [0x05, 0x00]))))
                           (kill-conn c "bad auth method")))
                       (kill-conn c "too small")))
        request    (fn [c data]
                     (println "entered rq")
                     (if (> len 4)
                       (let [cmd       (b 1)
                             addr-type (b 3)
                             [too-short? type to-port to-ip] (condp = addr-type
                                                               1 [(< len 10) :ipv4 #(b16 8)  #(->> (range 4 8) (map b) (interpose ".") (apply str))]
                                                               4 [(< len 5)  :ipv6 #(b16 20) #(->> (.toString data "hex" 4 20) (partition 4) (interpose [\:]) (apply concat) (apply str))]
                                                               3 (let [ml?  (>= len 5)
                                                                       alen (when ml? (b 4))
                                                                       aend (when ml? (+ alen 5))]
                                                                   [(or (not ml?) (< len (+ 2 aend))) :dns #(b16 aend) #(.toString data "utf8" 5 aend)])
                                                               (repeat false))]
                         (if (not= cmd 1) ;; FIXME: Add udp here.
                           (kill-conn c "bad request command")
                           (if too-short? ;; to-[ip/port] are functions to avoid executing the code if not enough data
                             (kill-conn c (str "not enough data. conn type: " type))
                             (-> c
                               (set-conn-dest {:type type :addr (to-ip) :port (to-port)})
                               (set-conn-state :relay)))))))]
    (println (str "state: " state))
    (if (not= socks-vers 5)
      (kill-conn c "bad socks version")
      (condp = state
        :handshake   (handshake c data)
        :request     (request c data)
        (kill-conn c)))))

(defn -main [& args]
  (let [net     (node/require "net")
        srv     (.createServer net (fn [c] 
                                     (println "conn start")
                                     (-> c 
                                       (add-conn)
                                       (.on "end" #(println "conn end")) 
                                       (.on "error" kill-conn) 
                                       (.on "data" #(socks-recv c %)))))]
    (.listen srv 6666 #(println (str "listening on: " (-> srv .address .-port))))))

(set! *main-cli-fn* -main)
