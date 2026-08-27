(ns dj.web.datastar.subscribed-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dj.web.datastar.fused :as fused]
            [dj.web.datastar.subscribed :as subscribed]
            [dj.web.http :as http]
            [dj.web.http.error :as http-error]
            [dj.web.http.protocols :as http-proto])
  (:import [java.io BufferedReader ByteArrayOutputStream InputStream InputStreamReader]
           [java.net Socket URI]
           [java.net.http HttpClient HttpRequest HttpResponse$BodyHandlers]
           [java.nio.charset StandardCharsets]
           [java.util.concurrent ArrayBlockingQueue CountDownLatch TimeUnit]
           [java.util.zip GZIPInputStream]))

(defn- write-response! [response out]
  (http-proto/write-body! (:body response) out))

(defn- open-sse! [port]
  (let [socket (Socket. "127.0.0.1" (int port))
        out    (.getOutputStream socket)
        reader (BufferedReader. (InputStreamReader. (.getInputStream socket)))]
    (.setSoTimeout socket 2000)
    (.write out (.getBytes "GET /updates HTTP/1.1\r\nHost: localhost\r\nAccept: text/event-stream\r\n\r\n"
                           "UTF-8"))
    (.flush out)
    (loop []
      (let [line (.readLine reader)]
        (when-not (and line (str/includes? line "data: elements"))
          (recur))))
    socket))

(defn- read-through!
  "Read bytes through `suffix`. This deliberately does not wrap a live gzip
  stream in a Reader: InflaterInputStream.available() may report data before a
  complete character/frame exists, which can deadlock buffered readers."
  [^InputStream in suffix]
  (let [suffix (.getBytes ^String suffix StandardCharsets/UTF_8)
        out    (ByteArrayOutputStream.)]
    (loop [matched 0]
      (let [b (.read in)]
        (when (neg? b) (throw (ex-info "stream ended before delimiter" {})))
        (.write out b)
        (let [matched (if (= b (bit-and 0xff (aget suffix matched)))
                        (inc matched)
                        (if (= b (bit-and 0xff (aget suffix 0))) 1 0))]
          (if (= matched (alength suffix))
            (.toString out StandardCharsets/UTF_8)
            (recur matched)))))))

(defn- open-gzip-sse! [port]
  (let [client   (HttpClient/newHttpClient)
        request  (-> (HttpRequest/newBuilder
                      (URI/create (str "http://127.0.0.1:" port "/updates")))
                     (.header "Accept" "text/event-stream")
                     (.header "Accept-Encoding" "gzip")
                     (.build))
        response (.send client request (HttpResponse$BodyHandlers/ofInputStream))
        encoding (some-> response .headers (.firstValue "Content-Encoding")
                         (.orElse nil))]
    (when-not (= "gzip" encoding)
      (throw (ex-info "server did not negotiate gzip" {:encoding encoding})))
    (GZIPInputStream. (.body response))))

(defn- eventually-zero? [registry]
  (loop [attempts 100]
    (cond
      (zero? (subscribed/active-count registry)) true
      (zero? attempts) false
      :else (do (Thread/sleep 5) (recur (dec attempts))))))

(defn- deterministic-scheduler
  "A monotonic clock whose wait executes scheduled mutations without sleeping.
  Event times are milliseconds; events at the wait boundary happen before the
  timeout is observed."
  []
  (let [now-ms (atom 0)
        events (atom [])
        clock  {:now #(* 1000000 @now-ms)
                :wait (fn [{:keys [^ArrayBlockingQueue dirty]} timeout-nanos]
                        (if (.poll dirty)
                          true
                          (let [through (+ @now-ms (/ timeout-nanos 1000000))
                                event   (first @events)]
                            (if (and event (<= (:at event) through))
                              (do (reset! now-ms (:at event))
                                  (swap! events subvec 1)
                                  ((:run event))
                                  (boolean (.poll dirty)))
                              (do (reset! now-ms through)
                                  false)))))}]
    {:now-ms now-ms
     :schedule! #(reset! events (vec %))
     :clock clock}))

(defn- test-subscription [clock]
  {:id :test
   :dirty (ArrayBlockingQueue. 1)
   :pending (atom nil)
   :clock clock})

(defn- test-registry [subscription]
  (subscribed/->Registry (atom {(:id subscription) subscription})
                         (:clock subscription)))

