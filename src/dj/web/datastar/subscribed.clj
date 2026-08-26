(ns dj.web.datastar.subscribed
  "A fused, long-lived Datastar response with current-state scheduling.

  One request virtual thread owns each writer for its entire lifetime. Producers
  can only call `mark-dirty!`; they cannot render or touch a socket. A one-place
  wake queue per connection conflates bursts, while a wake offered during a
  render remains for the next pass.

  The queue is only a permit to re-read `pending`; every await path checks that
  authoritative state before blocking. A stale or conflated permit can therefore
  cost at most one extra loop iteration. The registry deliberately has no keys
  yet: for the first whole-page view, a singleton dirty-key set would only
  disguise a boolean."
  (:require [dj.web.datastar.fused :as fused])
  (:import [java.util.concurrent ArrayBlockingQueue TimeUnit]))

(defrecord Registry [connections clock])

(defn- system-nano-time []
  (System/nanoTime))

(defn- queue-wait
  [{:keys [^ArrayBlockingQueue dirty]} timeout-nanos]
  (if (nil? timeout-nanos)
    (.take dirty)
    (.poll dirty (long timeout-nanos) TimeUnit/NANOSECONDS)))

(def ^:private system-clock
  {:now system-nano-time
   :wait queue-wait})

(defn registry
  "Create an empty registry of live update connections.

  The optional clock bundles monotonic `:now` and `(wait subscription nanos)`.
  It is an injectable boundary for deterministic tests; production callers use
  the no-argument form so timestamping and waiting cannot use different clocks."
  ([]
   (registry system-clock))
  ([{:keys [now wait] :as clock}]
   {:pre [(ifn? now) (ifn? wait)]}
   (->Registry (atom {}) clock)))

(defn active-count
  "Number of request owners currently registered. Intended for lifecycle
  visibility and tests, not application routing."
  [{:keys [connections]}]
  (count @connections))

(defn- register! [{:keys [connections clock]}]
  (let [id           (random-uuid)
        subscription {:id id
                      :dirty (ArrayBlockingQueue. 1)
                      :pending (atom nil)
                      :clock clock}]
    (swap! connections assoc id subscription)
    subscription))

(defn- unregister! [{:keys [connections]} {:keys [id]}]
  (swap! connections dissoc id)
  nil)

(defn mark-dirty!
  "Non-blockingly wake every live request owner.

  Returns the number of clean connections changed to dirty. Repeated calls while
  a window is pending return zero for those connections: intermediate views are
  intentionally discarded because owners render current authoritative state."
  [{:keys [connections]}]
  (transduce
   (map (fn [{:keys [dirty pending clock]}]
          (let [now ((:now clock))
                [before _]
                (swap-vals! pending
                            (fn [window]
                              (if window
                                (assoc window :last now)
                                {:first now :last now})))]
            (.offer ^ArrayBlockingQueue dirty true)
            (if (nil? before) 1 0))))
   +
   0
   (vals @connections)))

(defn- ms->nanos [ms]
  (unchecked-multiply (long ms) 1000000))

(defn- validate-ms! [option value positive?]
  (when-not (and (integer? value)
                 (if positive? (pos? value) (not (neg? value)))
                 (<= value (quot Long/MAX_VALUE 1000000)))
    (throw (ex-info (str (name option) " must be "
                         (if positive? "a positive" "a non-negative")
                         " whole number of milliseconds within the nanoTime range")
                    {option value}))))

(defn- deadline-reached? [now deadline]
  ;; System/nanoTime has an arbitrary origin and may wrap. Only differences are
  ;; meaningful; configured durations are far below half the signed-long range.
  (not (neg? (unchecked-subtract now deadline))))

(defn- after [now duration]
  (unchecked-add now duration))

(defn- until [now deadline]
  (unchecked-subtract deadline now))

