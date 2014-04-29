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
  (rate/init config s))

(defn aqua-dir-recv [config s]
  "Setup socket as a dir service, sending all received data through dir/process"
  (log/debug "new dir tls conn from:" (-> s .-remoteAddress) (-> s .-remotePort))
  (c/add-listeners s {:data #(dir/process config s %)}))

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

(defn hardcoded-rtp-path [config {dest :dest port :listen-port}] ;; keep this for testing and benchmarks. should move this.
  "Was used for facilitating benchmarking."
  (go (let [cid            (<! (path/get-path :rt))
            circ           (circ/get-data cid)
            state          (chan)]
        (circ/update-data cid [:state-ch] state)
        (go (>! (:dest-ctrl circ) (merge dest {:proto :udp :type :ip4})))
        (go (let [state          (<! state)
                  [_ local-port] (<! (path/attach-local-udp4 config cid {:host "127.0.0.1"} path/app-proxy-forward-udp port))]
              (log/info "Hardcoded RTP: listening on:" local-port "forwarding to:" (:host dest) (:port dest) "using circ:" cid))))))

(defn aqua-connect [config dest & [ctrl]]
  (let [con (chan)
        soc (conn/new :aqua :client dest config {:connect #(go (>! con :done))})]
    (log/debug "Aqua: Connecting to" dest)
    (c/add-listeners soc {:data #(circ/process config soc %)})
    (go (<! con)
        (rate/init config soc)
        (when ctrl (>! ctrl soc)))))

(defn is? [role roles]
  "Tests if a role is part of the given roles"
  (some #(= role %) roles))

(defn bootstrap [{roles :roles ap :app-proxy aq :aqua ds :remote-dir dir :dir sip-dir :sip-dir :as config}]
  "Setup the services needed by the given role."
  (go (let [is?         #(is? % roles)                        ;; tests roles for our running instance
            geo         (go (<! (geo/parse config)))          ;; match our ip against database, unless already specified in config:
            net-info    (go (when-not (is? :dir)              ;; request net-info if we're not a dir. FIXME -> get-net-info will be called periodically.
                              (<! (get-net-info config ds))))]
        (log/info "Bootstrapping as" roles "in")
        (when (is? :app-proxy)
          (let [geo      (<! geo)
                net-info (<! net-info)
                sip-chan (sip/create-server config net-info)
                cfg      (merge config {:sip-chan sip-chan})
                mix      (path/init-pools cfg net-info geo 2)]
            (conn/new :socks :server ap config {:data     path/app-proxy-forward
                                                :udp-data path/app-proxy-forward-udp
                                                :init     app-proxy-init
                                                :error    circ/destroy-from-socket})
            (log/info "Dir: sending register info")
            (register-to-dir config geo mix ds)))
        (when (or (is? :mix) (is? :rdv))
          (let [sip-chan (sip-dir/create-mix-dir config)
                cfg      (merge config {:sip-chan sip-chan :aqua sip-dir :sip-mix-dir sip-dir/mix-dir :aqua-connect aqua-connect})]
            (conn/new :aqua :server aq cfg {:connect aqua-server-recv})
            (register-to-dir config (<! geo) nil ds)
            ;; for each mix in node info, extract ip & port and connect.
            (doseq [[[ip port] mix] (seq (<! net-info))
                    :when (or (not= (:host aq) ip)
                              (not= (:port aq) port))]
              (aqua-connect cfg mix))))
        (when (is? :dir)
          (conn/new :dir :server dir config {:connect aqua-dir-recv}))
        (when (is? :sip-dir)
          (let [sip-chan (sip-dir/create-dir config)
                cfg      (merge config {:sip-chan sip-chan :aqua sip-dir})]
            (conn/new :aqua :server sip-dir cfg {:connect aqua-server-recv})
            (register-to-dir cfg (<! geo) nil ds))))))
