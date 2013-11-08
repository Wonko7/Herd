(ns aqua-node.ntor
  (:require [cljs.core :as cljs]
            [cljs.nodejs :as node]))


;; see: torspec/proposals/216-ntor-handshake.txt
;;      torspec/tor-spec.txt 5.1.4

;; FIXME: part or all of this static (non user) conf will get exported as pieces of it are needed by other modules.
;; FIXME: also, buffers or not?
(def conf
  (let [protoid   "ntor-curve25519-sha256-1"
        b         #(js/Buffer. %1)
        bp        #(b (str protoid %1))]
    {:m-expand    (bp ":key_expand")
     :t-key       (bp ":key_extract")
     :mac         (bp ":mac")
     :verify      (bp ":verify")
     :protoid     (b protoid)
     :server      (js/Buffer. "Server")
     :node-id-len 20
     :key-id-len  32
     :g-len       32
     :h-len       32}))

(defn hmac [key message]
  (let [crypto (node/require "crypto")]
        (-> (.createHmac crypto. "sha256" key)
            (.update message)
            .digest)))

(def h-mac (partial hmac (:mac conf)))
(def h-verify (partial hmac (:verify conf)))

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

(defn req-curve-crypto []
  [(node/require "node-curve25519") (node/require "crypto")])

(defn gen-keys [& [curve]]
  (let [[curve crypto] (req-curve-crypto)
        sec            (.makeSecretKey curve (.randomBytes crypto 32))
        pub            (.derivePublicKey curve sec)]
    [sec pub]))

;; FIXME: assert all lens.
(defn client-init [{srv-id :srv-id pub-B :pub-B :as auth}]
  (let [[secret-x public-X]        (gen-keys)] ;; FIXME: save secret-x in conn's ntor state or something.
    [(merge auth {:sec-x secret-x :pub-X public-X}) (cct srv-id pub-B public-X)]))

(defn server-reply [{pub-B :pub-B sec-b :sec-b id :node-id :as auth} req key-len]
  (assert (= (.-length req) (+ (:node-id-len conf) (:h-len conf) (:h-len conf))) "bad client req ntor length")
  (let [[curve crypto]             (req-curve-crypto) ;; FIXME, useless in the end.
        [req-nid req-pub pub-X]    (b-cut req (:node-id-len conf) (+ (:node-id-len conf) (:h-len conf)))]
    (assert (b= req-nid id)    "received create request with bad node-id")
    (assert (b= req-pub pub-B) "received create request with bad pub key")
    (let [[sec-y pub-Y]            (gen-keys)
          x-y                      (.deriveSharedSecret curve sec-y pub-X)
          x-b                      (.deriveSharedSecret curve sec-b pub-X)
          secret-input             (cct x-y x-b id pub-B pub-X pub-Y (:protoid conf))
          auth-input               (cct (h-verify secret-input) id pub-B pub-Y pub-X (:protoid conf) (:server conf))]
      [(expand secret-input key-len) (cct pub-Y (h-mac auth-input))])))

(defn client-finalise [{srv-id :srv-id pub-B :pub-B pub-X :pub-X sec-x :sec-x :as auth} req key-len]
  (assert (= (.-length req) (+ (:g-len conf) (:h-len conf))) "bad server req ntor length")
  (let [curve                      (node/require "node-curve25519")
        [pub-Y srv-auth]           (b-cut req (:g-len conf))
        x-y                        (.deriveSharedSecret curve sec-x pub-Y)
        x-b                        (.deriveSharedSecret curve sec-x pub-B)
        secret-input               (cct x-y x-b srv-id pub-B pub-X pub-Y (:protoid conf))
        auth                       (h-mac (cct (h-verify secret-input) srv-id pub-B pub-Y pub-X (:protoid conf) (:server conf)))]
    (assert (b= auth srv-auth) "mismatching auth") ;; FIXME here and srv, check x-y & b none 0000.
    (expand secret-input key-len)))
