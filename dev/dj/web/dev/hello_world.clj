(ns dj.web.dev.hello-world
  "Runnable current-state Datastar example. Start with:

      nix develop --command clojure -M:hello-world

  then open http://localhost:8080. Set PORT to choose another port."
  (:require [dj.web.datastar.assets :as assets]
            [dj.web.datastar.fused :as fused]
            [dj.web.datastar.subscribed :as subscribed]
            [dj.web.html :as html]
            [dj.web.http :as http]
            [dj.web.http.response :as response]))

(def state (atom {:count 0}))
(def subscriptions (subscribed/registry))

(defn render-main! [writer]
  (fused/write-patch-elements!
   writer
   (html/html
    [:main#app
     [:h1 "dj.web hello world"]
     [:p#count "Count: " (:count @state)]
     [:button {:type "button" :data-on:click "@post('/increment')"}
      "Increment"]])))

(defn page []
  (html/page
   [:html
    [:head
     [:meta {:charset "utf-8"}]
     [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
     [:title "dj.web hello world"]
     (assets/script)]
    [:body {:data-init "@get('/updates', {retry: 'always', retryMaxCount: 1000})"}
     [:main#app
      [:h1 "dj.web hello world"]
      [:p#count "Count: " (:count @state)]
      [:button {:type "button" :data-on:click "@post('/increment')"}
       "Increment"]]]]))

(defn app [request]
  (case [(:request-method request) (:uri request)]
    [:get "/"] (response/html-response (page))
    [:get "/updates"]
    (subscribed/subscription-response request subscriptions #'render-main!)
    [:post "/increment"]
    (do (swap! state update :count inc)
        (subscribed/mark-dirty! subscriptions)
        {:status 204})
    response/not-found))

(defn -main [& _]
  (let [port   (parse-long (or (System/getenv "PORT") "8080"))
        server (http/start! #'app {:port port})]
    (.addShutdownHook (Runtime/getRuntime)
                      (Thread. #(http/stop! server)))
    (println (str "dj.web hello world: http://localhost:" (http/port server)))
    @(promise)))