(defn- earliest-wait [now & deadlines]
  (when-let [deadlines (seq (remove nil? deadlines))]
    (reduce min (map #(until now %) deadlines))))

(defn- claim! [pending window]
  (compare-and-set! pending window nil))

(defn immediate-policy
  "Construct an await policy that renders as soon as a conflated wake is seen.

  Configuration is validated and converted once. Each invocation of the
  returned await function follows a write, so its heartbeat deadline means
  `heartbeat-ms` since the last render or heartbeat."
  [{:keys [heartbeat-ms]}]
  (when (some? heartbeat-ms)
    (validate-ms! :heartbeat-ms heartbeat-ms true))
  (let [heartbeat (some-> heartbeat-ms ms->nanos)]
    (fn [{:keys [pending clock] :as subscription}]
      (let [{:keys [now wait]} clock
            heartbeat-at (when heartbeat (after (now) heartbeat))]
        (loop []
          (let [t (now)
                window @pending]
            (cond
              window
              (if (claim! pending window) :dirty (recur))

              (and heartbeat-at (deadline-reached? t heartbeat-at))
              :heartbeat

              :else
              (do (wait subscription (when heartbeat-at (until t heartbeat-at)))
                  (recur)))))))))

(defn deadline-policy
  "Construct a trailing-settle, maximum-progress await policy.

  A quiet burst renders `settle-ms` after its last mutation. Continuous mutation
  cannot postpone a render beyond `max-wait-ms` after the first unsent mutation:

      min(last-change + settle, first-unsent-change + max-wait)

  While a window is pending, a mutation can only move that deadline later or
  leave it pinned by max-wait. Waiting on a stale deadline is therefore safe: it
  can only wake early, re-read `pending`, and wait again.

  Configuration is validated and converted once. Each invocation of the
  returned await function follows a write, so its heartbeat deadline means
  `heartbeat-ms` since the last render or heartbeat."
  [{:keys [settle-ms max-wait-ms heartbeat-ms]}]
  (validate-ms! :settle-ms settle-ms false)
  (validate-ms! :max-wait-ms max-wait-ms true)
  (when (some? heartbeat-ms)
    (validate-ms! :heartbeat-ms heartbeat-ms true))
  (let [settle    (ms->nanos settle-ms)
        max-wait  (ms->nanos max-wait-ms)
        heartbeat (some-> heartbeat-ms ms->nanos)]
    (fn [{:keys [pending clock] :as subscription}]
      (let [{:keys [now wait]} clock
            heartbeat-at (when heartbeat (after (now) heartbeat))]
        (loop []
          (let [t         (now)
                window    @pending
                render-at (when window
                            (let [settled (after (:last window) settle)
                                  forced  (after (:first window) max-wait)]
                              ;; Compare each deadline by its duration from the
                              ;; same `t`, rather than by nanoTime's absolute value.
                              (if (<= (until t settled) (until t forced))
                                settled
                                forced)))]
            (cond
              (and render-at (deadline-reached? t render-at))
              (if (claim! pending window) :dirty (recur))

              (and heartbeat-at (deadline-reached? t heartbeat-at))
              :heartbeat

              :else
              (do (wait subscription
                        (earliest-wait t render-at heartbeat-at))
                  (recur)))))))))

(defn subscription-response
  "Construct a long-lived fused SSE response.

  `render!` receives the lexically owned writer and must query current
  authoritative state itself. It is called immediately after registration and
  once after every scheduled dirty window. Registration before the initial
  render closes the race: a producer that misses the new connection committed
  before that render reads current truth; a later producer marks it dirty.
  Heartbeats are SSE comments and therefore do not alter browser state.

  Options:
  - `:heartbeat-ms` — idle time before `: ping` (default 15000; nil disables)
  - `:compression`  — fused writer profile (default gzip)
  - `:await`        — policy factory, `(fn [opts] (fn [subscription]))`
  - `:settle-ms`    — trailing quiet period (default 10)
  - `:max-wait-ms`  — maximum age of unsent work (default 100)

  An await function returns `:dirty`, `:heartbeat`, or `:stop`. The last is
  useful for orderly server shutdown and deterministic tests. Every return or
  throw unregisters in `finally`; the fused response then closes its writer
  lexically."
  ([request registry render!]
   (subscription-response request registry render! {}))
  ([request registry render! opts]
   {:pre [(ifn? render!)]}
   (let [{policy :await
          :as opts} (merge {:heartbeat-ms 15000
                            :compression  :gzip
                            :await        deadline-policy
                            :settle-ms    10
                            :max-wait-ms  100}
                           opts)
         await-fn (policy opts)]
     (fused/response
      request
      (fn [writer]
        (let [subscription (register! registry)]
          (try
            (render! writer)
            (fused/flush! writer)
            (loop []
              (let [result (await-fn subscription)]
                (case result
                  :dirty     (do (render! writer)
                                 (fused/flush! writer)
                                 (recur))
                  :heartbeat (do (fused/write-comment! writer "ping")
                                 (fused/flush! writer)
                                 (recur))
                  :stop      nil
                  (throw (ex-info "Unknown subscription await result"
                                  {:result result})))))
            (catch InterruptedException _
              (.interrupt (Thread/currentThread))
              nil)
            (finally
              (unregister! registry subscription)))))
      opts))))
