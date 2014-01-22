(ns aqua-node.path
  (:require [cljs.core :as cljs]
            [cljs.nodejs :as node]
            [cljs.core.async :refer [chan <! >!]]
            [aqua-node.log :as log]
            [aqua-node.buf :as b]
            [aqua-node.parse :as conv]
            [aqua-node.conns :as c]
            [aqua-node.conn-mgr :as conn]
            [aqua-node.circ :as circ]
            [aqua-node.geo :as geo]
            [aqua-node.dir :as dir])
  (:require-macros [cljs.core.async.macros :as m :refer [go-loop go]]))


;; make requests: path level ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn create-single [config [n & nodes :as all-nodes]]
  "Creates a single path. Assumes a connection to the first node exists."
  (let [socket (c/find-by-dest (:dest n))
        id     (circ/create config socket (:auth n))
        ctrl   (chan)]
    (circ/update-data id [:roles] [:origin])
    (circ/update-data id [:ctrl] ctrl)
    (circ/update-data id [:mk-path-fn] #(go (>! ctrl :next)))
    (circ/update-data id [:path-dest] (-> all-nodes last :dest))
    (go (loop [cmd (<! ctrl), [n & nodes] nodes]
          (when n
            (circ/relay-extend config id n)
            (log/debug "Circ" id "extended, remaining =" (count nodes)) ;; debug
            (recur (<! ctrl) nodes)))
        (let [cmd  (<! ctrl)
              circ (circ/get-data id)]
          (circ/relay-begin config id (:ap-dest circ))
          (circ/update-data id [:state] :relay-ack-pending)
          (circ/update-data id [:path-dest :port] (:port (<! ctrl)))
          (circ/update-data id [:state] :relay)
          (>! (-> circ :backward-hop c/get-data :ctrl) :relay)
          (log/info "Circuit" id "is ready for relay")))
    id))

(defn create-rt [config socket mix]
  "Creates a real time path. Assumes a connection to the first node exists."
  (let [id     (circ/create config socket (:auth mix))
        ctrl   (chan)
        dest   (chan)]
    (circ/update-data id [:roles] [:origin])
    (circ/update-data id [:ctrl] ctrl)
    (circ/update-data id [:dest-ctrl] dest)
    (circ/update-data id [:mk-path-fn] #(go (>! ctrl :next)))
    (go (<! ctrl)
        (let [rt-dest        (<! dest)
              [ap-dest mix2] (<! (dir/query config (:host rt-dest)))]
          (circ/update-data id [:path-dest] rt-dest)
          (if-not mix2
            (>! (-> id circ/get-data :state-ch) :error)
            (do (circ/relay-extend config id (merge mix2 {:dest mix2}))
                (<! ctrl)
                (circ/relay-extend config id (merge ap-dest {:dest ap-dest}))
                (<! ctrl)
                (let [circ (circ/get-data id)]
                  (circ/relay-begin config id rt-dest)
                  (circ/update-data id [:state] :relay-ack-pending)
                  ;(circ/update-data id [:path-dest :port] (:port (<! ctrl)))
                  (circ/update-data id [:state] :relay)
                  (<! ctrl) ;; relay begin.
                  (>! (-> circ :state-ch) :done)
                  ;(>! (-> circ :backward-hop c/get-data :ctrl) :relay)
                  (log/info "RT Circuit" id "is ready for relay")
                  )))))
    id))


;; path ap glue ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn app-proxy-forward-udp [config s b]
  "For packets already encapsulated in socks5 header"
  (let [circ-id   (:circuit (c/get-data s))
        circ-data (circ/get-data circ-id)
        config    (merge config {:data s})]
    (if (= (-> circ-data :state) :relay)
      (circ/relay-data config circ-id b)
      (log/info "UDP: not ready for data, dropping on circuit" circ-id))))

(defn forward-udp [config s b]
  "For packets needing socks5 header"
  (let [circ-id    (:circuit (c/get-data s))
        circ-data  (circ/get-data circ-id)
        dest       (:path-dest circ-data)
        config     (merge config {:data s}) ;; FIXME we are going to have to get rid of this.
        data       (b/new (+ 10 (.-length b)))
        [w1 w2 w4] (b/mk-writers data)]
    (w4 0 0)
    (w1 1 3)
    (.copy (conv/ip4-to-bin (:host dest)) data 4)
    (w2 (:port dest) 8)
    (.copy b data 10)
    (if (= (-> circ-data :state) :relay)
      (circ/relay-data config circ-id data)
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

(defn attach-local-udp4 [config circ-id forward-to & [forwarder]]
  (go (let [ctrl      (chan)
            udp-sock  (.createSocket (node/require "dgram") "udp4") ;; FIXME should not be hardcoded to ip4
            port      (do (.bind udp-sock 0 (:host forward-to) #(go (>! ctrl (-> udp-sock .address .-port))))
                          (<! ctrl))
            dest      {:type :ip4 :proto :udp :host "0.0.0.0" :port 0}]
        (-> udp-sock
            (c/add {:ctype :udp :ctrl ctrl :type :udp-ap :circuit circ-id :local-dest forward-to}) ;; FIXME circ data is messy... need to seperate and harmonise things.
            (c/add-listeners {:message (partial (or forwarder app-proxy-forward-udp) config udp-sock)}))
        (circ/update-data circ-id [:ap-dest] dest)
        (circ/update-data circ-id [:backward-hop] udp-sock)
        (circ/update-data circ-id [:local-dest] forward-to)
        [udp-sock port])))


;; path pool ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def pool (atom []))
(def chosen-mix (atom nil))

(defn init-pool [config soc mix N] ;; FIXME -> we can now keep the path.
  (reset! chosen-mix mix)
  (doseq [n (range N)]
    ;(swap! pool conj (create-single config path))
    (swap! pool conj (create-rt config soc mix))))

(defn init-pools [config geo-db loc N] ;; this will 
  (let [reg (-> loc :reg)
        mix (->> geo-db seq (map second) (filter #(= (:reg %) reg)) shuffle first)
        soc (conn/new :aqua :client mix config {:connect identity})]
    (log/info "Init Circuit pools: we are in" (:country loc) "/" (geo/reg-to-continent reg))
    (log/debug "Chosen mix:" (:host mix) (:port mix))
    (c/add-listeners soc {:data #(circ/process config soc %)})
    (reset! chosen-mix mix)
    (init-pool config soc mix N)
    mix))

(defn get-path [config]
  (let [[p & ps] @pool]
    (reset! pool (vec ps))
    ;(init-pool config (c/find-by-dest @chosen-mix) @chosen-mix 1)
    p))
