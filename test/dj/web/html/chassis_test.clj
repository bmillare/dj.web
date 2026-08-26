(ns dj.web.html.chassis-test
  (:require [clojure.test :refer [deftest is testing]]
            [dj.web.html.chassis :as html]))

(defn representative-view [text]
  (html/template
   [:div#panel.card
    [:button {:data-on:click "@get('/next')"
              :disabled false}
     text]
    [:style (html/raw ".card > b { color: red; }")]]))

(deftest compiled-rendering-semantics
  (let [rendered (html/html (representative-view "<&> 'quoted'"))]
    (testing "text, attributes, tag sugar, booleans, and raw content"
      (is (= (str "<div id=\"panel\" class=\"card\">"
                  "<button data-on:click=\"@get(&apos;/next&apos;)\">"
                  "&lt;&amp;&gt; 'quoted'</button>"
                  "<style>.card > b { color: red; }</style></div>")
             rendered)))

    (testing "the string and Appendable sinks have identical semantics"
      (let [sink (StringBuilder.)]
        (is (identical? sink
                        (html/write-html! sink
                                          (representative-view "<&> 'quoted'"))))
        (is (= rendered (str sink)))))))

(deftest full-page-boundary
  (is (= "<!DOCTYPE html>\n<html><body>ok</body></html>"
         (html/page (html/template [:html [:body "ok"]])))))
