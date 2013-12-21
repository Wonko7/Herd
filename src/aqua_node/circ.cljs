(ns aqua-node.circ
  (:require [cljs.core :as cljs]
            [cljs.nodejs :as node]
            [cljs.core.async :refer [chan <! >!]]
            [aqua-node.log :as log]
            [aqua-node.buf :as b]
            [aqua-node.ntor :as hs]
            [aqua-node.conns :as c]
            [aqua-node.parse :as conv]
            [aqua-node.crypto :as crypto]
            [aqua-node.conn-mgr :as conn])
  (:require-macros [cljs.core.async.macros :as m :refer [go]]))

(declare from-relay-cmd from-cmd to-cmd
         create relay-begin relay-extend
         recv-destroy
         process)

;; General API FIXME:
;;  - should get rid of most conn/sockets in prototypes because explicitly using :f-hop & :b-hop ensures we are doing the right thing --> give direction instead.

;; * Notes from tor spec:
;;  - see section 5 for circ creation.
;;  - create2 will be used for ntor hs.
;;  - circ id: msb set to 1 when created on current node. otherwise 0.
;;  - will not be supporting create fast: tor spec: 221-stop-using-create-fast.txt
;;
;; * Extensions to tor spec:
;;
;;  - adding forward cell: ignores circ id, reads host & address from header and forwards.
;;
;;  - we will be using the following link specifiers:
;;   - 03 = ip4 4 | port 2 -> reliable (tcp) routed over udp & dtls
;;   - 04 = ip6 16 | port 2 -> reliable (tcp) routed over udp & dtls
;;   - 05 = ip6 16 | port 2 -> unreliable (udp) routed over dtls
;;   - 06 = ip6 16 | port 2 -> unreliable (udp) routed over dtls


;; role helpers ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn is? [role circ]
  (some #(= % role) (:roles circ)))

(defn is-not? [role circ]
  (every? #(not= % role) (:roles circ)))


;; circuit state management ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def circuits (atom {}))

(defn add [circ-id socket & [state]]
  ;; FIXME remove socket from there. this shall become :forward-hop.
  (assert (nil? (@circuits circ-id)) (str "could not create circuit, " circ-id " already exists"))
  (swap! circuits merge {circ-id (merge state {:conn socket})}))

(defn update-data [circ keys subdata]
  (swap! circuits assoc-in (cons circ keys) subdata)
  circ)

(defn rm [circ]
  (swap! circuits dissoc circ)
  circ)

(defn destroy [config circ]
  (when-let [c (@circuits circ)] ;; FIXME also send destroy cells to the path
    (recv-destroy config nil circ (b/new "because reasons"))
    (log/info "destroying circuit" circ)
    (rm circ)))

(defn destroy-from-socket [config s]
  (let [circ-id   (:circuit (c/get-data s))
        circ      (@circuits circ-id)]
    (when circ
      (destroy config circ-id))))

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

(defn get-path-enc [circ-data direction] ;; FIXME unused
  (filter identity (map #(-> % direction) (:path circ-data)))) ;; FIXME may need to reverse order with mux

(defn add-path-auth [id circ-data auth]
  (update-data id [:path] (concat (:path circ-data) [{:auth auth}])))

(defn add-path-secret-to-last [config id circ-data secret]
  (let [l        (last (:path circ-data))
        ls       (drop-last (:path circ-data))
        enc      [(partial crypto/create-tmp-enc secret) (partial crypto/create-tmp-dec secret)]
        [f b]    (if (is? :origin circ-data)
                   enc
                   (reverse enc))]
    (update-data id [:path] (concat ls [(merge l {:f-enc f
                                                  :b-enc b})]))))


;; make requests: path level ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn mk-single-path [config [n & nodes]]
  "Creates a single path. Assumes a connection to the first node exists."
  (let [socket (c/find-by-dest (:dest n))
        id     (create config socket (:auth n))]
    (update-data id [:roles] [:origin])
    (update-data id [:remaining-nodes] nodes)
    (update-data id [:mk-path-fn] (fn [config id]
                                    (let [circ        (@circuits id)
                                          [n & nodes] (:remaining-nodes circ)]
                                      (cond n                           (do (relay-extend config id n)
                                                                            (println "extended remaining=" (count nodes)) ;; debug
                                                                            (update-data id [:remaining-nodes] nodes))
                                            (not= (:state circ) :relay) (when (:ap-dest circ)
                                                                          (relay-begin config id (:ap-dest circ))
                                                                          (update-data id [:state] :relay)
                                                                          (go (>! (-> circ :backward-hop c/get-data :ctrl) :relay)))
                                            :else                       (log/error "mk-single-path called with nothing to do. Do not do this again.")))))))


;; send cell ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def block-count (atom 0))

(defn inc-block []
  (swap! block-count inc))

(defn done? []
  (zero? @block-count))

(defn dec-block []
  (swap! block-count dec))

(defn cell-send [config socket circ-id cmd payload & [len]]
  (let [len          (or len (.-length payload))
        buf          (b/new (+ 9 len)) ;; add len to cells -> fixme
        [w8 w16 w32] (b/mk-writers buf)]
    (w32 (+ 9 len) 0)
    (w32 circ-id 4)
    (w8 (from-cmd cmd) 8)
    (.copy payload buf 9)
    (if (-> config :mk-packet)
      buf
      ;(.write socket buf))))
      (js/setImmediate (do 
                         (when (and (:data config) (zero? (dec-block)))
                           (.emit (:data config) "readable"))
                         #(when socket
                            (.write socket buf))))))) ;-> good perf, more drops --> socket can be killed before we send
