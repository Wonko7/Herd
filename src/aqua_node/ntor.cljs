(ns aqua-node.ntor
  (:require [cljs.core :as cljs]
            [cljs.nodejs :as node]))


;; see: torspec/proposals/216-ntor-handshake.txt
;;      torspec/tor-spec.txt 5.1.4

(def init
  (let [protoid "ntor-curve25519-sha256-1"]
    {:m-expand (str protoid ":key_expand")
     :t-key    (str protoid ":key_extract")
     :mac      (str protoid ":mac")
     :verify   (str protoid ":verify")
     :protoid  protoid}))

(defn hmac [key message]
  (let [crypto (node/require "crypto")]
        (-> (.createHmac crypto. "sha256" key)
            (.update message)
            .digest)))

;; FIXME: perfect function to start unit testing...
(defn expand [k n]
  (let [cct    #(js/Buffer.concat (cljs/clj->js %&))
        prk    (hmac (:t-key init) k)
        info   (js/Buffer. (:m-expand init))]
    (loop [out (js/Buffer. 0), prev (js/Buffer. 0), i 1]
      (if (>= (.-length out) n)
        (.slice out 0 n)
        (let [h   (hmac prk (cct prev info (js/Buffer. (cljs/clj->js. [i])))) ;; FIXME, test wtf happens when i > 255...
              out (cct out h)]
          (recur out h (inc i)))))))
