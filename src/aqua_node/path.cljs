(ns aqua-node.path
  (:require [cljs.core :as cljs]
            [cljs.nodejs :as node]
            [cljs.core.async :refer [chan <! >! filter< map<]]
            [aqua-node.log :as log]
            [aqua-node.conns :as c]
            [aqua-node.circ :as circ])
  (:require-macros [cljs.core.async.macros :as m :refer [go-loop go]]))


;; make requests: path level ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn create-single [config [n & nodes]]
  "Creates a single path. Assumes a connection to the first node exists."
  (let [socket (c/find-by-dest (:dest n))
        id     (circ/create config socket (:auth n))
        ctrl   (chan)]
    (circ/update-data id [:roles] [:origin])
    (circ/update-data id [:ctrl] ctrl)
    (circ/update-data id [:mk-path-fn] #(go (>! ctrl :next)))
    (go (loop [cmd (<! ctrl), [n & nodes] nodes]
          (when n
            (circ/relay-extend config id n)
            (log/debug "Circ" id "extended, remaining =" (count nodes)) ;; debug
            (recur (<! ctrl) nodes)))
        (let [cmd  (<! ctrl)
              circ (circ/get-data id)]
          (circ/relay-begin config id (:ap-dest circ))
          (circ/update-data id [:state] :relay-ack-pending)
          (<! ctrl)
          (circ/update-data id [:state] :relay)
          (>! (-> circ :backward-hop c/get-data :ctrl) :relay)))
    id))


;; path ap glue ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn app-proxy-forward-udp [config s b]
  (let [circ-id   (:circuit (c/get-data s))
        circ-data (circ/get-data circ-id)
        config    (merge config {:data s})]
    (if (= (-> circ-data :state) :relay)
      (circ/relay-data config circ-id b)
      (log/info "UDP: not ready for data, dropping on circuit" circ-id))))

(defn app-proxy-forward [config s]
  (let [circ-id   (:circuit (c/get-data s))
        circ-data (circ/get-data circ-id)
        config    (merge config {:data s})]
    (if (= (-> circ-data :state) :relay)
      (when (circ/done?)
        (if-let [b (.read s 1350)]
          (do (circ/inc-block)
              (circ/relay-data config circ-id b))
          (when-let [b (.read s)]
            (circ/inc-block)
            (circ/relay-data config circ-id b))))
      (log/info "TCP: not ready for data, dropping on circuit" circ-id))))

(defn attach-local-udp4 [config circ-id forward-to]
  (go (let [ctrl      (chan)
            udp-sock  (.createSocket (node/require "dgram") "udp4") ;; FIXME should not be hardcoded to ip4
            port      (do (.bind udp-sock 0 (:host forward-to) #(go (>! ctrl (-> udp-sock .address .-port))))
                          (<! ctrl))
            dest      {:type :ip4 :proto :udp :host "0.0.0.0" :port 0}]
        (-> udp-sock
            (c/add {:ctrl ctrl :type :udp-ap :circuit circ-id :from forward-to :ap-dest forward-to}) ;; FIXME ap-dest mess.
            (c/add-listeners {:message (partial app-proxy-forward-udp config udp-sock)}))
        (circ/update-data circ-id [:ap-dest] dest)
        (circ/update-data circ-id [:backward-hop] udp-sock)
        (>! (:ctrl (circ/get-data circ-id)) :relay-connect)
        (assert (= :relay (<! ctrl)))
        [udp-sock port])))


;; path pool ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def pool (atom []))

(defn init-pool [config path N]
  (doseq [n (range N)]
    (swap! pool conj (create-single config path))))

(defn get-path []
  (let [[p & ps] @pool]
    (reset! pool (vec ps))
    ;(init-pool config path 1) --> where do we get path from?
    p))
