(ns aqua-node.conns
  (:require [cljs.core :as cljs]
            [cljs.nodejs :as node]
            [aqua-node.log :as log]))

(declare destroy find-by-dest)


(def connections (atom {}))

(defn add [conn & [data]]
  (when (and (= :aqua (:type data)) (:host data))
    (destroy (find-by-dest data)))
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

(defn destroy [conn]
  (when-let [c (@connections conn)]
    (rm conn)
    (doall (map #(%) (:on-destroy c)))
    (if (= :udp (:ctype c))
      (.close conn)
      (.destroy conn))))

(defn add-listeners [conn listeners]
  (doseq [k (keys listeners) :let [fns (k listeners) fns (if (seq? fns) fns [fns])]]
    (dorun (map #(.on conn (name k) %) fns)))
  conn)

(defn get-all []
  @connections)

(defn get-data [conn]
  (@connections conn))

(defn find-by-dest [{host :host port :port}] ;; FIXME make a map to link to sockets.
  (first (keep (fn [[s d]]
                 (when (= host (:host d)) s))
               (seq @connections))))
