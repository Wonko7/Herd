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
  (println (str dest))
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
                             addr-type (b 3)]
                         (doseq [i (range len)]
                           (println (str "buf: " (.readUInt8 data i))))
                         (if (not= cmd 1) 
                           (kill-conn c "bad request command")
                           (condp = addr-type
                             1 (if (< len 10)
                                 (kill-conn c "ip4 info too small")
                                 (-> c
                                   (set-conn-dest {:type :ip4 :addr (apply str (interpose "." (map b (range 4 8)))) :port (b16 8)})
                                   (set-conn-state :relay)))
                             (kill-conn c "bad address type"))))
                       (kill-conn c "too small")))
        ]
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
