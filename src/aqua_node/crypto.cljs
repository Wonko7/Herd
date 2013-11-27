(ns aqua-node.crypto
  (:require [cljs.core :as cljs]
            [cljs.nodejs :as node]
            [aqua-node.buf :as b]))


;; FIXME should use this everywhere. put hashes and stuff here.

(defn fin [c]
  "work around reserved final keyword"
  (.apply (aget c "final") c))

(defn enc-aes [key iv msg & [extra]]
  (let [c    (node/require "crypto")
        aes  (.createCipheriv c. "aes-256-ctr" key iv)
        copycat2  #(let [len  (+ (.-length %1) (.-length %2))
                         data (js/Buffer. len)]
                     (.copy %1 data)
                     (.copy %2 data (-> %1 .-length))
                     data)
        copycat3  #(let [len  (+ (.-length %1) (.-length %2) (.-length %3))
                         data (js/Buffer. len)]
                     (.copy %1 data)
                     (.copy %2 data (-> %1 .-length))
                     (.copy %3 data (-> %2 .-length))
                     data)]
    (if extra
      (copycat3 (.update aes extra) (.update aes msg) (fin aes))
      (copycat2 (.update aes msg) (fin aes)))))

(defn dec-aes [key iv msg]
  (let [c    (node/require "crypto")
        aes  (.createDecipheriv c. "aes-256-ctr" key iv)]
    (b/cat (.update aes msg) (fin aes))))


;; curve:

(defn gen-keys []
  (let [[curve crypto] [(node/require "node-curve25519") (node/require "crypto")]
        sec            (.makeSecretKey curve (.randomBytes crypto 32))
        pub            (.derivePublicKey curve sec)]
    [sec pub]))
