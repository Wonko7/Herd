(ns aqua-node.crypto
  (:require [cljs.core :as cljs]
            [cljs.nodejs :as node]
            [aqua-node.buf :as b]))


;; FIXME should use this everywhere. put hashes and stuff here.

(defn fin [c]
  "work around reserved final keyword"
  (.apply (aget c "final") c))

(def encode (atom {}))
(def decode (atom {}))

(defn enc-aes [key iv msg]
  (if-let [enc (@encode key)]
    (.update enc msg)
    (let [c    (node/require "crypto")
          aes  (.createCipheriv c. "aes-256-ctr" key iv)]
      (swap! encode merge {key aes})
      (.update aes msg))))

(defn dec-aes [key iv msg]
  (if-let [dec (@decode key)]
    (.update dec (b/copycat2 iv msg))
    (let [c    (node/require "crypto")
          aes  (.createDecipheriv c. "aes-256-ctr" key iv)]
      (swap! decode merge {key aes})
      (.update aes msg))))

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
