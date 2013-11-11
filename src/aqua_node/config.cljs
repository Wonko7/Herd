(ns aqua-node.config
  (:require [cljs.core :as cljs]
            [cljs.nodejs :as node]
            [cljs.reader :as reader]
            [aqua-node.buf :as b]
            [aqua-node.ntor :as hs]))


(def config (atom {}))

(defn read-config []
  (let [;; read config
        fs          (node/require "fs")
        read        #(swap! config merge (reader/read-string %))
        cfg         (read (.readFileSync fs "aquarc" "utf8"))
        ;; file manipulation
        cat         (fn [k auth enc]
                      (try {k (.readFileSync fs (auth k) enc)}
                           (catch js/Object e (do (println "/!\\  could not load auth info:" e) {k nil}))))
        mcat        (fn [enc auth & keys]
                      (apply merge auth (map #(cat % auth enc) keys)))
        echo-to     (fn [file buf]
                      (.writeFile fs file (b/hx buf "hex"))
                      buf)
        ;; cat key paths as keys
        ossl        (mcat "ascii" (-> cfg :auth :openssl) :cert :key)
        aqua        (mcat "ascii" (-> cfg :auth :aqua-id) :sec :pub :id)
        aqua        (if (:sec aqua)
                      aqua
                      (let [[s p] (hs/gen-keys)]
                        (merge {:sec (echo-to (-> cfg :auth :aqua-id :sec) s)}
                               {:pub (echo-to (-> cfg :auth :aqua-id :pub) p)}
                               {:id  (echo-to (-> cfg :auth :aqua-id :id)  (-> (node/require "crypto") (.createHash "sha256") (.update p) .digest))})))]
    (merge cfg {:auth {:openssl ossl :aqua-id aqua}})))
