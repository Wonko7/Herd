(ns aqua-node.conns
  (:require [cljs.core :as cljs]
            [cljs.nodejs :as node]))

(def connections (atom {}))

(defn add [conn & [data]]
  (swap! connections merge {conn data})
  conn)

(defn set-data [conn data]
  (swap! connections merge {conn data})
  conn)

(defn update-data [conn keys subdata]
  (swap! connections assoc-in (cons conn keys) subdata)
  conn)

(defn rm [conn]
  (swap! connections dissoc conn)
  conn)

(defn add-listeners [conn listeners]
  (doseq [k (keys listeners) :let [fns (k listeners) fns (if (seq? fns) fns [fns])]]
    (dorun (map #(.on conn (name k) %) fns)))
  conn)

(defn get-all []
  @connections)

(defn get-data [conn]
  (@connections conn))
