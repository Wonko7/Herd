(ns aqua-node.roles
  (:require [cljs.core :as cljs]
            [cljs.nodejs :as node]
            [cljs.core.async :refer [chan <! >! filter< mult tap]]
            [aqua-node.log :as log]
            [aqua-node.buf :as b]
            [aqua-node.conns :as c]
            [aqua-node.conn-mgr :as conn]
            [aqua-node.dir :as dir]
            [aqua-node.circ :as circ]
            [aqua-node.path :as path]
            [aqua-node.rtpp :as rtp]
            [aqua-node.geo :as geo])
  (:require-macros [cljs.core.async.macros :as m :refer [go-loop go]]))


(def test-path [{:auth {:srv-id (js/Buffer. "h00z6mIWXCPWK4Pp1AQh+oHoHs8=" "base64")
                        :pub-B  (js/Buffer. "KYi+NX2pCOQmYnscN0K+MB+NO9A6ynKiIp41B5GlkHc=" "base64")}
                :dest {:type :ip4 :host "54.194.191.213" :port 6666}}
                ;:dest {:type :ip4 :host "192.168.0.10" :port 6669}}
                ;:dest {:type :ip4 :host "139.19.176.82" :port 6669}}
                ;{:auth {:srv-id (js/Buffer. "pQh62d3z8LisFWg8qENauDn7dtU=" "base64")
                ;        :pub-B  (js/Buffer. "JnJ35yUEiabocQUR6noo9JAB8prhvu7OP4kQlLVS4QI=" "base64")}
                ; :dest {:type :ip4 :host "127.0.0.1" :port 6667}}
                ; :dest {:type :ip4 :host "139.19.176.83" :port 6667}}
                ;{:auth {:srv-id (js/Buffer. "/kYydVqsBs2ssFGq6270h5cw9lg=" "base64")
                ;        :pub-B  (js/Buffer. "MVoWVfmV+DDUQTPU/vrhROnrnIOowFKvx1ZNSf0wjCY=" "base64")}
                ; ;:dest {:type :ip4 :host "127.0.0.1" :port 6660}}]))
                ; :dest {:type :ip4 :host "139.19.176.83" :port 6660}}
                ;{:auth {:srv-id (js/Buffer. "Spfv2p0qoXnW/4HotIOUMSDt2bk=" "base64")
                ;        :pub-B  (js/Buffer. "EiRtu6iEoFT9te0QS6uOJWHo7P95/uWbLAhsU+Oxjnc=" "base64")}
                ; ;:dest {:type :ip4 :host "127.0.0.1" :port 6661}}]))
                ; :dest {:type :ip4 :host "139.19.176.83" :port 6661}}
                ])

(defn app-proxy-init [config socket dest]
  (let [circ-id (path/get-path config)] ;; FIXME -> rt for testing, but really this should be single path. only full rtp understands rt path.
    (c/update-data socket [:circuit] circ-id)
    (circ/update-data circ-id [:ap-dest] dest)
    (circ/update-data circ-id [:backward-hop] socket)
    (go (>! (:ctrl (circ/get-data circ-id)) :relay-connect))))

(defn aqua-server-recv [config s]
  (log/debug "new aqua dtls conn from:" (-> s .-socket .-_destIP) (-> s .-socket .-_destPort)) ;; FIXME: investigate nil .-remote[Addr|Port]
  (c/add-listeners s {:data #(circ/process config s %)}))

(defn aqua-client-recv [config s]
  (c/add-listeners s {:data #(circ/process config s %)}))

(defn aqua-dir-recv [config s]
  (log/debug "new dir tls conn from:" (-> s .-remoteAddress) (-> s .-remotePort))
  (c/add-listeners s {:data #(dir/process config s %)}))

(defn register-dir [config geo mix dir]
  (log/info "start registering:")
  (let [done (chan)
        c    (conn/new :dir :client dir config {:connect #(go (>! done :connected))})]
    (c/add-listeners c {:data #(dir/process config c %)})
    (go (<! done)
        (dir/send-client-info config c geo mix done)
        (<! done)
        (log/info "successfully registered")
        (c/rm c)
        (.end c))))

(defn get-net-info [config dir]
  (log/info "requesting net info:")
  (let [done (chan)
        c    (conn/new :dir :client dir config {:connect #(go (>! done :connected))})]
    (c/add-listeners c {:data #(dir/process config c % done)})
    (go (<! done)
        (dir/send-net-request config c done)
        (<! done)
        (<! done)
        (c/rm c)
        (.end c)
        (dir/get-net-info))))


(defn is? [role roles]
  (some #(= role %) roles))

(defn bootstrap [{roles :roles ap :app-proxy rtp :rtp-proxy aq :aqua ds :remote-dir dir :dir :as config}]
  (let [is?      #(is? % roles)
        geo      (chan)
        mg       (mult geo)
        geo1     (chan)
        geo2     (chan)
        mix      (chan)]
    (tap mg geo1)
    (tap mg geo2)
    (log/info "Bootstrapping as" roles)
    (go (>! geo (<! (geo/parse config))))
    (when (is? :app-proxy)
      ;(path/create-single config test-path)
      (go (>! mix (path/init-pools config (<! (get-net-info config ds)) (<! geo1) 10)))
      (conn/new :socks :server ap config {:data     path/app-proxy-forward
                                          :udp-data path/app-proxy-forward-udp
                                          :init     app-proxy-init
                                          :error    circ/destroy-from-socket})
      (when rtp
        (rtp/create-server rtp config)))
    (conn/new :aqua :server aq config {:connect aqua-server-recv})
    (cond (is? :dir)       (conn/new :dir :server dir config {:connect aqua-dir-recv})
          (is? :app-proxy) (go (let [geo (<! geo2)
                                     mix (<! mix)]
                                 (register-dir config geo mix ds)
                                 (js/setInterval #(register-dir config geo mix ds) 300000)))
          :else            (go (register-dir config (<! geo2) nil ds)))))
