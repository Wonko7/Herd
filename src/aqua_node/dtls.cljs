(ns aqua-node.dtls
  (:require [cljs.core :as cljs]
            [cljs.nodejs :as node]))

(defn create-aqua-listening-socket [{auth :auth
                                     {addr :addr
                                      port :port} :listen}
                                    new-conn-handler]
  (let [dtls       (node/require "./node_modules/nodedtls/index.js") ;; FIXME: look into making this cleaner.
        fs         (node/require "fs")
        auth       (apply merge (for [k (keys auth)] {k (.readFileSync fs (auth k) "utf8")}))]
    (.createServer dtls (cljs/clj->js auth) new-conn-handler)))
