(ns dj.web.http-test
  "Round-trip tests for the JDK-HttpServer layer and the Datastar SSE adapter.

  These go through a real socket rather than calling handlers directly, because
  the things most likely to break are exactly the wire-level ones: chunked vs
  fixed-length framing (get it wrong and streaming silently stops), header
  multiplicity, HEAD, and the SSE event encoding."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dj.web.datastar :as ds]
            [dj.web.html :as h]
            [dj.web.http :as http]
            [dj.web.http.error :as http-error]
            [dj.web.http.protocols :as proto]
            [dj.web.http.response :as resp]
            [starfederation.datastar.clojure.adapter.common :as ac]
            [starfederation.datastar.clojure.api :as d*])
  (:import [java.io BufferedReader InputStream InputStreamReader OutputStream StringWriter]
           [java.net Socket URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
            HttpResponse$BodyHandlers]
           [java.util.zip GZIPInputStream]))

(def ^:private client (HttpClient/newHttpClient))

(defn- send*
  "`method` request to `path`, as `{:status :headers :header-values :body}`.
  `:headers` keeps the first value of each header, `:header-values` all of them."
  [method port path]
  (let [resp (.send client
                    (-> (HttpRequest/newBuilder (URI/create (str "http://127.0.0.1:" port path)))
                        (.method ^String method (HttpRequest$BodyPublishers/noBody))
                        .build)
                    (HttpResponse$BodyHandlers/ofString))
        hs   (.headers resp)]
    {:status        (.statusCode resp)
     :headers       (into {} (for [[k vs] (.map hs)] [k (first vs)]))
     :header-values (into {} (for [[k vs] (.map hs)] [k (vec vs)]))
     :body          (.body resp)}))

(defn- GET  [port path] (send* "GET" port path))
(defn- HEAD [port path] (send* "HEAD" port path))

(defn- raw-status-line [port request]
  (with-open [socket (Socket. "127.0.0.1" (int port))
              reader (BufferedReader. (InputStreamReader. (.getInputStream socket)))]
    (let [out (.getOutputStream socket)]
      (.write out (.getBytes ^String request "UTF-8"))
      (.flush out)
      (.readLine reader))))

(defn- with-server
  "Run `f` against a server on an ephemeral port, then stop it."
  ([handler f] (with-server handler nil f))
  ([handler opts f]
   (let [server (http/start! handler (merge {:port 0} opts))]
     (try (f (http/port server))
          (finally (http/stop! server))))))

(deftest fixed-length-responses
  (with-server
    (fn [req]
      (case (:uri req)
        "/hello" (resp/html-response "<h1>hi</h1>")
        "/echo"  (resp/text-response (pr-str [(:request-method req) (:query-params req)]))
        resp/not-found))
    (fn [port]
      (testing "a known-length body gets a Content-Length, not chunking"
        (let [{:keys [status headers body]} (GET port "/hello")]
          (is (= 200 status))
          (is (= "<h1>hi</h1>" body))
          (is (= "11" (get headers "content-length")))
          (is (nil? (get headers "transfer-encoding")))))

      (testing "request parsing: method keyword + decoded query params"
        (is (= (pr-str [:get {"q" "dj http" "n" "2"}])
               (:body (GET port "/echo?q=dj%20http&n=2")))))

      (testing "a repeated key collects into a vector, as in ring"
        (is (= (pr-str [:get {"id" ["1" "2" "3"]}])
               (:body (GET port "/echo?id=1&id=2&id=3")))))

      (testing "the query string is decoded exactly once, after splitting"
        ;; %26 is an escaped '&' INSIDE a value: it must not split the pair, and
        ;; %2520 must come back as the literal "%20", not as a space.
        (is (= (pr-str [:get {"q" "a&b"}])
               (:body (GET port "/echo?q=a%26b"))))
        (is (= (pr-str [:get {"q" "%20"}])
               (:body (GET port "/echo?q=%2520")))))

      (testing "unmatched route"
        (is (= 404 (:status (GET port "/nope"))))))))