;(.nextTick js/process #(.write socket buf)))))


;; make requests: circuit level ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn mk-create [config srv-auth circ-id]
    (let [[auth create] (hs/client-init srv-auth)
               header   (b/new 4)]
           (.writeUInt16BE header 2 0)
           (.writeUInt16BE header (.-length create) 2)
           [auth (b/cat header create)]))

(defn create [config socket srv-auth]
  (let [circ-id        (gen-id) ;; FIXME may remove this. seems to make more sense to be path logic.
        [auth create]  (mk-create config srv-auth circ-id)]
    (add circ-id socket nil)
    (update-data circ-id [:forward-hop] socket)
    (add-path-auth circ-id nil auth) ;; FIXME: PATH: mk pluggable
    (cell-send config socket circ-id :create2 create)
    circ-id))

(defn create-mux [config socket circ-id srv-auth]
  (let [[auth create]  (mk-create config srv-auth circ-id)
        dest           (conv/dest-to-tor-str (:dest srv-auth))
        create         (b/cat (b/new dest) (b/new (cljs/clj->js [0])) create)]
    (update-data circ-id [:mux :auth] auth)
    (update-data circ-id [:mux :fhop] (conv/dest-to-tor-str dest))
    (update-data circ-id [:roles] (cons :mux (:roles (circ-id @circuits))))
    (cell-send config socket circ-id :create-mux create)))

