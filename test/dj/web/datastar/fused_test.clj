(ns dj.web.datastar.fused-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dj.web.datastar.fused :as fused]
            [dj.web.datastar.wire :as wire])
  (:import [java.io ByteArrayInputStream ByteArrayOutputStream]
           [java.nio.charset StandardCharsets]
           [java.util.zip GZIPInputStream]))

(deftest caller-controls-the-visibility-boundary
  (let [flushes (atom 0)
        out     (proxy [ByteArrayOutputStream] []
                  (flush []
                    (swap! flushes inc)
                    (proxy-super flush)))
        writer  (fused/identity-writer out)]
    (fused/write-patch-signals! writer "{\"n\":1}")
    (fused/write-patch-elements! writer "<p>one</p>")
    (fused/write-comment! writer "ping")
    (is (zero? @flushes))
    (is (zero? (.size out)))

    (fused/flush! writer)
    (is (= 1 @flushes))
    (let [body (.toString out StandardCharsets/UTF_8)]
      (is (str/includes? body "data: signals {\"n\":1}"))
      (is (str/includes? body "data: elements <p>one</p>"))
      (is (str/includes? body ": ping\n\n")))

    (fused/patch-elements! writer "<p>two</p>")
    (is (= 2 @flushes))))

(deftest gzip-writer-finishes-one-readable-member
  (let [out      (ByteArrayOutputStream.)
        writer   (fused/gzip-writer out)
        expected (str "event: datastar-patch-elements\n"
                      "data: elements <p>gzip</p>\n\n")]
    (try
      (fused/write-patch-elements! writer "<p>gzip</p>")
      (fused/flush! writer)
      (testing "sync flush makes the completed batch decodable before close"
        (with-open [in (GZIPInputStream.
                        (ByteArrayInputStream. (.toByteArray out)))]
          (is (= expected
                 (String. (.readNBytes in (count expected))
                          StandardCharsets/UTF_8)))))
      (finally
        (.close writer)))

    (testing "close finishes the gzip trailer"
      (let [body (with-open [in (GZIPInputStream.
                                 (ByteArrayInputStream. (.toByteArray out)))]
                   (slurp in))]
        (is (= expected body))))))

(deftest response-couples-negotiation-header-and-writer-profile
  (testing "gzip selection varies on the request's advertised coding"
    (let [accepted (fused/response nil identity {:compression :gzip})
          refused  (fused/response {:headers {"accept-encoding" "identity"}}
                                   identity {:compression :gzip})]
      (is (= "gzip" (get-in accepted [:headers "Content-Encoding"])))
      (is (= "Accept-Encoding" (get-in accepted [:headers "Vary"])))
      (is (nil? (get-in refused [:headers "Content-Encoding"])))
      (is (= "Accept-Encoding" (get-in refused [:headers "Vary"])))))

  (testing "identity stays the default and unknown profiles fail visibly"
    (is (nil? (get-in (fused/response nil identity) [:headers "Content-Encoding"])))
    (is (thrown? clojure.lang.ExceptionInfo
                 (fused/response nil identity {:compression :brotli})))))

(deftest fused-options-fail-before-any-frame-is-written
  (doseq [opts [{:selector "#app"}
                {:mode "append"}
                {:d*.elements/typo true}
                {wire/patch-mode "morph"}]]
    (let [writer (java.io.StringWriter.)]
      (is (thrown? clojure.lang.ExceptionInfo
                   (fused/write-patch-elements! writer "<main></main>" opts)))
      (is (= "" (str writer)))))
  (let [writer (java.io.StringWriter.)]
    (is (thrown? clojure.lang.ExceptionInfo
                 (fused/write-patch-signals! writer "{}" {:selector "#app"})))
    (is (= "" (str writer)))))
