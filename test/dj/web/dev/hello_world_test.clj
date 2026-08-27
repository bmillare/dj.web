(ns dj.web.dev.hello-world-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [dj.web.dev.hello-world :as hello]))

(deftest example-page-and-command-stay-wired-together
  (reset! hello/state {:count 0})
  (let [page (:body (hello/app {:request-method :get :uri "/"}))]
    (is (str/includes? page "data-on:click"))
    (is (str/includes? page "@get(&apos;/updates&apos;, {retry: &apos;always&apos;"))
    (is (str/includes? page "Count: 0")))
  (is (= 204 (:status (hello/app {:request-method :post :uri "/increment"}))))
  (is (= {:count 1} @hello/state)))
