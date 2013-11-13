(ns aqua-node.circ
  (:require [cljs.core :as cljs]
            [cljs.nodejs :as node]
            [aqua-node.log :as log]
            [aqua-node.buf :as b]
            [aqua-node.ntor :as hs]
            [aqua-node.conns :as c]
            [aqua-node.crypto :as crypto]))

(declare from-cmd to-cmd)

;; Tor doc. from tor spec file:
;;  - see section 5 for circ creation.
;;  - create2 will be used for ntor hs.
;;  - circ id: msb set to 1 when created on current node. otherwise 0.
;;  - will not be supporting create fast: tor spec: 221-stop-using-create-fast.txt


;; circuit state management ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def circuits (atom {}))

(defn circ-add [circ-id conn & [state]]
  (assert (nil? (@circuits circ-id)) (str "could not create circuit, " circ-id " already exists"))
  (swap! circuits merge {circ-id (merge state {:conn conn})}))

(defn circ-update-data [circ keys subdata]
  (swap! circuits assoc-in (cons circ keys) subdata)
  circ)

(defn circ-rm [circ]
  (swap! circuits dissoc circ)
  circ) ;; FIXME think about what we could return

(defn circ-destroy [circ]
  (when (@circuits circ) ;; FIXME also send destroy cells to the path
    (circ-rm circ)))


;; send cell ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn cell-send [conn circ-id cmd payload & [len]]
  (let [len          (or len (.-length payload))
        buf          (b/new (+ 5 len))
        [w8 w16 w32] (b/mk-writers buf)]
    (w32 circ-id 0)
    (w8 (from-cmd cmd) 4)
    (.copy payload buf 5)
    (.write conn buf)))


;; make requests ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn mk-path [config socket srv-auth] ;; FIXME: the api will change. remove socket.  ;; since this looks like a good entry point, try goes here for now
  (let [circ-id 42] ;; FIXME generate
    (try (let [[auth b] (hs/client-init srv-auth)]
           (circ-add circ-id socket {:type :client})
           (circ-update-data circ-id [:auth] auth)
           (cell-send socket circ-id :create2 b))
         (catch js/Object e (log/c-info e (str "failed circuit creation: " circ-id) (circ-destroy circ-id))))))

(defn relay [config socket circ-id msg]
  (assert (@circuits circ-id) "cicuit does not exist") ;; FIXME this assert will probably be done elsewhere (process?)
  ;; FIXME assert state.
  (let [circ     (@circuits circ-id)
        c        (node/require "crypto")
        iv       (.randomBytes c. 16)
        msg      (crypto/enc-aes (-> circ :auth :secret) iv msg)]
    (cell-send socket circ-id :relay (b/cat iv msg))))


;; process recv ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; FIXME: these need state
(defn recv-create2 [config conn circ-id {payload :payload len :len}]
  (circ-add circ-id conn {:type :server})
  (let [{pub-B :pub node-id :id sec-b :sec} (-> config :auth :aqua-id) ;; FIXME: renaming the keys is stupid.
        [shared-sec created]                (hs/server-reply {:pub-B pub-B :node-id node-id :sec-b sec-b} payload 32)] ;; FIXME -> key len & iv len should be in configw
    (circ-update-data circ-id [:auth :secret] shared-sec)
    (cell-send conn circ-id :created2 created)))

(defn recv-created2 [config conn circ-id {payload :payload len :len}]
  (assert (@circuits circ-id) "cicuit does not exist") ;; FIXME this assert will probably be done elsewhere (process?)
  (let [auth       (:auth (@circuits circ-id))
        shared-sec (hs/client-finalise auth payload 32)] ;; FIXME aes 256 seems to want 32 len key. seems short to me.
    (circ-update-data circ-id [:auth :secret] shared-sec)))

(defn recv-relay [config conn circ-id {payload :payload len :len}]
  (assert (@circuits circ-id) "cicuit does not exist")
  ;(assert (@circuits circ-id) "cicuit does not exist") something about the len
  (let [circ     (@circuits circ-id)
        [iv msg] (b/cut payload 16)
        msg      (crypto/dec-aes (-> circ :auth :secret) iv msg)]
    (condp = (:type circ) ;; FIXME will be changed by app-proxy, mix, exit etc? also depends on path type, link & encr proto.
      :server (println (.toString msg "ascii"))
      :client (b/print-x msg)
      (assert (= 1 0) "unsupported relay type, bad circ state, something is wrong"))))


;; cell management (no state logic here) ;;;;;;;;;;;;;;;;;;;;;;;;;

(def to-cmd
  {0   {:name :padding         :fun nil}
   1   {:name :create          :fun nil}
   2   {:name :created         :fun nil}
   3   {:name :relay           :fun recv-relay}
   4   {:name :destroy         :fun nil}
   5   {:name :create_fast     :fun nil}
   6   {:name :created_fast    :fun nil}
   8   {:name :netinfo         :fun nil}
   9   {:name :relay_early     :fun nil}
   10  {:name :create2         :fun recv-create2}
   11  {:name :created2        :fun recv-created2}
   7   {:name :versions        :fun nil}
   128 {:name :vpadding        :fun nil}
   129 {:name :certs           :fun nil}
   130 {:name :auth_challenge  :fun nil}
   131 {:name :authenticate    :fun nil}
   132 {:name :authorize       :fun nil}})

(def from-cmd
  (apply merge (for [k (keys to-cmd)]
                 {((to-cmd k) :name) k})))

(defn process [config conn buff]
  ;; FIXME check len first -> match with fix buf size
  (let [[r8 r16 r32] (b/mk-readers buff)
        len          (.-length buff)
        circ-id      (r32 0)
        command      (to-cmd (r8 4))
        payload      (.slice buff 5 len)]
    (log/debug "recv cell: id:" circ-id "cmd:" (:name command) ":" (.toString payload "hex"))
    (when (:fun command)
      (try
        ((:fun command) config conn  circ-id {:payload payload :len (- len 5)})
        (catch js/Object e (log/c-info e (str "Killed circuit " circ-id)) (circ-destroy circ-id))))))
