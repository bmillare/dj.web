(ns dj.web.wire-matrix
  "The differential harness. Not a test namespace—it is the instrument the
  Datastar compatibility tests are built with.

  The method: we are rewriting the Datastar wire one layer at a time **while the
  official SDK stays on the classpath**, so every owned function has an oracle
  sitting right next to it. Each layer adds `(is (= (sdk-fn ...) (our-fn ...)))`
  over one shared, exhaustive option matrix. The diffs identify precisely which
  parts of the spec need closer inspection.

  The matrix is **exhaustive, not random**: a few thousand fixed cases that run
  in milliseconds, so a failure is reproducible and nameable instead of a seed.
  It is split into independent groups rather than one big cross product, because
  the axes do not interact — `->patch-elements` turns (html, opts) into data
  lines without ever seeing `id`/`retry`, and `write-event!` frames data lines
  without ever seeing a selector. Crossing all eight axes would multiply the
  case count twelvefold and prove nothing extra.

  This namespace and its two consumers are the ONLY places where ours and the
  SDK's appear together. Production has no indirection: no dynamic var, no
  protocol dispatch, no `with-redefs` picking an implementation. The adapter
  simply calls whichever function is ours today.

  Consumers, one per layer:
  - `dj.web.sse-test`            — layer 3, the envelopes (and 5, the headers)
  - `dj.web.datastar.wire-test`  — layer 2, the letters (and 6, the way in)

  The opts maps here are built from the **SDK's** option keywords on purpose,
  not ours. Ours are asserted equal to them, and an oracle that reads its inputs
  through the thing under test is not an oracle."
  (:require [starfederation.datastar.clojure.api.common :as sdk-common]
            [starfederation.datastar.clojure.consts :as sdk-consts]))

;; -----------------------------------------------------------------------------
;; The instrument
;; -----------------------------------------------------------------------------

(defn diffs
  "Run `ours` and `theirs` over every case and return the cases where they
  disagree, as `{:label :ours :theirs}`. Returning the diffs rather than
  asserting per case keeps one broken rule from printing 1,500 failures."
  [cases ours theirs]
  (into []
        (keep (fn [case*]
                (let [o (ours case*)
                      t (theirs case*)]
                  (when-not (= o t)
                    {:label (:label case*) :ours o :theirs t}))))
        cases))

(defn report
  "A failure message that names the first divergence, with the strings printed
  readably — a wire diff you have to squint at is a wire diff you skip."
  [ds]
  (when-let [{:keys [label ours theirs]} (first ds)]
    (format "%d/%d cases diverge. First: %s\n  ours:   %s\n  theirs: %s"
            (count ds) (count ds) (pr-str label) (pr-str ours) (pr-str theirs))))

(defn assoc-some
  "Set `k` only when `v` is non-nil, so an `:absent` axis value means the key is
  genuinely missing from the opts map — which is how callers write it."
  [m k v]
  (cond-> m (some? v) (assoc k v)))

;; -----------------------------------------------------------------------------
;; Layer 2 axes — the payloads
;; -----------------------------------------------------------------------------

(def elements-payloads
  "Every payload shape that has ever mattered. This axis is the **whole** of
  the wire extraction's surface — with the option lines deferred to the unsupported option surface, splitting and
  blank-suppression is all layer 2 does — so it is deliberately the widest axis
  in the matrix.

  The line-terminator group is the one that decides the slice. Java's
  `String.lines()` and `clojure.string/split-lines` agree on `\\n` and on
  `\\r\\n` and disagree on a lone `\\r`, and a lone `\\r` reaches us from every
  string we did not author — var docstrings, ledger content, fetched pages —
  all of which the original namespace-browser example renders into a `<pre>`.

  The blank group is the other rule: `.lines()` gives `[]` for `\"\"` but `[\"\"]`
  for `\"\\n\"`, so the payload-is-blank guard is a *separate* rule that only
  looks redundant. `:whitespace` and `:newline-only` are the cases that prove it.

  `:data-prefix` and `:colon` are payloads that look like SSE syntax themselves —
  they must survive as content, since `data: ` is added by layer 3 and a payload
  is never parsed."
  (array-map :blank          ""
             :whitespace     "  "
             :newline-only   "\n"
             :cr-only        "\r"
             :one-line       "<div>x</div>"
             :lf             "a\nb"
             :crlf           "a\r\nb"
             :cr             "a\rb"
             :mixed          "a\nb\rc\r\nd"
             :leading        "\na"
             :trailing       "a\n\n"
             :crlf-trailing  "a\r\n"
             :inner-blank    "a\n\nb"
             :tabs           "\ta\n\tb"
             :data-prefix    "data: not-a-field"
             :colon          ": not-a-comment"
             :unicode        "<p>an em — dash</p>"
             :astral         "<p>\uD83D\uDE80 rocket</p>"))

