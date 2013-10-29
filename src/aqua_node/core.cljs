(ns aqua-node.core
  (:require [cljs.core :as cljs]
            [cljs.nodejs :as node]
            [aqua-node.socks :as socks]))

(defn -main [& args]
  (socks/create-socks-server {:port 6666}))

(set! *main-cli-fn* -main)
