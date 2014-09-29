(ns aqua-node.rate
  (:require [cljs.core :as cljs]
            [cljs.nodejs :as node]
            [cljs.core.async :refer [chan <! >!]]
            [aqua-node.buf :as b]
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
            (c/update-data c [:rate :tokens] (dec t)))   ;; -> not counting these anymore.
          (if (< (count fs) 5)
            (c/update-data c [:rate :fs] (concat fs [f]))     ;; add f to fs in last position.
            (log/error "Rate limiter; dropped one."))))))     ;; FIXME -> add to list if not= t 1.

(defn pop-write [config c data]
  "pop the write function queue."
  (let [{t :tokens tot :total [f & fs] :fs} (:rate data)]
    (if (and f (.-writable c))
      (do (f)                                         ;; f is called and sends a packet.
          (c/update-data c [:rate :fs] fs))           ;; update queue
      (when (.-writable c)
        (circ/padding config c)))                     ;; send a padding packet instead.
    (c/update-data c [:rate :tokens] tot)))           ;; -> also useless.

(def timer (atom nil))

(defn init [{{p :period} :rate :as config} c]
  "Initialise rate limiter on a socket."
  (let [t 1
        keep-alive-interval (:keep-alive-interval config)]
    (log/info "Rate limiter:" t "packet per" p "millisecond period.")
    ;; init rate data on socket metadata:
    (c/update-data c [:rate] {:period p
                              :tokens t
                              :total  t
                              :queue  (partial queue c)})
    (when-not (or (zero? p) @timer)
      (reset! timer (js/setInterval #(doseq [[c data] (filter (fn [[c data]]
                                                                (:rate data))
                                                              (c/get-all))]
                                       (pop-write config c data))
                                    p))
      (js/setInterval  #(let [now (.now js/Date)]
                          (doseq [[c data] (filter (fn [[c data]]
                                                     (:rate data))
                                                   (c/get-all))]
                            (when (> (- now (:keep-alive-date data)) keep-alive-interval)
                              (let [id (-> data :auth :srv-id)]
                                (log/info "Lost connection to" (when id (b/hx id)))
                                (circ/destroy-from-socket config c)))))
                      keep-alive-interval))))
