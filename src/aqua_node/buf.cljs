(ns aqua-node.buf
  (:require [cljs.core :as cljs]
            [cljs.nodejs :as node]))


;; node js/buffer helpers ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn new [data]
  (js/Buffer. data))

(defn cat [& bs]
  "concatenate buffers"
  (js/Buffer.concat (cljs/clj->js bs)))

(defn copycat [& bs]
  (let [len  (reduce #(+ %1 (.-length %2)) 0 bs)
        data (js/Buffer. len)]
    (loop [[b & bs] bs i 0]
      (if b
        (do (.copy b data i)
            (recur bs (+ i (.-length b))))
        data))))

(defn copycat2 [a b]
  (let [len  (+ (.-length a) (.-length b))
        data (js/Buffer. len)]
    (.copy a data)
    (.copy b data (.-length a))
    data))

(defn b= [a b]
  "buffer content equality"
  (= (.toString b) (.toString a)))

(defn cut [b & xs]
  "divide the buffer: (cut b 55 88 99) will return a seq of slices from 0 to 55, 55 to 88, 88 to end of buf"
  (map #(.slice b %1 %2) (cons 0 xs) (concat xs [(.-length b)])))


;; low level io ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn mk-readers [b]
  "make big endian readers"
  [#(.readUInt8 b %) #(.readUInt16BE b %) #(.readUInt32BE b %)])

(defn mk-writers [b]
  "make big endian writers"
  [#(.writeUInt8 b %1 %2) #(.writeUInt16BE b %1 %2) #(.writeUInt32BE b %1 %2)])


;; debug ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn hx [b]
  "debug helper"
  (.toString b "hex"))

(defn print-x [b & [s]]
  (println "---  " s (hx b)))
