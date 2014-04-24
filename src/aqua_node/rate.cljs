(ns aqua-node.rate
  (:require [cljs.core :as cljs]
            [cljs.nodejs :as node]
            [cljs.core.async :refer [chan <! >!]]
            [aqua-node.log :as log]
            [aqua-node.conns :as c]
            [aqua-node.circ :as circ])
  (:require-macros [cljs.core.async.macros :as m :refer [go]]))


;; FIXME t now always = 1. Keeping :tokens for now in case we change our minds.

(defn queue [c f]
  "Add a function that will send a packet to the queue on the given socket."
  (let [{p :period t :tokens fs :fs} (-> c c/get-data :rate)] ;; fs: functions queue
    (if (zero? p)
      (f) ;; period is zero, immediate send, no chaffing.
      (do (when-not (zero? t)
            (do (c/update-data c [:rate :tokens] (dec t))))   ;; -> not counting these anymore.
          (if (< (count fs) 10)
            (c/update-data c [:rate :fs] (concat fs [f]))     ;; add f to fs in last position.
            (log/error "Rate limiter; dropped one."))))))     ;; FIXME -> add to list if not= t 1.

(defn pop-write [config c]
  "pop the write function queue."
  (let [{t :tokens tot :total [f & fs] :fs} (:rate (c/get-data c))]
    (if f
      (do (f)                                         ;; f is called and sends a packet.
          (c/update-data c [:rate :fs] fs))           ;; update queue
      (when (.-writable c)
        (comment (js/setImmediate #(circ/padding config c)))))  ;; send a padding packet instead.
    (c/update-data c [:rate :tokens] tot)))           ;; -> also useless.

(defn init [{{p :period} :rate :as config} c]
  "Initialise rate limiter on a socket."
  (let [t 1]
    (log/info "Rate limiter:" t "packet per" p "millisecond period.")
    ;; init rate data on socket metadata:
    (c/update-data c [:rate] {:period p
                              :tokens t
                              :total  t
                              :queue  (partial queue c)})
    (when-not (zero? p)
      (js/setInterval #(pop-write config c) p))))
