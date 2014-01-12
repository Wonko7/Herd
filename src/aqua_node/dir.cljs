(ns aqua-node.dir
  (:require [cljs.core :as cljs]
            [cljs.nodejs :as node]
            [cljs.core.async :refer [chan <! >!]]
            [clojure.string :as str]
            [aqua-node.buf :as b]
            [aqua-node.log :as log])
  (:require-macros [cljs.core.async.macros :as m :refer [go]]))

(def dir (atom {}))
(def net-info (atom nil))

(defn rm [id]
  (swap! dir dissoc id))

(defn rm-nw [id]
  (swap! net-info dissoc id))

(defn recv-client-info [config srv msg]
  (let [[client msg] (parse-addr msg)
        [mix]        (parse-addr msg)
        cip          (-> host client)
        entry        (@dir cip)
        to-id        (js/setTimeout #(rm cip) 600000)]
    (when entry
      (js/clearTimeout (:timeout entry)))
    (swap! dir merge {cip {:mix mix :client client :timeout to-id}})))

(defn recv-nw-info [config srv msg]
  (let [nb      (.readUInt32BE msg 0)]
    (loop [i 0, m msg]]
      (when (< i nb)
        (let [[mix msg]        (parse-addr msg)
              reg              (.readUInt8 msg 0)
              mip              (:host mix)
              entry            (mip @net-info)
              to-id            (js/setTimeout #(rm-nw mip) 600000)]]
          (when entry
            (js/clearTimeout (:timeout entry)))
          (swap! net-info merge {mip {:mix mix :geo reg}})
          (recur (inc i) (.slice msg 1)))))))

(def to-cmd
  {0   {:name :client-info  :fun recv-client-info}
   1   {:name :net-info     :fun recv-net-info}})

(def from-cmd
  (apply merge (for [k (keys to-cmd)]
                 {((to-cmd k) :name) k})))
(defn send [srv loc]
  (.write srv "oh hi there"))

(defn process [config srv buf]
  (when (> (.-length buf) 4) ;; FIXME put real size when message header is finalised.
  (let [[r1 r2 r4] (b/mk-readers buf)
        cmd        (r1 0)
        sz         (r1 1)
        msg        (.slice 2)
        process    (-> cmd to-cmd :fun)]
    (if process
      (try (process config srv msg)
           (catch js/Object e (log/c-error e (str "Malformed Dir message" (to-cmd cmd)))))
      (log/info "Net-Info: invalid message command")))))
