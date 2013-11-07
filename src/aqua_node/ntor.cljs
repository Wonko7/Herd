(ns aqua-node.ntor
  (:require [cljs.core :as cljs]
            [cljs.nodejs :as node]))


;; see: torspec/proposals/216-ntor-handshake.txt
;;      torspec/tor-spec.txt 5.1.4

;; FIXME: buff helpers: this will be exported.
(defn cct [& bs]
  (js/Buffer.concat (cljs/clj->js bs)))
;; end buff helpers

;; FIXME: part or all of this static (non user) conf will get exported as pieces of it are needed by other modules.
(def conf
  (let [protoid "ntor-curve25519-sha256-1"]
    {:m-expand (str protoid ":key_expand")
     :t-key    (str protoid ":key_extract")
     :mac      (str protoid ":mac")
     :verify   (str protoid ":verify")
     :protoid  protoid
     :node-id-len 20
     }))

(defn hmac [key message]
  (let [crypto (node/require "crypto")]
        (-> (.createHmac crypto. "sha256" key)
            (.update message)
            .digest)))

;; FIXME: perfect function to start unit testing...
(defn expand [k n]
  (let [prk    (hmac (:t-key conf) k)
        info   (js/Buffer. (:m-expand conf))]
    (loop [out (js/Buffer. 0), prev (js/Buffer. 0), i 1]
      (if (>= (.-length out) n)
        (.slice out 0 n)
        (let [h   (hmac prk (cct prev info (js/Buffer. (cljs/clj->js. [i])))) ;; FIXME, test wtf happens when i > 255...
              out (cct out h)]
          (recur out h (inc i)))))))

;; node id len = 20, keyid len = 32
(defn client-init [srv-id config]
  (let [cv (node/require "node-curve25519")]
    (println :curve (.derivePublicKey cv (.toString (.makeSecretKey cv (js/Buffer. 32)) "hex")))))
