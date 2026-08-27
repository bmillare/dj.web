(ns dj.web.datastar.fused
  "A fused, one-shot Datastar response stack.

  One scoped operation owns Datastar semantics, SSE framing, buffering, and
  output. Unlike `dj.web.datastar.reference`, patch data lines are never
  collected into an intermediate vector and the connection is not published as
  a mutable, independently closable stream:

      HTML or signal JSON
      -> direct Datastar/SSE writes to this response's UTF-8 BufferedWriter
      -> dj.web.http.protocols/ResponseBody

  `response` creates an identity or negotiated gzip writer, lends it to one
  function, and closes it when that function returns or throws. The application
  may write several complete frames and flush once. This member remains
  one-shot; `dj.web.datastar.subscribed` owns long-lived subscriptions."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [dj.web.datastar.wire :as wire]
            [dj.web.http.protocols :as http-proto]
            [dj.web.sse :as sse])
  (:import [java.io BufferedWriter InputStream OutputStream OutputStreamWriter Writer]
           [java.nio.charset StandardCharsets]
           [java.util.zip GZIPOutputStream]))

(defn- malformed-signals! [^String raw why cause]
  (throw (ex-info (str "Datastar signals are " why ": "
                       (pr-str (cond-> raw (< 120 (count raw)) (subs 0 120))))
                  {:type ::malformed-signals :status 400 :raw raw}
                  cause)))

