(ns aqua-node.rate
  (:require [cljs.core :as cljs]
            [cljs.nodejs :as node]
            [cljs.core.async :refer [chan <! >!]]
            [aqua-node.log :as log]
            [aqua-node.conns :as c]
            [aqua-node.circ :as circ])
  (:require-macros [cljs.core.async.macros :as m :refer [go]]))


;; FIXME t now always = 1.

(defn queue [c f]
  (let [{p :period t :tokens fs :fs} (-> c c/get-data :rate)]
    (if (zero? p)
      (f) ;; immediate send, no chaffing.
      (do (when-not (zero? t)
            (do (c/update-data c [:rate :tokens] (dec t)))) ;; -> not counting these anymore.
          (if (< (count fs) 10)
            (c/update-data c [:rate :fs] (concat fs [f]))
            (log/error "Rate limiter; dropped one.")))))) ;; FIXME -> add to list if not= t 1.

(defn reset [config c]
  (let [{t :tokens tot :total [f & fs] :fs} (:rate (c/get-data c))]
    (if f
      (do (f)
          (c/update-data c [:rate :fs] fs))
      (when (.-writable c)
        (js/setImmediate #(circ/padding config c))))
    (c/update-data c [:rate :tokens] tot))) ;; -> also useless.

(defn init [{{t :tokens p :period} :rate :as config} c]
  (let [t 1]
    (log/info "Rate limiter:" t "packets per" p "millisecond period.")
    (c/update-data c [:rate] {:period p
                              :tokens t
                              :total  t
                              :queue  (partial queue c)})
    (when-not (zero? p)
      (js/setInterval #(reset config c) p))))
