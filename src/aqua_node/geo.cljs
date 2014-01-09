(ns aqua-node.geo
  (:require [cljs.core :as cljs]
            [cljs.nodejs :as node]
            [cljs.core.async :refer [chan <! >!]]
            [clojure.string :as str]
            [aqua-node.log :as log]
            [aqua-node.parse :as conv])
  (:require-macros [cljs.core.async.macros :as m :refer [go]]))

(def db (atom {}))

(defn gfind [ip4]
  (let [ip (-> ip4 conv/ip4-to-bin (.readUInt32BE 0))]
    (filter (fn [{f :from t :to :as entry}]
              (when (and (>= ip f) (<= ip t))
                entry))
            @db)))

(defn load-db [config]
  (try (let [fs  (node/require "fs")
             geo (chan)
             done (chan)]
         (println :lol)
         (.readFile fs (-> config :dir :geo-db) #(go (println %1 :read) (>! geo %2)))
         (go (doseq [l (str/split (<! geo) #"\n")
                     :when (not= \# (first l))
                     :let  [[from to reg & stuffs] (str/split (str/replace l \" "") #",")]]
               (swap! db concat [{:from (js/parseInt from)
                                  :to   (js/parseInt to)
                                  :reg  reg
                                  :lol  stuffs}]))
             (println :done (gfind "88.191.150.19"))))
       (catch js/Object e (log/c-error "Error reading Geo loc db" e))))
