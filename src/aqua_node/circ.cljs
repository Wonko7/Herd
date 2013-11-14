(ns aqua-node.circ
  (:require [cljs.core :as cljs]
            [cljs.nodejs :as node]
            [aqua-node.log :as log]
            [aqua-node.buf :as b]
            [aqua-node.ntor :as hs]
            [aqua-node.conns :as c]
            [aqua-node.crypto :as crypto]
            [aqua-node.conn-mgr :as conn]))

(declare from-relay-cmd from-cmd to-cmd)

;; Tor doc. from tor spec file:
;;  - see section 5 for circ creation.
;;  - create2 will be used for ntor hs.
;;  - circ id: msb set to 1 when created on current node. otherwise 0.
;;  - will not be supporting create fast: tor spec: 221-stop-using-create-fast.txt


;; circuit state management ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def circuits (atom {}))

(defn circ-add [circ-id conn & [state]]
  (assert (nil? (@circuits circ-id)) (str "could not create circuit, " circ-id " already exists"))
  (swap! circuits merge {circ-id (merge state {:conn conn})}))

(defn circ-update-data [circ keys subdata]
  (swap! circuits assoc-in (cons circ keys) subdata)
  circ)

(defn circ-rm [circ]
  (swap! circuits dissoc circ)
  circ)

(defn circ-destroy [circ]
  (when (@circuits circ) ;; FIXME also send destroy cells to the path
    (circ-rm circ)))


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

(defn mk-path [config socket srv-auth] ;; FIXME: the api will change. remove socket.  ;; since this looks like a good entry point, try goes here for now
  (let [circ-id 42] ;; FIXME generate
    (try (let [[auth b] (hs/client-init srv-auth)]
           (circ-add circ-id socket {:type :client})
           (circ-update-data circ-id [:auth] auth)
           (cell-send socket circ-id :create2 b))
         (catch js/Object e (log/c-info e (str "failed circuit creation: " circ-id) (circ-destroy circ-id))))))

(defn enc-send [config socket circ-id circ-cmd msg]
  (assert (@circuits circ-id) "cicuit does not exist") ;; FIXME this assert will probably be done elsewhere (process?)
  ;; FIXME assert state.
  (let [circ     (@circuits circ-id)
        c        (node/require "crypto")
        iv       (.randomBytes c. 16)
        msg      (crypto/enc-aes (-> circ :auth :secret) iv msg)]
    (cell-send socket circ-id circ-cmd (b/cat iv msg))))

(defn relay [config socket circ-id relay-cmd msg]
  (let [circ         (@circuits circ-id)
        pl-len       (.-length msg)
        data         (b/new (+ pl-len 11))
        [w8 w16 w32] (b/mk-writers data)]
    (w8 (from-relay-cmd relay-cmd) 0)
    (w16 101 1) ;; Recognized
    (w16 101 3) ;; StreamID
    (w32 101 5) ;; Digest
    (w16 101 9) ;; Length
    (.copy msg data 11)
    (enc-send config socket circ-id :relay data)))

;; see tor spec 6.2. 160 = ip6 ok & prefered.
(defn relay-begin [config socket circ-id {addr :addr port :port type :type}]
  (let [addr (if (= type :ip6) (str "[" addr "]") addr)
        dest (str addr ":" port)
        len  (count dest)
        dest  (b/cat (b/new dest) (b/new (cljs/clj->js [0 160 0 0 0])))]
    (relay config socket circ-id :begin dest)))


;; process recv ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; FIXME: these need state
(defn recv-create2 [config conn circ-id {payload :payload len :len}]
  (circ-add circ-id conn {:type :server})
  (let [{pub-B :pub node-id :id sec-b :sec} (-> config :auth :aqua-id) ;; FIXME: renaming the keys is stupid.
        [shared-sec created]                (hs/server-reply {:pub-B pub-B :node-id node-id :sec-b sec-b} payload 32)] ;; FIXME -> key len & iv len should be in configw
    (circ-update-data circ-id [:auth :secret] shared-sec)
    (cell-send conn circ-id :created2 created)))

(defn recv-created2 [config conn circ-id {payload :payload len :len}]
  (assert (@circuits circ-id) "cicuit does not exist") ;; FIXME this assert will probably be done elsewhere (process?)
  (let [auth       (:auth (@circuits circ-id))
        shared-sec (hs/client-finalise auth payload 32)] ;; FIXME aes 256 seems to want 32 len key. seems short to me.
    (circ-update-data circ-id [:auth :secret] shared-sec)))

(defn parse-addr [buf]
  (let [z            (->> (range (.-length buf))
                          (map #(when (= 0 (.readUInt8 buf %)) %))
                          (some identity))]
    (assert z "bad buffer: no zero delimiter")
    (let [buf        (.toString buf "ascii" 0 z)
          ip4-re     #"^((\d+\.){3}\d+):(\d+)$"
          ip6-re     #"^\[((\d|[a-fA-F]|:)+)\]:(\d+)$"
          dns-re     #"^(.*):(\d+)$"
          re         #(let [res (cljs/js->clj (.match %2 %1))]
                        [(nth res %3) (nth res %4)])
          [t a p]    (->> [(re ip4-re buf 1 3) (re ip6-re buf 1 3) (re dns-re buf 1 2)]
                          (map #(cons %1 %2) [:ip4 :ip6 :dns])
                          (filter second)
                          first)]
      {:addr a :port p :type t})))

(defn process-relay [config conn circ-id relay-data original-pl]
  (let [circ-data (@circuits circ-id)
        r-payload (:payload relay-data)
        p-data    (fn []
                    (let [dest (-> circ-data :next-hop :conn)]
                      (if dest
                        (.write dest (:payload relay-data))
                        (log/ingo "no destination, dropping"))))
        p-begin   (fn []
                    (assert (= :server (:type circ-data)) "relay resolve command makes no sense")
                    (let [dest (parse-addr r-payload)
                          sock (conn/new :tcp :client dest config (fn [config socket buf]
                                                                    (relay config conn circ-id :data buf)))]
                      (circ-update-data circ-id [:forward] (merge dest {:conn sock}));; FIXME
                      (circ-update-data circ-id [:next-hop] (merge dest {:conn sock}))
                      (log/info "forward-to:" dest)))]
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
              14 (log/error :relay-extend2 "is an unsupported relay command")
              15 (log/error :relay-extended2 "is an unsupported relay command")
              (log/error "unsupported relay command"))))

;; see tor spec 6.
(defn recv-relay [config conn circ-id {payload :payload len :len}]
  (assert (@circuits circ-id) "cicuit does not exist")
  (let [circ       (@circuits circ-id)
        [iv msg]   (b/cut payload 16)
        msg        (crypto/dec-aes (-> circ :auth :secret) iv msg)
        [r1 r2 r4] (b/mk-readers msg)
        relay-data {:relay-cmd  (r1 0)
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
    (log/debug "recv cell: id:" circ-id "cmd:" (:name command) ":" (.toString payload "hex"))
    (when (:fun command)
      (try
        ((:fun command) config conn  circ-id {:payload payload :len (- len 5)})
        (catch js/Object e (log/c-info e (str "Killed circuit " circ-id)) (circ-destroy circ-id))))))
