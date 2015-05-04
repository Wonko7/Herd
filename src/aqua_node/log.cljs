(ns aqua-node.log
  (:require [cljs.core :as cljs]
            [cljs.nodejs :as node]
            [aqua-node.config :as cfg]))

(declare printfn)

(defn init [{log :log-output}]
  (def printfn (if (or (= log :stdout) (nil? log))
                 println
                 (let [fs (node/require "fs")
                       file (.createWriteStream fs log)]
                   #(.write file (apply println-str %&))))))

(defn dbg? []
  "Tests if debug is set."
  (:debug (cfg/get-cfg)))

(defn info [& msgs]
  "Print info messages"
  (apply printfn (cons "---" msgs)))

(defn error [& msgs]
  "Print error messages"
  (apply printfn (cons "/!\\" msgs)))

(defn debug [& msgs]
  "Print debug messages"
  (when (dbg?)
    (apply printfn (cons "###" msgs))))

(defn c-error [err message & [return-value]]
  "Catch an exception and print message as error. If debug is on, print stack."
  (error message)
  (printfn err)
  (when (dbg?)
    (printfn (.-stack err)))
  return-value)

(defn c-info [error message & [return-value]]
  "Catch an exception and print message as info. If debug is on, print stack."
  (info message)
  (printfn error)
  (when (dbg?)
    (printfn (.-stack error)))
  return-value)

(defn c-debug [error message & [return-value]]
  "Catch an exception. If debug is on, print message, print stack."
  (when (dbg?)
    (debug message)
    (printfn error)
    (printfn (.-stack error)))
  return-value)
