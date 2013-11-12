(ns aqua-node.circ
  (:require [cljs.core :as cljs]
            [cljs.nodejs :as node]
            [aqua-node.buf :as b]
            [aqua-node.ntor :as hs]
            [aqua-node.conns :as c]))

(declare from-cmd to-cmd)

;; Tor doc. from tor spec file:
;;  - see section 5 for circ creation.
;;  - create2 will be used for ntor hs.
;;  - circ id: msb set to 1 when created on current node. otherwise 0.
;;  - will not be supporting create fast: tor spec: 221-stop-using-create-fast.txt


;; circuit state management ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def circuits (atom {}))

(defn circ-add [circ-id conn & [state]]
  (assert (nil? (@circuits circ-id)) (str "could not create circuit," circ-id "already exists"))
  (swap! circuits merge {circ-id {:conn conn :state state}}))

(defn circ-update-data [circ keys subdata]
  (swap! circuits assoc-in (cons circ keys) subdata)
  circ)

(defn circ-rm [circ]
  (swap! circuits dissoc circ)
  circ) ;; FIXME think about what we could return


;; send cell ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn cell-send [conn circ-id cmd payload & [len]]
  (let [len          (or len (.-length payload))
        buf          (b/new (+ 5 len))
        [w8 w16 w32] (b/mk-writers payload)]
    (w32 circ-id 0)
    (w8 (from-cmd cmd) 4)
    (.copy payload buf 5)
    (.write conn buf)))


;; process recv ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn recv-create2 [config conn circ-id {buf :payload len :len}]
  (circ-add circ-id {:type :srv})
  (let [{auth-hs :auth-hs} config
        [srv-shared-sec created] (hs/server-reply auth-hs buf 72)]
    (circ-update-data circ-id [:secret] srv-shared-sec)
    (cell-send conn circ-id :created created)))

;; cell management (no state logic here) ;;;;;;;;;;;;;;;;;;;;;;;;;

(def to-cmd
  {0   {:name :padding         :fun nil}
   1   {:name :create          :fun nil}
   2   {:name :created         :fun nil}
   3   {:name :relay           :fun nil}
   4   {:name :destroy         :fun nil}
   5   {:name :create_fast     :fun nil}
   6   {:name :created_fast    :fun nil}
   8   {:name :netinfo         :fun nil}
   9   {:name :relay_early     :fun nil}
   10  {:name :create2         :fun recv-create2}
   11  {:name :created2        :fun nil}
   7   {:name :versions        :fun nil}
   128 {:name :vpadding        :fun nil}
   129 {:name :certs           :fun nil}
   130 {:name :auth_challenge  :fun nil}
   131 {:name :authenticate    :fun nil}
   132 {:name :authorize       :fun nil}})

(def from-cmd
  (merge (for [k (keys to-cmd)]
           {(-> to-cmd k :name) k})))

(defn process [config conn buff]
  ;; FIXME check len first -> match with fix buf size
  (let [[r8 r16 r32] (b/mk-readers buff)
        len          (.-length buff)
        circ-id      (r32 0)
        command      (to-cmd (r8 4))
        payload      (.slice buff 5 len)]
    (println "---  recvd cell: id:" circ-id "cmd:" (:name command) ":" (.toString payload "hex"))
    (when (:fun command)
      (try
        ((:fun command) config conn  circ-id {:payload payload :len (- len 5)})
        (catch js/Object e (println "/!\\  Error in circuit states:" e "circ" circ-id))))))
