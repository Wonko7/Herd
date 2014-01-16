(ns aqua-node.dir
  (:require [cljs.core :as cljs]
            [cljs.nodejs :as node]
            [cljs.core.async :refer [chan <! >!]]
            [clojure.string :as str]
            [aqua-node.buf :as b]
            [aqua-node.log :as log]
            [aqua-node.conns :as c]
            [aqua-node.conn-mgr :as conn]
            [aqua-node.parse :as conv]
            [aqua-node.geo :as geo])
  (:require-macros [cljs.core.async.macros :as m :refer [go]]))


;; defs & helpers ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(declare to-cmd from-cmd)

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
        id-len       (-> config :ntor-values :node-id-len)
        [id pub msg] (b/cut (.slice msg 2) id-len (+ id-len (-> config :ntor-values :h-len)))
        [client msg] (conv/parse-addr msg)
        ip           (:host client)
        [mix msg]    (if (= role :app-proxy)
                       (conv/parse-addr msg)
                       [nil msg])]
    [{:mix mix :host ip :port (:port client) :reg (geo/int-to-reg reg) :role role :auth {:srv-id id :pub-B pub}} msg]))

(defn mk-info-buf [info]
  (let [zero  (-> [0] cljs/clj->js b/new)
        role  (if (= :app-proxy (:role info)) 0 1)
        msg   [(-> [role (-> info :reg geo/reg-to-int)] cljs/clj->js b/new)
               (-> info :auth :srv-id)
               (-> info :auth :pub-B)
               (b/new (conv/dest-to-tor-str {:type :ip4 :proto :udp :host (:host info) :port (:port info)}))
               zero]
        msg   (if (zero? role)
                (concat msg [(b/new (conv/dest-to-tor-str (merge (:mix info) {:proto :udp :type :ip4}))) zero])
                msg)]
    (apply b/cat msg)))

(defn mk-net-buf! []
  (reset! net-info-buf (b/new 5))
  (.writeUInt8    @net-info-buf (from-cmd :net-info) 0)
  (.writeUInt32BE @net-info-buf (count @mix-dir) 1)
  (doseq [k (keys @mix-dir)]
    (swap! net-info-buf b/copycat2 (mk-info-buf (@mix-dir k)))))


;; send things ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn send-client-info [config soc geo mix done-chan]
  (let [header (-> [(from-cmd :client-info)] cljs/clj->js b/new)
        info   {:auth {:srv-id   (-> config :auth :aqua-id :id)
                       :pub-B    (-> config :auth :aqua-id :pub)}
                :host (-> config :external-ip)
                :port (-> config :aqua :port)
                :role (or (->> config :roles (filter #(= :app-proxy)) first) :mix)
                :mix  mix
                :reg  (-> geo :reg)}]
    (parse-info config (mk-info-buf info))
    (.write soc (b/copycat2 header (mk-info-buf info)) #(go (>! done-chan :done)))))

(defn send-net-request [config soc done]
  (.write soc (-> [(from-cmd :net-request) 101] cljs/clj->js b/new) #(go (>! done :done)))) ;; FIXME the done channel isn't necessary, I was tired. get rid of it here when you can.

(defn send-query [config soc ip]
  (.write soc (b/cat (-> [(from-cmd :query)] cljs/clj->js b/new)
                     (b/new (conv/dest-to-tor-str {:proto :udp :type :ip4 :host ip :port 0}))
                     (-> [0] cljs/clj->js b/new))))

;; process recv ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn recv-client-info [config srv msg recv-chan]
  (let [[info]      (parse-info config msg)
        ip          (:host info)
        role        (:role info)]
    (if (= role :mix)
      (do (swap! mix-dir merge {ip info})
          (mk-net-buf!)))
      (let [entry   (@app-dir ip)
            to-id   (js/setTimeout #(rm ip) 600000)]
        (when entry
          (js/clearTimeout (:timeout entry)))
        (swap! app-dir merge {ip (merge {:timeout to-id} info)})))
  (when recv-chan
    (go (>! recv-chan :got-geo))))

(defn recv-net-info [config srv msg recv-chan]
  (let [nb      (.readUInt32BE msg 0)]
    (loop [i 0, msg (.slice msg 4)]
      (when (< i nb)
        (let [[info msg] (parse-info config msg)]
          (swap! net-info merge {(:host info) info})
          (recur (inc i) msg))))
    (when recv-chan
      (go (>! recv-chan :got-geo)))))

(defn recv-net-request [config soc msg recv-chan]
  (.write soc @net-info-buf))

(defn recv-query [config soc msg recv-query]
  (println (-> msg conv/parse-addr first :host (@app-dir)))
  (println (-> msg conv/parse-addr))
  (println (.toString msg))
  (if-let [info (-> msg conv/parse-addr first :host (@app-dir))]
    (.write soc (b/copycat2 (-> [(from-cmd :query-ans)] cljs/clj->js b/new)
                            (mk-info-buf info)))
    (.write soc "no")))

(defn recv-query-ans [config soc msg recv-query]
  (go (if (= 2 (.-length msg))
        (>! recv-query :no)
        (>! recv-query (parse-info config msg)))))

(def to-cmd
  {0   {:name :client-info  :fun recv-client-info}
   1   {:name :net-info     :fun recv-net-info}
   2   {:name :net-request  :fun recv-net-request}
   3   {:name :query        :fun recv-query}
   4   {:name :query-ans    :fun recv-query-ans}})

(def roles {:mix 0 :app-proxy 1})

(def from-cmd
  (apply merge (for [k (keys to-cmd)]
                 {((to-cmd k) :name) k})))

(defn process [config srv buf & [recv-chan]]
  (when (> (.-length buf) 0) ;; FIXME put real size when message header is finalised.
    (let [cmd        (.readUInt8 buf 0)
          msg        (.slice buf 1)
          process    (-> cmd to-cmd :fun)]
      (log/info "Dir: Recieved:" (-> cmd to-cmd :name))
      (if process
        (try (process config srv msg recv-chan)
             (catch js/Object e (log/c-error e (str "Aqua-Dir: Malformed message" (to-cmd cmd)))))
        (log/info "Net-Info: invalid message command")))))


;; interface ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; FIXME things like get-net-info & register will move here.

(defn query [{dir :remote-dir :as config} ip]
  (log/info "querying dir for:" ip)
  (let [done (chan)
        c    (conn/new :dir :client dir config {:connect #(go (>! done :connected))})]
    (c/add-listeners c {:data #(process config c % done)})
    (go (<! done)
        (send-query config c ip)
        (let [[info] (<! done)]
          (c/rm c)
          (.end c)
          info))))
