(ns aqua-node.core
  (:require [cljs.core :as cljs]
            [cljs.nodejs :as node]))

(defn -main [& args]
  (let [net       (node/require "net")
        handler   (fn [data]
                    (println "handler used!")
                    (this-as self
                             (let [socket (.connect net (-> self .-target .-port) (-> self .-target .-ip) #(this-as t (.write t data)))]
                               (-> socket
                                 (.setEncoding "utf8")
                                 (.on "data" #(.write self %))))))
        handler #(println %)
        socks-srv ((node/require "sockserver") handler)
        ]
    (.listen socks-srv 6666 #(println (str "new conn: " (-> socks-srv .address .-port))))
    )
  (doseq [i (range 10)]
    (println (str "Hello Aqua " i))))
 
(set! *main-cli-fn* -main)


;; var socks = require('sockserver'),
;;     net = require('net');
;; 
;; function handler(data) {
;;   var self = this,
;;       socket = net.connect(
;;         this.target.port,
;;         this.target.ip || this.target.domain,
;;         function () {
;;           this.write(data);
;;         }
;;       );
;; 
;;   socket.setEncoding('utf8');
;;   socket.on('data', function (chunk) {
;;     self.write(chunk);
;;   });
;; }
;; 
;; var server = socks(handler);
;; 
;; server.listen(8000, function () {
;;   console.log('Listening on port %d', server.address().port);
;; });
;; 
