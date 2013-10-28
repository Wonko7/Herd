(ns aqua-node.core)

(defn -main [& args]
  (doseq [i (range)]
    (println (str "Hello Aqua forever " i))))
 
(set! *main-cli-fn* -main)
