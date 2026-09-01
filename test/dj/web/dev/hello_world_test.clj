(ns dj.web.dev.hello-world-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [dj.web.dev.hello-world :as hello]))

(deftest example-page-and-command-stay-wired-together
  (reset! hello/state {:count 0})
  (let [page (:body (hello/app {:request-method :get :uri "/"}))]
    (is (str/includes? page "data-on:click"))
    (is (str/includes? page "data-dj-web-mobile-resume"))
    (is (str/includes? page "data-on:dj-web-mobile-resume"))
    (is (str/includes? page "@get(&quot;/updates&quot;, {retry: &apos;always&apos;"))
    (is (str/includes? page "deep-mobile-resume compatibility adapter"))
    (is (str/includes? page "Count: 0")))
  (is (= 204 (:status (hello/app {:request-method :post :uri "/increment"}))))
  (is (= {:count 1} @hello/state)))
