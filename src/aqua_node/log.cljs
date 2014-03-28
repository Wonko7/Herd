(ns aqua-node.log
  (:require [cljs.core :as cljs]
            [cljs.nodejs :as node]
            [aqua-node.config :as cfg]))

(defn dbg? []
  "Tests if debug is set."
  (:debug (cfg/get-cfg)))

(defn info [& msgs]
  "Print info messages"
  (apply println (cons "---" msgs)))

(defn error [& msgs]
  "Print error messages"
  (apply println (cons "/!\\" msgs)))

(defn debug [& msgs]
  "Print debug messages"
  (when (dbg?)
    (apply println (cons "###" msgs))))

(defn c-error [err message & [return-value]]
  "Catch an exception and print message as error. If debug is on, print stack."
  (error message)
  (println err)
  (when (dbg?)
    (println (.-stack err)))
  return-value)

(defn c-info [error message & [return-value]]
  "Catch an exception and print message as info. If debug is on, print stack."
  (info message)
  (println error)
  (when (dbg?)
    (println (.-stack error)))
  return-value)

(defn c-debug [error message & [return-value]]
  "Catch an exception. If debug is on, print message, print stack."
  (when (dbg?)
    (debug message)
    (println error)
    (println (.-stack error)))
  return-value)