(def signals-payloads
  "The signals side of layer 2. Multi-line is the *normal* case here rather than
  an edge one — pretty-printed JSON is multi-line, and SSE has no way to carry a
  raw newline in a field."
  (array-map :blank      ""
             :whitespace " "
             :one        "{\"a\":1}"
             :lf         "{\n \"a\":1}"
             :crlf       "{\r\n \"a\":1}"
             :cr         "{\r \"a\":1}"
             :pretty     "{\n  \"a\": 1,\n  \"b\": [2, 3]\n}"
             :unicode    "{\"a\":\"—\"}"))

;; -----------------------------------------------------------------------------
;; Layer 2 axes — the option lines (the unsupported option surface, not implemented)
;; -----------------------------------------------------------------------------

(def selectors  (array-map :absent nil :blank "" :set "#x"))
(def modes      (array-map :absent nil :outer "outer" :append "append" :remove "remove"))
(def use-vts    (array-map :absent nil :true true :false false))
(def vt-sels    (array-map :absent nil :set "#y"))
(def namespaces (array-map :absent nil :html "html" :svg "svg"))

;; -----------------------------------------------------------------------------
;; Layer 3 axes
;; -----------------------------------------------------------------------------
;;
;; (Layer 6's axes are not separable — see `signals-request-cases` below, where
;; the method and the placement have to be crossed rather than listed.)

(def ids
  "`\"\"` is the interesting one: `write-event!` emits any truthy id, so a blank
  id only disappears because `normalize-event-opts` ran upstream. `false` is
  what that normalization actually produces for a dropped option — it is the
  value on the *default* path, not an edge case."
  (array-map :absent nil :dropped false :blank "" :set "e1"))

(def retries
  (array-map :absent nil :dropped false :zero 0 :datastar-default 1000 :custom 500))

(def data-line-samples
  "Representative data-line vectors, for the axes that frame them rather than
  produce them. `:empty` is what a blank payload produces, and `:blank-line` is
  a data line that is only the literal — both are frames the SSE spec still has
  to terminate correctly."
  (array-map :empty      []
             :one        ["elements <div>x</div>"]
             :opts+lines ["selector #x" "mode append" "elements a" "elements b"]
             :blank-line ["elements "]
             :unicode    ["elements <p>an em — dash</p>"]
             :signals    ["signals {\"a\":1}"]))

;; -----------------------------------------------------------------------------
;; The case sets
;; -----------------------------------------------------------------------------

(def element-cases
  "18 x 3 x 4 x 3 x 2 x 3 = 3,888 (payload, opts) pairs: the whole layer-2 option
  surface. Layer 3 uses these only as a generator of realistic, awkward data-line
  vectors; layer 2 diffs the *no-opts* subset below and asserts that everything
  else throws."
  (vec (for [[ek elements] elements-payloads
             [sk sel]      selectors
             [mk mode]     modes
             [vk use-vt]   use-vts
             [tk vt-sel]   vt-sels
             [nk ns*]      namespaces]
         {:label    {:elements ek :selector sk :mode mk
                     :use-vt vk :vt-selector tk :namespace nk}
          :elements elements
          :opts     (-> {}
                        (assoc-some sdk-common/selector sel)
                        (assoc-some sdk-common/patch-mode mode)
                        (assoc-some sdk-common/use-view-transition use-vt)
                        (assoc-some sdk-common/view-transition-selector vt-sel)
                        (assoc-some sdk-common/element-namespace ns*))})))

(def element-cases-no-opts
  "The subset the wire extraction implements: every payload, no option keys at all. This is
  what the original namespace-browser example calls twice per refresh."
  (vec (for [[ek elements] elements-payloads]
         {:label {:elements ek} :elements elements :opts {}})))

(def signals-cases
  (vec (for [[jk json] signals-payloads
             [ok oim]  (array-map :absent nil :true true :false false)]
         {:label   {:signals jk :only-if-missing ok}
          :signals json
          :opts    (assoc-some {} sdk-common/only-if-missing oim)})))

(def signals-cases-no-opts
  (vec (for [[jk json] signals-payloads]
         {:label {:signals jk} :signals json :opts {}})))

(def signals-request-cases
  "Layer 6 (the request-side extraction): 8 methods x 4 placements = 32 requests. The axes are
  deliberately crossed here, unlike everywhere else in this file, because for
  `get-signals` they genuinely interact — the method is what *selects* the
  placement, so the interesting cases are the mismatched ones (a `:get` whose
  payload is in the body, a `:post` whose payload is in the query param).

  `:body` is a shared `InputStream` **instance** rather than a fresh one per
  call: both implementations return the stream without reading it, so the
  assertion is identity, and a per-call stream would compare unequal even when
  both are right. It is never read, so being one-shot does not matter.

  `:head` and `:options` are here because the SDK's rule is a `case` over
  `(:get :delete)` with a **default**, not a lookup — everything that is not
  those two reads the body, including methods that cannot have one. Worth
  pinning: it is the kind of rule an independent implementation `cond`s
  differently by accident."
  (let [stream (java.io.ByteArrayInputStream. (.getBytes "{\"a\":1}" "UTF-8"))
        json   "{\"query\":\"dj http\",\"n\":2}"]
    (vec (for [m [:get :delete :post :put :patch :head :options nil]
               [pk qp body] [[:neither nil nil]
                             [:query   {"datastar" json} nil]
                             [:body    nil stream]
                             [:both    {"datastar" json} stream]]]
           {:label   {:method m :payload pk}
            :request (cond-> {:request-method m}
                       qp   (assoc :query-params qp)
                       body (assoc :body body))}))))

