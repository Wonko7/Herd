(ns aqua-node.core
  (:require [cljs.core :as cljs]
            [cljs.nodejs :as node]
            [clojure.walk :as walk]
            [cljs.core.async :refer [chan <! >!] :as a]
            [utils.helpers :as h]
            [aqua-node.log :as log]
            [aqua-node.roles :as roles]
            [aqua-node.config :as config])
  (:require-macros [cljs.core.async.macros :as m :refer [go-loop go]]
                   [utils.macros :refer [<? <?? go? dprint]]))

;; Aqua source code:
;;
;; Currently obsolete: socks, rtpp. Soon: rate, dtls.
;;
;; - roles.cljs sets up the different services the node is expected to have:
;;   - an app-proxy sets up a socks proxy & an rtp-proxy and inits a pool of
;;     circuits.
;;   - a mix only sets up an aqua stack
;;   - a dir only sets up a dir service.
;;   This is the entry point to the aqua process, a good starting point to
;;   reading the code.
;; - path.cljs is where the high level logic of building a circuit is.
;;   init-pool creates a pool of circuits, only initialized with the first hop
;;   (randomly chosen in the same geo loc). This is where you can add a filter
;;   to chose a super peer instead.
;;
;; - circ.cljs is where the low level circuit stuff lives. you shouldn't need
;;   it, but might want context. This is the basic protocol of asking for a new
;;   hop, waiting for an answer, adding the crypto handshake result to the list
;;   of the things we encrypt with for each hop...
;; - conns.cljs: used to keep track of open connections/sockets (be it aqua,
;;   socks, local udp).
;; - conn_mgr.cljs: high level network inits (dtls/tls/tcp/udp/dir).
;; - core.cljs: just to init the main function, immediately calls
;;   roles/bootstrap.
;; - dir.cljs: directory logic. Has server service & client requests.
;; - dtls_comm: communication with dtls-handler.
;; - rtpp.cljs: rtp proxy, used to manipulate sip sdp's, create circuits (using
;;   get-path) when a call is being initialised.
;; - sip.cljs: sip b2b that'll replace rtpp.
;; - socks.cljs: socks5 proxy server, to offer the same functionality as tor.
;; - rate.cljs: rate limiter, constantly sends packets at the same rate.
;;   Padding is sent if there is no queued data.
;; - tls.cljs: tls connection init.
;; - dtls.cljs: dtls connection init.
;;
;; - crypto.cljs: used to cipher & decipher with aes.
;; - ntor.cljs: the ntor handshake, used to init circuit hops.
;;
;; - config.cljs: reading the config file.
;; - geo.cljs: used to parse a geo database file to know what country we're in.
;;   For dev & testing just hardcode that info in the config file.
;; - parse.cljs: helpers for converting things from strings to bin
;;   representation & vice versa.
;; - log.cljs: logging functions.
;; - buf.cljs: node buffer manipulation helpers.

(defn -main [& args]
  "main function (entry point): parses argv, config, then calls roles/bootstrap."
  (let [argv   (-> ((node/require "minimist") (-> js/process .-argv (.slice 2))) cljs/js->clj walk/keywordize-keys)
        config (config/read-config argv)]
    (log/init config)
    (println (:dtls-handler-port config))
    (roles/bootstrap config)))


(set! *main-cli-fn* #(try (enable-console-print!)
                          (apply -main %&)
                          (catch js/Object e (log/c-error e "No one expects the Spanish Inquisition."))))

;; (set! *main-cli-fn* #(try (enable-console-print!)
;;                           (let [c (chan)
;;                                 a (filter (fn [a] (= :kkt a)) c)
;;                                 b (filter (fn [a] (= :mdr a)) c)]
;;                             (go
;;                               (go (println (<! c)))
;;                               (go (println (<! c)))
;;                               (>! c :kkt)
;;                               (>! c :mdr)
;;                               )
;;                             ;(go (println (<?? (>! c :lol)
;;                             ;                  {:loops 4 :timeout 0 :chan c})))
;;                             )
;;                           ;;(go-try-catch (go? (println (<? (lalal :lol))))
;;                           ;;              (fn [] (println "this the end my friend")))
;;                           (catch js/Object e (log/c-error e "No one expects the Spanish Inquisition."))))
