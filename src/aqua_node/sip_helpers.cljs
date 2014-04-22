(ns aqua-node.sip-helpers
  (:require [cljs.core :as cljs]
            [cljs.nodejs :as node]
            [cljs.core.async :refer [chan <! >!]]
            [clojure.walk :as walk]
            [aqua-node.log :as log]
            [aqua-node.buf :as b]
            [aqua-node.parse :as conv]
            [aqua-node.conns :as c]
            [aqua-node.conn-mgr :as conn]
            [aqua-node.circ :as circ]
            [aqua-node.path :as path]
            [aqua-node.dir :as dir])
  (:require-macros [cljs.core.async.macros :as m :refer [go-loop go]]))


;; Commands for our voip protocol ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def from-cmd {:register        0
               :query           1
               :query-reply     2
               :register-to-mix 3
               :mix-query       4
               :invite          5
               :error           9})

(def to-cmd
  (apply merge (for [k (keys from-cmd)]
                 {(from-cmd k) k})))


;; Parsing ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn get-call-id [msg]
  "First byte is command identifier. Then Call id null terminated. return call id and remainder of the buffer"
  (let [[id rest] (b/cut-at-null-byte (.slice msg 1))]
    [(.toString id) rest]))
