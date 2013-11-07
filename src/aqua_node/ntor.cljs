(ns aqua-node.ntor
  (:require [cljs.core :as cljs]
            [cljs.nodejs :as node]))


;; see: torspec/proposals/216-ntor-handshake.txt
;;      torspec/tor-spec.txt 5.1.4

;; FIXME: buff helpers: this will be exported.
(defn cct [& bs]
  (js/Buffer.concat (cljs/clj->js bs)))

(defn b= [a b]
  (= (.toString a "ascii") (.toString b "ascii")))

(defn b-cut [b & xs]
  (doall (map #(.slice b %1 %2) (cons 0 xs) (concat xs [(.-length b)]))))
;; end buff helpers

;; FIXME: part or all of this static (non user) conf will get exported as pieces of it are needed by other modules.
(def conf
  (let [protoid "ntor-curve25519-sha256-1"]
    {:m-expand    (str protoid ":key_expand")
     :t-key       (str protoid ":key_extract")
     :mac         (str protoid ":mac")
     :verify      (str protoid ":verify")
     :protoid     protoid
     :protoid-buf (js/SlowBuffer. protoid)  ;; for perf
     :server-buf  (js/SlowBuffer. "Server") ;; for perf
     :node-id-len 20
     :key-id-len  32
     :g-len       32
     :h-len       32}))

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

(defn req-curve-crypto []
  [(node/require "node-curve25519") (node/require "crypto")])

(defn gen-keys [& [curve]]
  (let [[curve crypto] (req-curve-crypto)
        sec            (.makeSecretKey curve (.randomBytes crypto 32))
        pub            (.derivePublicKey curve sec)]
    [sec pub]))

;; FIXME: assert all lens.
(defn client-init [srv]
  (let [[secret-x public-X]        (gen-keys)] ;; FIXME: save secret-x in conn's ntor state or something.
    [{:secret secret-x :public public-X} (cct (:node-id srv) (:pub-key srv) public-X)]))

(defn server-reply [{pub-B :public sec-b :secret id :node-id} key-len req]
  (assert (= (.-length req) (+ (:node-id-len conf) (:h-len conf) (:h-len conf))) "bad client ntor length")
  (let [[curve crypto]             (req-curve-crypto)
        [req-nid req-pub public-X] (b-cut req (:node-id-len conf) (+ (:node-id-len conf) (:h-len conf)))
        pub-X                      (.derivePublicKey curve public-X) ]
    (assert (= req-nid id)  "received create request with bad node-id")
    (assert (= req-pub pub-B) "received create request with bad pub key")
    (let [[sec-y pub-Y]            (gen-keys)
          x-y                      (.deriveSharedSecret curve sec-y pub-X)
          x-b                      (.deriveSharedSecret curve sec-b pub-X)
          secret-input             (cct x-y x-b id pub-B pub-X pub-Y (:protoid-buf conf))
          auth-input               (cct (hmac (:verify conf) secret-input) id pub-B pub-X pub-Y (:protoid-buf conf) (:server-buf conf))]
      [(expand secret-input key-len) (cct pub-Y (hmac (:t-mac conf) auth-input))])))
