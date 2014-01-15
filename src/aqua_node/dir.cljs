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

(def roles {:mix 0 :app-proxy 1})

(def from-cmd
  (apply merge (for [k (keys to-cmd)]
                 {((to-cmd k) :name) k})))

(def mix-dir (atom {}))
(def app-dir (atom {}))
(def net-info (atom {}))
(def net-info-buf (atom nil))

(defn get-net-info []
  @net-info)

(defn rm [id]
  (swap! app-dir dissoc id))

(defn rm-net [id]
  (swap! net-info dissoc id))


;; encode/decode ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn parse-info [config msg]
  (let [role         (if (zero? (.readUInt8 msg 0)) :app-proxy :mix)
        reg          (.readUInt8 msg 1)
        [client msg] (conv/parse-addr msg)
        [id pub msg] (b/cut msg (-> config :ntor-values :iv-len) (-> config :ntor-values :key-len))
        ip           (:host client)
        [mix msg]    (if (= role :app-proxy)
                       (conv/parse-addr msg)
                       [nil msg])]
    [{:mix mix :ip ip :client client :reg reg :role role :id id :pub pub} msg]))

(defn mk-info-buf [info]
  (let [zero  (-> [0] cljs/clj->js b/new)
        role  (if (= :app-proxy (:role info)) 0 1)
        info  [(-> [role (-> info :reg geo/reg-to-int)] cljs/clj->js b/new)
               (:id info)
               (:pub info)
               (conv/dest-to-tor-str {:type :ip4 :proto :udp :host (:ip info) :port 0})
               zero]
        info  (if (zero? role)
                (concat info [(conv/dest-to-tor-str (:mix info)) zero])
                info)]
    (apply b/cat info)))

(defn mk-net-buf! []
  (reset! net-info-buf (b/new 5))
  (.writeUInt8    net-info-buf (to-cmd :net-info) 0)
  (.writeUInt32BE net-info-buf (count @mix-dir) 1)
  (doseq [k (keys @mix-dir)]
    (swap! net-info-buf b/copycat2 (mk-info-buf (k @mix-dir)))))


;; send things ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn send-client-info [config soc geo mix done-chan]
  (let [header (-> [(to-cmd :client-info)] cljs/clj->js b/new)
        info   {:id   (-> config :auth :aqua-id :id)
                :pub  (-> config :auth :aqua-id :pub)
                :ip   (-> config :remote-ip)
                :role (or (->> config :roles (filter #(= :app-proxy)) first) :mix)
                :mix  (conv/dest-to-tor-str mix)
                :reg  (-> geo :reg)}]
    (.write soc (b/copycat2 header (mk-info-buf info)) #(go (>! done-chan :done)))))

(defn send-net-request [config soc done]
  (.write soc (->> [(to-cmd :net-request)] cljs/clj->js b/new) #(go (>! done :done))))


;; process recv ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn recv-client-info [config srv msg recv-chan]
  (let [[info]      (parse-info config msg)
        ip          (:ip info)
        role        (:role info)]
    (if (= role :mix)
      (do (swap! mix-dir merge {ip info})
          (mk-net-buf!)))
      (let [entry   (@app-dir ip)
            to-id   (js/setTimeout #(rm ip) 600000)]
        (when entry
          (js/clearTimeout (:timeout entry)))
        (swap! app-dir merge {ip (merge {:timeout to-id} info)})))
  (go (>! recv-chan :got-geo)))

(defn recv-net-info [config srv msg recv-chan]
  (let [nb      (.readUInt32BE msg 0)]
    (loop [i 0, m msg]
      (when (< i nb)
        (let [[info msg] (parse-info config msg)]
          (swap! net-info merge {{:ip info} info})
          (recur (inc i) msg))))
    (when recv-chan
      (go (>! recv-chan :got-geo)))))

(defn recv-net-request [config soc msg recv-chan]
  (.write soc @net-info-buf))

(defn process [config srv buf & [recv-chan]]
  (when (> (.-length buf) 4) ;; FIXME put real size when message header is finalised.
    (let [cmd        (.readUInt8 buf 0)
          msg        (.slice buf 1)
          process    (-> cmd to-cmd :fun)]
      (if process
        (try (process config srv msg recv-chan)
             (catch js/Object e (log/c-error e (str "Aqua-Dir: Malformed message" (to-cmd cmd)))))
        (log/info "Net-Info: invalid message command")))))
