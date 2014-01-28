(ns aqua-node.rate
  (:require [cljs.core :as cljs]
            [cljs.nodejs :as node]
            [cljs.core.async :refer [chan <! >!]]
            [aqua-node.log :as log]
            [aqua-node.conns :as c]
            [aqua-node.circ :as circ])
  (:require-macros [cljs.core.async.macros :as m :refer [go]]))


(defn queue [c f]
  (let [{p :period t :tokens} (-> c c/get-data :rate)]
    (when-not (zero? t)
      (c/update-data c [:rate :tokens] (dec t))
      (js/setTimeout f p))))

(defn reset [config c]
  (let [{t :tokens tot :total} (:rate (c/get-data c))]
    (doseq [i (range t)]
      (js/setImmediate #(circ/padding config c))) ;; Immediate is inaccurate. timeout on period * ratio t?
    (c/update-data c [:rate :tokens] tot)))

(defn init [config {t :tokens p :period :as rate} c]
  (log/info "Rate limiter:" t "packets per" p "millisecond period.")
  (c/update-data c [:rate] (merge rate {:tokens t
                                        :total  t}))
  (js/setInterval #(reset config c) p))