(defn signals
  "Parse request signals as a keywordized map; malformed/non-object JSON is 400."
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

(def ^:private unsupported-element-opts
  {wire/use-view-transition      "useViewTransition"
   wire/view-transition-selector "viewTransitionSelector"
   wire/element-namespace        "namespace"})

(def ^:private unsupported-signal-opts
  {wire/only-if-missing "onlyIfMissing"})

(def ^:private sse-opt-keys
  #{sse/id sse/retry-duration})

(def ^:private element-opt-keys
  (into sse-opt-keys
        [wire/selector wire/patch-mode wire/use-view-transition
         wire/view-transition-selector wire/element-namespace]))

(def ^:private signal-opt-keys
  (conj sse-opt-keys wire/only-if-missing))

(def ^:private patch-modes
  #{"remove" "outer" "inner" "replace" "prepend" "append" "before" "after"})

(defn- reject-unknown! [opts known op]
  (when-let [ks (not-empty (filterv #(not (contains? known %)) (keys opts)))]
    (throw (ex-info (str "dj.web.datastar.fused/" op " received unknown option "
                         (str/join ", " (map pr-str ks)))
                    {:unknown ks :op op}))))

(defn- validate-patch-mode! [mode op]
  (when-not (contains? patch-modes mode)
    (throw (ex-info (str "dj.web.datastar.fused/" op
                         " received unsupported patch mode " (pr-str mode))
                    {:patch-mode mode :supported patch-modes :op op}))))

(defn- reject-unsupported! [opts unsupported op]
  (when-let [ks (not-empty (filterv #(contains? opts %) (keys unsupported)))]
    (throw (ex-info (str "dj.web.datastar.fused/" op " does not implement "
                         (str/join ", " (map unsupported ks)))
                    {:unsupported ks :op op}))))

(defn- write-line! [^Writer writer prefix value]
  (.write writer ^String prefix)
  (.write writer (str value))
  (.write writer "\n"))

(defn- begin-event! [^Writer writer event-type opts]
  (let [{event-id sse/id retry sse/retry-duration}
        (sse/normalize-event-opts opts)]
    (write-line! writer "event: " event-type)
    (when event-id (write-line! writer "id: " event-id))
    (when retry (write-line! writer "retry: " retry))))

(defn- write-data-lines! [^Writer writer prefix ^String payload]
  (doseq [line (iterator-seq (.iterator (.lines payload)))]
    (.write writer "data: ")
    (.write writer ^String prefix)
    (.write writer ^String line)
    (.write writer "\n")))

(defn write-patch-elements!
  "Write one complete elements event directly to `writer` without flushing."
  ([writer elements] (write-patch-elements! writer elements {}))
  ([^Writer writer ^String elements opts]
   (reject-unknown! opts element-opt-keys "write-patch-elements!")
   (reject-unsupported! opts unsupported-element-opts "write-patch-elements!")
   (let [selector (get opts wire/selector "")
         mode     (get opts wire/patch-mode "outer")]
     (assert (string? selector) "Datastar selector must be a String")
     (assert (string? mode) "Datastar patch mode must be a String")
     (validate-patch-mode! mode "write-patch-elements!")
     (begin-event! writer wire/event-type-patch-elements opts)
     (when-not (str/blank? selector)
       (write-line! writer "data: selector " selector))
     (when (and (not (str/blank? mode)) (not= "outer" mode))
       (write-line! writer "data: mode " mode))
     (when-not (str/blank? elements)
       (write-data-lines! writer wire/elements-dataline-literal elements))
     (.write writer "\n")
     true)))

(defn write-patch-signals!
  "Write one complete signals event directly to `writer` without flushing."
  ([writer signals-json] (write-patch-signals! writer signals-json {}))
  ([^Writer writer ^String signals-json opts]
   (reject-unknown! opts signal-opt-keys "write-patch-signals!")
   (reject-unsupported! opts unsupported-signal-opts "write-patch-signals!")
   (begin-event! writer wire/event-type-patch-signals opts)
   (when-not (str/blank? signals-json)
     (write-data-lines! writer wire/signals-dataline-literal signals-json))
   (.write writer "\n")
   true))

(defn write-comment!
  "Write one complete SSE comment frame without flushing."
  [^Writer writer text]
  (write-line! writer ": " text)
  (.write writer "\n")
  true)

(defn flush!
  "Flush completed frames through the selected writer profile."
  [^Writer writer]
  (.flush writer)
  true)

(defn patch-elements!
  "Write and flush one complete elements event directly to `writer`."
  ([writer elements] (patch-elements! writer elements {}))
  ([writer elements opts]
   (write-patch-elements! writer elements opts)
   (flush! writer)))

(defn patch-signals!
  "Write and flush one complete signals event directly to `writer`."
  ([writer signals-json] (patch-signals! writer signals-json {}))
  ([writer signals-json opts]
   (write-patch-signals! writer signals-json opts)
   (flush! writer)))

(defn comment!
  "Write and flush one complete SSE comment frame."
  [writer text]
  (write-comment! writer text)
  (flush! writer))

(defn identity-writer
  "A buffered UTF-8 character writer over an HTTP output stream."
  ^BufferedWriter [^OutputStream out]
  (BufferedWriter. (OutputStreamWriter. out StandardCharsets/UTF_8)))

(defn gzip-writer
  "A buffered UTF-8 writer over one connection-long, sync-flushing gzip stream."
  ^BufferedWriter [^OutputStream out]
  (BufferedWriter.
   (OutputStreamWriter.
    (GZIPOutputStream. out 8192 true)
    StandardCharsets/UTF_8)))

(defn- writer-profile [request compression]
  (case (or compression :identity)
    :identity {:encoding nil :writer-factory identity-writer}
    :gzip     (if (sse/acceptable-encoding? request "gzip")
                {:encoding "gzip" :writer-factory gzip-writer}
                {:encoding nil :writer-factory identity-writer})
    (throw (ex-info (str "Unsupported fused compression: " (pr-str compression))
                    {:compression compression}))))

(deftype Body [writer-factory write!]
  http-proto/ResponseBody
  (content-length [_] nil)
  (streaming? [_] true)
  (write-body! [_ out]
    (with-open [writer (writer-factory out)]
      (write! writer))))

(defn response
  "Construct a one-shot SSE response using identity or negotiated gzip output.

  `write!` receives the response's BufferedWriter synchronously. Returning or
  throwing ends its scope and closes the underlying output stream."
  ([request write!] (response request write! {}))
  ([request write! {:keys [status headers compression]}]
   {:pre [(ifn? write!)]}
   (let [{:keys [encoding writer-factory]} (writer-profile request compression)
         headers (if (= :gzip compression)
                   (merge {"Vary" "Accept-Encoding"} headers)
                   headers)]
     {:status  (or status 200)
      :headers (sse/headers request encoding headers)
      :body    (Body. writer-factory write!)})))
