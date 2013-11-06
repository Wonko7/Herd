(ns aqua-node.cell
  (:require [cljs.core :as cljs]
            [cljs.nodejs :as node]
            [aqua-node.conns :as c]))


;; Tor doc. from tor spec file:
;;  - see section 5 for circ creation.
;;  - see section 5.1.4 for ntor hs.
;;  - create2 will be used for ntor hs. 
;;  - circ id: msb set to 1 when created on current node. otherwise 0.
;;  - will not be supporting create fast: tor spec: 221-stop-using-create-fast.txt

(defn to-cmd [num]
  (condp = num
    0   :padding
    1   :create
    2   :created
    3   :relay
    4   :destroy
    5   :create_fast
    6   :created_fast
    8   :netinfo
    9   :relay_early
    10  :create2
    11  :created2
    7   :versions
    128 :vpadding
    129 :certs
    130 :auth_challenge
    131 :authenticate
    132 :authorize
    :unknown))

(defn process [conn buff]
  ;; FIXME check len first
  (let [data    (c/get-data conn)
        b8      #(.readUInt8 buff %)
        b16     #(.readUInt16BE buff %)
        b32     #(.readUInt32BE buff %)
        len     (.-length buff)
        circ-id (b32 0)
        command (to-cmd (b8 4))
        payload (.slice buff 5 len)]
    (println "---  recvd cell: id:" circ-id "cmd:" command ":" (.toString payload "hex"))))
