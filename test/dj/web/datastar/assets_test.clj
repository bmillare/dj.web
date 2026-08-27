(ns dj.web.datastar.assets-test
  (:require [clojure.test :refer [deftest is testing]]
            [dj.web.datastar.assets :as assets]))

(deftest script-leaves-asset-delivery-with-the-consumer
  (testing "the convenience default is version-pinned"
    (is (= [:script
            {:type "module"
             :src "https://cdn.jsdelivr.net/gh/starfederation/datastar@v1.0.2/bundles/datastar.js"}]
           (assets/script))))
  (testing "consumers can supply their own asset URL"
    (is (= [:script {:type "module" :src "/assets/datastar.js"}]
           (assets/script "/assets/datastar.js")))))
