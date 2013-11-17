(ns aqua-node.parse
  (:require [cljs.core :as cljs]
            [cljs.nodejs :as node]
            [aqua-node.buf :as b]
            [aqua-node.log :as log]))

(defn ip4-to-bin [ip]
  (let [re   #"^(\d+)\.(\d+)\.(\d+)\.(\d+)$" ]
    (log/error "FIXME just testing" (next (.match ip re)))
    (-> (.match ip re) next cljs/clj->js b/new)))

(defn parse-addr [buf]
  (let [z            (->> (range (.-length buf))
                          (map #(when (= 0 (.readUInt8 buf %)) %))
                          (some identity))]
    (assert z "bad buffer: no zero delimiter")
    (let [buf        (.toString buf "ascii" 0 z)
          ip4-re     #"^((\d+\.){3}\d+):(\d+)$"
          ip6-re     #"^\[((\d|[a-fA-F]|:)+)\]:(\d+)$"
          dns-p      #(let [u (.parse (node/require "url") %)]
                        [(.-hostname u) (.-port u)])
          re         #(let [res (cljs/js->clj (.match %2 %1))]
                        [(nth res %3) (nth res %4)])
          [t a p]    (->> [(re ip4-re buf 1 3) (re ip6-re buf 1 3) (dns-p buf)]
                          (map cons [:ip4 :ip6 :dns])
                          (filter second)
                          first)]
      {:type t :host a :port p})))
