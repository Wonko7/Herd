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
  (let [{p :period t :tokens} (-> c c/get-data :rate)]
    (if-not (zero? t)
      (do (c/update-data c [:rate :tokens] (dec t))
          (c/update-data c [:rate :f] f))
      (log/error "Rate limiter; dropped one.")))) ;; FIXME -> add to list if not= t 1.

(defn reset [config c]
  (let [{t :tokens tot :total send :f} (:rate (c/get-data c))]
    (if (zero? t)
      (send)
      (js/setImmediate #(circ/padding config c)))
    (c/update-data c [:rate :tokens] tot)))

(defn init [config {t :tokens p :period :as rate} c]
  (assert (= t 1) "Rate limiter: tokens should be set to 1")
  (log/info "Rate limiter:" t "packets per" p "millisecond period.")
  (c/update-data c [:rate] (merge rate {:tokens t
                                        :total  t}))
  (js/setInterval #(reset config c) p))
