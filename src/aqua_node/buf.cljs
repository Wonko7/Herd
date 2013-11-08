(ns aqua-node.buf
  (:require [cljs.core :as cljs]
            [cljs.nodejs :as node]))


;; node js/buffer helpers ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn new [data]
  (js/Buffer. data))

(defn cat [& bs]
  "concatenate buffers"
  (js/Buffer.concat (cljs/clj->js bs)))

(defn b= [a b]
  "buffer content equality"
  (= (.toString a "ascii") (.toString b "ascii")))

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
