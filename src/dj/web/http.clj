(ns dj.web.http
  "Minimal HTTP server on the JDK's built-in `com.sun.net.httpserver`.

  `HttpServer` ships with the JDK, and JDK 21 virtual threads make one thread
  per request practical even for long-lived SSE responses. The HTTP mechanism
  therefore adds no web-server dependency.

  Shape: a handler is a plain function `request -> response`. The request map is
  ring-*flavoured* (`:request-method`, `:uri`, `:query-params`, `:headers`,
  `:body`) on purpose — that is the shape the Datastar SDK's request helpers
  expect, so they work unmodified. But this is deliberately NOT ring: no
  middleware stack, no ring dependency, no protocol beyond
  `dj.web.http.protocols/ResponseBody`.

  This namespace is mechanism only. The response constructors live next door in
  `dj.web.http.response`; they are convenience, and nothing here depends on
  them having been used.

  Response bodies go through `ResponseBody`, which answers three separate
  questions — how long it is, whether it streams, and how to write it. `respond!`
  is the only place that turns those into `sendResponseHeaders`' 0/-1/n encoding.

  Two gotchas, both load-bearing:

  1. `HttpServer`'s DEFAULT executor runs handlers on the dispatch thread, so a
     single open SSE stream would block every other request. `start!` always
     installs a virtual-thread-per-task executor — that is the single most
     important line in this namespace.
  2. `sendResponseHeaders(status, 0)` means \"unknown length\" => chunked =>
     bytes reach the client as they are flushed. Passing the real length makes
     the response fixed-size (still flushable, but the size is now a promise you
     must keep); -1 means no body at all.

  Binds to 127.0.0.1 by default. Applications must make an explicit security
  decision before binding a server without authentication to another address.

  Usage:
    (defn app [req] (resp/html-response \"<h1>hi</h1>\"))
    (def srv (http/start! #'app {:port 8899}))   ; #'var so edits take effect
    (http/stop! srv)"
  (:require [clojure.string :as str]
            [dj.web.http.error :as error]
            [dj.web.http.protocols :as proto]
            [dj.web.http.response :as resp])
  (:import [com.sun.net.httpserver HttpExchange HttpHandler HttpServer]
           [java.io Closeable File FileInputStream InputStream OutputStream]
           [java.net InetSocketAddress URLDecoder]
           [java.nio.charset StandardCharsets]
           [java.util.concurrent ExecutorService Executors]))

;; -----------------------------------------------------------------------------
;; Response bodies
;; -----------------------------------------------------------------------------

(extend-protocol proto/ResponseBody
  nil
  (content-length [_] 0)
  (streaming? [_] false)
  (write-body! [_ _])

  String
  (content-length [s] (alength (.getBytes s StandardCharsets/UTF_8)))
  (streaming? [_] false)
  (write-body! [s ^OutputStream out]
    (.write out (.getBytes s StandardCharsets/UTF_8)))

  File
  (content-length [f] (.length ^File f))
  (streaming? [_] false)
  (write-body! [f ^OutputStream out]
    (with-open [in (FileInputStream. ^File f)]
      (.transferTo in out)))

  ;; The one built-in that streams: length unknown, bytes as they arrive.
  InputStream
  (content-length [_] nil)
  (streaming? [_] true)
  (write-body! [in ^OutputStream out]
    (with-open [^InputStream in in]
      (.transferTo in out))))

(extend-type (class (byte-array 0))
  proto/ResponseBody
  (content-length [b] (alength ^bytes b))
  (streaming? [_] false)
  (write-body! [b ^OutputStream out] (.write out ^bytes b)))

;; -----------------------------------------------------------------------------
;; Request
;; -----------------------------------------------------------------------------

(defn- decode [s]
  (URLDecoder/decode (or s "") StandardCharsets/UTF_8))

(defn- add-param
  "Ring's collision rule: one occurrence is a string, more than one is a vector."
  [m k v]
  (let [prev (get m k)]
    (cond
      (nil? prev)    (assoc m k v)
      (vector? prev) (assoc m k (conj prev v))
      :else          (assoc m k [prev v]))))

(defn- query-params
  "\"a=1&b=hi%20there\" -> {\"a\" \"1\", \"b\" \"hi there\"}; a repeated key gives a
  vector. String keys, because that is where the Datastar SDK looks for its
  `datastar` param.

  Takes the RAW query string. Splitting a decoded one would split on delimiters
  that were escaped by the client (`%26` inside a value) and would decode escaped
  escapes twice."
  [raw-query-string]
  (reduce (fn [m pair]
            (let [[k v] (str/split pair #"=" 2)]
              (add-param m (decode k) (decode v))))
          {}
          (some-> raw-query-string not-empty (str/split #"&"))))

(defn- request-headers
  "Lower-cased names -> comma-joined values.

  Lossy on purpose: ring's shape is what the Datastar SDK reads. It is a
  projection, not a destruction — a header that legitimately repeats (or carries a
  comma inside a quoted value) is still available separately via
  `(.getRequestHeaders ex)` on the `::exchange` key below."
  [^HttpExchange ex]
  (into {} (for [[k vs] (.getRequestHeaders ex)]
             [(str/lower-case k) (str/join "," vs)])))

(defn request
  "The exchange as a ring-flavoured map. `::exchange` is the escape hatch for
  anything this map doesn't cover.

  Not a value, and worth knowing: `:body` is a one-shot mutable `InputStream` and
  `::exchange` is a live stateful object. So this map can't be logged, replayed, or
  handed to two consumers. Ring has the same property; it is the price of streaming
  and is deliberately not papered over."
  [^HttpExchange ex]
  (let [uri (.getRequestURI ex)
        qs  (.getRawQuery uri)]
    {:request-method (keyword (str/lower-case (.getRequestMethod ex)))
     :uri            (.getPath uri)
     :query-string   qs
     :query-params   (query-params qs)
     :headers        (request-headers ex)
     :protocol       (.getProtocol ex)
     :remote-addr    (some-> (.getRemoteAddress ex) .getAddress .getHostAddress)
     :body           (.getRequestBody ex)
     ::exchange      ex}))

;; -----------------------------------------------------------------------------
;; Response
;; -----------------------------------------------------------------------------

(defn- framing
  "Translate the body's two facts into `sendResponseHeaders`' third argument:
  `n` = exactly n bytes, `0` = chunked, `-1` = no body at all.

  This is the only place the host API's encoding exists. Note that a known length
  and streaming are NOT exclusive: a body may promise a size and still write it
  incrementally, which is what you want piping a large file."
  [len streaming?]
  (cond
    (and len (pos? len))       len
    (or streaming? (nil? len)) 0
    :else                      -1))

(defn- write-body-and-close! [body ^OutputStream out]
  (try
    (proto/write-body! body out)
    (catch Throwable t
      ;; JDK chunked streams may also throw while writing their final chunk.
      ;; Preserve the body-write failure as the primary error.
      (try
        (.close out)
        (catch Throwable close-error
          (when-not (identical? t close-error)
            (.addSuppressed t close-error))))
      (throw t)))
  ;; A close failure after a successful write is itself the primary error.
  (.close out))

(defn respond!
  "Send `response` ({:status :headers :body}) on `ex`.

  Header values may be a string or a collection of strings; each is `.add`ed, so
  headers that legally repeat (`Set-Cookie`) survive."
  [^HttpExchange ex {:keys [status headers body]}]
  ;; Preparing a String once avoids encoding it separately for length and write.
  (let [body        (if (string? body)
                      (.getBytes ^String body StandardCharsets/UTF_8)
                      body)
        out-headers (.getResponseHeaders ex)
        head?       (= "HEAD" (.getRequestMethod ex))
        len         (proto/content-length body)
        n           (framing len (proto/streaming? body))]
    (doseq [[k v] headers
            v     (if (sequential? v) v [v])
            :when (some? v)]
      (.add out-headers (name k) (str v)))
    ;; A HEAD response carries the length the body WOULD have had, and no body.
    (when (and head? len (pos? len))
      (.set out-headers "Content-Length" (str len)))
    (.sendResponseHeaders ex (int (or status 200)) (if head? -1 n))
    (if (or head? (= -1 n))
      ;; A response body can own a resource even when HTTP semantics forbid us
      ;; from reading it (HEAD, or another no-body response).
      (when (instance? Closeable body)
        (.close ^Closeable body))
      (write-body-and-close! body (.getResponseBody ex)))))

;; -----------------------------------------------------------------------------
;; Server
;; -----------------------------------------------------------------------------

(def ^:private server-error
  {:status 500
   :headers {"Content-Type" "text/plain; charset=utf-8"}
   :body "internal server error"})

(defn- report-and-recover! [^HttpExchange ex on-error throwable request]
  ;; Reporting is user-supplied policy. It must not prevent the best-effort 500
  ;; or escape into HttpServer's dispatcher if the policy itself is faulty.
  (try (on-error throwable request) (catch Throwable _ nil))
  ;; -1 means no headers went out yet, so a real 500 is still possible. Once a
  ;; stream is open it is not; closing the exchange is then all there is.
  (try
    (when (neg? (.getResponseCode ex))
      ;; Drop whatever the failed response had started to set.
      (.clear (.getResponseHeaders ex))
      (respond! ex server-error))
    (catch Throwable _ nil)))

(defn- request-result [^HttpExchange ex]
  ;; The JDK rejects syntactically malformed request targets before constructing
  ;; an exchange. This boundary is for unexpected projection/getter failures.
  (try
    [(request ex) nil]
    (catch Throwable t
      [nil t])))

(defn- ->http-handler ^HttpHandler [handler on-error]
  (reify HttpHandler
    (handle [_ ex]
      (try
        (let [[req parse-error] (request-result ex)]
          (if parse-error
            (report-and-recover! ex on-error parse-error nil)
            (try
              (respond! ex (or (handler req) resp/not-found))
              ;; Deliberately catch Throwable: one bad request must not kill a
              ;; server worker, even for an Error such as StackOverflowError.
              (catch Throwable t
                (report-and-recover! ex on-error t req)))))
        (finally
          (.close ^HttpExchange ex))))))

(defn start!
  "Start a server on `port` and return it (pass to `stop!`).

  Pass a VAR (`#'app`) rather than a function value so REPL edits to the handler
  take effect without restarting the server.

  Opts: `:port` (8899), `:host` (\"127.0.0.1\"), `:backlog` (0),
  `:on-error` `(fn [throwable request])` — `request` is nil if it couldn't even be
  parsed. A throw before anything was written still gets a 500; `:on-error` decides
  only what gets reported."
  ([handler] (start! handler nil))
  ([handler {:keys [port host backlog on-error]
             :or   {port 8899 host "127.0.0.1" backlog 0}}]
   (let [server (HttpServer/create (InetSocketAddress. ^String host (int port))
                                   (int backlog))]
     ;; THE gotcha: without this, handlers run on the dispatch thread and one
     ;; open SSE stream wedges the whole server.
     (.setExecutor server (Executors/newVirtualThreadPerTaskExecutor))
     ;; Capture the caller's dynamic bindings before work crosses onto the
     ;; executor. A Var remains a live indirection through bound-fn*, preserving
     ;; the advertised REPL-reload behavior.
     (.createContext server "/"
                     (->http-handler (bound-fn* handler)
                                     (bound-fn* (or on-error error/report))))
     (.start server)
     server)))

(defn stop!
  "Stop `server`, waiting at most `delay-seconds` (default 0) for in-flight
  exchanges. Open SSE streams are cut at that deadline. Also shuts down the
  executor `start!` installed."
  ([server] (stop! server 0))
  ([^HttpServer server delay-seconds]
   (.stop server (int delay-seconds))
   (when-let [ex (.getExecutor server)]
     (when (instance? ExecutorService ex)
       (.shutdown ^ExecutorService ex)))
   nil))

(defn port [^HttpServer server]
  (.getPort (.getAddress server)))
