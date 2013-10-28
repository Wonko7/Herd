(ns aqua-node.core
  (:require [cljs.core :as cljs]
            [cljs.nodejs :as node]))

(defn -main [& args]
  (let [net     (node/require "net")
        recv    (fn [c data]
                  (println (str "recv: " data))
                  (.write c "lolz\n"))
        srv     (.createServer net (fn [c] 
                                     (println "conn start")
                                     (-> c 
                                     (.on "end" #(println "conn end")) 
                                     (.on "error" #(println "conn error")) 
                                     (.on "data" (partial recv c)) 
                                     (.write "lol\n"))))
        ]
    (.listen srv 6666 #(println (str "listening on: " (-> srv .address .-port))))))

(set! *main-cli-fn* -main)
