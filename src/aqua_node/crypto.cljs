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
        aes  (.createCipheriv c "aes-256-ctr" key iv)]
    (.update aes msg)))

(defn create-tmp-dec [key iv msg]
  (let [c    (node/require "crypto")
        aes  (.createDecipheriv c "aes-256-ctr" key iv)
    _ (println :key (.readUInt8 key 0) (.readUInt8 key 1) (.readUInt8 key 2) (.readUInt8 key 31))
    _ (println :decb (.readUInt8 msg 0))
    msg (.update aes msg)]
    _ (println :deca (.readUInt8 msg 0))
    msg))

(defn create-dec [key iv]
  (let [c    (node/require "crypto")
        aes  (.createDecipheriv c "aes-256-ctr" key iv)]
    aes))

(defn create-enc [key iv]
  (let [c    (node/require "crypto")
        aes  (.createCipheriv c "aes-256-ctr" key iv)]
    aes))

;; curve:

(defn gen-keys [config]
  (let [[curve crypto] [(node/require "curve25519") (node/require "crypto")]
        sec            (.makeSecretKey curve (.randomBytes crypto (-> config :ntor-values :key-len)))
        pub            (.derivePublicKey curve sec)]
    [sec pub]))
