(ns aqua-node.config
  (:require [cljs.core :as cljs]
            [cljs.nodejs :as node]
            [cljs.reader :as reader]))

(def config (atom {}))

;; FIXME: add try catch for missing or unparsable files.
(defn read-config []
  (let [fs   (node/require "fs")
        read #(swap! config merge (reader/read-string %))]
    (read (.readFileSync fs "aquarc" "utf8"))))
