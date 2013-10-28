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

(defn rm-conn [conn]
  (swap! connections dissoc conn)
  conn)

(defn kill-conn [conn]
  (println "killing a conn")
  (-> conn rm-conn .destroy))

(defn handshake [c data]
  (println "entered hs")
  (let [len   (.-length data)
        b     #(.readUInt8 data %)
        state (:state (@connections c))]
    (if (> len 2)
      (let [socks-vers (b 0)
            nb-auth-methods (b 1)
            no-auth? (some zero? (map b (range 2 (min len (+ 2 nb-auth-methods)))))]
        (if no-auth? 
          (do 
            (println (str "no auth " nb-auth-methods " -" state "-"))
            (-> c 
              (set-conn-state :request)
              (.write (js/Buffer. (cljs/clj->js [0x05, 0x00])))))
          (kill-conn c)))
      (kill-conn c))))

(defn request [c data]
  (let [len (.-length data)]
    (println len)
    (doseq [i (range len)]
      (println (str "buf: " (.readUInt8 data i))))))

(defn socks-recv [c data]
  (let [state (:state (@connections c))]
    (println (str "state: " state))
    (condp = state
      :handshake   (handshake c data)
      :request     (request c data)
      (kill-conn c))))

(defn -main [& args]
  (let [net     (node/require "net")
        recv    (fn [c data]
                  (let [len (.-length data)
                        b   #(.readUInt8 data %)
                        state (:state (@connections c))]
                    (when (> len 2)
                      (let [socks-vers (b 0)
                            nb-auth-methods (b 1)
                            no-auth? (some zero? (map b (range 2 (+ 2 nb-auth-methods))))]
                        (println @connections)
                        (if no-auth? 
                          (println (str "no auth " nb-auth-methods " -" state "-"))
                          (kill-conn c)))))
                  (println (str "recv: " data))
                  (doseq [i (range (.-length data))]
                    (println (str "buf: " (.readUInt8 data i))))
                  (.write c "lolz\n"))
        srv     (.createServer net (fn [c] 
                                     (println "conn start")
                                     (-> c 
                                       (add-conn)
                                       (.on "end" #(println "conn end")) 
                                       (.on "error" #(println "conn error")) 
                                       (.on "data" #(socks-recv c %)) 
                                       )))
        ]
    (.listen srv 6666 #(println (str "listening on: " (-> srv .address .-port))))))

(set! *main-cli-fn* -main)
