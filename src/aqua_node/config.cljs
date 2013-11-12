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
        cat         (fn [k auth]
                      (try {k (.readFileSync fs (auth k) "ascii")}
                           (catch js/Object e (do (println "/!\\  could not load auth info:" e) {k nil}))))
        xcat        (fn [k auth]
                      (try {k (js/Buffer. (.toString (.readFileSync fs (auth k)) "binary") "base64")} ;; FIXME -> this buf->str->buf conversions make base64 work. get this working with a single call, this is ridiculous.
                           (catch js/Object e (do (println "/!\\  could not load auth info:" e) {k nil}))))
        mcat        (fn [cat auth & keys]
                      (apply merge auth (map #(cat % auth) keys)))
        echo-to     (fn [file buf]
                      (.writeFile fs file (.toString buf "base64"))
                      buf)
        ;; cat key paths as keys
        ossl        (mcat cat  (-> cfg :auth :openssl) :cert :key)
        aqua        (mcat xcat (-> cfg :auth :aqua-id) :sec :pub :id)
        aqua        (if (:sec aqua)
                      aqua
                      (let [[s p] (hs/gen-keys)]
                        (merge {:sec (echo-to (-> cfg :auth :aqua-id :sec) s)}
                               {:pub (echo-to (-> cfg :auth :aqua-id :pub) p)}
                               {:id  (echo-to (-> cfg :auth :aqua-id :id)  (-> (node/require "crypto") (.createHash "sha256") (.update p) .digest (.slice 0 20)))})))] ;; FIXME get 20 from conf.
    (merge cfg {:auth {:openssl ossl :aqua-id aqua}})))
