(ns utils.macros)
;; see https://github.com/swannodette/swannodette.github.com

(defmacro <? [expr]
  `(utils.helpers/throw-err (cljs.core.async/<! ~expr)))

(defmacro go-try-catch [& body]
  `(cljs.core.async.macros/go
     (try 
       ~@(drop-last body)
       (catch js/Object e# (do (~(last body))
                               (println e#))))))

(defmacro go? [& body]
  `(cljs.core.async.macros/go
     (try 
       ~@body
       (catch js/Object e# (do (aqua-node.log/info (str ~cljs.analyzer/*cljs-file* ":" ~(:line (meta &form))) e#)
                               e#)))))

(defmacro go-retry? [expr & [{loops :loops timeout-val :timeout return-value :ret-val return-fn :return-fn}]]
  (assert (or (and (nil? return-value) (nil? return-fn))
              (nil? return-value)
              (nil? return-fn))
          "Setting both return-fn & return-value does not make sense.")
  (let [loops       (or loops 3)
        timeout-fn  `(fn [ch#]
                       (go (cljs.core.async/<! (cljs.core.async/timeout ~timeout-val))
                           (cljs.core.async/>! ch# [:end])))
        return-fn   (or return-fn (if return-value
                                    `#(= % ~return-value)
                                    `identity))
        to-ch       (gensym)]
    `(let [result#    (cljs.core.async/chan)
           ~to-ch     (cljs.core.async/chan)
           retry-fn#  (fn []
                        (go? (cljs.core.async/>! result# [~expr])) ;; we are putting result in an array in case its nil.
                        ~(when timeout-val
                           `(~timeout-fn ~to-ch))
                        (cljs.core.async.macros/go (cljs.core.async/alts! [result# ~to-ch])))]
       (go? (loop [[[v#] ch#] (cljs.core.async/<! (retry-fn#))
                   i#         1]
              (cond (= ch# ~to-ch) (do (when (= i# ~loops)
                                         (throw "Failed, reached timeout on each try"))
                                       (recur (cljs.core.async/<! (retry-fn#)) (inc i#)))
                    (~return-fn v#) v#
                    (= i# ~loops)  (throw "Reached max retries")
                    :else          (recur (cljs.core.async/<! (retry-fn#)) (inc i#))))))))

;; only used for debugging:
(defmacro dprint [& body]
  `(let [result# (do ~@body)]
     (println "::::::::::" (str ~cljs.analyzer/*cljs-file* ":" ~(:line (meta &form))))
     (println :body '~@body)
     (println :result result#)
     result#))
