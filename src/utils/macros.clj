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

(defmacro <?? [expr & [{loops :loops
                        timeout-val :timeout return-value secs :secs mins :mins
                        :ret-val return-fn :return-fn result-chan :chan}]]
  (assert (or (and (nil? return-value) (nil? return-fn))
              (nil? return-value)
              (nil? return-fn))
          "Setting both return-fn & return-value does not make sense.")
  (assert (or timeout (or secs mins))
          "Setting both timeout & secs/mins does not make sense.")
  (let [loops       (or loops 3)
        timeout-val (if (or secs mins)
                      (+ (* mins 60 1000) (* secs 1000))
                      timeout-val)
        timeout-fn  `(fn [ch#]
                       (go (cljs.core.async/<! (cljs.core.async/timeout ~timeout-val))
                           (cljs.core.async/>! ch# [:end])))
        return-fn   (or return-fn (if return-value
                                    `#(= % ~return-value)
                                    `identity))
        to-ch       (gensym)
        result      (gensym)]
    `(let [~result    (or ~result-chan (cljs.core.async/chan))
           ~to-ch     (cljs.core.async/chan)
           retry-fn#  (fn []
                        (go? ~(if result-chan
                                `~expr
                                `(cljs.core.async/>! ~result ~expr)))
                        ~(when timeout-val
                           `(~timeout-fn ~to-ch))
                        (cljs.core.async.macros/go (cljs.core.async/alts! [~result ~to-ch])))]
       (<? (go? (loop [[v# ch#] (cljs.core.async/<! (retry-fn#))
                       i#       1]
                  (cond (= ch# ~to-ch)  (do (when (= i# ~loops)
                                              (throw "Failed, reached timeout on each try"))
                                            (recur (cljs.core.async/<! (retry-fn#)) (inc i#)))
                        (~return-fn v#) v#
                        (= i# ~loops)   (throw "Reached max retries")
                        :else           (recur (cljs.core.async/<! (retry-fn#)) (inc i#)))))))))

;; only used for debugging:
(defmacro dprint [& body]
  `(let [result# (do ~@body)]
     (println "::::::::::" (str ~cljs.analyzer/*cljs-file* ":" ~(:line (meta &form))))
     (println :body '~@body)
     (println :result result#)
     result#))
