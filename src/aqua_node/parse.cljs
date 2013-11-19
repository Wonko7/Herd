(ns aqua-node.parse
  (:require [cljs.core :as cljs]
            [cljs.nodejs :as node]
            [aqua-node.buf :as b]
            [aqua-node.log :as log]))

(defn ip4-to-bin [ip]
  (let [re   #"^(\d+)\.(\d+)\.(\d+)\.(\d+)$" ]
    (-> (.match ip re) next cljs/clj->js b/new)))

(defn ip6-to-bin [ip]
  (assert nil "FIXME"))

(defn port-to-bin [port]
  (let [p (b/new 2)]
    (.writeUInt16BE p port 0)
    p))

(defn ip4-to-str [buf4]
  (->> (range 0 4) (map #(.readUInt8 buf4 %)) (interpose ".") (apply str)))

(defn ip6-to-str [buf16]
  (->> (.toString buf16 "hex") (partition 4) (interpose [\:]) (apply concat) (apply str)))

(defn dest-to-tor-str [{host :host port :port type :type}]
  (let [host   (if (= type :ip6) (str "[" host "]") host)]
    (str host ":" port)))

(defn parse-addr [buf]
  (let [z            (->> (range (.-length buf))
                          (map #(when (= 0 (.readUInt8 buf %)) %))
                          (some identity))]
    (assert z "bad buffer: no zero delimiter")
    (let [str        (.toString buf "ascii" 0 z)
          ip4-re     #"^((\d+\.){3}\d+):(\d+)$"
          ip6-re     #"^\[((\d|[a-fA-F]|:)+)\]:(\d+)$"
          dns-p      #(let [u (.parse (node/require "url") %)]
                        [(.-hostname u) (.-port u)])
          re         #(let [res (cljs/js->clj (.match %2 %1))]
                        [(nth res %3) (nth res %4)])
          [t a p]    (->> [(re ip4-re str 1 3) (re ip6-re str 1 3) (dns-p str)]
                          (map cons [:ip4 :ip6 :dns])
                          (filter second)
                          first)]
      [{:type t :host a :port p} (.slice buf (inc z))])))
