(ns aqua-node.circ
  (:require [cljs.core :as cljs]
            [cljs.nodejs :as node]
            [aqua-node.log :as log]
            [aqua-node.buf :as b]
            [aqua-node.ntor :as hs]
            [aqua-node.conns :as c]
            [aqua-node.parse :as conv]
            [aqua-node.crypto :as crypto]
            [aqua-node.conn-mgr :as conn]))

(declare from-relay-cmd from-cmd to-cmd)

;; Tor doc. from tor spec file:
;;  - see section 5 for circ creation.
;;  - create2 will be used for ntor hs.
;;  - circ id: msb set to 1 when created on current node. otherwise 0.
;;  - will not be supporting create fast: tor spec: 221-stop-using-create-fast.txt
;;  - we will be using the following link specifiers:
;;      - 03 = ip4 4 | port 2 -> reliable (tcp) routed over udp & dtls
;;      - 04 = ip6 16 | port 2 -> reliable (tcp) routed over udp & dtls
;;      - 05 = ip6 16 | port 2 -> unreliable (udp) routed over dtls
;;      - 06 = ip6 16 | port 2 -> unreliable (udp) routed over dtls


;; circuit state management ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def circuits (atom {}))

(defn add [circ-id conn & [state]]
  ;; FIXME remove conn from there. this shall become :forward-hop.
  (assert (nil? (@circuits circ-id)) (str "could not create circuit, " circ-id " already exists"))
  (swap! circuits merge {circ-id (merge state {:conn conn})}))

(defn update-data [circ keys subdata]
  (swap! circuits assoc-in (cons circ keys) subdata)
  circ)

(defn rm [circ]
  (swap! circuits dissoc circ)
  circ)

(defn destroy [circ]
  (when (@circuits circ) ;; FIXME also send destroy cells to the path
    (log/info "destroying circuit" circ)
    (rm circ)))

(defn get-all []
  @circuits)

(defn get-data [id]
  (@circuits id))

(defn gen-id [] ;; FIXME temporary, it might be interesting to use something that guarantees an answer instead of an infinite loop. yeah.
  (let [i (-> (node/require "crypto") (.randomBytes 4) (.readUInt32BE 0) (bit-clear 31))]
    (if (@circuits i)
      (recur)
      i)))


;; path management ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn get-path-keys [circ-data]
  (filter identity (map #(-> % :secret) (:path circ-data))))

(defn add-path-auth [id circ-data auth]
  (update-data id [:path] (concat (:path circ-data) [{:auth auth}])))

(defn add-path-secret-to-last [id circ-data secret]
  (let [l  (last (:path circ-data))
        ls (drop-last (:path circ-data))]
    (update-data id [:path] (concat ls [(merge l {:secret secret})]))))


;; send cell ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn cell-send [conn circ-id cmd payload & [len]]
  (let [len          (or len (.-length payload))
        buf          (b/new (+ 5 len))
        [w8 w16 w32] (b/mk-writers buf)]
    (w32 circ-id 0)
    (w8 (from-cmd cmd) 4)
    (.copy payload buf 5)
    (.write conn buf)))


;; make requests ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn mk-create [config socket srv-auth circ-id]
    (let [[auth create] (hs/client-init srv-auth)
               header   (b/new 4)]
           (.writeUInt16BE header 2 0)
           (.writeUInt16BE header (.-length create) 2)
           [auth (b/cat header create)]))

(defn create [config socket srv-auth]
  (let [circ-id        (gen-id)
        [auth create]  (mk-create config socket srv-auth circ-id)]
    (add circ-id socket {:type :app-proxy}) ;; nope.
    (update-data circ-id [:forward-hop] socket)
    (add-path-auth circ-id nil auth)
    (cell-send socket circ-id :create2 create)))

