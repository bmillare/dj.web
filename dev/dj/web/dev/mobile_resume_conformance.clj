(ns dj.web.dev.mobile-resume-conformance
  "Deterministic browser fixture for the pinned Datastar mobile-resume seam.

  Start with DATASTAR_JS naming the v1.0.2 bundle:

      PORT=8091 DATASTAR_JS=/path/to/datastar.js
        nix develop --command clojure -M:mobile-resume-conformance

  This is deliberately a development fixture, not server-core behavior."
  (:require [clojure.data.json :as json]
            [dj.web.datastar.assets :as assets]
            [dj.web.datastar.fused :as fused]
            [dj.web.datastar.mobile-resume :as mobile-resume]
            [dj.web.datastar.subscribed :as subscribed]
            [dj.web.html :as html]
            [dj.web.http :as http]
            [dj.web.http.response :as response]))

(def state (atom 0))
(def opens (atom 0))
(def renders (atom 0))
(def subscriptions (subscribed/registry))

(defn render-main! [writer]
  (swap! renders inc)
  (fused/write-patch-elements!
   writer
   (html/html
    [:main#app {:style "min-height: 1200px"}
     [:h1 "mobile-resume conformance"]
     [:p#truth "Truth: " @state]
     [:label {:for "draft"} "Draft"]
     [:textarea#draft {:data-bind "draft"} ""]])))

(defn page []
  (html/page
   [:html
    [:head
     [:meta {:charset "utf-8"}]
     [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
     [:title "dj.web mobile-resume conformance"]
     (assets/script "/datastar.js")
     (mobile-resume/script)]
    [:body (mobile-resume/subscription-attrs "/updates")
     [:main#app {:style "min-height: 1200px"}
      [:h1 "mobile-resume conformance"]
      [:p#truth "Truth: " @state]
      [:label {:for "draft"} "Draft"]
      [:textarea#draft {:data-bind "draft"} ""]]]]))

(defn facts []
  (json/write-str {:state @state
                   :opens @opens
                   :renders @renders
                   :active (subscribed/active-count subscriptions)}))

(defn app [request]
  (case [(:request-method request) (:uri request)]
    [:get "/"] (response/html-response (page))
    [:get "/datastar.js"]
    (or (response/file-response (System/getenv "DATASTAR_JS") "text/javascript")
        response/not-found)
    [:get "/updates"]
    (do (swap! opens inc)
        (subscribed/subscription-response
         request subscriptions #'render-main! {:heartbeat-ms 200}))
    [:post "/dirty"]
    (do (swap! state inc)
        (subscribed/mark-dirty! subscriptions)
        {:status 204})
    [:get "/facts"]
    {:status 200
     :headers {"Content-Type" "application/json; charset=utf-8"
               "Cache-Control" "no-store"}
     :body (facts)}
    response/not-found))

(defn -main [& _]
  (when-not (response/file-response (System/getenv "DATASTAR_JS") "text/javascript")
    (throw (ex-info "DATASTAR_JS must name the pinned v1.0.2 bundle" {})))
  (let [port (parse-long (or (System/getenv "PORT") "8091"))
        server (http/start! #'app {:port port})]
    (.addShutdownHook (Runtime/getRuntime)
                      (Thread. #(http/stop! server)))
    (println (str "mobile-resume conformance: http://localhost:" (http/port server)))
    @(promise)))
