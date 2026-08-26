(ns dj.web.datastar.reference-test
  (:require [clojure.test :refer [deftest is testing]]
            [dj.web.datastar.reference :as reference]
            [dj.web.http.protocols :as http-proto]
            [dj.web.sse :as sse])
  (:import [java.io ByteArrayOutputStream IOException OutputStream]))

(deftest close-claim-is-atomic-before-a-writer-exists
  (let [opened  (atom 0)
        closed  (atom 0)
        stream  (:body (reference/response
                        nil {:on-open  (fn [_] (swap! opened inc))
                             :on-close (fn [_] (swap! closed inc))}))
        start   (promise)
        closers (mapv (fn [_]
                        (future @start (reference/close! stream)))
                      (range 32))]
    (deliver start true)
    (testing "one CAS claimant owns close and its callback"
      (is (= 1 (count (filter true? (mapv deref closers)))))
      (is (= 1 @closed)))
    (testing "a later response write does not resurrect the closed stream"
      (let [out (ByteArrayOutputStream.)]
        (http-proto/write-body! stream out)
        (is (zero? @opened))
        (is (zero? (.size out)))))))

(deftest close-callback-runs-outside-the-writer-lock
  (let [callback-result (promise)
        stream          (:body
                         (reference/response
                          nil {:on-open  reference/close!
                               :on-close (fn [closed-stream]
                                           (deliver callback-result
                                                    (deref
                                                     (future
                                                       (reference/patch-elements!
                                                        closed-stream "<p>x</p>"))
                                                     1000 ::timeout)))}))]
    (http-proto/write-body! stream (ByteArrayOutputStream.))
    (is (false? (deref callback-result 1000 ::timeout)))))

(deftest invalid-event-options-bypass-the-connection-handler
  (let [handled (atom 0)
        body    (:body
                 (reference/response
                  nil {:on-exception (fn [& _] (swap! handled inc) true)
                       :on-open      #(reference/patch-elements! % "<p>x</p>")}))]
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"invalid options"
         (with-redefs [sse/normalize-event-opts
                       (fn [_] (throw (ex-info "invalid options" {})))]
           (http-proto/write-body! body (ByteArrayOutputStream.)))))
    (is (zero? @handled))))

(deftest close-errors-have-distinct-policies
  (testing "explicit close reports its writer failure after running on-close"
    (let [closed (atom 0)
          body   (:body
                  (reference/response
                   nil {:on-close (fn [_] (swap! closed inc))
                        :on-open  reference/close!}))
          out    (proxy [OutputStream] []
                   (write
                     ([x] nil)
                     ([bytes offset length] nil))
                   (close [] (throw (IOException. "explicit close failed"))))]
      (is (thrown-with-msg? IOException #"explicit close failed"
                            (http-proto/write-body! body out)))
      (is (= 1 @closed))))

  (testing "handled write cleanup suppresses only IO, not on-close failures"
    (let [body (:body
                (reference/response
                 nil {:on-exception (fn [& _] true)
                      :on-close     (fn [_]
                                      (throw (ex-info "callback failed" {})))
                      :on-open      #(reference/patch-elements! % "<p>x</p>")}))
          out  (proxy [OutputStream] []
                 (write
                   ([x] (throw (IOException. "write failed")))
                   ([bytes offset length]
                    (throw (IOException. "write failed"))))
                 (close [] (throw (IOException. "close failed"))))]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"callback failed"
                            (http-proto/write-body! body out)))))

  (testing "an unexpected close failure still cannot skip on-close"
    (let [writes (atom 0)
          closed (atom 0)
          body   (:body
                  (reference/response
                   nil {:on-exception (fn [& _] true)
                        :on-close     (fn [_] (swap! closed inc))
                        :on-open      #(reference/patch-elements! % "<p>x</p>")}))
          out    (proxy [OutputStream] []
                   (write
                     ([x]
                      (when (= 1 (swap! writes inc))
                        (throw (IOException. "write failed"))))
                     ([bytes offset length]
                      (when (= 1 (swap! writes inc))
                        (throw (IOException. "write failed")))))
                   (close [] (throw (IllegalStateException.
                                     "unexpected close failure"))))]
      (is (thrown-with-msg? IllegalStateException #"unexpected close failure"
                            (http-proto/write-body! body out)))
      (is (= 1 @closed)))))
