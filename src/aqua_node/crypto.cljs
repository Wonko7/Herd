(ns aqua-node.crypto
  (:require [cljs.core :as cljs]
            [cljs.nodejs :as node]
            [aqua-node.buf :as b]))


;; FIXME should use this everywhere. put hashes and stuff here.

(defn fin [c]
  "work around reserved final keyword"
  (.apply (aget c "final") c))

(defn enc-aes [key iv msg]
  (let [c    (node/require "crypto")
        aes  (.createCipheriv c. "aes-256-ctr" key iv)]
    (b/print-x iv :dec-i)
    (b/print-x key :dec-k)
    (b/cat (.update aes msg) (fin aes))))

(defn dec-aes [key iv msg]
  (let [c    (node/require "crypto")
        aes  (.createDecipheriv c. "aes-256-ctr" key iv)]
    (b/print-x iv :enc-i)
    (b/print-x key :enc-k)
    (b/cat (.update aes msg) (fin aes))))


;; curve:

(defn gen-keys []
  (let [[curve crypto] [(node/require "node-curve25519") (node/require "crypto")]
        sec            (.makeSecretKey curve (.randomBytes crypto 32))
        pub            (.derivePublicKey curve sec)]
    [sec pub]))