(defn- deadline-await [subscription opts]
  ((subscribed/deadline-policy opts) subscription))

(deftest subscription-renders-initial-state-before-awaiting
  (let [scheduler (deterministic-scheduler)
        registry  (subscribed/registry (:clock scheduler))
        renders   (atom 0)]
    (write-response!
     (subscribed/subscription-response
      nil registry
      (fn [writer]
        (swap! renders inc)
        (fused/write-patch-elements! writer "<main>ready</main>"))
      {:compression :identity
       :await (fn [_] (constantly :stop))})
     (ByteArrayOutputStream.))
    (is (= 1 @renders))
    (is (zero? @(:now-ms scheduler)))
    (is (zero? (subscribed/active-count registry)))))

(deftest live-gzip-subscription-is-readable-across-dirty-marks
  (let [registry (subscribed/registry)
        n        (atom 0)
        server   (http/start!
                  (fn [request]
                    (subscribed/subscription-response
                     request registry
                     #(fused/write-patch-elements!
                       % (str "<main id=\"app\">" @n "</main>"))))
                  {:port 0})
        ^InputStream gzip (open-gzip-sse! (http/port server))]
    (try
      (is (str/includes? (read-through! gzip "\n\n") ">0</main>"))
      (reset! n 1)
      (is (= 1 (subscribed/mark-dirty! registry)))
      (is (str/includes? (read-through! gzip "\n\n") ">1</main>"))
      (reset! n 2)
      (is (= 1 (subscribed/mark-dirty! registry)))
      (is (str/includes? (read-through! gzip "\n\n") ">2</main>"))
      (finally
        (.close gzip)
        (http/stop! server)))))

(deftest deadline-policy-settles-a-quiet-burst-after-the-last-change
  (let [scheduler    (deterministic-scheduler)
        subscription (test-subscription (:clock scheduler))
        registry     (test-registry subscription)
        mutate!      #(subscribed/mark-dirty! registry)
        opts          {:settle-ms 10 :max-wait-ms 100 :heartbeat-ms nil}]
    ((:schedule! scheduler) [{:at 5 :run mutate!}
                             {:at 9 :run mutate!}])
    (mutate!)
    (is (= :dirty (deadline-await subscription opts)))
    (is (= 19 @(:now-ms scheduler)))))

(deftest deadline-policy-makes-progress-under-continuous-mutation
  (let [scheduler    (deterministic-scheduler)
        subscription (test-subscription (:clock scheduler))
        registry     (test-registry subscription)
        mutate!      #(subscribed/mark-dirty! registry)
        opts          {:settle-ms 10 :max-wait-ms 100 :heartbeat-ms nil}]
    ((:schedule! scheduler)
     (mapv (fn [at] {:at at :run mutate!}) (range 5 101 5)))
    (mutate!)
    (is (= :dirty (deadline-await subscription opts)))
    (is (= 100 @(:now-ms scheduler))
        "an event exactly at max-wait is included without postponing progress")))

(deftest deadline-policy-treats-a-settle-boundary-change-as-part-of-the-burst
  (let [scheduler    (deterministic-scheduler)
        subscription (test-subscription (:clock scheduler))
        registry     (test-registry subscription)
        mutate!      #(subscribed/mark-dirty! registry)
        opts          {:settle-ms 10 :max-wait-ms 100 :heartbeat-ms nil}]
    ((:schedule! scheduler) [{:at 10 :run mutate!}])
    (mutate!)
    (is (= :dirty (deadline-await subscription opts)))
    (is (= 20 @(:now-ms scheduler)))))

(deftest deadline-policy-heartbeats-without-disturbing-pending-work
  (let [scheduler    (deterministic-scheduler)
        subscription (test-subscription (:clock scheduler))
        registry     (test-registry subscription)
        opts          {:settle-ms 50 :max-wait-ms 100 :heartbeat-ms 30}
        await         (subscribed/deadline-policy opts)]
    (subscribed/mark-dirty! registry)
    (is (= :heartbeat (await subscription)))
    (is (= 30 @(:now-ms scheduler)))
    (is (some? @(:pending subscription)))
    ;; A policy invocation follows the heartbeat write, so its new heartbeat
    ;; deadline is 60 ms while the original render deadline remains 50 ms.
    (is (= :dirty (await subscription)))
    (is (= 50 @(:now-ms scheduler)))
    (is (nil? @(:pending subscription)))))