(deftest headers-that-legally-repeat
  (testing "a vector header value repeats while nil values are omitted"
    (with-server
      (fn [_req]
        {:status  200
         :headers {"Set-Cookie"   ["a=1" "b=2"]
                   "Content-Type" "text/plain"
                   "X-Omitted"    nil}
         :body    "ok"})
      (fn [port]
        (let [{:keys [headers header-values]} (GET port "/")]
          (is (= ["a=1" "b=2"] (get header-values "set-cookie")))
          (is (= "text/plain" (get headers "content-type")))
          (is (nil? (get headers "x-omitted"))))))))

(deftest head-requests-carry-the-length-but-no-body
  (with-server
    (fn [_req] (resp/html-response "<h1>hi</h1>"))
    (fn [port]
      (let [{:keys [status headers body]} (HEAD port "/")]
        (is (= 200 status))
        (is (= "" body))
        (is (= "11" (get headers "content-length")))))))

(deftest head-closes-a-resource-owning-body-without-reading-it
  (let [closed? (atom false)
        body    (proxy [InputStream] []
                  (read [] (throw (AssertionError. "HEAD read its body")))
                  (close [] (reset! closed? true)))]
    (with-server
      (fn [_] {:status 200 :body body})
      (fn [port]
        (is (= 200 (:status (HEAD port "/"))))
        (is @closed?)))))

(deftest malformed-request-target-is-rejected-before-the-handler
  ;; HttpServer parses the target into java.net.URI before constructing an
  ;; exchange. A malformed %-escape is therefore already its 400, not a failure
  ;; from our query decoder and not something application reporting should see.
  (let [handled  (atom 0)
        reported (atom [])]
    (with-server
      (fn [_] (swap! handled inc) (resp/text-response "unexpected"))
      {:on-error (fn [error request] (swap! reported conj [error request]))}
      (fn [port]
        (is (str/starts-with?
             (raw-status-line port
                              "GET /?q=%zz HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n")
             "HTTP/1.1 400"))
        (is (zero? @handled))
        (is (empty? @reported))))))

(deftest a-known-length-body-may-still-stream
  (testing "length and streaming are independent — Content-Length AND incremental
            writes, the combination the old nil-means-chunked sentinel forbade"
    (with-server
      (fn [_req]
        {:status 200
         :body   (reify proto/ResponseBody
                   (content-length [_] 6)
                   (streaming? [_] true)
                   (write-body! [_ out]
                     (doseq [s ["abc" "def"]]
                       (.write ^OutputStream out (.getBytes ^String s "UTF-8"))
                       (.flush ^OutputStream out))))})
      (fn [port]
        (let [{:keys [status headers body]} (GET port "/")]
          (is (= 200 status))
          (is (= "abcdef" body))
          (is (= "6" (get headers "content-length")))
          (is (nil? (get headers "transfer-encoding"))))))))

