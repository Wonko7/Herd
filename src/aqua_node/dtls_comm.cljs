(ns aqua-node.roles
  (:require [cljs.core :as cljs]
            [cljs.nodejs :as node]
            [cljs.core.async :refer [chan <! >! ] :as a]
            [aqua-node.log :as log]
            [aqua-node.buf :as b]
            [aqua-node.conns :as c]
            [aqua-node.circ :as circ])
  (:require-macros [cljs.core.async.macros :as m :refer [go-loop go]]))



(defn init [{port :dtls-handler-port fixme :files-for-certs :as config}]
  (let [exec          (.-exec (node/require "child_process"))
        dtls-handler  (exec (str "./dtls-handler " port)
                            nil
                            #(do (log/error "dtls-handler exited with" %1)
                                 (init config)))]
    (log/info "Started dtls handler, PID:" (.-pid dtls-handler) "Port:" port)
    ;; create socket for IPC?

    (c/add )

    ))

(defn create-server [{host :host port :port :as dest} config conn-info new-conn-handler err]
  )



(defn connect [dest config conn-info conn-handler err]
)
