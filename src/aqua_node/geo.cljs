(ns aqua-node.geo
  (:require [cljs.core :as cljs]
            [cljs.nodejs :as node]
            [cljs.core.async :refer [chan <! >!]]
            [clojure.string :as str]
            [aqua-node.log :as log]
            [aqua-node.parse :as conv])
  (:require-macros [cljs.core.async.macros :as m :refer [go]]))

(defn int-to-zone [zone]
  "Return keyword from int coding of zone"
  (condp = zone
    0 :apcnic
    1 :arin
    2 :lacnic
    3 :ripencc
    4 :afrinic))

(defn zone-to-int [zone]
  "Return int coding of region keyword. For sending over network."
  (condp = zone
    :apcnic   0
    :arin     1
    :lacnic   2
    :ripencc  3
    :afrinic  4))

(defn zone-to-continent [zone]
  "Just for pretty printing."
  (condp = zone
    :apcnic  "asia-pacific"
    :arin    "north-america"
    :lacnic  "south-america"
    :ripencc "europe"
    :afrinic "africa"))

(defn parse [config]
  (try
    (let [fs  (node/require "fs")
          geo (chan)
          ip  (-> config :external-ip conv/ip4-to-bin (.readUInt32BE 0))]
      (if (:geo-info config)
        ;; zone is specified in config
        (go (log/info "Geo: We are in" (-> config :geo-info :zone))
            (:geo-info config))
        ;; parse geo db, downloaded from http://software77.net/geo-ip/
        (do (.readFile fs (-> config :geo-db) #(go (>! geo %2)))
            (go (first (for [l (str/split (<! geo) #"\n")
                             :let  [[from to zone _ _ _ country] (str/split (str/replace l \" "") #",") ]
                             :when (and (not= \# (first l))
                                        (>= ip from)
                                        (<= ip to))] ;; parse line by line, only keep the entry that encapsulates our ip.
                         {:zone      (keyword zone)
                          :country   country
                          :ip        ip}))))))
    (catch js/Object e (log/c-error "Error reading Geo loc DB" e))))
