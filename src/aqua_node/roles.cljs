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
            [aqua-node.rate :as rate]
            [aqua-node.rtpp :as rtp]
            [aqua-node.geo :as geo])
  (:require-macros [cljs.core.async.macros :as m :refer [go-loop go]]))


(defn app-proxy-init [config socket dest]
  (let [circ-id (path/get-path config)] ;; FIXME -> rt for testing, but really this should be single path. only full rtp understands rt path.
    (c/update-data socket [:circuit] circ-id)
    (circ/update-data circ-id [:ap-dest] dest)
    (circ/update-data circ-id [:backward-hop] socket)
    (go (>! (:ctrl (circ/get-data circ-id)) :relay-connect))))

(defn aqua-server-recv [config s]
  (log/debug "new aqua dtls conn from:" (-> s .-socket .-_destIP) (-> s .-socket .-_destPort)) ;; FIXME: investigate nil .-remote[Addr|Port]
  (c/add s {:cs :client :type :aqua :host (-> s .-socket .-_destIP) :port (-> s .-socket .-_destPort)})
  (c/add-listeners s {:data #(circ/process config s %)})
  (js/setTimeout #(rate/init config s) 10)) ;; FIXME *sigh*

(defn aqua-dir-recv [config s]
  (log/debug "new dir tls conn from:" (-> s .-remoteAddress) (-> s .-remotePort))
  (c/add-listeners s {:data #(dir/process config s %)}))

(defn register-dir [config geo mix dir]
  (let [done (chan)
        c    (conn/new :dir :client dir config {:connect #(go (>! done :connected))})]
    (c/add-listeners c {:data #(dir/process config c %)})
    (go (<! done)
        (dir/send-client-info config c geo mix done)
        (<! done)
        ;(log/info "Dir: successfully sent register info")
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

(defn hardcoded-rtp-path [config {dest :dest port :listen-port}]
  (let [cid            (path/get-path config)
        circ           (circ/get-data cid)
        state          (chan)]
    (circ/update-data cid [:state-ch] state)
    (go (>! (:dest-ctrl circ) (merge dest {:proto :udp :type :ip4})))
    (go (let [state          (<! state)
              [_ local-port] (<! (path/attach-local-udp4 config cid {:host "127.0.0.1"} path/app-proxy-forward-udp port))]
          (log/info "Hardcoded RTP: listening on:" local-port "forwarding to:" (:host dest) (:port dest) "using circ:" cid)))))

(defn is? [role roles]
  (some #(= role %) roles))

(defn bootstrap [{roles :roles ap :app-proxy rtp :rtp-proxy aq :aqua ds :remote-dir dir :dir :as config}]
  (let [is?                              #(is? % roles)
        [geo geo geo1 geo2 mix net-info] (repeatedly chan)
        mg                               (mult geo)]
    (tap mg geo1)
    (tap mg geo2)
    (log/info "Bootstrapping as" roles)
    (go (>! geo (<! (geo/parse config))))
    (when-not (is? :dir)
      (go (>! net-info (<! (get-net-info config ds)))))
    (when (is? :app-proxy)
      (go (>! mix (path/init-pools config (<! net-info) (<! geo1) 10)))
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
                                 (log/info "Dir: sending register info")
                                 (register-dir config geo mix ds)
                                 (js/setInterval #(register-dir config geo mix ds) 10000)
                                 (when-let [hc (:hc-rtp config)]
                                   (hardcoded-rtp-path config hc))))
          :else            (go (register-dir config (<! geo2) nil ds)
                               (doseq [[[ip port] mix] (seq (<! net-info))
                                       :when (or (not= (:host aq) ip)
                                                 (not= (:port aq) port))
                                       :let [con (chan)
                                             soc (conn/new :aqua :client mix config {:connect #(go (>! con :done))})]]
                                 (c/add-listeners soc {:data #(circ/process config soc %)})
                                 (go (<! con)
                                     (rate/init config soc)))))))
