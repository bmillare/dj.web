(ns dj.web.datastar.stack-contract
  "Shared observable contract for every member of the Datastar stack population.

  A stack is a map of small adapter functions (see `assert-contract!`). The map
  is deliberately a test seam, not a production protocol: implementations may
  have unrelated ownership and internal APIs while this namespace compares the
  values, bytes, HTTP behavior, and lifecycle visible at their boundaries."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [is testing]]
            [dj.web.http :as http]
            [dj.web.http.protocols :as proto]
            [dj.web.wire-matrix :as matrix]
            [starfederation.datastar.clojure.adapter.common :as sdk-adapter]
            [starfederation.datastar.clojure.api :as sdk-api]
            [starfederation.datastar.clojure.api.common :as sdk-common]
            [starfederation.datastar.clojure.api.elements :as sdk-elements]
            [starfederation.datastar.clojure.api.signals :as sdk-signals]
            [starfederation.datastar.clojure.api.sse :as sdk-sse])
  (:import [java.io ByteArrayInputStream IOException OutputStream]
           [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
            HttpResponse$BodyHandlers]
           [java.nio.charset StandardCharsets]
           [java.util.zip GZIPInputStream]))

(def ^:private client (HttpClient/newHttpClient))

(defn- sdk-frame [{:keys [event-type data-lines opts]}]
  (str (sdk-sse/write-event! (StringBuilder.) event-type data-lines opts)))

(defn- sdk-headers [{:keys [request encoding extra]}]
  (sdk-adapter/headers
   request
   {sdk-adapter/write-profile
    {sdk-adapter/content-encoding encoding}
    :headers extra}))

(defn- supported-case? [supported opts]
  (every? supported (keys opts)))

(defn- request!
  ([method port path] (request! method port path nil nil))
  ([method port path headers body]
   (let [builder (HttpRequest/newBuilder
                  (URI/create (str "http://127.0.0.1:" port path)))
         _       (doseq [[k v] headers] (.header builder k v))
         request (.build (.method builder method
                                  (if body
                                    (HttpRequest$BodyPublishers/ofString body)
                                    (HttpRequest$BodyPublishers/noBody))))
         result  (.send client request (HttpResponse$BodyHandlers/ofString))
         hs      (.headers result)]
     {:status  (.statusCode result)
      :headers (into {} (for [[k vs] (.map hs)] [k (first vs)]))
      :body    (.body result)})))

(defn- with-server [handler opts f]
  (let [server (http/start! handler (merge {:port 0} opts))]
    (try
      (f (http/port server))
      (finally
        (http/stop! server)))))

