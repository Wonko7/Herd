(ns aqua-node.geo
  (:require [cljs.core :as cljs]
            [cljs.nodejs :as node]
            [cljs.core.async :refer [chan <! >!]]
            [clojure.string :as str]
            [aqua-node.log :as log]
            [aqua-node.parse :as conv])
  (:require-macros [cljs.core.async.macros :as m :refer [go]]))

(defn int-to-reg [reg]
  (condp = reg
    0 :apcnic
    1 :arin
    2 :lacnic
    3 :ripencc
    4 :afrinic))

(defn reg-to-int [reg]
  (condp = reg
    :apcnic   0
    :arin     1
    :lacnic   2
    :ripencc  3
    :afrinic  4))

(defn parse [config done]
  (try (let [fs  (node/require "fs")
             geo (chan)
             ip   (:extenal-ip config)
             ip   (-> "82.239.82.44" conv/ip4-to-bin (.readUInt32BE 0))]
         (.readFile fs (-> config :geo-db) #(go (>! geo %2)))
         (go (>! done (first (for [l (str/split (<! geo) #"\n")
                                   :let  [[from to reg _ _ _ country] (str/split (str/replace l \" "") #",")]
                                   :when (and (not= \# (first l)) 
                                              (>= ip from)
                                              (<= ip to))]
                               {:reg       (keyword reg)
                                :continent (condp = reg
                                             "apcnic"  :asia-pacific
                                             "arin"    :north-america
                                             "lacnic"  :south-america
                                             "ripencc" :europe
                                             "afrinic" :africa)
                                :country   country
                                :ip        ip})))))
       (catch js/Object e (log/c-error "Error reading Geo loc db" e))))
