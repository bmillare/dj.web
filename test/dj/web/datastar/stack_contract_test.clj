(ns dj.web.datastar.stack-contract-test
  "Runs the named population contract against implementations that exist today.

  New population members add one adapter map and one `deftest` here. The shared
  assertions remain in `stack-contract`; implementation-specific tests and the
  executable negative controls remain beside the source seam they explain."
  (:require [clojure.test :refer [deftest]]
            [dj.web.datastar :as datastar]
            [dj.web.datastar.fused :as fused]
            [dj.web.datastar.reference :as reference]
            [dj.web.datastar.stack-contract :as contract]
            [dj.web.datastar.wire :as wire]
            [dj.web.sse :as sse]
            [starfederation.datastar.clojure.adapter.common :as adapter]
            [starfederation.datastar.clojure.protocols :as protocols])
  (:import [java.io StringWriter]))

(def current-stack
  {:name                    :current-extracted-stack
   :supported-element-opts #{wire/selector wire/patch-mode}
   :supported-signal-opts  #{}
   :compression            #{:gzip}
   :element-lines          wire/->patch-elements
   :signal-lines           wire/->patch-signals
   :frame                   (fn [event-type data-lines opts]
                              (str (sse/write-event! (StringBuilder.)
                                                     event-type data-lines opts)))
   :comment                 (fn [text]
                              (str (sse/write-comment! (StringBuilder.) text)))
   :normalize-event-opts    sse/normalize-event-opts
   :get-signals             wire/get-signals
   :signals                 datastar/signals
   :datastar-request?       wire/datastar-request?
   :headers                 sse/headers
   :response                (fn [request {:keys [on-open on-close on-exception
                                                 compression]}]
                              (datastar/->sse-response
                               request
                               (cond-> {adapter/on-open on-open}
                                 on-close     (assoc adapter/on-close on-close)
                                 on-exception (assoc adapter/on-exception on-exception)
                                 (= :gzip compression)
                                 (assoc adapter/write-profile adapter/gzip-profile))))
   :patch-elements!         datastar/patch-elements!
   :patch-signals!          datastar/patch-signals!
   :close!                  protocols/close-sse!})

(deftest current-extracted-stack-satisfies-the-population-contract
  (contract/assert-contract! current-stack))

(def reference-stack
  {:name                    :de-complected-reference
   :supported-element-opts #{wire/selector wire/patch-mode}
   :supported-signal-opts  #{}
   :compression            #{}
   :element-lines          wire/->patch-elements
   :signal-lines           wire/->patch-signals
   :frame                   (fn [event-type data-lines opts]
                              (str (sse/write-event! (StringBuilder.)
                                                     event-type data-lines opts)))
   :comment                 (fn [text]
                              (str (sse/write-comment! (StringBuilder.) text)))
   :normalize-event-opts    sse/normalize-event-opts
   :get-signals             reference/get-signals
   :signals                 reference/signals
   :datastar-request?       reference/datastar-request?
   :headers                 sse/headers
   :response                reference/response
   :patch-elements!         reference/patch-elements!
   :patch-signals!          reference/patch-signals!
   :close!                  reference/close!})

(deftest de-complected-reference-satisfies-the-population-contract
  (contract/assert-contract! reference-stack))

(defn- emitted [write! payload opts]
  (let [writer (StringWriter.)]
    (write! writer payload opts)
    (str writer)))

(def fused-stack
  {:name                    :fused-one-shot
   :supported-element-opts #{wire/selector wire/patch-mode}
   :supported-signal-opts  #{}
   :compression            #{:gzip}
   :element-lines          wire/->patch-elements
   :signal-lines           wire/->patch-signals
   :frame                   (fn [event-type data-lines opts]
                              (str (sse/write-event! (StringBuilder.)
                                                     event-type data-lines opts)))
   :comment                 (fn [text]
                              (str (sse/write-comment! (StringBuilder.) text)))
   :normalize-event-opts    sse/normalize-event-opts
   :get-signals             fused/get-signals
   :signals                 fused/signals
   :datastar-request?       fused/datastar-request?
   :headers                 sse/headers
   :emit-element            #(emitted fused/patch-elements! %1 %2)
   :emit-signal             #(emitted fused/patch-signals! %1 %2)
   :io-failure-mode         :throws
   :response                (fn [request {:keys [on-open on-close status headers
                                                 compression]}]
                              (fused/response
                               request
                               (fn [writer]
                                 (try
                                   (on-open writer)
                                   (finally
                                     (when on-close (on-close writer)))))
                               {:status status :headers headers
                                :compression compression}))
   :patch-elements!         fused/patch-elements!
   :patch-signals!          fused/patch-signals!
   :close!                  (fn [writer]
                              (.close ^java.io.Closeable writer)
                              true)})

(deftest fused-one-shot-satisfies-the-population-contract
  (contract/assert-contract! fused-stack))
