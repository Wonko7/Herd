(ns aqua-node.crypto
  (:require [cljs.core :as cljs]
            [cljs.nodejs :as node]
            [aqua-node.buf :as b]))


;; Crypto helpers.

(defn fin [c]
  "work around reserved final keyword"
  (.apply (aget c "final") c))

(defn create-tmp-enc [key iv msg]
  (let [c    (node/require "crypto")
        aes  (.createCipheriv c. "aes-256-ctr" key iv)]
    (.update aes msg)))

(defn create-tmp-dec [key iv msg]
  (let [c    (node/require "crypto")
        aes  (.createDecipheriv c. "aes-256-ctr" key iv)]
    (.update aes msg)))

(defn create-dec [key iv]
  (let [c    (node/require "crypto")
        aes  (.createDecipheriv c. "aes-256-ctr" key iv)]
    aes))

(defn create-enc [key iv]
  (let [c    (node/require "crypto")
        aes  (.createCipheriv c. "aes-256-ctr" key iv)]
    aes))

;; curve:

(defn gen-keys []
  (let [[curve crypto] [(node/require "node-curve25519") (node/require "crypto")]
        sec            (.makeSecretKey curve (.randomBytes crypto 32))
        pub            (.derivePublicKey curve sec)]
    [sec pub]))
