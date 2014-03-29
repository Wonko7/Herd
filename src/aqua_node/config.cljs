(ns aqua-node.config
  (:require [cljs.core :as cljs]
            [cljs.nodejs :as node]
            [cljs.reader :as reader]
            [aqua-node.buf :as b]
            [aqua-node.crypto :as c]))


(def config (atom {}))

(def static-conf 
  ;; set defaults and non user stuff here.
  (let [;; ntor handshake configuration:
        protoid   "ntor-curve25519-sha256-1"
        bp        #(b/new (str protoid %1))
        ntor      {:m-expand    (bp ":key_expand")
                   :t-key       (bp ":key_extract")
                   :mac         (bp ":mac")
                   :verify      (bp ":verify")
                   :protoid     (b/new protoid)
                   :server      (b/new "Server")
                   :node-id-len 20
                   :key-id-len  32
                   :g-len       32
                   :h-len       32}]
    ;; key lengths, ntor, and rate period in milliseconds.
    {:enc               {:iv-len 16 :key-len 32}
     :debug             false
     :ntor-values       ntor
     :rate              {:period 10}
     :register-interval 10000}))

(defn read-config [argv]
  "Parse config file aquarc in current directory, or from argv's --config <path>."
  (let [;; read config
        fs          (node/require "fs")
        read        #(reader/read-string %)
        cfg         (read (.readFileSync fs (or (:config argv) "aquarc") "utf8"))
        ;; file manipulation
        cat         (fn [k auth]
                      "Read file, path in the k key of the auth map: used for reading openssl certs."
                      (try {k (.readFileSync fs (auth k) "ascii")}
                           (catch js/Object e (do (println "/!\\  could not load auth info:" e) {k nil}))))
        xcat        (fn [k auth]
                      "Read file, path in the k key of the auth map: used for reading aqua certs. File expected in base64."
                      (try {k (js/Buffer. (.toString (.readFileSync fs (auth k)) "binary") "base64")} ;; FIXME -> this buf->str->buf conversions make base64 work. get this working with a single call, this is ridiculous.
                           (catch js/Object e (do (println "/!\\  could not load auth info:" e) {k nil}))))
        mcat        (fn [cat auth & keys]
                      "map one of the cat functions over keys."
                      (apply merge auth (map #(cat % auth) keys)))
        echo-to     (fn [file buf]
                      "Write a buffer to a file as base64, for writing aqua certs. Return the original buffer."
                      (.writeFile fs file (.toString buf "base64"))
                      buf)
        ;; cat key paths as keys
        ;; read certs, from the paths given in config:
        ossl        (mcat cat  (-> cfg :auth :openssl) :cert :key)
        aqua        (mcat xcat (-> cfg :auth :aqua-id) :sec :pub :id)
        ;; if aqua certs are null, create some (first run).
        aqua        (if (:sec aqua)
                      aqua
                      (let [[s p] (c/gen-keys)] ;; generate aqua certs.
                        (merge {:sec (echo-to (-> cfg :auth :aqua-id :sec) s)}
                               {:pub (echo-to (-> cfg :auth :aqua-id :pub) p)}
                               {:id  (echo-to (-> cfg :auth :aqua-id :id)  (-> (node/require "crypto") (.createHash "sha256") (.update p) .digest (.slice 0 (-> static-conf :ntor-values :node-id-len))))})))] ;; FIXME test node len
    (swap! config merge static-conf cfg {:auth {:openssl ossl :aqua-id aqua}} argv))) ;; FIXME will probably need to remove that, filter argv values we accept & do sanity checking. For now anything in argv overwrites config file.
                                                                                      ;; FIXME Argv supported options:
                                                                                      ;;           --debug (also --debug false)
                                                                                      ;;           --config <file path>. 

(defn get-cfg []
  @config)
