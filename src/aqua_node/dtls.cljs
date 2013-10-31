(ns aqua-node.dtls
  (:require [cljs.core :as cljs]
            [cljs.nodejs :as node]))

(defn mk-dtls [auth addr]
  (let [fs          (node/require "fs")
        cat         (fn [k] (try {k (.readFileSync fs (auth k) "utf8")}
                              (catch js/Object e (println "/!\\  could not load auth info: " e))))]
    [(node/require "nodedtls") (cljs/clj->js (merge auth {:host addr} (cat :key) (cat :cert)))]))

(defn create-aqua-listening-socket [{auth :auth addr :addr port :port}
                                    new-conn-handler]
  (let [[dtls auth] (mk-dtls auth addr)]
    (.createServer dtls port auth new-conn-handler))) ;; addr goes in options?

(defn connect-to-aqua-node [{addr :addr port :port} {auth :auth} conn-handler]
  (let [[dtls auth] (mk-dtls auth addr)]
    (.connect dtls port 0 auth conn-handler)))
