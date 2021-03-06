(ns aqua-node.roles
  (:require [cljs.core :as cljs]
            [cljs.nodejs :as node]
            [cljs.core.async :refer [chan <! >!] :as a]
            [aqua-node.log :as log]
            [aqua-node.misc :as m]
            [aqua-node.buf :as b]
            [aqua-node.dtls-comm :as dtls]
            [aqua-node.conns :as c]
            [aqua-node.conn-mgr :as conn]
            [aqua-node.dir :as dir]
            [aqua-node.circ :as circ]
            [aqua-node.path :as path]
            [aqua-node.geo :as geo]
            [aqua-node.sp :as sp]
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
  (c/add-listeners s {:data #(circ/process config s %)}))

(defn aqua-dir-recv [config s]
  "Setup socket as a dir service, sending all received data through dir/process"
  (log/debug "new dir tls conn from:" (-> s .-remoteAddress) (-> s .-remotePort))
  (c/add-listeners s {:data #(dir/process config s %) :error #(log/info "Dir: socket error")}))

(def register-to-dir-timer (atom nil))
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
                                     (.end c)
                                     (c/destroy c))))]
    (send-register-to-dir)
    (when @register-to-dir-timer
      (js/clearInterval @register-to-dir-timer))
    (reset! register-to-dir-timer (js/setInterval #(send-register-to-dir) (:register-interval config)))))

(defn get-net-info [config dir]
  "Create a socket to dir, send net request, wait until we get an answer, close, return net info."
  (log/info "requesting net info:")
  (let [done (chan)
        c    (conn/new :dir :client dir config {:connect #(go (>! done :connected))})] ;; also, we'd need a one hop aqua circ here.
    (c/add-listeners c {:data #(dir/process config c % done)})
    (go (<! done) ;; wait for socket connect
        (dir/send-net-request config c done)
        (<! done) ;; wait for sent message
        (<! done) ;; wait for received message
        (.end c)
        (c/destroy c)
        (dir/get-net-info))))

(defn aqua-connect [config dest & [ctrl]]
  (let [con     (chan)
        soc     (conn/new :aqua :client dest config {:connect #(go (>! con :done))})
        timer   (js/setTimeout #(go (>! con :timeout)) (:keep-alive-interval config))]
    (log/debug "Aqua: Connecting to" (select-keys dest [:host :port :role]))
    (go (let [soc (<! (<! soc))] ;; FIXME bug in <?? ?
          (if (or (= :fail soc) (= :timeout (<! con)))
            (do (c/destroy soc)
                :fail)
            (do (js/clearTimeout timer)
                (c/update-data soc [:auth] (:auth dest))
                (dtls/send-role soc (:role dest))
                (circ/send-id config soc)
                (when ctrl (>! ctrl soc))))))))

(defn aqua-connect-from-id [config net-info id ctrl]
  (let  [mixes (for [[[ip port] mix] (seq (<! net-info))
                     :when (= id (-> mix :auth :srv-id))]
                 mix)]
    (aqua-connect config (first mixes) ctrl)))

(defn connect-to-all [{aq :aqua :as config}]
  "For each mix in node info, if not already connected, extract ip & port and connect."
  (go (let [ctrl   (chan)]
        (doall (map #(let [s (second %1)
                           c? (nil? (c/find-by-id (-> s :auth :srv-id)))
                           s (dissoc s :auth)]
                       [(first %1) s c?])
                    (seq (dir/get-net-info))))
        (doseq [[[ip port] mix] (seq (dir/get-net-info))
                :let [id (-> mix :auth :srv-id)]
                ]
          (log/debug ip port ":" (:role mix) ":" (b/hx id) ":" (nil? (c/find-by-id id))))
        (doseq [[[ip port] mix] (seq (dir/get-net-info))
                :let [id (-> mix :auth :srv-id)]
                :when (and (not (b/b= id (-> config :auth :aqua-id :id)))
                           (not= :super-peer (:role mix))
                           (nil? (c/find-by-id id)))]
          (aqua-connect config mix ctrl)
          (<! ctrl)))))

(defn bootstrap [{roles :roles ap :app-proxy aq :aqua ds :remote-dir dir :dir sip-dir :sip-dir :as config}]
  "Setup the services needed by the given role."

  (let [config      (merge config {:sip-chan (chan) :sp-chans [(chan) (chan)]})]          ;; init sip-chan, sp-chans
    (dtls/init config circ/process aqua-server-recv)            ;; init dtls-handler

    (go (let [is?         #(m/is? % roles)                      ;; tests roles for our running instance
              geo         (go (<! (geo/parse config)))          ;; FIXME: remove this? match our ip against database, unless already specified in config:
              net-info    (go (when-not (is? :dir)              ;; request net-info if we're not a dir. FIXME -> get-net-info will be called periodically.
                                (<! (get-net-info config ds))))]

          (log/info "Aqua node ID:" (-> config :auth :aqua-id :id b/hx))
          (log/info "Bootstrapping as" roles)

          (comment (when (:debug config)
                     (js/setInterval #(log/info "Memory Usage:"
                                                :date (-> js/Date .now (/ 1000) int)
                                                (.inspect (node/require "util") (.memoryUsage js/process)))
                                     300000)
                     (js/setInterval (fn []
                                       (let [circs (circ/get-all)
                                             conns (c/get-all)
                                             net-info (dir/get-net-info)]
                                         (log/info "Status:" (count circs) "circuits")
                                         (log/info "Status:" (count conns) "connections")
                                         (log/info "Status:" (count (filter #(= :aqua (-> % second :type)) conns)) "aqua dtls connections")
                                         (log/info "Status:" (count net-info) "net-info entries")))
                                     20000)))

          (when (is? :app-proxy)
            (let [geo                 (<! geo)
                  net-info            (<! net-info)
                  sip-chan            (atom nil)
                  [sp-ctrl sp-notify] (:sp-chans config)
                  get-id              #(-> % :auth :srv-id b/hx)
                  reconnect           (fn []
                                        ;; connect to SP:
                                        (go (>! sp-ctrl {:cmd :connect}))
                                        (go (let [[sp-socket mix-data] (<! sp-notify)
                                                  sp                   (c/get-data sp-socket)]
                                              (log/info "Connected to MIX:" (get-id sp))
                                              (log/info "      through SP:" (-> sp :sp-auth :srv-id b/hx))
                                              (log/info "Dir: sending register info")
                                              (register-to-dir config geo mix-data ds)
                                              ;; SIP:
                                              (when @sip-chan
                                                (a/close! @sip-chan))
                                              (reset! sip-chan (sip/create-server config))
                                              (a/pipe (:sip-chan config) @sip-chan))))]
              (sp/init config)
              (doseq [n (keys net-info)]
                (log/info (select-keys (net-info n) [:role :zone]))) ;; debugging.
              (reconnect)

              (comment (js/setInterval (fn []
                                (go (<! (get-net-info config ds))
                                    (when (empty? (circ/get-all)) ;; FIXME: maybe count the nb of aqua sockets
                                      (log/error "Lost connectivity, reconnecting")
                                      (doseq [c (c/get-all)]
                                        (c/destroy c))
                                      (>! @sip-chan :destroy)
                                      (reconnect)
                                      )))
                              (:register-interval config)))))

          (when (or (is? :mix) (is? :rdv))
            (let [sip-chan (sip-dir/create-mix-dir config)
                  ctrl     (chan)
                  config              (merge config {:sip-mix-dir sip-dir/mix-dir})] ;; FIXME :sip-mix-dir unused.
              (sp/init config)
              (a/pipe (:sip-chan config) sip-chan)
              (<! net-info)
              (connect-to-all config)
              (js/setInterval #(go (<! (get-net-info config ds))
                                   (connect-to-all config))
                              (:register-interval config))
              (register-to-dir config (<! geo) nil ds)))


          (when (is? :super-peer)
            (go (let [mixes (for [[[ip port] mix] (seq (<! net-info))
                                  :when (and (= (:role mix) :mix)
                                             (= (:zone mix) (-> config :geo-info :zone)))]
                              mix)
                      [mix] mixes
                      ctrl  (chan)]
                  (sp/init config)
                  (when (not= 1 (count mixes))
                    (log/error mix)
                    (log/error "SP init: more than one suitable mix found" mixes))
                  (register-to-dir config (<! geo) nil ds)
                  (aqua-connect config mix ctrl)
                  (log/debug :sp 1)
                  (let [mix-socket (<! ctrl)
                  _ (log/debug :sp 2)
                        [sp-ctrl sp-notify] (:sp-chans config)]
                  (log/debug :sp 3)
                    (>! sp-ctrl {:cmd :connect :socket mix-socket})
                    (log/debug :sp 4))

                  )))

          (comment (when (is? :super-peer)
                     (go (let [mixes (for [[[ip port] mix] (seq (<! net-info))
                                           :when (and (= (:role mix) :mix)
                                                      (= (:zone mix) (-> config :geo-info :zone)))]
                                       mix)
                               [mix] mixes
                               ctrl  (chan)]
                           (when (not= 1 (count mixes))
                             (log/error mix)
                             (log/error "SP init: more than one suitable mix found" mixes))
                           (register-to-dir config (<! geo) nil ds)
                           (<! (aqua-connect config mix ctrl))
                           (<! ctrl)))))

          (when (is? :dir)
            (conn/new :dir :server dir config {:connect aqua-dir-recv}))

          (when (is? :sip-dir)
            (let [sip-chan (sip-dir/create-dir config)]
              (a/pipe (:sip-chan config) sip-chan)
              (register-to-dir config (<! geo) nil ds)))))))