(defn- enc-send [config socket circ-id circ-cmd msg]
  (assert (@circuits circ-id) "cicuit does not exist") ;; FIXME this assert will probably be done elsewhere (process?)
  ;; FIXME assert state.
  (let [circ     (@circuits circ-id)
        c        (node/require "crypto")
        iv       #(.randomBytes c. 16)
        keys     (reverse (get-path-keys circ))
        msg      (reduce #(let [iv (iv)] (b/cat iv (crypto/enc-aes %2 iv %1))) msg keys)] ;; FIXME: new iv for each? seems overkill...
    (cell-send socket circ-id circ-cmd msg)))

(defn- relay [config socket circ-id relay-cmd msg]
  (let [pl-len       (.-length msg)
        data         (b/new (+ pl-len 11))
        [w8 w16 w32] (b/mk-writers data)]
    (w8 (from-relay-cmd relay-cmd) 0)
    (w16 0 1) ;; Recognized
    (w16 101 3) ;; StreamID
    (w32 101 5) ;; Digest
    (w16 101 9) ;; Length
    (.copy msg data 11)
    (enc-send config socket circ-id :relay data)))

;; see tor spec 6.2. 160 = ip6 ok & prefered.
(defn relay-begin [config circ-id {host :host port :port type :type}]
  (let [socket (:conn (@circuits circ-id))
        host   (if (= type :ip6) (str "[" host "]") host)
        dest   (str host ":" port)
        len    (count dest)
        dest   (b/cat (b/new dest) (b/new (cljs/clj->js [0 160 0 0 0])))]
    (update-data circ-id [:circuit :state :relay] true) ;; FIXME this should be done on r-begin ack. temp.
    (relay config socket circ-id :begin dest)))

(defn relay-data [config circ-id data]
  (relay config (:conn (@circuits circ-id)) circ-id :data data))

;; see tor spec 5.1.2.
(defn relay-extend [config circ-id next-hop]
  (let [data          (@circuits circ-id)
        socket        (:conn data)
        [auth create] (mk-create config (:conn data) (:auth next-hop) circ-id) ;; FIXME use the same id or create a new one?
        nspec         (condp = (:type next-hop)
                        :ip4 (b/cat (b/new (cljs/clj->js [1 3 6]))  (conv/ip4-to-bin (:host next-hop)) (conv/port-to-bin (:port next-hop)))
                        :ip6 (b/cat (b/new (cljs/clj->js [1 4 16])) (conv/ip6-to-bin (:host next-hop)) (conv/port-to-bin (:port next-hop)))
                        (assert nil "unsupported next hop address type"))]
    (relay config socket circ-id :relay (b/cat nspec create))))


;; process recv ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn recv-create2 [config conn circ-id {payload :payload len :len}] ;; FIXME this will be a sub function of the actual recv create2
  (add circ-id conn {:type :server})
  (let [{pub-B :pub node-id :id sec-b :sec} (-> config :auth :aqua-id) ;; FIXME: renaming the keys is stupid.
        hs-type                             (.readUInt16BE payload 0)
        len                                 (.readUInt16BE payload 2)
        [shared-sec created]                (hs/server-reply {:pub-B pub-B :node-id node-id :sec-b sec-b} (.slice payload 4) 32)
        header                              (b/new 2)]
    (assert (= hs-type 2) "unsupported handshake type")
    (.writeUInt16BE header (.-length created) 0)
    (add-path-secret-to-last circ-id (@circuits circ-id) shared-sec)
    (update-data circ-id [:backward-hop] conn)
    (cell-send conn circ-id :created2 (b/cat header created))))

(defn recv-created2 [config conn circ-id {payload :payload len :len}]
  (let [circ       (@circuits circ-id)]
    (assert circ "cicuit does not exist") ;; FIXME this assert will probably be done elsewhere (process?)
    (if (= :mix (:type circ))
      (relay config (:backward-hop circ) circ-id :extended2 payload)
      (let [auth       (-> circ :path last :auth)
            len        (.readUInt16BE payload 0)
            shared-sec (hs/client-finalise auth (.slice payload 2) 32)] ;; FIXME aes 256 seems to want 32 len key. seems short to me.
        (add-path-secret-to-last circ-id circ shared-sec)))))

(defn process-relay [config conn circ-id relay-data original-pl]
  (let [circ-data  (@circuits circ-id)
        r-payload  (:payload relay-data)
        p-data     (fn []
                     (let [[fhop bhop :as hops] (map circ-data [:forward-hop :backward-hop])
                           dest                 (if (= conn fhop) bhop fhop)]
                       (assert (some (partial = conn) hops) "relay data came from neither forward or backward hop.")
                       (assert dest "no destination, illegal state")
                       (.write dest (:payload relay-data))))
        p-begin    (fn []
                     (assert (not= :app-proxy (:type circ-data)) "relay begin command makes no sense")
                     (update-data circ-id [:type] :exit)
                     (let [dest (conv/parse-addr r-payload)
                           sock (conn/new :tcp :client dest config (fn [config socket buf]
                                                                     (relay config conn circ-id :data buf)
                                                                     (c/add-listeners socket {:error #(do (c/rm socket)
                                                                                                          (destroy circ-id))})))]
                       (update-data circ-id [:forward-hop] sock)))
        p-extend   (fn []
                     (let [[r1 r2 r4] (b/mk-readers relay-data)
                           nb-lspec   (r1 0) ;; FIXME we're assuming 1 for now.
                           ls-type    (r1 1)
                           ls-len     (r1 2)
                           dest       (condp = ls-type
                                        3 {:type :ip4 :host (conv/ip4-to-str (.slice relay-data 3 7))  :port (r2 7)  :create (.slice relay-data 9)}
                                        4 {:type :ip6 :host (conv/ip6-to-str (.slice relay-data 3 19)) :port (r2 19) :create (.slice relay-data 21)})
                           sock       (c/find-by-dest dest)]
                       (assert sock "could not find destination")
                       (update-data circ-id [:forward-hop] sock)
                       (update-data circ-id [:type] :mix)
                       (cell-send sock circ-id :create2 (:create dest))))
        p-extended #(recv-created2 config conn circ-id {:payload relay-data})]
    (condp = (:relay-cmd relay-data)
      1  (p-begin)
      2  (p-data)
      3  (log/error :relay-end "is an unsupported relay command")
      4  (log/error :relay-connected "is an unsupported relay command")
      5  (log/error :relay-sendme "is an unsupported relay command")
      6  (log/error :relay-extend "is an unsupported relay command")
      7  (log/error :relay-extended "is an unsupported relay command")
      8  (log/error :relay-truncate "is an unsupported relay command")
      9  (log/error :relay-truncated "is an unsupported relay command")
      10 (log/error :relay-drop "is an unsupported relay command")
      11 (log/error :relay-resolve "is an unsupported relay command")
      12 (log/error :relay-resolved "is an unsupported relay command")
      13 (log/error :relay-begin_dir "is an unsupported relay command")
      14 (p-extend)
      15 (p-extended)
      (log/error "unsupported relay command"))))

;; see tor spec 6.
(defn recv-relay [config conn circ-id {payload :payload len :len}]
  (assert (@circuits circ-id) "cicuit does not exist")
  (let [circ        (@circuits circ-id)
        recognised? #(zero? (.readUInt16BE % 1)) ;; FIXME -> add digest
        [k & ks]    (get-path-keys circ)
        msg         (loop [k k, ks ks, m payload]
                      (let [[iv m] (b/cut m 16)
                            m      (crypto/dec-aes k iv m)
                            [k & ks] ks]
                        (cond (recognised? m) m ;; should probably return the auth to know where in the path this comes from.
                              k               (recur k ks m)
                              :else           (assert nil "undecipherable relay, something has gone wrong in the path"))))
        [r1 r2 r4]  (b/mk-readers msg)
        relay-data  {:relay-cmd  (r1 0)
                     :recognised (r2 1)
                     :stream-id  (r2 3)
                     :digest     (r4 5)
                     :relay-len  (r2 9)
                     :payload    (.slice msg 11 (.-length msg))}] ;; FIXME check how aes padding is handled.
    (process-relay config conn circ-id relay-data {:unused? true})))


;; cell management (no state logic here) ;;;;;;;;;;;;;;;;;;;;;;;;;

(def to-cmd
  {0   {:name :padding         :fun nil}
   1   {:name :create          :fun nil}
   2   {:name :created         :fun nil}
   3   {:name :relay           :fun recv-relay}
   4   {:name :destroy         :fun nil}
   5   {:name :create_fast     :fun nil}
   6   {:name :created_fast    :fun nil}
   8   {:name :netinfo         :fun nil}
   9   {:name :relay_early     :fun nil}
   10  {:name :create2         :fun recv-create2}
   11  {:name :created2        :fun recv-created2}
   7   {:name :versions        :fun nil}
   128 {:name :vpadding        :fun nil}
   129 {:name :certs           :fun nil}
   130 {:name :auth_challenge  :fun nil}
   131 {:name :authenticate    :fun nil}
   132 {:name :authorize       :fun nil}})

(def from-cmd
  (apply merge (for [k (keys to-cmd)]
                 {((to-cmd k) :name) k})))

(def from-relay-cmd
  {:begin      1
   :data       2
   :end        3
   :connected  4
   :sendme     5
   :extend     6
   :extended   7
   :truncate   8
   :truncated  9
   :drop       10
   :resolve    11
   :resolved   12
   :begin_dir  13
   :extend2    14
   :extended2  15})

(defn process [config conn buff]
  ;; FIXME check len first -> match with fix buf size
  (let [[r8 r16 r32] (b/mk-readers buff)
        len          (.-length buff)
        circ-id      (r32 0)
        command      (to-cmd (r8 4))
        payload      (.slice buff 5 len)]
    (log/debug "recv cell: id:" circ-id "cmd:" (:name command))
    (when (:fun command)
      (try
        ((:fun command) config conn  circ-id {:payload payload :len (- len 5)})
        (catch js/Object e (log/c-info e (str "Killed circuit " circ-id)) (destroy circ-id))))))
