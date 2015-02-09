(ns utils.helpers)

;; see https://github.com/swannodette/swannodette.github.com

;; (defn now [] (js/Date.))
;; 
;; (defn index-of [xs x]
;;   (let [len (count xs)]
;;     (loop [i 0]
;;       (if (< i len)
;;         (if (= (nth xs i) x)
;;           i
;;           (recur (inc i)))
;;         -1))))

(defn error? [x]
  (instance? js/Error x))

(defn throw-err [x]
  (if (error? x)
    (throw x)
    x))
