(ns aqua-node.roles
  (:require [cljs.core :as cljs]
            [cljs.nodejs :as node]
            [aqua-node.log :as log]
            [aqua-node.buf :as b]
            [aqua-node.ntor :as hs]
            [aqua-node.circ :as circ]
            [aqua-node.conns :as c]
            [aqua-node.conn-mgr :as conn]))


(defn aqua-client-init-path-for-testing [config]
  (circ/mk-single-path config [{:auth {:srv-id (js/Buffer. "h00z6mIWXCPWK4Pp1AQh+oHoHs8=" "base64")
                                       :pub-B  (js/Buffer. "KYi+NX2pCOQmYnscN0K+MB+NO9A6ynKiIp41B5GlkHc=" "base64")}
                                :dest {:type :ip4 :host "127.0.0.1" :port 6669}}
                                ;:dest {:type :ip4 :host "139.19.176.82" :port 6669}}
                               ;{:auth {:srv-id (js/Buffer. "pQh62d3z8LisFWg8qENauDn7dtU=" "base64")
                               ;        :pub-B  (js/Buffer. "JnJ35yUEiabocQUR6noo9JAB8prhvu7OP4kQlLVS4QI=" "base64")}
                               ; ;:dest {:type :ip4 :host "127.0.0.1" :port 6667}}]))
                               ; :dest {:type :ip4 :host "139.19.176.83" :port 6667}}
                               ;{:auth {:srv-id (js/Buffer. "/kYydVqsBs2ssFGq6270h5cw9lg=" "base64")
                               ;        :pub-B  (js/Buffer. "MVoWVfmV+DDUQTPU/vrhROnrnIOowFKvx1ZNSf0wjCY=" "base64")}
                               ; ;:dest {:type :ip4 :host "127.0.0.1" :port 6660}}]))
                               ; :dest {:type :ip4 :host "139.19.176.83" :port 6660}}
                               ;{:auth {:srv-id (js/Buffer. "Spfv2p0qoXnW/4HotIOUMSDt2bk=" "base64")
                               ;        :pub-B  (js/Buffer. "EiRtu6iEoFT9te0QS6uOJWHo7P95/uWbLAhsU+Oxjnc=" "base64")}
                               ; ;:dest {:type :ip4 :host "127.0.0.1" :port 6661}}]))
                               ; :dest {:type :ip4 :host "139.19.176.83" :port 6661}}
                               ]))

(defn app-proxy-init [config socket dest]
  (let [circ-id (aqua-client-init-path-for-testing config)] ;; FIXME -> choose (based on...?) or create circuit
    (c/update-data socket [:circuit] circ-id)
    (println :init (keys (c/get-data socket)))
    (circ/update-data circ-id [:ap-dest] dest)
    (circ/update-data circ-id [:backward-hop] socket)))

(defn app-proxy-forward-udp [config s b]
  (let [circ-id   (:circuit (c/get-data s))
        circ-data (circ/get-data circ-id)
        config    (merge config {:data s})]
    (println :forward (keys (c/get-data s)))
    (if (= (-> circ-data :state) :relay)
      (circ/relay-data config circ-id b)
      (log/info "UDP: not ready for data, dropping on circuit" circ-id))))

(defn app-proxy-forward [config s]
  (let [circ-id   (:circuit (c/get-data s))
        circ-data (circ/get-data circ-id)
        config    (merge config {:data s})]
    (if (= (-> circ-data :state) :relay)
      (when (circ/done?)
        (if-let [b (.read s 1350)]
          (do (circ/inc-block)
              (circ/relay-data config circ-id b))
          (when-let [b (.read s)]
            (circ/inc-block)
            (circ/relay-data config circ-id b))))
      (log/info "TCP: not ready for data, dropping on circuit" circ-id))))

(defn aqua-server-recv [config s]
  (log/debug "new dtls conn on:" (-> s .-socket .-_destIP) (-> s .-socket .-_destPort)) ;; FIXME: investigate nil .-remote[Addr|Port]
  (c/add-listeners s {:data #(circ/process config s %)}))

(defn aqua-client-recv [config s]
  (c/add-listeners s {:data #(circ/process config s %)}))

;(js/setInterval#(circ/relay config s 42 :data "If at first you don't succeed, you fail.")  1000)

(defn is? [role roles] ;; FIXME -> when needed elsewhere move to roles
  (some #(= role %) roles))

(defn bootstrap [{roles :roles ap :app-proxy-conn aq :aqua-conn ds :dir-server :as config}]
  (set! js/process.maxTickDepth 10)
  (let [is?   #(is? % roles)]
    (log/info "Bootstrapping as" roles)
    (when (some is? [:mix :entry :exit])
      (conn/new :aqua :server aq config {:data aqua-server-recv}))
    (when ds ;; the following will be covered by conn-to all known nodes --> sooooon
      (conn/new :aqua  :client ds config {:data aqua-client-recv}))
    (when (is? :app-proxy)
      (conn/new :socks :server ap config {:data     app-proxy-forward
                                          :udp-data app-proxy-forward-udp
                                          :init     app-proxy-init
                                          :error    circ/destroy-from-socket}))))
