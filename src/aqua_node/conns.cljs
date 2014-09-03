(ns aqua-node.conns
  (:require [cljs.core :as cljs]
            [cljs.nodejs :as node]
            [aqua-node.log :as log]
            [aqua-node.buf :as b]))

(declare destroy find-by-dest)

;; conns.cljs: used to keep track of open connections/sockets (be it aqua,
;; socks, local udp, etc).
;; Add new, update info, remove, etc.


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

(defn destroy [conn]
  (when-let [c (@connections conn)]
    (rm conn)
    (doall (map #(%) (:on-destroy c)))
    (when conn
      (if (= :udp (:ctype c))
        (.close conn)
        (.destroy conn)))))

(defn add-listeners [conn listeners]
  "Add callbacks to socket events.
  Listeners is a hash map of events & functions: {:connect do-connect, :close do-cleanup}"
  (doseq [k (keys listeners) :let [fns (k listeners) fns (if (seq? fns) fns [fns])]]
    (dorun (map #(.on conn (name k) %) fns)))
  conn)

(defn get-all []
  @connections)

(defn get-data [conn]
  (@connections conn))

(defn find-by-id [id]
  "Find an open socket for the given host.
  Might also add a filter to match a type of connections (aqua, dir, etc)."
  (first (keep (fn [[s d]]
                 (when (and (-> d :auth :srv-id) (b/b= id (-> d :auth :srv-id)))
                   s))
               (seq @connections))))

(defn find-by-dest [{host :host}] ;; FIXME should deprecate this. breaks on nat for example.
  "Find an open socket for the given host.
  Might also add a filter to match a type of connections (aqua, dir, etc)."
  (first (keep (fn [[s d]]
                 (when (= host (:host d)) s))
               (seq @connections))))