(defn- assert-pure-contract!
  [{:keys [element-lines signal-lines frame comment normalize-event-opts
           get-signals signals datastar-request? headers
           supported-element-opts supported-signal-opts]
    :or {supported-element-opts #{} supported-signal-opts #{}}}]
  (testing "Datastar letters match the oracle over every supported option shape"
    (let [element-cases (filterv #(supported-case? supported-element-opts (:opts %))
                                 matrix/element-cases)
          signal-cases  (filterv #(supported-case? supported-signal-opts (:opts %))
                                 matrix/signals-cases)
          ediffs (matrix/diffs element-cases
                               #(element-lines (:elements %) (:opts %))
                               #(sdk-elements/->patch-elements (:elements %) (:opts %)))
          sdiffs (matrix/diffs signal-cases
                               #(signal-lines (:signals %) (:opts %))
                               #(sdk-signals/->patch-signals (:signals %) (:opts %)))]
      (is (empty? ediffs) (matrix/report ediffs))
      (is (empty? sdiffs) (matrix/report sdiffs))))

  (testing "unsupported Datastar options fail on presence, including nil"
    (doseq [k (remove supported-element-opts
                      [sdk-common/selector sdk-common/patch-mode
                       sdk-common/use-view-transition
                       sdk-common/view-transition-selector
                       sdk-common/element-namespace])]
      (is (thrown? clojure.lang.ExceptionInfo
                   (element-lines "<div>x</div>" {k nil}))
          (str "unsupported element option did not throw: " k)))
    (doseq [k (remove supported-signal-opts [sdk-common/only-if-missing])]
      (is (thrown? clojure.lang.ExceptionInfo
                   (signal-lines "{\"a\":1}" {k nil}))
          (str "unsupported signal option did not throw: " k))))

  (testing "SSE framing and option suppression match the oracle"
    (let [fdiffs (matrix/diffs matrix/frame-cases
                               #(frame (:event-type %) (:data-lines %) (:opts %))
                               sdk-frame)
          option-cases (vec (for [[ik id] matrix/ids
                                  [rk retry] matrix/retries
                                  :when (and (not= ik :dropped) (not= rk :dropped))]
                              {:label {:id ik :retry rk}
                               :opts  (-> {}
                                          (matrix/assoc-some sdk-common/id id)
                                          (matrix/assoc-some sdk-common/retry-duration retry))}))
          sdk-normalize @#'sdk-sse/rework-options
          odiffs (matrix/diffs option-cases
                               #(normalize-event-opts (:opts %))
                               #(sdk-normalize (:opts %)))]
      (is (empty? fdiffs) (matrix/report fdiffs))
      (is (empty? odiffs) (matrix/report odiffs))))

  (testing "an SSE heartbeat is a comment, not an event"
    (is (= ": ping\n\n" (comment "ping")))
    (is (not (str/includes? (comment "ping") "event:"))))

  (testing "request-side signal placement and Datastar header recognition match"
    (let [gdiffs (matrix/diffs matrix/signals-request-cases
                               #(get-signals (:request %))
                               #(sdk-signals/get-signals (:request %)))
          hdiffs (matrix/diffs matrix/datastar-request-cases
                               #(datastar-request? (:request %))
                               #(sdk-api/datastar-request? (:request %)))]
      (is (empty? gdiffs) (matrix/report gdiffs))
      (is (empty? hdiffs) (matrix/report hdiffs))))

  (testing "signals parse maps and reject malformed or non-object JSON as 400"
    (is (= {:query "dj"} (signals {:request-method :get
                                    :query-params {"datastar" "{\"query\":\"dj\"}"}})))
    (let [body (ByteArrayInputStream. (.getBytes "{\"n\":2}" StandardCharsets/UTF_8))]
      (is (= {:n 2} (signals {:request-method :post :body body}))))
    (doseq [raw ["notjson" "5" "[1,2]" "\"x\""]]
      (let [error (try
                    (signals {:request-method :get
                              :query-params {"datastar" raw}})
                    nil
                    (catch clojure.lang.ExceptionInfo e e))]
        (is (some? error) (str "expected a classified client error for " raw))
        (is (= 400 (:status (ex-data error)))))))

  (testing "SSE response headers match the 90-case protocol/coding matrix"
    (let [diffs (matrix/diffs matrix/sse-header-cases
                              #(headers (:request %) (:encoding %) (:extra %))
                              sdk-headers)]
      (is (empty? diffs) (matrix/report diffs)))))

(defn- assert-direct-write-contract!
  [{:keys [element-lines signal-lines frame normalize-event-opts
           emit-element emit-signal supported-element-opts supported-signal-opts]
    :or {supported-element-opts #{} supported-signal-opts #{}}}]
  (when emit-element
    (testing "the implementation's direct elements path emits exact composite bytes"
      (let [cases (filterv #(supported-case? supported-element-opts (:opts %))
                           matrix/element-cases)
            diffs (matrix/diffs
                   cases
                   #(emit-element (:elements %) (:opts %))
                   #(frame "datastar-patch-elements"
                           (element-lines (:elements %) (:opts %))
                           (normalize-event-opts (:opts %))))]
        (is (empty? diffs) (matrix/report diffs))))
    (testing "the direct path owns SSE option normalization and ordering too"
      (let [cases (vec (for [[ik event-id] (dissoc matrix/ids :dropped)
                             [rk retry]    (dissoc matrix/retries :dropped)]
                         {:label {:id ik :retry rk}
                          :opts  (-> {}
                                     (matrix/assoc-some sdk-common/id event-id)
                                     (matrix/assoc-some sdk-common/retry-duration retry))}))
            diffs (matrix/diffs
                   cases
                   #(emit-element "<p>x</p>" (:opts %))
                   #(frame "datastar-patch-elements"
                           (element-lines "<p>x</p>" (:opts %))
                           (normalize-event-opts (:opts %))))]
        (is (empty? diffs) (matrix/report diffs)))))
  (when emit-signal
    (testing "the implementation's direct signals path emits exact composite bytes"
      (let [cases (filterv #(supported-case? supported-signal-opts (:opts %))
                           matrix/signals-cases)
            diffs (matrix/diffs
                   cases
                   #(emit-signal (:signals %) (:opts %))
                   #(frame "datastar-patch-signals"
                           (signal-lines (:signals %) (:opts %))
                           (normalize-event-opts (:opts %))))]
        (is (empty? diffs) (matrix/report diffs))))))

(defn- assert-real-socket-contract!
  [{:keys [response patch-elements! patch-signals! close!]}]
  (testing "a complete response crosses a real socket with exact headers and frames"
    (with-server
      (fn [request]
        (response request
                  {:on-open (fn [stream]
                              (patch-signals! stream (json/write-str {:clicks 1}) {})
                              (patch-elements! stream "<pre id=\"doc\">a\rb\n\n</pre>" {})
                              (close! stream))}))
      nil
      (fn [port]
        (let [{:keys [status headers body]} (request! "GET" port "/")]
          (is (= 200 status))
          (is (= "text/event-stream" (get headers "content-type")))
          (is (= "no-cache" (get headers "cache-control")))
          (is (= "chunked" (get headers "transfer-encoding")))
          (is (nil? (get headers "content-length")))
          (is (= (str "event: datastar-patch-signals\n"
                      "data: signals {\"clicks\":1}\n\n"
                      "event: datastar-patch-elements\n"
                      "data: elements <pre id=\"doc\">a\n"
                      "data: elements b\n"
                      "data: elements \n"
                      "data: elements </pre>\n\n")
                 body))))))

  (testing "return, explicit close, and throw all clean up exactly once"
    (let [closed (atom [])
          done   (atom nil)]
      (with-server
        (fn [request]
          (response request
                    {:on-close (fn [_]
                                 (swap! closed conj (:uri request))
                                 (deliver @done true))
                     :on-open  (fn [stream]
                                 (case (:uri request)
                                   "/return" nil
                                   "/close"  (close! stream)
                                   "/throw"  (throw (ex-info "boom" {}))))}))
        {:on-error (fn [_ _] nil)}
        (fn [port]
          (doseq [path ["/return" "/close" "/throw"]]
            (reset! closed [])
            (reset! done (promise))
            (request! "GET" port path)
            (is (= true (deref @done 1000 ::timeout))
                (str "cleanup completed for " path))
            (is (= [path] @closed) (str "cleanup for " path))))))))

(defn- assert-io-failure-contract!
  [{:keys [response patch-elements! io-failure-mode]}]
  (testing "the lifecycle handler receives the original socket IOException"
    (let [failure (IOException. "broken socket")
          seen    (atom nil)
          result  (atom nil)
          out     (proxy [OutputStream] []
                    (write
                      ([x] (throw failure))
                      ([bytes offset length] (throw failure)))
                    (flush [] nil)
                    (close [] nil))
          body    (:body
                   (response nil
                             {:on-exception (fn [_stream error _context]
                                              (reset! seen error)
                                              true)
                              :on-open      (fn [stream]
                                              (reset! result
                                                      (patch-elements!
                                                       stream "<p>x</p>" {})))}))]
      (if (= :throws io-failure-mode)
        (let [thrown (try
                       (proto/write-body! body out)
                       nil
                       (catch Exception error error))]
          (is (identical? failure thrown)))
        (do
          (proto/write-body! body out)
          (is (false? @result))
          (is (identical? failure @seen)))))))

(defn- assert-compression-contract!
  [{:keys [compression response patch-elements!]}]
  (when (contains? compression :gzip)
    (testing "compression uses one decision for both header and body, and closes trailers"
      (with-server
        (fn [request]
          (response request
                    {:compression :gzip
                     :on-open (fn [stream]
                                (patch-elements! stream "<p>gzip</p>" {}))}))
        nil
        (fn [port]
          (let [request (.build (HttpRequest/newBuilder
                                 (URI/create (str "http://127.0.0.1:" port "/"))))
                result  (.send client request (HttpResponse$BodyHandlers/ofInputStream))
                body    (slurp (GZIPInputStream. (.body result)))]
            (is (= "gzip" (.orElse (.firstValue (.headers result)
                                                "content-encoding") nil)))
            (is (str/includes? body "data: elements <p>gzip</p>")))))
      (with-server
        (fn [request]
          (response request
                    {:compression :gzip
                     :on-open (fn [stream]
                                (patch-elements! stream "<p>plain</p>" {}))}))
        nil
        (fn [port]
          (let [{:keys [headers body]} (request! "GET" port "/"
                                                 {"Accept-Encoding" "identity"} nil)]
            (is (nil? (get headers "content-encoding")))
            (is (str/includes? body "data: elements <p>plain</p>"))))))))

(defn assert-contract!
  "Assert the shared population contract for one adapted implementation.

  Required keys:
  `:element-lines`, `:signal-lines`, `:frame`, `:comment`,
  `:normalize-event-opts`, `:get-signals`, `:signals`, `:datastar-request?`,
  `:headers`, `:response`, `:patch-elements!`, `:patch-signals!`, and `:close!`.

  `:supported-element-opts` and `:supported-signal-opts` are sets of Datastar
  option keys (default empty). `:compression` is a capability set containing
  `:gzip` when offered; an uncompressed implementation has no compression
  obligation. A fused member supplies `:emit-element` / `:emit-signal` so its
  direct composite path, rather than only the shared pure seams, is checked over
  the exhaustive payload and option matrices. `:io-failure-mode :throws` names
  lexical failure propagation in place of a lifecycle callback."
  [{:keys [name] :as implementation}]
  (testing (str "Datastar stack contract: " name)
    (assert-pure-contract! implementation)
    (assert-direct-write-contract! implementation)
    (assert-real-socket-contract! implementation)
    (assert-io-failure-contract! implementation)
    (assert-compression-contract! implementation)))
