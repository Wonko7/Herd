(ns aqua-node.roles
  (:require [cljs.core :as cljs]
            [cljs.nodejs :as node]
            [cljs.core.async :refer [chan <! >! filter< mult tap]]
            [aqua-node.log :as log]
            [aqua-node.misc :as m]
            [aqua-node.buf :as b]
            [aqua-node.conns :as c]
            [aqua-node.conn-mgr :as conn]
            [aqua-node.dir :as dir]
            [aqua-node.circ :as circ]
            [aqua-node.path :as path]
            [aqua-node.rate :as rate]
            [aqua-node.geo :as geo]
            [aqua-node.sip :as sip]
            [aqua-node.sip-dir :as sip-dir])
  (:require-macros [cljs.core.async.macros :as m :refer [go-loop go]]))


;; roles.cljs sets up the different services the node is expected to have:
;;   - an app-proxy sets up a socks proxy & an rtp-proxy and inits a pool of
;;     circuits.
;;   - a mix only sets up an aqua stack
;;   - a dir only sets up a dir service.


(defn app-proxy-init [config socket dest]
  "Attach a socket from socks proxy to a single circuit"
  (go (let [circ-id (<! (path/get-path :single))] ;; FIXME -> rt for testing, but really this should be single path. -> Fixed, but untested for now.
        (c/update-data socket [:circuit] circ-id)
        (circ/update-data circ-id [:ap-dest] dest)
        (circ/update-data circ-id [:backward-hop] socket)
        (>! (:ctrl (circ/get-data circ-id)) :relay-connect))))

(defn aqua-server-recv [config s]
  "Setup socket as an aqua service, send all received data through circ/process."
  (log/debug "new aqua dtls conn from:" (-> s .-socket .-_destIP) (-> s .-socket .-_destPort)) ;; FIXME: investigate nil .-remote[Addr|Port]
  (c/add s {:cs :client :type :aqua :host (-> s .-socket .-_destIP) :port (-> s .-socket .-_destPort)})
  (c/add-listeners s {:data #(circ/process config s %)})
  (c/update-data s [:rate-timer] (rate/init config s))
  (circ/reset-keep-alive config s))

(defn aqua-dir-recv [config s]
  "Setup socket as a dir service, sending all received data through dir/process"
  (log/debug "new dir tls conn from:" (-> s .-remoteAddress) (-> s .-remotePort))
  (c/add-listeners s {:data #(dir/process config s %) :error #(log/info "Dir: socket error")}))

(defn register-to-dir [config geo mix dir]
  "Call send-register-to-dir periodically."
  (let [send-register-to-dir (fn []
                               "Send register to directory"
                               (let [done (chan)
                                     c    (conn/new :dir :client dir config {:connect #(go (>! done :connected))})]
                                 (c/add-listeners c {:data #(dir/process config c %)})
                                 (go (<! done)
                                     (dir/send-client-info config c geo mix done)
                                     (<! done)
                                     (log/debug "successfully sent register info")
                                     (c/rm c)
                                     (.end c))))]
    (send-register-to-dir)
    (js/setInterval #(send-register-to-dir) (:register-interval config))))

(defn get-net-info [config dir]
  "Create a socket to dir, send net request, wait until we get an answer, close, return net info."
  (log/info "requesting net info:")
  (let [done (chan)
        c    (conn/new :dir :client dir config {:connect #(go (>! done :connected))})] ;; also, we'd need a one hop aqua circ here.
    (c/add-listeners c {:data #(dir/process config c % done)})
    (go (<! done)
        (dir/send-net-request config c done)
        (<! done)
        (<! done)
        (c/rm c)
        (.end c)
        (dir/get-net-info))))

(defn aqua-connect [config dest & [ctrl]]
  (let [con (chan)
        soc (conn/new :aqua :client dest config {:connect #(go (>! con :done))})]
    (log/debug "Aqua: Connecting to" (select-keys dest [:host :port :role]))
    (c/add-listeners soc {:data #(circ/process config soc %)})
    (c/update-data soc [:auth] (:auth dest))
    (go (<! con)
        (rate/init config soc)
        (rate/queue soc #(circ/send-id config soc))
        (when ctrl (>! ctrl soc)))))

(defn aqua-connect-from-id [config net-info id ctrl]
  (let  [mixes (for [[[ip port] mix] (seq (<! net-info))
                     :when (= id (-> mix :auth :srv-id))]
                 mix)]
    (aqua-connect config (first mixes) ctrl)))

(defn connect-to-all [{aq :aqua :as config}]
  "For each mix in node info, if not already connected, extract ip & port and connect."
  (go (let [ctrl   (chan)]
        (doseq [[[ip port] mix] (seq (dir/get-net-info))
                :when (and (or (not= (:host aq) ip)
                               (not= (:port aq) port))
                           (nil? (c/find-by-id (-> mix :auth :srv-id))))]
          (aqua-connect config mix ctrl)
          (<! ctrl)))))

(defn bootstrap [{roles :roles ap :app-proxy aq :aqua ds :remote-dir dir :dir sip-dir :sip-dir :as config}]
  "Setup the services needed by the given role."
  (go (let [is?         #(m/is? % roles)                        ;; tests roles for our running instance
            geo         (go (<! (geo/parse config)))          ;; match our ip against database, unless already specified in config:
            net-info    (go (when-not (is? :dir)              ;; request net-info if we're not a dir. FIXME -> get-net-info will be called periodically.
                              (<! (get-net-info config ds))))]

        (log/info "Aqua node ID:" (-> config :auth :aqua-id :id b/hx))
        (log/info "Bootstrapping as" roles)

        (when (is? :app-proxy)
          (let [geo      (<! geo)
                net-info (<! net-info)
                sip-chan (sip/create-server config)
                cfg      (merge config {:sip-chan sip-chan})
                mix      (path/init-pools cfg net-info geo 2)]
            (conn/new :socks :server ap config {:data     path/app-proxy-forward
                                                :udp-data path/app-proxy-forward-udp
                                                :init     app-proxy-init
                                                :error    circ/destroy-from-socket})
            (js/setInterval #(get-net-info config ds) (:register-interval config))
            (log/info "Dir: sending register info")
            (register-to-dir config geo mix ds)))

        ;; (when (is? :super-peer)
        ;;   (register-to-dir config (<! geo) mix ds)
        ;;   (conn/new :channel :server sp config {:connect identity}))

        (when (or (is? :mix) (is? :rdv))
          (let [sip-chan (sip-dir/create-mix-dir config)
                config   (merge config {:sip-chan sip-chan :sip-mix-dir sip-dir/mix-dir})
                ctrl     (chan)]
            (conn/new :aqua :server aq config {:connect aqua-server-recv})
            (<! net-info)
            (connect-to-all config)
            (js/setInterval #(go (<! (get-net-info config ds))
                                 (connect-to-all config))
                            (:register-interval config))
            (register-to-dir config (<! geo) nil ds)))

        (when (is? :dir)
          (conn/new :dir :server dir config {:connect aqua-dir-recv}))

        (when (is? :sip-dir)
          (let [sip-chan (sip-dir/create-dir config)
                cfg      (merge config {:sip-chan sip-chan :aqua sip-dir})]
            (conn/new :aqua :server sip-dir cfg {:connect aqua-server-recv})
            (register-to-dir cfg (<! geo) nil ds))))))
