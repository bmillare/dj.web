(ns dj.web.datastar.mobile-resume-test
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is testing]]
            [dj.web.datastar.mobile-resume :as mobile-resume]
            [dj.web.html :as html]))

(def expected-module-source
  "// Datastar v1.0.2 deep-mobile-resume compatibility adapter.\nlet pending = null;\nlet datastarRecoveryUntil = 0;\n\nfunction scheduleRecovery() {\n  if (document.visibilityState !== \"visible\" ||\n      performance.now() < datastarRecoveryUntil ||\n      pending !== null) return;\n\n  pending = window.setTimeout(() => {\n    pending = null;\n    if (document.visibilityState !== \"visible\" ||\n        performance.now() < datastarRecoveryUntil) return;\n\n    for (const owner of document.querySelectorAll(\"[data-dj-web-mobile-resume]\")) {\n      owner.dispatchEvent(new CustomEvent(\"dj-web-mobile-resume\", {bubbles: true}));\n    }\n  }, 50);\n}\n\ndocument.addEventListener(\"visibilitychange\", () => {\n  if (document.visibilityState !== \"visible\") return;\n\n  // Datastar's own v1.0.2 visibility driver synchronously aborts and reopens.\n  // Let that path own an ordinary visible transition instead of replacing twice.\n  datastarRecoveryUntil = performance.now() + 50;\n  if (pending !== null) {\n    clearTimeout(pending);\n    pending = null;\n  }\n});\n\nwindow.addEventListener(\"pageshow\", (event) => {\n  if (event.persisted || performance.now() > 1000) scheduleRecovery();\n});\n\ndocument.addEventListener(\"resume\", scheduleRecovery);\n")

(deftest subscription-attributes-pair-the-identical-durable-expression
  (let [attrs (mobile-resume/subscription-attrs "/updates?room=one&view=full")
        expression "@get(\"/updates?room=one&view=full\", {retry: 'always', retryMaxCount: 1000})"]
    (is (= {:data-dj-web-mobile-resume true
            :data-init expression
            "data-on:dj-web-mobile-resume" expression}
           attrs))
    (is (identical? (:data-init attrs)
                    (get attrs "data-on:dj-web-mobile-resume")))))

(deftest subscription-url-is-a-json-string-literal
  (doseq [url ["/quotes/\"double\"/'single'"
               "/back\\slash"
               "/query?a=1&b=<two>"
               "/unicode/λ/雪"
               "/literal/</script><script>alert(1)</script>"]]
    (testing url
      (let [{:keys [data-init] :as attrs}
            (mobile-resume/subscription-attrs url)]
        (is (= data-init (get attrs "data-on:dj-web-mobile-resume")))
        (is (= (str "@get(" (json/write-str url :escape-slash false)
                    ", {retry: 'always', retryMaxCount: 1000})")
               data-init)))))
  (testing "Hiccup keeps script-significant URL text inside an escaped attribute"
    (let [rendered (html/html
                    [:body (mobile-resume/subscription-attrs
                            "/literal/</script><script>alert(1)</script>")])]
      (is (not (.contains rendered "</script>")))
      (is (.contains rendered "&lt;/script&gt;")))))

(deftest subscription-url-validation-is-eager
  (doseq [invalid [nil "" :updates 42]]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"updates-url must be a non-empty string"
                          (mobile-resume/subscription-attrs invalid)))))

(deftest script-is-an-exact-static-inline-module
  (is (= (str "<script type=\"module\">" expected-module-source "</script>")
         (html/html (mobile-resume/script))))
  (is (not (.contains expected-module-source "</script>")))
  (is (= (str "<script nonce=\"a&amp;&quot;&lt;b\" type=\"module\">"
              expected-module-source "</script>")
         (html/html (mobile-resume/script {:nonce "a&\"<b"})))))

(deftest script-options-are-bounded-and-validated
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"options must be a map"
                        (mobile-resume/script nil)))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"unknown option"
                        (mobile-resume/script {:src "/resume.js"})))
  (doseq [nonce [nil "" :nonce 7]]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"nonce must be a non-empty string"
                          (mobile-resume/script {:nonce nonce})))))

(deftest server-subscription-namespace-does-not-depend-on-mobile-resume
  (let [source (slurp "src/dj/web/datastar/subscribed.clj")]
    (is (not (.contains source "mobile-resume")))))