(defn- enc-send [config socket circ-id circ-cmd direction msg & [iv]]
  "Add all onion skins before sending the packet."
  (assert (@circuits circ-id) "cicuit does not exist") ;; FIXME this assert will probably be done elsewhere (process?)
  ;; FIXME assert state.
  (let [circ     (@circuits circ-id)
        c        (node/require "crypto")
        encs     (get-path-enc circ direction) ;; FIXME: PATH: mk pluggable
        iv       (or iv (.randomBytes c. (-> config :enc :iv-len)))
        msg      (b/copycat2 iv (reduce #(%2 iv %1) msg encs))]
    (cell-send config socket circ-id circ-cmd msg)))

(defn- enc-noiv-send [config socket circ-id circ-cmd direction msg]
  "Add all onion skins before sending the packet."
  (assert (@circuits circ-id) "cicuit does not exist") ;; FIXME this assert will probably be done elsewhere (process?)
  ;; FIXME assert state.
  (let [circ     (@circuits circ-id)
        encs     (get-path-enc circ direction) ;; FIXME: PATH: mk pluggable
        msg      (reduce #(.update %2 %1) msg encs)] ;; FIXME: new iv for each? seems overkill...
    (cell-send config socket circ-id circ-cmd msg)))

(defn- relay [config socket circ-id relay-cmd direction msg]
  (let [data         (b/new (+ (.-length msg) 11))
        [w8 w16 w32] (b/mk-writers data)]
    (w8 (from-relay-cmd relay-cmd) 0)
    (w16 0 1) ;; Recognized
    (w16 101 3) ;; StreamID
    (w32 101 5) ;; Digest
    (w16 101 9) ;; Length
    (.copy msg data 11)
    (enc-send config socket circ-id :relay direction data)))

;; see tor spec 6.2. 160 = ip6 ok & prefered.
(defn relay-begin [config circ-id dest]
  (let [socket (:forward-hop (@circuits circ-id))
        dest   (conv/dest-to-tor-str dest)
        dest   (b/cat (b/new dest) (b/new (cljs/clj->js [0 160 0 0 0])))]
    (relay config socket circ-id :begin :f-enc dest)))

(defn relay-data [config circ-id data]
  (relay config (:forward-hop (@circuits circ-id)) circ-id :data :f-enc data))

;; see tor spec 5.1.2.
(defn relay-extend [config circ-id {nh-auth :auth nh-dest :dest}]
  (let [data          (@circuits circ-id)
        socket        (:forward-hop data)
        [auth create] (mk-create config nh-auth circ-id) ;; FIXME use the same id or create a new one?
        nspec         (condp = (:type nh-dest)
                        :ip4 (b/cat (b/new (cljs/clj->js [1 3 6]))  (conv/ip4-to-bin (:host nh-dest)) (conv/port-to-bin (:port nh-dest)))
                        :ip6 (b/cat (b/new (cljs/clj->js [1 4 16])) (conv/ip6-to-bin (:host nh-dest)) (conv/port-to-bin (:port nh-dest)))
                        (assert nil "unsupported next hop address type"))]
    (add-path-auth circ-id data auth) ;; FIXME: PATH: mk pluggable
    (relay config socket circ-id :extend2 :f-enc (b/cat nspec create))))

(defn forward [config circ-id dest-str cell]
  (let [socket  (c/find-by-dest {})
        iv      (.randomBytes c. 16)
        key     (-> (@circuits circ-id) :mux :auth :secret)
        cell    (b/cat iv (crypto/enc-aes key iv cell))
        payload (b/cat (b/new dest-str) (b/new (cljs/clj->js [0])) cell)]
    (cell-send config socket 0 :forward payload)))

(defn send-destroy [config dest circ-id reason]
  (cell-send config dest circ-id :destroy reason))


;; process recv ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn recv-create2 [config socket circ-id payload] ;; FIXME this will be a sub function of the actual recv create2
  (add circ-id socket {:roles [:mix]})
  (let [{pub-B :pub node-id :id sec-b :sec} (-> config :auth :aqua-id) ;; FIXME: renaming the keys is stupid.
        hs-type                             (.readUInt16BE payload 0)
        len                                 (.readUInt16BE payload 2)
        [shared-sec created]                (hs/server-reply {:pub-B pub-B :node-id node-id :sec-b sec-b} (.slice payload 4) (-> config :enc :key-len))
        header                              (b/new 2)]
    (assert (= hs-type 2) "unsupported handshake type")
    (.writeUInt16BE header (.-length created) 0)
    (add-path-secret-to-last config circ-id (@circuits circ-id) shared-sec) ;; FIXME: PATH: mk pluggable
    (update-data circ-id [:backward-hop] socket)
    (cell-send config socket circ-id :created2 (b/cat header created))))

(defn recv-created2 [config socket circ-id payload]
  "Process created2, add the resulting shared secret to the path, call
  the path's :mk-path-fn to proceed to the next step."
  (let [circ       (@circuits circ-id)]
    (assert circ "cicuit does not exist") ;; FIXME this assert will probably be done elsewhere (process?)
    (if (is? :mix circ)
      (relay config (:backward-hop circ) circ-id :extended2 :b-enc payload)
      (let [mux?       (is? :mux circ)
            auth       (if mux? (-> circ :mux :auth) (-> circ :path last :auth))
            len        (.readUInt16BE payload 0)
            shared-sec (hs/client-finalise auth (.slice payload 2) (-> config :enc :key-len))] ;; FIXME aes 256 seems to want 32 len key. seems short to me.
        (if mux?
          (update-data circ-id [:mux :auth :secret] shared-sec) ;; broken but unused on noiv.
          (add-path-secret-to-last config circ-id circ shared-sec))
        (when (:mk-path-fn circ)
          ((:mk-path-fn circ) config circ-id))))))

(defn recv-create-mux [config socket circ-id payload] ;; FIXME this will be a sub function of the actual recv create2
  (add circ-id socket {:roles [:mix]})
  (let [{pub-B :pub node-id :id sec-b :sec} (-> config :auth :aqua-id) ;; FIXME: renaming the keys is stupid.
        [dest payload]                      (conv/parse-addr payload)
        hs-type                             (.readUInt16BE payload 0)
        len                                 (.readUInt16BE payload 2)
        [shared-sec created]                (hs/server-reply {:pub-B pub-B :node-id node-id :sec-b sec-b} (.slice payload 4) 32)
        header                              (b/new 2)]
    (assert (= hs-type 2) "unsupported handshake type")
    (.writeUInt16BE header (.-length created) 0)
    (update-data circ-id [:mux :auth] {:secret shared-sec}) ;; FIXME: PATH: mk pluggable
    (update-data circ-id [:mux :bhop] (conv/dest-to-tor-str dest))
    (cell-send config socket circ-id :created2 (b/cat header created))))

(defn recv-forward [config socket circ-id payload]
  (let [circ       (@circuits circ-id)
        [dest pl]  (conv/parse-addr payload)]
    (if (and (= (:port dest) (.-localPort socket)) (= (:host dest) (.-localAddress socket)))
      (let [k      (-> circ :mux :auth :secret)
            [iv m] (b/cut payload 16)
            cell   (crypto/dec-aes k iv m)]
        (process config socket cell))
      (let [socket (c/find-by-dest dest)]
        (assert socket "could not find next hop for forwarding")
        (.write socket payload)))))

(defn recv-destroy [config socket circ-id payload]
  (let [circ                 (@circuits circ-id)
        [fhop bhop :as hops] (map circ [:forward-hop :backward-hop])
        dest                 (if (= socket fhop) bhop fhop)
        d                    #(send-destroy config % circ-id payload)]
    (when (or (nil? socket) (and (some (partial = socket) hops)))
      (cond (is? :origin circ) (do (if socket
                                     (c/destroy bhop)
                                     (d fhop)))
            (is? :exit circ)   (do (if socket
                                     (c/destroy fhop)
                                     (d bhop)))
            :else              (do (if socket
                                     (d dest)
                                     (map d hops))))
      (rm circ))))

(defn process-relay [config socket circ-id relay-data]
  (let [circ       (@circuits circ-id)
        r-payload  (:payload relay-data)
        p-data     (fn []
                     (let [[fhop bhop :as hops] (map circ [:forward-hop :backward-hop])
                           dest                 (if (= socket fhop) bhop fhop)
                           dest-data            (c/get-data dest)]
                       (assert (some (partial = socket) hops) "relay data came from neither forward or backward hop.")
                       (assert dest "no destination, illegal state")
                       (cond (= :udp-exit (:type dest-data)) (let [[r1 r2]    (b/mk-readers r-payload)
                                                                   type       (r1 3)
                                                                   [h p data] (cond (= 1) [(conv/ip4-to-str (.slice r-payload 4 8)) (r2 8) (.slice r-payload 10)]
                                                                                    (= 4) [(conv/ip6-to-str (.slice r-payload 4 20)) (r2 20) (.slice r-payload 22)]
                                                                                    (= 3) (let [len  (.-length r-payload)
                                                                                                ml?  (>= len 5)
                                                                                                alen (when ml? (r1 4))
                                                                                                aend (when ml? (+ alen 5))]
                                                                                            [(.toString r-payload "utf8" 5 aend) (r2 aend) (.slice r-payload (inc aend))]))]
                                                               (.send dest data 0 (.-length data) p h))
                             (= :udp-ap (:type dest-data))   (.send dest r-payload 0 (.-length r-payload) (-> dest-data :from :port) (-> dest-data :from :host))
                             :else                           (.write dest r-payload))))
        p-begin    (fn []
                     (assert (is-not? :origin circ) "relay begin command makes no sense") ;; FIXME this assert is good, but more like these are needed. roles are not inforced.
                     (update-data circ-id [:roles] (cons :exit (:roles circ)))
                     (let [dest (first (conv/parse-addr r-payload))
                           sock (if (= :tcp (:proto dest))
                                  (conn/new :tcp :client dest config {:data  (fn [config soc b]
                                                                               (doall (map (fn [b] (.nextTick js/process #(relay config socket circ-id :data :b-enc b)))
                                                                                           (apply (partial b/cut b) (range 1350 (.-length b) 1350)))))
                                                                      :error #(do (log/error "closed:" dest) (destroy config circ-id))})
                                  (conn/new :udp :client nil config {:data  (fn [config soc msg rinfo]
                                                                              (let [data       (b/new (+ 10 (.-length msg)))
                                                                                    [w1 w2 w4] (b/mk-writers data)]
                                                                                (w4 0 0)
                                                                                (w1 1 3)
                                                                                (.copy (-> rinfo .-address conv/ip4-to-bin) data 4)
                                                                                (w2 (-> rinfo .-port) 8)
                                                                                (.copy msg data 10))
                                                                              (relay config socket circ-id :data :b-enc msg))
                                                                     :error #(do (log/error "closed:" dest) (destroy config circ-id))}))]
                       (c/update-data sock [:circuit] circ-id)
                       (update-data circ-id [:forward-hop] sock)))
        p-extend   (fn []
                     (let [[r1 r2 r4] (b/mk-readers r-payload)
                           nb-lspec   (r1 0) ;; FIXME we're assuming 1 for now.
                           ls-type    (r1 1)
                           ls-len     (r1 2)
                           dest       (condp = ls-type
                                        3 {:type :ip4 :host (conv/ip4-to-str (.slice r-payload 3 7))  :port (r2 7)  :create (.slice r-payload 9)}
                                        4 {:type :ip6 :host (conv/ip6-to-str (.slice r-payload 3 19)) :port (r2 19) :create (.slice r-payload 21)})
                           sock       (c/find-by-dest dest)]
                       (assert sock "could not find destination")
                       (update-data circ-id [:forward-hop] sock)
                       (update-data circ-id [:roles] [:mix]) ;; FIXME just add?
                       (cell-send config sock circ-id :create2 (:create dest))))
        p-extended #(recv-created2 config socket circ-id r-payload)]
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
(defn recv-relay [config socket circ-id payload]
  "If relay message is going backward add an onion skin and send.
  Otherwise, take off the onion skins we can, process it if we can or forward."

  (assert (@circuits circ-id) "cicuit does not exist")
  (let [circ        (@circuits circ-id)
        mux?        (is? :mux circ)
        direction   (if (= (:forward-hop circ) socket) :b-enc :f-enc)
        [iv msg]    (b/cut payload (-> config :enc :iv-len))]

    (if (and (is-not? :origin circ) (= direction :b-enc)) ;; then message is going back to origin -> add enc & forwad
      (if (and mux? (-> circ :mux :fhop))
        (forward config circ-id (-> circ :mux :fhop) payload)
        (enc-send config (:backward-hop circ) circ-id :relay :b-enc msg iv))

      (let [msg         (reduce #(%2 iv %1) msg (get-path-enc circ direction)) ;; message going towards exit -> rm our enc layer. OR message @ origin, peel of all layers.
            [r1 r2 r4]  (b/mk-readers msg)
            recognised? (and (= 101 (r2 3) (r4 5) (r2 9)) (zero? (r2 1))) ;; FIXME -> add digest
            relay-data  {:relay-cmd  (r1 0)
                         :recognised recognised?
                         :stream-id  (r2 3)
                         :digest     (r4 5)
                         :relay-len  (r2 9)
                         :payload    (when recognised? (.slice msg 11))}] ;; FIXME check how aes padding is handled.

        (cond (:recognised relay-data)        (process-relay config socket circ-id relay-data)
              (and mux? (-> circ :mux :bhop)) (forward config circ-id (-> circ :mux :bhop) msg)
              :else                           (cell-send config (:forward-hop circ) circ-id :relay (b/copycat2 iv msg)))))))


;; cell management (no state logic here) ;;;;;;;;;;;;;;;;;;;;;;;;;

(def to-cmd
  {0   {:name :padding         :fun nil}
   1   {:name :create          :fun nil}
   2   {:name :created         :fun nil}
   3   {:name :relay           :fun recv-relay}
   4   {:name :destroy         :fun recv-destroy}
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
   132 {:name :authorize       :fun nil}
   256 {:name :forward         :fun recv-forward}
   257 {:name :create-mux      :fun recv-create-mux}})

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

(def wait-buffer (atom nil)) ;; FIXME we need one per socket
(defn process [config socket data-orig]
  ;; FIXME check len first -> match with fix buf size
  (let [data         (if @wait-buffer (b/copycat2 @wait-buffer data-orig) data-orig)
        [r8 r16 r32] (b/mk-readers data)
        len          (.-length data)
        cell-len     (r32 0)
        circ-id      (r32 4)
        command      (to-cmd (r8 8))
        payload      (.slice data 9)]
    (log/debug "recv cell: id:" circ-id "cmd:" (:name command) "len:" len)
    (cond (> len cell-len) (let [[f r] (b/cut data cell-len)]
                             (reset! wait-buffer nil)
                             (process config socket f)
                             (process config socket r))
          (< len cell-len) (cond (@circuits circ-id)                     (reset! wait-buffer data)
                                 (@circuits (.readUInt32BE data-orig 4)) (reset! wait-buffer data-orig)
                                 :else                                   (reset! wait-buffer nil))
          :else            (do (reset! wait-buffer nil)
                               (when (:fun command)
                                 (try
                                   ((:fun command) config socket circ-id payload)
                                   (catch js/Object e (log/c-info e (str "Killed circuit " circ-id)) (destroy config circ-id))))))))