(deftest sse-round-trip
  (with-server
    (fn [req]
      (ds/->sse-response
       req {ac/on-open (fn [sse]
                         (ds/patch-signals! sse (json/write-str {:clicks 1}))
                         (ds/patch-elements! sse (h/html [:p#out "hello"]))
                         (d*/close-sse! sse))}))
    (fn [port]
      (let [{:keys [status headers body]} (GET port "/anything")]
        (testing "SSE headers, and an unknown length so the response streams"
          (is (= 200 status))
          (is (= "text/event-stream" (get headers "content-type")))
          (is (= "no-cache" (get headers "cache-control")))
          (is (= "chunked" (get headers "transfer-encoding")))
          (is (nil? (get headers "content-length"))))

        (testing "our wire format comes out intact"
          (is (str/includes? body "event: datastar-patch-signals"))
          (is (str/includes? body "event: datastar-patch-elements"))
          (is (str/includes? body "data: elements <p id=\"out\">hello</p>"))
          ;; every event is terminated by a blank line
          (is (str/ends-with? body "\n\n")))))))

(deftest sse-multiline-elements
  (testing "HTML containing newlines is split into one data: line per line —
            the encoding that carries docstrings, <pre>, and formatted markup"
    (with-server
      (fn [req]
        (ds/->sse-response
         req {ac/on-open (fn [sse]
                           (ds/patch-elements! sse (h/html [:pre#doc "line one\nline two"]))
                           (d*/close-sse! sse))}))
      (fn [port]
        (let [lines (->> (:body (GET port "/")) str/split-lines
                         (filter #(str/starts-with? % "data: elements")))]
          (is (= ["data: elements <pre id=\"doc\">line one"
                  "data: elements line two</pre>"]
                 lines)))))))

(deftest sdk-fallback-still-works-through-our-generator
  (testing "an option the wire extraction does not implement is still one `d*/` call away —
            the claim that makes trimming layer 2 safe rather than a gap"
    ;; the extraction design: we implement only what we call, and everything else stays
    ;; reachable because our generator implements `p/SSEGenerator`. That is the
    ;; whole reason the jar is in :deps rather than :test, so it is pinned over
    ;; a real socket rather than asserted in prose. `mode append` + `selector`
    ;; are the unsupported option surface; `wire/->patch-elements` throws on them on purpose.
    (with-server
      (fn [req]
        (ds/->sse-response
         req {ac/on-open (fn [sse]
                           (d*/patch-elements! sse (h/html [:li "appended"])
                                               {d*/selector "#log"
                                                d*/patch-mode d*/pm-append})
                           (d*/close-sse! sse))}))
      (fn [port]
        (let [body (:body (GET port "/"))]
          (is (str/includes? body "data: selector #log"))
          (is (str/includes? body "data: mode append"))
          (is (str/includes? body "data: elements <li>appended</li>")))))))

(deftest signals-in
  (testing "signals arrive as JSON on the datastar query param for @get"
    (with-server
      (fn [req]
        (resp/text-response (pr-str (ds/signals req))))
      (fn [port]
        (is (= (pr-str {:query "dj http" :n 2})
               (:body (GET port "/?datastar=%7B%22query%22%3A%22dj%20http%22%2C%22n%22%3A2%7D"))))
        (testing "no signals at all is nil, not a parse error"
          (is (= "nil" (:body (GET port "/")))))
        (testing "and `null` is nil too — the client said so explicitly"
          (is (= "nil" (:body (GET port "/?datastar=null")))))))))

(deftest malformed-signals-are-a-client-error-not-a-server-crash
  ;; the request-side extraction's ADR MUST: return an error on invalid JSON rather than letting
  ;; `json/read-str` throw whatever it likes. The `datastar` param is
  ;; client-controlled — anyone can edit a URL — so an unparseable one must not
  ;; read as a fault in this process.
  (let [!errs (atom [])]
    (with-server
      (fn [req]
        (try
          (resp/text-response (pr-str (ds/signals req)))
          (catch clojure.lang.ExceptionInfo e
            (let [{:keys [type status]} (ex-data e)]
              (if (= ::ds/malformed-signals type)
                {:status status :body "bad signals"}
                (throw e))))))
      ;; A handler that DOESN'T catch would land here; the test asserts it stays
      ;; empty, which is the actual claim — this is a 400 path, not a 500 path.
      {:on-error (fn [t _req] (swap! !errs conj t) nil)}
      (fn [port]
        (testing "unparseable JSON is catchable by type and carries a 400"
          (let [{:keys [status body]} (GET port "/?datastar=notjson")]
            (is (= 400 status))
            (is (= "bad signals" body))))

        (testing "so is valid JSON that is not a store"
          ;; `5`, `[1,2]` and `"x"` all parse. None is a signals map, and
          ;; letting one through means the next `(:query ...)` quietly answers
          ;; nil somewhere with no idea what a signal is.
          (is (= 400 (:status (GET port "/?datastar=5"))))
          (is (= 400 (:status (GET port "/?datastar=%5B1%2C2%5D"))))
          (is (= 400 (:status (GET port "/?datastar=%22x%22")))))

        (testing "and nothing reached the server's error path"
          (is (= [] @!errs)))))))

(deftest on-close-always-fires-when-on-open-returns
  (testing "the adapter closes the generator when on-open returns — so on-close is
            a trustworthy 'this connection is done' signal, and a wrapped (e.g.
            gzip) stream gets closed rather than only the raw socket"
    (let [closed (atom [])]
      (with-server
        (fn [req]
          (ds/->sse-response
           req {ac/on-close (fn [_sse] (swap! closed conj (:uri req)))
                ac/on-open  (fn [sse]
                              (ds/patch-elements! sse (h/html [:p#out "bye"]))
                              (case (:uri req)
                                "/plain"  nil                       ; just return
                                "/closed" (d*/close-sse! sse)       ; close ourselves
                                "/throw"  (throw (ex-info "boom" {}))))}))
        {:on-error (fn [_t _req] nil)}       ; the /throw case; keep output clean
        (fn [port]
          (testing "returning without closing still fires on-close"
            (GET port "/plain")
            (is (= ["/plain"] @closed)))

          (testing "closing explicitly fires it exactly once, not twice"
            (reset! closed [])
            (GET port "/closed")
            (is (= ["/closed"] @closed)))

          (testing "and a throw out of on-open does not skip cleanup"
            (reset! closed [])
            (GET port "/throw")
            (is (= ["/throw"] @closed))))))))

(deftest gzip-write-profile-produces-a-readable-stream
  (testing "the SDK's gzip profile wraps the output stream, and a GZIP stream is
            only decodable once its trailer is written — i.e. once the WRAPPED
            stream is closed. Closing the raw socket is not enough, so this is the
            test that fails if `write-body!` stops closing the generator itself."
    ;; This client sends no `Accept-Encoding`, and since the header extraction that is still a
    ;; gzip response on purpose — RFC 9110 §12.5.3 makes silence permission. The
    ;; refusal case is `gzip-is-not-forced-on-a-client-that-refused-it` below.
    (with-server
      (fn [req]
        (ds/->sse-response
         req {ac/write-profile ac/gzip-profile
              ;; note: returns WITHOUT closing — the case that used to truncate
              ac/on-open       (fn [sse]
                                 (ds/patch-elements! sse (h/html [:p#out "gz"])))}))
      (fn [port]
        (let [resp (.send client
                          (.build (HttpRequest/newBuilder
                                   (URI/create (str "http://127.0.0.1:" port "/"))))
                          (HttpResponse$BodyHandlers/ofInputStream))
              body (slurp (GZIPInputStream. ^java.io.InputStream (.body resp)))]
          (is (= "gzip" (.orElse (.firstValue (.headers resp) "content-encoding") nil)))
          (is (str/includes? body "data: elements <p id=\"out\">gz</p>")))))))

(deftest gzip-is-not-forced-on-a-client-that-refused-it
  (testing "the header extraction: `Accept-Encoding: identity` downgrades the write profile, so
            the client gets readable bytes and no Content-Encoding — the defect
            the SDK cannot fix, since `ac/headers` never reads the request's
            Accept-Encoding and the stream wrapping is decided elsewhere again"
    ;; Over a real socket rather than in `sse-test`, because the claim is about
    ;; what a client can read. `java.net.http.HttpClient` sends no
    ;; `Accept-Encoding` of its own and decompresses nothing, so an undeclared
    ;; gzip stream reads here as binary garbage — exactly what a programmatic
    ;; consumer would see.
    (with-server
      (fn [req]
        (ds/->sse-response
         req {ac/write-profile ac/gzip-profile
              ac/on-open       (fn [sse]
                                 (ds/patch-elements! sse (h/html [:p#out "plain"]))
                                 (d*/close-sse! sse))}))
      (fn [port]
        (let [resp (.send client
                          (.build (.header (HttpRequest/newBuilder
                                            (URI/create (str "http://127.0.0.1:" port "/")))
                                           "Accept-Encoding" "identity"))
                          (HttpResponse$BodyHandlers/ofString))]
          (is (nil? (.orElse (.firstValue (.headers resp) "content-encoding") nil)))
          (is (str/includes? (.body resp) "data: elements <p id=\"out\">plain</p>")))))))

(deftest handler-exceptions-do-not-kill-the-server
  (let [reported (atom [])]
    (with-server
      (fn [req]
        (if (= "/boom" (:uri req))
          (throw (ex-info "boom" {}))
          (resp/text-response "ok")))
      {:on-error (fn [_t req] (swap! reported conj (:uri req)))}
      (fn [port]
        (testing "nothing was written yet, so the client gets a real 500"
          (is (= 500 (:status (GET port "/boom")))))
        (testing ":on-error sees the request that failed"
          (is (= ["/boom"] @reported)))
        (testing "and the next request is still served"
          (is (= "ok" (:body (GET port "/fine")))))))))

(deftest an-error-reporter-cannot-prevent-the-500
  (with-server
    (fn [_] (throw (ex-info "handler failed" {})))
    {:on-error (fn [_ _] (throw (ex-info "reporter failed" {})))}
    (fn [port]
      (let [{:keys [status headers body]} (GET port "/")]
        (is (= 500 status))
        (is (= "text/plain; charset=utf-8" (get headers "content-type")))
        (is (= "internal server error" body))))))

(def ^:dynamic *handler-binding* :root)

(deftest start-carries-dynamic-bindings-onto-server-workers
  (let [err    (StringWriter.)
        server (binding [*handler-binding* :captured
                         *err* err]
                 (http/start!
                  (fn [request]
                    (if (= "/boom" (:uri request))
                      (throw (ex-info "bound failure" {}))
                      (resp/text-response (name *handler-binding*))))
                  {:port 0}))
        port   (http/port server)]
    (try
      (is (= "captured" (:body (GET port "/binding"))))
      (is (= 500 (:status (GET port "/boom"))))
      (is (str/includes? (str err) "bound failure"))
      (finally
        (http/stop! server)))))

(deftest response-close-cannot-mask-a-body-write-failure
  (let [write-failure (ex-info "write failed" {})
        close-failure (ex-info "close failed" {})
        body          (reify proto/ResponseBody
                        (content-length [_] 1)
                        (streaming? [_] false)
                        (write-body! [_ _] (throw write-failure)))
        out           (proxy [OutputStream] []
                        (write [_])
                        (close [] (throw close-failure)))
        write!        (var-get (ns-resolve 'dj.web.http
                                           'write-body-and-close!))]
    (try
      (write! body out)
      (is false "expected the body write to fail")
      (catch Throwable actual
        (is (identical? write-failure actual))
        (is (= [close-failure] (vec (.getSuppressed actual))))))))

(deftest client-disconnect-error-policy-is-narrow-and-preserves-other-errors
  (testing "known direct socket-write failures are routine disconnects"
    (doseq [message ["Broken pipe"
                     "Connection reset"
                     "Connection reset by peer"
                     "An established connection was aborted by the software in your host machine"
                     "An existing connection was forcibly closed by the remote host"]]
      (is (http-error/client-disconnect? (java.io.IOException. message)) message)))

  (testing "type and message must both identify a disconnect"
    (is (not (http-error/client-disconnect? (java.io.IOException. "disk failed"))))
    (is (not (http-error/client-disconnect? (ex-info "Broken pipe" {}))))
    (is (not (http-error/client-disconnect?
              (ex-info "render failed" {}
                       (java.io.IOException. "Broken pipe")))))))

  (testing "the wrapper suppresses disconnects and delegates everything else unchanged"
    (let [seen    (atom [])
          policy (http-error/ignore-client-disconnects
                  (fn [error request] (swap! seen conj [error request])))
          dropped (java.io.IOException. "Broken pipe")
          failure (ex-info "render failed" {})
          request {:uri "/updates"}]
      (policy dropped request)
      (is (empty? @seen))
      (policy failure request)
      (is (= 1 (count @seen)))
      (is (identical? failure (ffirst @seen)))
      (is (identical? request (second (first @seen))))))

(deftest a-throw-mid-stream-cannot-become-a-500
  (testing "headers are already on the wire, so the connection just ends"
    (with-server
      (fn [_req]
        (ds/->sse-response
         nil {ac/on-open (fn [sse]
                           (ds/patch-elements! sse (h/html [:p#out "partial"]))
                           (throw (ex-info "boom" {})))}))
      {:on-error (fn [_t _req] nil)}          ; expected here; keep test output clean
      (fn [port]
        (let [{:keys [status body]} (GET port "/")]
          (is (= 200 status))                 ; the 200 went out before the throw
          (is (str/includes? body "partial")))))))
