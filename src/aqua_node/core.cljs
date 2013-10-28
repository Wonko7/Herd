(ns aqua-node.core
  (:require [cljs.core :as cljs]
            [cljs.nodejs :as node]))

(js/setInterval #(println (str "Hello Aqua forever")) 1000)

(defn -main [& args]
  (doseq [i (range 10)]
    (println (str "Hello Aqua " i))))
 
(set! *main-cli-fn* -main)
