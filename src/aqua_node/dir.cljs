(ns aqua-node.dir
  (:require [cljs.core :as cljs]
            [cljs.nodejs :as node]
            [cljs.core.async :refer [chan <! >!]]
            [clojure.string :as str]
            [aqua-node.buf :as b]
            [aqua-node.log :as log]
            [aqua-node.parse :as conv])
  (:require-macros [cljs.core.async.macros :as m :refer [go]]))


;; defs & helpers ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(declare recv-client-info recv-net-info recv-net-request)

(def to-cmd
  {0   {:name :client-info  :fun recv-client-info}
   1   {:name :net-info     :fun recv-net-info}
   2   {:name :net-request  :fun recv-net-request}})

(def from-cmd
  (apply merge (for [k (keys to-cmd)]
                 {((to-cmd k) :name) k})))

(def dir (atom {}))
(def net-info (atom {}))
(def net-info-buf (atom nil))

(defn get-net-info []
  @net-info)

(defn rm [id]
  (swap! dir dissoc id))

(defn rm-net [id]
  (swap! net-info dissoc id))

(defn mk-net-buf! []
  (reset! net-info-buf (b/new 5))
  (.writeUInt8    net-info-buf (to-cmd :net-info) 0)
  (.writeUInt32BE net-info-buf (count @net-info) 1)
  (doseq [k (keys @net-info)
          :let [m    (@net-info k)
                addr (conv/dest-to-tor-str (:mix m))]]
    (swap! net-info-buf b/cat (b/new addr) (b/new (js/Array. 0 (:geo m))))))


;; send things ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn send-client-info [config soc mix done-chan]
  (let [z  (-> [0] cljs/clj->js b/new)
        m  (b/cat (-> [(to-cmd :client-info)] cljs/clj->js b/new)
                  (conv/dest-to-tor-str {:type :ip4 :proto :udp :host (:extenal-ip config) :port 0})
                  z
                  (conv/dest-to-tor-str mix)
                  z)]
    (.write soc m #(go (>! done-chan :done)))))

(defn send-net-request [config soc done]
  (.write soc (->> [(to-cmd :net-request)] cljs/clj->js b/new) #(go (>! done :done))))

;; process recv ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn recv-client-info [config srv msg recv-chan]
  (let [[client msg] (conv/parse-addr msg)
        [mix]        (conv/parse-addr msg)
        cip          (:host client)
        entry        (@dir cip)
        to-id        (js/setTimeout #(rm cip) 600000)]
    (when entry
      (js/clearTimeout (:timeout entry)))
    (swap! dir merge {cip {:mix mix :client client :timeout to-id}})
    (mk-net-buf!)
    (when recv-chan
      (go (>! recv-chan :got-geo)))))

(defn recv-net-info [config srv msg recv-chan]
  (let [nb      (.readUInt32BE msg 0)]
    (loop [i 0, m msg]
      (when (< i nb)
        (let [[mix msg]        (conv/parse-addr msg)
              reg              (.readUInt8 msg 0)
              mip              (:host mix)
              entry            (mip @net-info)
              to-id            (js/setTimeout #(rm-net mip) 600000)]
          (when entry
            (js/clearTimeout (:timeout entry)))
          (swap! net-info merge {mip {:mix mix :geo reg}})
          (recur (inc i) (.slice msg 1)))))
    (when recv-chan
      (go (>! recv-chan :got-geo)))))

(defn recv-net-request [config soc msg recv-chan]
  (.write soc @net-info-buf))

(defn process [config srv buf & [recv-chan]]
  (when (> (.-length buf) 4) ;; FIXME put real size when message header is finalised.
    (let [[r1 r2 r4] (b/mk-readers buf)
          cmd        (r1 0)
          msg        (.slice buf 1)
          process    (-> cmd to-cmd :fun)]
      (if process
        (try (process config srv msg recv-chan)
             (catch js/Object e (log/c-error e (str "Aqua-Dir: Malformed message" (to-cmd cmd)))))
        (log/info "Net-Info: invalid message command")))))