(deftest deadline-policy-preserves-a-mutation-arriving-during-render
  (let [scheduler (deterministic-scheduler)
        registry  (subscribed/registry (:clock scheduler))
        renders   (atom 0)
        awaits    (atom 0)
        policy    (fn [opts]
                    (let [await (subscribed/deadline-policy opts)]
                      (fn [subscription]
                        (if (= 1 (swap! awaits inc))
                          (await subscription)
                          :stop))))]
    (write-response!
     (subscribed/subscription-response
      nil registry
      (fn [writer]
        (let [n (swap! renders inc)]
          (fused/write-patch-elements! writer (str "<main>" n "</main>"))
          (when (= n 1)
            (is (= 1 (subscribed/mark-dirty! registry))))))
      {:compression :identity
       :heartbeat-ms nil
       :settle-ms 10
       :max-wait-ms 100
       :await policy})
     (ByteArrayOutputStream.))
    (is (= 2 @renders))
    (is (= 10 @(:now-ms scheduler)))
    (is (zero? (subscribed/active-count registry)))))

(deftest deadline-policy-uses-differences-across-nano-time-wraparound
  (let [start   (- Long/MAX_VALUE 5000000)
        now     (atom start)
        clock   {:now #(long @now)
                 :wait (fn [{:keys [^ArrayBlockingQueue dirty]} timeout]
                         (if (.poll dirty)
                           true
                           (do (swap! now #(unchecked-add (long %) (long timeout)))
                               false)))}
        sub     (test-subscription clock)
        registry (test-registry sub)]
    (subscribed/mark-dirty! registry)
    (is (= :dirty
           (deadline-await sub
                           {:settle-ms 10
                            :max-wait-ms 100
                            :heartbeat-ms nil})))
    (is (= (unchecked-add start 10000000) @now))))

(deftest policies-validate-and-convert-configuration-at-construction
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"settle-ms must be a non-negative whole number"
                        (subscribed/deadline-policy
                         {:settle-ms -1 :max-wait-ms 100 :heartbeat-ms nil})))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"max-wait-ms must be a positive whole number"
                        (subscribed/deadline-policy
                         {:settle-ms 10 :max-wait-ms 0 :heartbeat-ms nil})))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"heartbeat-ms must be a positive whole number"
                        (subscribed/immediate-policy {:heartbeat-ms 0}))))

(deftest immediate-policy-conflates-dirty-during-render
  (let [registry (subscribed/registry)
        renders  (atom 0)
        awaits   (atom 0)
        policy   (fn [opts]
                   (let [await (subscribed/immediate-policy opts)]
                     (fn [subscription]
                       (if (= 1 (swap! awaits inc))
                         (await subscription)
                         :stop))))]
    (write-response!
     (subscribed/subscription-response
      nil registry
      (fn [writer]
        (let [n (swap! renders inc)]
          (fused/write-patch-elements! writer (str "<main>" n "</main>"))
          (when (= 1 n)
            (testing "many changes during a render leave exactly one later wake"
              (is (= 1 (subscribed/mark-dirty! registry)))
              (is (= 0 (subscribed/mark-dirty! registry)))
              (is (= 0 (subscribed/mark-dirty! registry)))))))
      {:compression :identity :await policy})
     (ByteArrayOutputStream.))
    (is (= 2 @renders))
    (is (zero? (subscribed/active-count registry)))))

(deftest one-mutation-fans-out-without-publishing-a-writer
  (let [registry (subscribed/registry)
        ready    (CountDownLatch. 2)
        changed  (CountDownLatch. 2)
        stop?    (atom false)
        owner    (fn []
                   (future
                     (write-response!
                      (subscribed/subscription-response
                       nil registry
                       (fn [writer]
                         (fused/write-patch-elements! writer "<main>now</main>")
                         (if @stop?
                           (.countDown changed)
                           (.countDown ready)))
                       {:compression :identity
                        :heartbeat-ms nil})
                      (ByteArrayOutputStream.))))
        owners   [(owner) (owner)]]
    (is (.await ready 2 TimeUnit/SECONDS) "both initial renders completed")
    (reset! stop? true)
    (is (= 2 (subscribed/mark-dirty! registry)))
    (is (.await changed 2 TimeUnit/SECONDS) "both owners rendered the mutation")
    (doseq [f owners] (future-cancel f))
    (is (loop [attempts 100]
          (cond
            (zero? (subscribed/active-count registry)) true
            (zero? attempts) false
            :else (do (Thread/sleep 5) (recur (dec attempts)))))
        "interrupted owners unregister")))

