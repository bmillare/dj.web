(ns dj.web.datastar.mobile-resume
  "Opt-in recovery for Datastar v1.0.2 subscriptions after deep mobile resume.

  A suspended browser can retain a Fetch that is still logically pending but no
  longer receives bytes. Datastar cannot retry a request that has not settled,
  so this adapter re-invokes the identical durable GET after visible lifecycle
  resume signals. It relies on v1.0.2's default same-method/same-URL request
  cancellation and must be conformance-tested when the supported client changes.

  This is isolated compatibility infrastructure, not part of the server-side
  subscription core. Applications opt in by putting `script` in the page head
  and merging `subscription-attrs` onto each subscription owner. Owners must use
  distinct URLs. The endpoint must be an idempotent current-state GET for which a
  fresh connection requires no replay cursor."
  (:require [clojure.data.json :as json]
            [dj.web.html :as html]))

(def ^:private module-source
  "// Datastar v1.0.2 deep-mobile-resume compatibility adapter.\nlet pending = null;\nlet datastarRecoveryUntil = 0;\n\nfunction scheduleRecovery() {\n  if (document.visibilityState !== \"visible\" ||\n      performance.now() < datastarRecoveryUntil ||\n      pending !== null) return;\n\n  pending = window.setTimeout(() => {\n    pending = null;\n    if (document.visibilityState !== \"visible\" ||\n        performance.now() < datastarRecoveryUntil) return;\n\n    for (const owner of document.querySelectorAll(\"[data-dj-web-mobile-resume]\")) {\n      owner.dispatchEvent(new CustomEvent(\"dj-web-mobile-resume\", {bubbles: true}));\n    }\n  }, 50);\n}\n\ndocument.addEventListener(\"visibilitychange\", () => {\n  if (document.visibilityState !== \"visible\") return;\n\n  // Datastar's own v1.0.2 visibility driver synchronously aborts and reopens.\n  // Let that path own an ordinary visible transition instead of replacing twice.\n  datastarRecoveryUntil = performance.now() + 50;\n  if (pending !== null) {\n    clearTimeout(pending);\n    pending = null;\n  }\n});\n\nwindow.addEventListener(\"pageshow\", (event) => {\n  if (event.persisted || performance.now() > 1000) scheduleRecovery();\n});\n\ndocument.addEventListener(\"resume\", scheduleRecovery);\n")

(defn- require-non-empty-string! [label value]
  (when-not (and (string? value) (not (empty? value)))
    (throw (ex-info (str label " must be a non-empty string")
                    {:value value}))))

(defn subscription-attrs
  "Attributes for a durable current-state GET subscription at `updates-url`.

  Merge these onto the element that owns the subscription. The initial and
  recovery expressions deliberately share the exact JSON-encoded URL spelling;
  Datastar v1.0.2 uses method plus exact URL for its default request cancellation.
  Multiple owners on one page must therefore use distinct URLs."
  [updates-url]
  (require-non-empty-string! "updates-url" updates-url)
  (let [expression (str "@get(" (json/write-str updates-url :escape-slash false)
                        ", {retry: 'always', retryMaxCount: 1000})")]
    {:data-dj-web-mobile-resume true
     :data-init expression
     "data-on:dj-web-mobile-resume" expression}))

(defn script
  "Inline ES-module tag that normalizes visible deep-resume lifecycle signals.

  The source is static and library-controlled. Pass `{:nonce \"...\"}` when a
  Content-Security-Policy authorizes this inline module by nonce. Policies that
  prohibit all inline scripts are outside this helper's first support boundary."
  ([] (script {}))
  ([opts]
   (when-not (map? opts)
     (throw (ex-info "mobile-resume/script options must be a map"
                     {:value opts})))
   (let [unknown (seq (remove #{:nonce} (keys opts)))]
     (when unknown
       (throw (ex-info (str "mobile-resume/script received unknown option "
                            (pr-str (first unknown)))
                       {:unknown (set unknown)}))))
   (when (contains? opts :nonce)
     (require-non-empty-string! "nonce" (:nonce opts)))
   [:script (cond-> {:type "module"}
              (contains? opts :nonce) (assoc :nonce (:nonce opts)))
    (html/raw module-source)]))