(def datastar-request-cases
  "Layer 6's other half (the request-side review): every header value worth distinguishing from
  the literal `\"true\"`. The rule is exact equality, so the cases that matter
  are the near misses — a wrong case, whitespace, a substring, and the
  comma-joined pair `dj.web.http/request-headers` produces when a header
  arrives twice.

  `:capitalized-name` is the one that is about *us* rather than the spec: our
  http layer lower-cases header names, so a lookup that did not would answer
  false for every action request. It is in the matrix because it is the mistake
  an independent implementation makes, not because the SDK does anything
  interesting with it."
  (vec (for [[k headers] (array-map
                          :absent       {}
                          :true         {"datastar-request" "true"}
                          :false        {"datastar-request" "false"}
                          :upper        {"datastar-request" "TRUE"}
                          :padded       {"datastar-request" " true"}
                          :substring    {"datastar-request" "untrue"}
                          :doubled      {"datastar-request" "true,true"}
                          :empty        {"datastar-request" ""}
                          :capitalized-name {"Datastar-Request" "true"}
                          :other-header {"accept" "text/event-stream"})]
         {:label   {:header k}
          :request {:request-method :get :headers headers}})))

;; -----------------------------------------------------------------------------
;; Layer 5 axes — the response headers
;; -----------------------------------------------------------------------------

(def protocols
  "`:absent` is not an edge case: `dj.web.http` always sets `:protocol`, but
  the SDK's rule treats nil as *below* HTTP/1.1 and callers hand this function a
  bare map often enough (`http-test/a-throw-mid-stream…` passes `nil` for the
  whole request) that the nil path is a live one.

  `:http-1.1` is where the ADR's text and every SDK's behaviour disagree, so it
  is the axis value the negative control turns on."
  (array-map :absent   nil
             :http-0.9 "HTTP/0.9"
             :http-1.0 "HTTP/1.0"
             :http-1.1 "HTTP/1.1"
             :http-2   "HTTP/2"
             :http-3   "HTTP/3"))

(def content-encodings
  "What the write profile compresses with. `:none` is the whole product today —
  nothing in `dj.web` passes a gzip profile — and `:br` is here because the
  header is a pass-through of whatever the profile declares, not a gzip flag."
  (array-map :none nil :gzip "gzip" :br "br"))

(def extra-headers
  "The caller's `:headers`, merged last. `:override` and `:connection` are the
  cases that assert *last* is what it means: a caller who sets `Cache-Control`
  or `Connection` wins over the rule, which is the SDK's contract and is
  documented on `sse/headers` rather than defended against."
  (array-map :absent     nil
             :empty      {}
             :added      {"X-Trace" "1"}
             :override   {"Cache-Control" "no-store"}
             :connection {"Connection" "close"}))

(def sse-header-cases
  "Layer 5 (the header extraction): 6 protocols x 3 codings x 5 caller-header shapes = 90
  header maps. Listed rather than crossed with anything else — headers are
  written once per response and never see a data line."
  (vec (for [[pk protocol] protocols
             [ek encoding] content-encodings
             [xk extra]    extra-headers]
         {:label    {:protocol pk :encoding ek :extra xk}
          :request  (cond-> {:request-method :get}
                      protocol (assoc :protocol protocol))
          :encoding encoding
          :extra    extra})))

(def frame-cases
  "6 x 4 x 5 x 2 = 240 (data-lines, id, retry, event-type) frames. These opts are
  deliberately NOT normalized — this asserts the raw framing rule (emit any
  truthy id/retry), and the normalization is asserted separately. Cross them and
  you would be testing the composition while believing you had tested the parts."
  (vec (for [[dk data-lines] data-line-samples
             [ik id]         ids
             [rk retry]      retries
             [ek event-type] (array-map :elements sdk-consts/event-type-patch-elements
                                        :signals  sdk-consts/event-type-patch-signals)]
         {:label      {:data-lines dk :id ik :retry rk :event-type ek}
          :event-type event-type
          :data-lines data-lines
          :opts       (-> {}
                          (assoc-some sdk-common/id id)
                          (assoc-some sdk-common/retry-duration retry))})))
