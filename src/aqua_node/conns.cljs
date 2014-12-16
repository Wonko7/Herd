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

(def id-to-connections (atom {}))

(defn rm [conn]
  (when (-> conn (@connections) :auth :srv-id)
    (swap! id-to-connections dissoc (-> conn (@connections) :auth :srv-id b/hx)))
  (swap! connections dissoc conn)
  conn)

(defn destroy [conn]
  (.trace js/console "who called me?")
  (when-let [c (@connections conn)]
    (when (-> c :auth :srv-id)
      (log/info "Removed connection to:" (-> c :auth :srv-id b/hx)))
    (rm conn)
    (doall (map #(%) (:on-destroy c)))
    (when (and conn (not= :aqua (:type c)))
      (if (= :udp (:ctype c))
        (.close conn)
        (.destroy conn))
      (.removeAllListeners conn))))

(defn add [conn & [data]]
  (swap! connections merge {conn data})
  (when-let [id (-> data :auth :srv-id)]
    (destroy (-> id b/hx (@id-to-connections) :socket))
    (swap! id-to-connections merge {(-> id b/hx) {:socket conn}})
    (log/info "Added connection to:" (-> id b/hx)))
  conn)

(defn add-id [conn id]
  (destroy (-> id b/hx (@id-to-connections) :socket))
  (swap! id-to-connections merge {(b/hx id) {:socket conn}})
  (log/info "Received ID connection to:" (b/hx id)))

(defn set-data [conn data]
  (swap! connections merge {conn data})
  conn)

(defn update-data [conn keys subdata]
  (swap! connections assoc-in (cons conn keys) subdata)
  conn)

(defn add-listeners [conn listeners]
  "Add callbacks to socket events.
  Listeners is a hash map of events & functions: {:connect do-connect, :close do-cleanup}"
  (doseq [k (keys listeners) :let [fns (k listeners) fns (if (seq? fns) fns [fns])]]
    (dorun (map #(.on conn (name k) %) fns)))
  conn)

(defn get-all []
  @connections)

(defn get-data [conn]
  (when conn
    (@connections conn)))

(defn find-by-id [id]
  "Find an open socket for the given host.
  Might also add a filter to match a type of connections (aqua, dir, etc)."
  (-> id b/hx (@id-to-connections) :socket))

(defn find-by-dest [{host :host}] ;; FIXME should deprecate this. breaks on nat for example.
  "Find an open socket for the given host.
  Might also add a filter to match a type of connections (aqua, dir, etc)."
  (first (keep (fn [[s d]]
                 (when (= host (:host d)) s))
               (seq @connections))))
