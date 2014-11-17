(ns aqua-node.roles
  (:require [cljs.core :as cljs]
            [cljs.nodejs :as node]
            [cljs.core.async :refer [chan <! >! ] :as a]
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
    (c/add-listeners soc {:data #(circ/process config soc %)})
    (c/update-data soc [:auth] (:auth dest))
    (go (if (= :timeout (<! con))
          (c/destroy soc)
          (do (js/clearTimeout timer)
              (rate/init config soc)
              (rate/queue soc #(circ/send-id config soc))
              (circ/reset-keep-alive config soc)
              (when ctrl (>! ctrl soc)))))))

(defn aqua-connect-from-id [config net-info id ctrl]
  (let  [mixes (for [[[ip port] mix] (seq (<! net-info))
                     :when (= id (-> mix :auth :srv-id))]
                 mix)]
    (aqua-connect config (first mixes) ctrl)))

(defn connect-to-all [{aq :aqua :as config}]
  "For each mix in node info, if not already connected, extract ip & port and connect."
  (go (let [ctrl   (chan)]
        (doseq [[[ip port] mix] (seq (dir/get-net-info))
                :when (and (not= (-> mix :auth :srv-id b/hx) (-> config :auth :aqua-id :id b/hx))
                           (nil? (c/find-by-id (-> mix :auth :srv-id))))]
          (aqua-connect config mix ctrl)
          (<! ctrl)))))

(defn bootstrap [{roles :roles ap :app-proxy aq :aqua ds :remote-dir dir :dir sip-dir :sip-dir :as config}]
  "Setup the services needed by the given role."
  (go (let [is?         #(m/is? % roles)                      ;; tests roles for our running instance
            geo         (go (<! (geo/parse config)))          ;; match our ip against database, unless already specified in config:
            net-info    (go (when-not (is? :dir)              ;; request net-info if we're not a dir. FIXME -> get-net-info will be called periodically.
                              (<! (get-net-info config ds))))]

        (log/info "Aqua node ID:" (-> config :auth :aqua-id :id b/hx))
        (log/info "Bootstrapping as" roles)

        (when (:debug config)
          (js/setInterval #(log/info "Memory Usage:"
                                     :date (-> js/Date .now (/ 1000) int)
                                     (.inspect (node/require "util") (.memoryUsage js/process)))
                          300000)
          (js/setInterval #(let [conns (c/get-all)]
                             (log/info "Status: timestamp:" (.now js/Date))
                             (log/info "Status: open rate connections:")
                             (doseq [[c data] (filter (fn [[c data]]
                                                        (:rate data))
                                                      conns)
                                     :let [id      (-> data :auth :srv-id)
                                           rate-dw (:rate-count-dw data)
                                           rate-up (:rate-count-up data)]]
                               (c/update-data c [:rate-count-dw] 0)
                               (c/update-data c [:rate-count-up] 0)
                               (log/info "Rate connection to:"
                                         (if id (b/hx id) "unknown")
                                         "down:"
                                         (/ rate-dw 5)
                                         "p/s,\tup:"
                                         (/ rate-up 5)
                                         "p/s")))
                          5000)
          (js/setInterval (fn []
                            (let [circs (circ/get-all)
                                  conns (c/get-all)
                                  net-info (dir/get-net-info)]
                              (log/info "Status:" (count circs) "circuits")
                              (log/info "Status:" (count conns) "connections")
                              (log/info "Status:" (count (filter #(-> % second :rate) conns)) "rate limited connections")
                              (log/info "Status:" (count net-info) "net-info entries")))
                          20000))

        (when (is? :app-proxy)
            ;; tmp/FIXME:
            (let [ctrl (chan)
                  dt-comm (conn/new :udp :client {:host "127.0.0.1" :port 1234} config {:connect #(go (>! ctrl :connected)) :data #(println (str %2))})
                  msg "lol hello1!#@!"]
              (println "connecting...")
              (go (<! ctrl)
                  (println "connected")
                  (js/setInterval #(.send dt-comm (b/new msg) 0 (count msg) 1234 "127.0.0.1") 1000)))

          (let [geo       (<! geo)
                net-info  (<! net-info)
                sip-chan  (atom nil)
                reconnect (fn []
                            (reset! sip-chan (sip/create-server config))
                            (let [cfg      (merge config {:sip-chan @sip-chan})
                                  mix      (path/init-pools cfg net-info geo 2)]
                              (log/info "Dir: sending register info")
                              (register-to-dir config geo mix ds)))]
            (reconnect)
            ;; FIXME: not really using this right now... could be used for external API.
            (conn/new :socks :server ap config {:data     path/app-proxy-forward
                                                :udp-data path/app-proxy-forward-udp
                                                :init     app-proxy-init
                                                :error    circ/destroy-from-socket})

            (js/setInterval (fn []
                              (go (<! (get-net-info config ds))
                                  (when (or (empty? (filter #(-> % second :rate) (c/get-all))) (empty? (circ/get-all)))
                                    (log/error "Lost connectivity, reconnecting")
                                    (doseq [c (c/get-all)]
                                      (c/destroy c))
                                    (>! @sip-chan :destroy)
                                    (reconnect))))
                            (:register-interval config))))

        ;; (when (is? :super-peer)
        ;;   (register-to-dir config (<! geo) mix ds)
        ;;   (conn/new :channel :server sp config {:connect identity}))

        (when (or (is? :mix) (is? :rdv))
          (let [sip-chan (sip-dir/create-mix-dir config)
                config   (merge config {:sip-chan sip-chan :sip-mix-dir sip-dir/mix-dir}) ;; FIXME :sip-mix-dir unused.
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
