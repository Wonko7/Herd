(ns aqua-node.crypto
  (:require [cljs.core :as cljs]
            [cljs.nodejs :as node]
            [aqua-node.buf :as b]
            [aqua-node.config :as cfg]))

;; FIXME should use this everywhere. put hashes and stuff here.

(def dbg? #(:debug (cfg/get)))

(defn fin [c]
  "work around reserved final keyword"
  (.apply (aget c "final") c))

(defn enc-aes [key iv msg]
  (let [c    (node/require "crypto")
        aes  (.createCipheriv c. "aes-256-ctr" key iv)]
    (b/cat (.update aes msg) (fin aes))))

(defn dec-aes [key iv msg]
  (let [c    (node/require "crypto")
        aes  (.createDecipheriv c. "aes-256-ctr" key iv)]
    (b/cat (.update aes msg) (fin aes))))
