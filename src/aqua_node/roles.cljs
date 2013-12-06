(ns aqua-node.roles
  (:require [cljs.core :as cljs]
            [cljs.nodejs :as node]
            [aqua-node.log :as log]
            [aqua-node.buf :as b]
            [aqua-node.ntor :as hs]
            [aqua-node.circ :as circ]
            [aqua-node.conns :as c]
            [aqua-node.conn-mgr :as conn]))


(defn app-proxy-init [config socket dest]
  (let [[circ-id circ-data] (first (circ/get-all))] ;; FIXME -> choose (based on...?) or create circuit
    (c/update-data socket [:circuit] circ-id)
    (circ/update-data circ-id [:ap-dest] dest)
    ((:mk-path-fn circ-data) config circ-id)
    (circ/update-data circ-id [:backward-hop] socket)))

(defn app-proxy-forward [config s]
  (let [circ-id   (:circuit (c/get-data s))
        circ-data (circ/get-data circ-id)
        config    (merge config {:data s})]
    (if (= (-> circ-data :state) :relay)
      (when (circ/done?)
        (loop [b (.read s 1350)]
          (if b
            (do (circ/inc-block)
                (js/setImmediate #(circ/relay-data config circ-id b))
                (recur (.read s 1350)))
            (when-let [b (.read s)]
              (circ/inc-block)
              (js/setImmediate #(circ/relay-data config circ-id b))))))
      (log/info "not ready for data, dropping on circuit" circ-id))))

(defn aqua-server-recv [config s]
  (log/debug "new dtls conn on:" (-> s .-socket .-_destIP) (-> s .-socket .-_destPort)) ;; FIXME: investigate nil .-remote[Addr|Port]
  (c/add-listeners s {:data #(circ/process config s %)}))

(defn aqua-client-recv [config s]
  (c/add-listeners s {:data #(circ/process config s %)}))

(defn aqua-client-init-path-for-testing [config]
  (circ/mk-single-path config [{:auth {:srv-id (js/Buffer. "h00z6mIWXCPWK4Pp1AQh+oHoHs8=" "base64")
                                       :pub-B  (js/Buffer. "KYi+NX2pCOQmYnscN0K+MB+NO9A6ynKiIp41B5GlkHc=" "base64")}
                                :dest {:type :ip4 :host "127.0.0.1" :port 6669}}
                                ;:dest {:type :ip4 :host "139.19.176.82" :port 6669}}
                               {:auth {:srv-id (js/Buffer. "pQh62d3z8LisFWg8qENauDn7dtU=" "base64")
                                       :pub-B  (js/Buffer. "JnJ35yUEiabocQUR6noo9JAB8prhvu7OP4kQlLVS4QI=" "base64")}
                                :dest {:type :ip4 :host "127.0.0.1" :port 6667}}]))
                                ;:dest {:type :ip4 :host "139.19.176.83" :port 6667}}]))

;(js/setInterval#(circ/relay config s 42 :data "If at first you don't succeed, you fail.")  1000)

(defn is? [role roles] ;; FIXME -> when needed elsewhere move to roles
  (some #(= role %) roles))

(defn bootstrap [{roles :roles ap :app-proxy-conn aq :aqua-conn ds :dir-server :as config}]
  (set! js/process.maxTickDepth 10)
  (let [is?   #(is? % roles)]
    (log/info "Bootstrapping as" roles)
    (when (some is? [:mix :entry :exit])
      (conn/new :aqua :server aq config aqua-server-recv nil nil))
    (when ds ;; the following will be covered by conn-to all known nodes --> sooooon
      (conn/new :aqua  :client ds config aqua-client-recv nil nil))
    (when (is? :app-proxy)
      (conn/new :socks :server ap config app-proxy-forward app-proxy-init circ/destroy-from-socket)
      (aqua-client-init-path-for-testing config))))
