(ns aqua-node.log
  (:require [cljs.core :as cljs]
            [cljs.nodejs :as node]
            [aqua-node.config :as cfg]))

(defn dbg? []
  (:debug (cfg/get-cfg)))

(defn info [& msgs]
  (apply println (cons "---" msgs)))

(defn error [& msgs]
  (apply println (cons "/!\\" msgs)))

(defn debug [& msgs]
  (when (dbg?)
    (apply println (cons "###" msgs))))

(defn c-error [err message & [return-value]]
  (error message)
  (when (dbg?)
    (println err)
    (println (.-stack err)))
  return-value)

(defn c-info [error message & [return-value]]
  (info message)
  (when (dbg?)
    ;(println error)
    (println (.-stack error)))
  return-value)

(defn c-debug [error message & [return-value]]
  (when (dbg?)
    (debug message)
    (println error)
    (println (.-stack error)))
  return-value)
