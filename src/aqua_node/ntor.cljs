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

(defn expand [k n]
  (let [prk    (hmac (:t-key init) k)
        cct    (.concat (js/Buffer. 0) (cljs/clj->js %&))
        info   (js/Buffer. (:m-expand init))
        ib     (js/Buffer. 0)
        out    (loop [out (js/Buffer. 0), prev (js/Buffer. 0), i 1, len 0]
                 (if (< (count out) n)
                   (let [m (cct prev info (.writeUInt8 ib 0 (char i))) ;; FIXME, wtf happens when i > 255...
                         h (hmac prk m)]
                     (recur (cct out h) h (inc i)))))] ;; could be optimised, write in out instead of recreating buffs.
    (println (str))
    ))

