(ns dj.web.datastar.reference
  "A complete, de-complected Datastar response stack.

  The path from an application value to browser bytes is deliberately visible:

      HTML or signal JSON
      -> `wire/->patch-elements` or `wire/->patch-signals` (Datastar data lines)
      -> `sse/write-event!` (an SSE frame)
      -> this connection's UTF-8 writer
      -> `http-proto/ResponseBody` (the HTTP socket)

  This member is the readable reference for one-shot and long-lived responses.
  It has no Datastar SDK runtime dependency, no write profiles, and no
  compression. One lock makes each complete frame and close atomic. The
  response remains open exactly while `:on-open` runs; returning, throwing, or
  calling `close!` closes it and invokes `:on-close` at most once."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [dj.web.datastar.wire :as wire]
            [dj.web.http.protocols :as http-proto]
            [dj.web.sse :as sse])
  (:import [java.io BufferedWriter Closeable IOException InputStream
            OutputStreamWriter]
           [java.nio.charset StandardCharsets]
           [java.util.concurrent.locks ReentrantLock]))

(defn- malformed-signals! [^String raw why cause]
  (throw (ex-info (str "Datastar signals are " why ": "
                       (pr-str (cond-> raw (< 120 (count raw)) (subs 0 120))))
                  {:type ::malformed-signals :status 400 :raw raw}
                  cause)))

(defn signals
  "Parse the signals carried by `request` as a keywordized map. Missing or JSON
  `null` signals return nil; malformed and non-object JSON are classified 400."
  [request]
  (let [value (wire/get-signals request)
        raw   (if (instance? InputStream value) (slurp value) value)]
    (when-not (str/blank? raw)
      (let [parsed (try
                     (json/read-str raw :key-fn keyword)
                     (catch Exception error
                       (malformed-signals! raw "not valid JSON" error)))]
        (cond
          (nil? parsed) nil
          (map? parsed) parsed
          :else         (malformed-signals! raw "not a JSON object" nil))))))

(def get-signals wire/get-signals)
(def datastar-request? wire/datastar-request?)

(defn- default-on-exception
  "Treat ordinary socket loss as the end of a connection. Anything else is an
  application/programming failure and must remain visible to the caller."
  [_stream error _context]
  (instance? IOException error))