(deftest idle-owner-emits-a-client-ignored-heartbeat
  (let [registry (subscribed/registry)
        awaits   (atom [:heartbeat :stop])
        out      (ByteArrayOutputStream.)]
    (write-response!
     (subscribed/subscription-response
      nil registry
      #(fused/write-patch-elements! % "<main>ready</main>")
      {:compression :identity
       :await (fn [_]
                (fn [_]
                  (let [result (first @awaits)]
                    (swap! awaits subvec 1)
                    result)))})
     out)
    (is (str/includes? (.toString out "UTF-8") ": ping\n\n"))
    (is (zero? (subscribed/active-count registry)))))

(deftest render-failure-unregisters-and-preserves-the-original-error
  (let [registry (subscribed/registry)
        failure  (ex-info "render failed" {})]
    (try
      (write-response!
       (subscribed/subscription-response
        nil registry (fn [_] (throw failure))
        {:compression :identity})
       (ByteArrayOutputStream.))
      (is false "expected render failure")
      (catch Throwable actual
        (is (identical? failure actual))))
    (is (zero? (subscribed/active-count registry)))))

(deftest real-socket-error-policy-distinguishes-disconnect-from-render-failure
  (testing "an aborted long-lived response is routine and still unregisters"
    (let [registry (subscribed/registry)
          reported (promise)
          server   (http/start!
                    (fn [request]
                      (subscribed/subscription-response
                       request registry
                       #(fused/write-patch-elements! % "<main>ready</main>")
                       {:compression :identity :heartbeat-ms nil}))
                    {:port 0
                     :on-error (http-error/ignore-client-disconnects
                                (fn [error request]
                                  (deliver reported [error request])))})
          socket   (open-sse! (http/port server))]
      (try
        ;; An RST makes the next server write observe the departed peer now,
        ;; instead of waiting for TCP's orderly-close bookkeeping.
        (.setSoLinger socket true 0)
        (.close socket)
        (subscribed/mark-dirty! registry)
        (is (eventually-zero? registry))
        (is (= ::none (deref reported 100 ::none)))
        (finally
          (when-not (.isClosed socket) (.close socket))
          (http/stop! server)))))

  (testing "a render failure reaches the reporter with its identity intact"
    (let [registry (subscribed/registry)
          failure  (ex-info "render failed" {})
          fail?    (atom false)
          reported (promise)
          server   (http/start!
                    (fn [request]
                      (subscribed/subscription-response
                       request registry
                       (fn [writer]
                         (when @fail? (throw failure))
                         (fused/write-patch-elements! writer "<main>ready</main>"))
                       {:compression :identity :heartbeat-ms nil}))
                    {:port 0
                     :on-error (http-error/ignore-client-disconnects
                                (fn [error request]
                                  (deliver reported [error request])))})
          socket   (open-sse! (http/port server))]
      (try
        (reset! fail? true)
        (subscribed/mark-dirty! registry)
        (let [[actual request] (deref reported 2000 [::timeout nil])]
          (is (identical? failure actual))
          (is (= "/updates" (:uri request))))
        (is (eventually-zero? registry))
        (finally
          (.close socket)
          (http/stop! server))))))

(deftest interruption-is-an-orderly-stop-that-preserves-the-interrupt
  (let [registry (subscribed/registry)
        result   (deref
                  (future
                    (write-response!
                     (subscribed/subscription-response
                      nil registry identity
                      {:compression :identity
                       :await       (fn [_]
                                      (fn [_]
                                        (throw (InterruptedException. "stop"))))})
                     (ByteArrayOutputStream.))
                    ;; Read-and-clear it so the pooled worker is not returned with
                    ;; its interrupted status set.
                    (Thread/interrupted))
                  2000
                  ::timed-out)]
    (is (true? result) "the owner restored the interrupted status before return")
    (is (zero? (subscribed/active-count registry)))))
