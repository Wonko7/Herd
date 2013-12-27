(ns aqua-node.path
  (:require [cljs.core :as cljs]
            [cljs.nodejs :as node]
            [cljs.core.async :refer [chan <! >! filter<]]
            [aqua-node.log :as log]
            [aqua-node.conns :as c]
            [aqua-node.circ :as circ])
  (:require-macros [cljs.core.async.macros :as m :refer [go-loop go]]))


;; make requests: path level ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn create-single [config [n & nodes]]
  "Creates a single path. Assumes a connection to the first node exists."
  (let [socket (c/find-by-dest (:dest n))
        id     (circ/create config socket (:auth n))
        ctrl   (chan)
        ctrl-x (filter< #(= :next %) ctrl)
        ctrl-r (filter< #(= :relay-connect %) ctrl)]
    (circ/update-data id [:roles] [:origin])
    (circ/update-data id [:ctrl] ctrl)
    (circ/update-data id [:mk-path-fn] #(go (>! ctrl :next)))
    (go-loop [cmd (<! ctrl-x), [n & nodes] nodes]
             (when n
               (circ/relay-extend config id n)
               (log/debug "Circ" id "extended, remaining =" (count nodes)) ;; debug
               (recur (<! ctrl-x) nodes)))
    (go (let [cmd  (<! ctrl-r)
              circ (circ/get-data id)]
          (circ/relay-begin config id (:ap-dest circ))
          (circ/update-data id [:state] :relay-ack-pending)
          (circ/update-data id [:state] :relay)
          (>! (-> circ :backward-hop c/get-data :ctrl) :relay))) ;; FIXME -> will go in begin-ack
    id))

(def pool (atom []))

(defn init-pool [config path N]
  (doseq [n (range N)]
    (swap! pool conj (create-single config path))))

(defn get-path []
  (let [[p & ps] @pool]
    (reset! pool (vec ps))
    ;(init-pool config path 1) --> where do we get path from?
    p))
