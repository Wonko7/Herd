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
            [aqua-node.rate :as rate]
            [aqua-node.geo :as geo]
            [aqua-node.dir :as dir])
  (:require-macros [cljs.core.async.macros :as m :refer [go-loop go]]))

;; Overview;
;;
;; This makes use of circ.cljs to create circuits. This is where the logic for
;; choosing the nodes of a circuit lives.
;;
;; init-path calls create-rt (create real time circuits, for rtp) and
;; create-single (TOR like circuits, for the socks proxy). create-rt will be
;; executed up to: (go (<! ctrl).  When a circuit is needed (say for an
;; outgoing call) we get one with get-path, feed the rest of our destination
;; (the callee and his mix) to the circuit's ctrl channel and create-rt will
;; resume and complete the circuit.  (and this is a nice example of what async
;; is useful for, instead of having create logic broken up in multiple
;; callbacks, it is all in one place).


;; make requests: path level ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; Create a single path circuit (TOR like).
(defn create-single [config [n & nodes :as all-nodes]]
  "Creates a single path. Assumes a connection to the first node exists."
  ;; Find the first mix's socket & send a create.
  (let [socket (c/find-by-dest (:dest n))
        id     (circ/create config socket (:auth n))
        ctrl   (chan)
        dest   (chan)]
    (circ/update-data id [:roles] [:origin])
    (circ/update-data id [:ctrl] ctrl)
    (circ/update-data id [:dest-ctrl] dest)
    (circ/update-data id [:mk-path-fn] #(go (>! ctrl :next)))
    (circ/update-data id [:path-dest] (-> all-nodes last :dest))
    ;; for each remaining mix (nodes here), send a relay-extend, wait until
    ;; the handshaking is over by waiting on (<! ctrl)
    (go (loop [cmd (<! ctrl), [n & nodes] nodes]
          (when n
            (circ/relay-extend config id n)
            (log/debug "Circ" id "extended, remaining =" (count nodes))
            (recur (<! ctrl) nodes)))
        ;; the circuit is built, waiting on dest-ctrl for a destination before sending relay begin.
        (let [cmd  (<! dest)
              circ (circ/get-data id)]
          (condp = cmd
            :begin (do (circ/relay-begin config id (:ap-dest circ))
                       (circ/update-data id [:state] :relay-ack-pending)
                       (circ/update-data id [:path-dest :port] (:port (<! ctrl)))
                       (circ/update-data id [:state] :relay)
                       (>! (-> circ :backward-hop c/get-data :ctrl) :relay)
                       (log/info "Single Circuit" id "is ready for relay"))
            :rdv   (do (circ/relay-rdv config id)
                       (log/info "Single Circuit" id "is our RDV"))
            :else  (log/error "Did not understand command" cmd "on circ" id))))
    id))

;; create a realtime path for RTP. This version connects to the callee's ap.
(defn create-rt [config socket mix]
  "Creates a real time path. Assumes a connection to the first node exists."
  ;; Find the first mix's (will be our assigned mix/SP) socket & send a create.
  (let [id     (circ/create config socket (:auth mix))
        ctrl   (chan)
        dest   (chan)]
    (circ/update-data id [:roles] [:origin])
    (circ/update-data id [:ctrl] ctrl)
    (circ/update-data id [:dest-ctrl] dest)
    (circ/update-data id [:mk-path-fn] #(go (>! ctrl :next)))
    (go (<! ctrl)
        (log/debug "RT Circuit" id "waiting for destination")
        ;; query our dir for the host's mix (will be rdv)
        (let [rt-dest        (<! dest)
              [ap-dest mix2] (<! (dir/query config (:host rt-dest)))]
          (circ/update-data id [:path-dest] rt-dest)
          (if-not mix2
            (>! (-> id circ/get-data :state-ch) :error) ;; no rdv = error.
            ;; extend circuit from our mix to the rdv (mix2, callee's mix)
            (do (circ/relay-extend config id (merge mix2 {:dest mix2}))
                (<! ctrl)
                ;; extend to the callee's AP.
                (circ/relay-extend config id (merge ap-dest {:dest ap-dest}))
                (<! ctrl)
                ;; we are done, update state info & notify we are ready.
                (let [circ (circ/get-data id)]
                  (circ/relay-begin config id rt-dest) ;; ask exit (callee's ap) to begin relaying data.
                  (circ/update-data id [:state] :relay-ack-pending)
                  (circ/update-data id [:state] :relay)
                  (<! ctrl)                            ;; relay begin was acknowledged.
                  (>! (-> circ :state-ch) :done)       ;; notify we can start relaying data.
                  (log/info "RT Circuit" id "is ready for relay"))))))
    id))


;; path ap glue ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; the following functions are helpers for forwarding local data into circuits.

(defn app-proxy-forward-udp [config s b]
  "For packets already encapsulated in socks5 header"
  (let [circ-id   (:circuit (c/get-data s))
        circ-data (circ/get-data circ-id)
        config    (merge config {:data s})]
    (if (= (-> circ-data :state) :relay)
      (rate/queue (:forward-hop circ-data) #(circ/relay-data config circ-id b))
      (log/info "UDP: not ready for data, dropping on circuit" circ-id))))

(defn forward-udp [config s b]
  "For packets needing socks5 header."
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
  "Used for forwarding local TCP data."
  (let [circ-id   (:circuit (c/get-data s))
        circ-data (circ/get-data circ-id)
        config    (merge config {:data s})]
    (if (= (-> circ-data :state) :relay)
      (when (circ/done?)
        (if-let [b (.read s 1350)] ;; FIXME, this value should not be hardcoded.
          (do (circ/inc-block)
              (circ/relay-data config circ-id b))
          (when-let [b (.read s)]
            (circ/inc-block)
            (circ/relay-data config circ-id b))))
      (log/info "TCP: not ready for data, dropping on circuit" circ-id))))

(defn attach-local-udp4 [config circ-id forward-to forwarder & [bind-port]] ;; FIXME: forward-to changed meaning, it is now only used for what ip we're binding to.
  "Unused right now, except by hardcoded rtp benchmarks."
  (go (let [ctrl      (chan)
            udp-sock  (.createSocket (node/require "dgram") "udp4") ;; FIXME should not be hardcoded to ip4
            port      (do (.bind udp-sock (or bind-port 0) (:host forward-to) #(go (>! ctrl (-> udp-sock .address .-port))))
                          (<! ctrl))
            dest      {:type :ip4 :proto :udp :host "0.0.0.0" :port 0}]
        (-> udp-sock
            (c/add {:ctype :udp :ctrl ctrl :type :udp-ap :circuit circ-id :local-dest forward-to}) ;; FIXME circ data is messy... need to separate and harmonise things.
            (c/add-listeners {:message (partial forwarder config udp-sock)}))
        (circ/update-data circ-id [:ap-dest] dest)
        (circ/update-data circ-id [:backward-hop] udp-sock)
        (circ/update-data circ-id [:local-dest] forward-to)
        [udp-sock port])))


;; path pool ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def pool (atom {}))
(def chosen-mix (atom nil))

(defn init-pool [config soc type path-data]
  "Initialises a channel, filling it with as many circuits as it will take.
  Replaces them as they are consumed."
  (let [new-path (condp = type
                   :single #(create-single config (path-data))
                   :rt     #(create-rt config soc path-data))]
    (go-loop [] ;; as soon as one of our buffered circs is claimed, this loop recurs once to replace it:
      (>! (@pool type) (new-path))
      (recur))))

(defn init-pools [config net-info loc N]
  "Initialise a pool of N of each type of circuits (rt and single for now)
  net-info is the list of mixes with their geo info."
  (let [reg          (-> loc :reg)
        select-mixes #(->> net-info seq (map second) (filter %) shuffle)
        ;; entry mix, for :rt --> will be assigned by dir.
        mix          (first (select-mixes #(= (:reg %) reg)))
        ;; make path for :single, three hops, the first being mix chosen for :rt.
        mk-path      (fn []
                       (->> (select-mixes #(not= mix %)) (take 2) (cons mix) (map #(merge % {:dest %})))) ;; use same mix as entry point for single & rt. ; not= mix
        connected    (chan)
        soc          (conn/new :aqua :client mix config {:connect #(go (>! connected :done))})]
    (log/info "Init Circuit pools: we are in" (:country loc) "/" (geo/reg-to-continent reg))
    (log/debug "Chosen mix:" (:host mix) (:port mix))
    (reset! chosen-mix mix)
    ;; init channel pools:
    (reset! pool {:rt (chan N) :single (chan N)})
    ;; wait until connected to the chosen mix before sending requests
    (go (<! connected)
        (rate/init config soc)
        (c/add-listeners soc {:data #(circ/process config soc %)})
        (init-pool config soc :rt mix)
        (init-pool config soc :single mk-path))
    mix))

(defn get-path [type]
  "Return a channel to the chosen type rt/single of path.
  As soon as a circuit is used, a new one will be put in the channel, see init-pool."
  (@pool type))