(defmacro ^:private with-lock [lock & body]
  `(let [^ReentrantLock lock# ~lock]
     (.lock lock#)
     (try
       ~@body
       (finally
         (.unlock lock#)))))

(declare close!)

(defn- claim-close! [state]
  (let [[old _] (swap-vals! state assoc :writer nil :closed? true)]
    (when-not (:closed? old) old)))

;; Lifecycle callback contracts:
;; - on-open runs synchronously once the writer is installed. The response stays
;;   open for that call and is closed in `finally` when it returns or throws.
;; - on-close runs at most once, after one caller atomically claims cleanup. It
;;   is notification that this Stream is closed, not a request to close it.
;; - on-exception receives [stream error context]. Truthy means "I handled this
;;   write failure": claim close and return false. Falsy rethrows `error`.
(deftype Stream [^ReentrantLock lock state on-open on-close on-exception]
  http-proto/ResponseBody
  (content-length [_] nil)
  (streaming? [_] true)
  (write-body! [this out]
    (let [writer (BufferedWriter.
                  (OutputStreamWriter. out StandardCharsets/UTF_8))
          ;; A response body can be closed before the server starts writing it.
          ;; Do not resurrect that stream by installing a writer after its
          ;; close claim.
          [old _] (swap-vals! state
                              #(if (:closed? %) % (assoc % :writer writer)))]
      (if (:closed? old)
        (.close writer)
        (do
          (.flush out)
          (try
            (on-open this)
            (finally
              (close! this)))))))

  Closeable
  (close [this] (close! this)))

;; Closing has two deliberately separate stages. `claim-close!` changes the
;; atom immediately and chooses exactly one cleanup owner. A normal closer then
;; takes the writer lock below; a failed writer already holds it. Both attempt
;; physical close before sending the one close notification outside that lock.
(defn- close-writer! [^Stream stream writer]
  (when writer
    (with-lock (.-lock stream)
      (.close ^BufferedWriter writer))))

(defn- invoke-close-callback! [^Stream stream]
  (when-let [f (.-on-close stream)] (f stream)))

(defn- finish-close! [^Stream stream writer]
  (try
    (close-writer! stream writer)
    (finally
      (invoke-close-callback! stream)))
  true)

(defn- close-writer-quietly! [^BufferedWriter writer]
  ;; A handled write failure is commonly followed by a second IOException while
  ;; closing the same dead socket. That cleanup noise is not a new failure.
  (try
    (.close writer)
    (catch IOException _ nil)))

(defn- guarded-write! [^Stream stream context write-fn]
  ;; Serialize one complete frame against other frames and close. Returns true
  ;; only when the frame was flushed. A handled failure returns false; if this
  ;; path wins the close claim, it closes once and notifies `on-close` after
  ;; releasing the frame lock. Unhandled failures remain visible to the caller.
  (let [state         (.-state stream)
        notify-close? (volatile! false)]
    (try
      (with-lock (.-lock stream)
        (if-let [^BufferedWriter writer (:writer @state)]
          (try
            (write-fn writer)
            (.flush writer)
            true
            (catch Exception error
              (when-not ((.-on-exception stream)
                         stream error (assoc context :stream stream))
                (throw error))
              (when (claim-close! state)
                ;; Record ownership first so even an unexpected unchecked close
                ;; failure cannot skip the notification in the outer `finally`.
                (vreset! notify-close? true)
                (close-writer-quietly! writer))
              false))
          false))
      (finally
        ;; `with-lock` has released the frame lock before this callback runs.
        (when @notify-close?
          (invoke-close-callback! stream))))))

(defn- send-event! [^Stream stream event-type data-lines opts]
  ;; Option validation is a caller concern, not a socket failure. Keep it
  ;; outside both the writer lock and the connection exception handler.
  (let [normalized-opts (sse/normalize-event-opts opts)]
    (guarded-write!
     stream
     {:event-type event-type :data-lines data-lines :opts opts}
     #(sse/write-event! % event-type data-lines normalized-opts))))

(defn patch-elements!
  "Write one Datastar elements patch. Returns false after the stream is closed."
  ([stream elements] (patch-elements! stream elements {}))
  ([stream elements opts]
   (send-event! stream wire/event-type-patch-elements
                (wire/->patch-elements elements opts) opts)))

(defn patch-signals!
  "Write one Datastar signals patch from an already-encoded JSON string."
  ([stream signals-json] (patch-signals! stream signals-json {}))
  ([stream signals-json opts]
   (send-event! stream wire/event-type-patch-signals
                (wire/->patch-signals signals-json opts) opts)))

(defn comment!
  "Write one SSE comment frame. Comments carry no Datastar operation."
  [^Stream stream text]
  (guarded-write! stream {:comment text} #(sse/write-comment! % text)))

(defn close!
  "Close `stream` and invoke its close callback once. Returns false if already closed."
  [^Stream stream]
  (if-let [{:keys [writer]} (claim-close! (.-state stream))]
    (finish-close! stream writer)
    false))

(defn response
  "Construct an uncompressed SSE response.

  The response body owns its writer: `close!` closes the underlying HTTP output
  stream so an explicit close ends a long-lived response immediately. The HTTP
  adapter may safely close that raw stream again after `write-body!` returns.

  Options are ordinary keys: `:on-open` (required), `:on-close`,
  `:on-exception`, `:status`, and extra `:headers`."
  [request {:keys [on-open on-close on-exception status headers]}]
  {:pre [(ifn? on-open)]}
  {:status  (or status 200)
   :headers (sse/headers request nil headers)
   :body    (Stream. (ReentrantLock.)
                     (atom {:writer nil :closed? false})
                     on-open
                     on-close
                     (or on-exception default-on-exception))})
