(ns dj.web.datastar
  "The adapter: the official Datastar Clojure SDK, plugged into
  `dj.web.http` instead of a web-server adapter.

  Datastar is two ideas. (a) **Signals** — a page-global reactive store declared
  in HTML (`data-signals` seeds it, `data-bind` two-way-binds an input,
  `data-text`/`data-show` react to it). (b) **SSE patches** — an action like
  `data-on:click=\"@get('/foo')\"` fires a request; the response is a
  `text/event-stream` of patch instructions that Datastar applies to the DOM
  (matched by element `id` unless you pass a selector). The backend owns the
  state; the browser is a patch applier. There is no client build step.

  This namespace IS the adapter that would otherwise be `dev.data-star.clojure/
  http-kit` or `/ring` — ~50 lines, adapted from the SDK's own generic ring
  adapter, so we keep the JDK server and skip the beta http-kit. It is the one
  place two protocols are deliberately braided, so it is the only place whose
  requires name both `dj.web.http.protocols` and `starfederation.*`:

  - `->sse-response` — an SSE response for `dj.web.http`, backed by a real
    SDK `SSEGenerator`. Everything in `starfederation.datastar.clojure.api`
    (`patch-elements!`, `patch-signals!`, `close-sse!`, ...) then works.
  - `signals` / `datastar-request?` — reading the request side.

  Deliberately NOT here, because the coupling would be invented rather than
  real: hiccup rendering (`dj.web.html`) and serving the client bundle
  (`dj.web.datastar.assets`).

  **This is a wrapper, not an abstraction layer.** SSE options are the SDK's own
  keys — require `starfederation.datastar.clojure.adapter.common :as ac` and
  write `ac/on-open`. We used to re-export them, which saved a require and made
  the dependency look smaller than it is; it isn't smaller, so it now shows.

  The SDK owns the parts that only bite on
  long-lived streams — a `ReentrantLock` per generator so two threads patching
  one connection can't interleave garbage, `on-open`/`on-close`/`on-exception`
  lifecycle so a client that walks away is noticed, optional gzip, and the
  multi-line encoding maintained upstream. Those are exactly the four failure
  modes we'd otherwise have to write ourselves. The SDK has zero transitive
  dependencies, so this costs one jar and no server choice.

  The lifecycle remains SDK-owned, while the small, spec-defined wire format is
  implemented here. This namespace is the only place those parts are composed:

  - `dj.web.sse` — the envelopes. `event:`/`id:`/`retry:`/`data:` framing,
    option normalization, and the response headers. Knows nothing about
    Datastar.
  - `dj.web.datastar.wire` — the letters. `elements `/`signals ` data
    lines, and `get-signals` on the way back in. Knows nothing about SSE.
  - here — transport, the lock, the lifecycle callbacks, and the two entry
    points (`patch-elements!`, `patch-signals!`) that join the two.

  The jar stays on the classpath as the oracle the differential tests diff
  against (`test/dj/web/sse_test.clj`, `test/dj/web/datastar/wire_test.clj`)
  and as the fallback for everything unimplemented: option
  lines, `patch-elements-seq!`, `execute-script!` and the rest are still one
  `d*/` call away and work unchanged, because our generator implements
  `p/SSEGenerator`.

  Reactive primitives are deliberately NOT here either: they are
  application-dependent. An atom plus a `refresh!` that patches is enough for small
  things; `dj.recorder` (a durable atom) or a debounced/batched timer are the
  same idea one size up. `dj.web.datastar.subscribed` provides the reusable
  long-lived current-state scheduler.

  Sketch:
    (defn handler [req]
      (let [{:keys [name]} (ds/signals req)]
        (ds/->sse-response
          req {ac/on-open (fn [sse]
                            (ds/patch-elements! sse (h/html [:p#out (str \"hi \" name)]))
                            (d*/close-sse! sse))})))"
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [dj.web.datastar.wire :as wire]
            [dj.web.http.protocols :as http-proto]
            [dj.web.sse :as sse]
            [starfederation.datastar.clojure.adapter.common :as ac]
            ;; NOT `…clojure.api :as d*`. As of the request-side review nothing here calls it —
            ;; layer 6 was the last user — and the remaining coupling is exactly
            ;; the three requires below: profiles/lifecycle, the 4-method
            ;; protocol, and `lock!`. The escape hatch this file's docstring
            ;; points at (`d*/patch-elements!` for an unimplemented option) is a
            ;; require the *caller* writes, which is the honest shape: the
            ;; fallback is available, and it is not something we depend on.
            [starfederation.datastar.clojure.protocols :as p]
            [starfederation.datastar.clojure.utils :as u])
  (:import [java.io BufferedWriter Closeable InputStream OutputStream Writer]
           [java.util.concurrent.locks ReentrantLock]))

;; -----------------------------------------------------------------------------
;; Signals in
;; -----------------------------------------------------------------------------
;;
;; **The the request-side extraction swap point** (the extraction design, layer 6) — and it is one line, because this
;; direction was already half ours: the SDK never parsed the JSON, so all it
;; contributed was *where to look*. What made the slice worth doing is the other
;; half, below `signals`' docstring: owning the lookup put the parse and its
;; failure mode in the same function, and the ADR MUST we had been failing
;; (return an error on invalid JSON) is a `try` you can only write on this side
;; of the boundary.
;;
;; Layer 6 is now ours **whole**: `datastar-request?` came with it (Brent's call,
;; the request-side review). The first draft left it behind on the grounds that it buys no spec
;; tracking — it reads a header, and unlike its four neighbours the string has no
;; entry in the SDK's `consts.clj` to pin against. That is true and it is not the
;; question. Leaving one `d*/` call inside an otherwise-owned layer costs every
;; future reader a "why that one?", and the whole point of owning a layer is that
;; the *next* design in it is unconstrained. Two lines is not a cost worth
;; reasoning about; a partially-owned layer is.

(defn- malformed-signals!
  [^String s why cause]
  (throw (ex-info (str "Datastar signals are " why ": "
                       (pr-str (cond-> s (< 120 (count s)) (subs 0 120))))
                  {:type ::malformed-signals :status 400 :raw s}
                  cause)))

(defn signals
  "The Datastar signals sent with this request, as a keywordized map (`nil` if
  none). `@get`/`@delete` carry them as a JSON `datastar` query param;
  `@post`/`@put`/`@patch` carry them as the JSON body. Signals whose name starts
  with `_` are client-local and never sent.

  Where they live on the request is `wire/get-signals` (the request-side extraction); the JSON parse
  is here, because the SDK deliberately does not have one — it imposes no JSON
  library, and `clojure.data.json` is our choice, not the wire's.

  **Malformed signals raise `ex-info`, not whatever `data.json` felt like
  throwing** — the ADR's one MUST for this layer, which the Clojure SDK cannot
  satisfy from where it stands. The ex-data is `{:type ::malformed-signals,
  :status 400, :raw <text>}`, and the `:status` is the point: the `datastar`
  query param is client-controlled, so a bad one is a *client* error. Today an
  unparseable param becomes `dj.web.http`'s blanket 500 with a stack trace
  on `*err*` — meaning anyone can make the server log a stack trace by editing a
  URL. A handler (or an `:on-error` that reads `ex-data`) can now answer 400
  instead. Nothing does yet; the classification has to exist before anything can.

  **Not returning `nil` on bad JSON**, tempting as it is: `nil` already means
  \"the client sent no signals\", and folding \"the client sent garbage\" into it
  is precisely the silent-failure mode this whole layer is written against (see
  `wire/reject-unsupported-opts!`). The caller would read `{}` and act as though
  the user had submitted an empty form.

  A **non-object** payload raises the same error. `\"5\"`, `\"[1,2]\"` and
  `\"\\\"x\\\"\"` all parse fine and are not stores; letting one through means the
  very next `(:query ...)` returns `nil` and the failure resurfaces somewhere
  with no idea what a signal is. `\"null\"` is not an error — it parses to `nil`,
  which is genuinely \"no signals\"."
  [request]
  (let [raw (wire/get-signals request)
        s   (if (instance? InputStream raw) (slurp raw) raw)]
    (when-not (str/blank? s)
      (let [parsed (try
                     (json/read-str s :key-fn keyword)
                     (catch Exception e
                       (malformed-signals! s "not valid JSON" e)))]
        (cond
          (nil? parsed)  nil
          (map? parsed)  parsed
          :else          (malformed-signals! s "not a JSON object" nil))))))

(def ^{:arglists '([request])} datastar-request?
  "True when the request came from a Datastar action rather than a plain page
  load — useful for handlers that serve both. The rule and its two gotchas (our
  headers are lower-cased, and a repeated header is comma-joined) are documented
  on `wire/datastar-request?`, which this is."
  wire/datastar-request?)

;; -----------------------------------------------------------------------------
;; The adapter: an SDK SSEGenerator that is also a ResponseBody
;; -----------------------------------------------------------------------------

(defn- write-with-temp-buffer!
  "Assemble one event into a fresh StringBuilder, then hand the writer a single
  String. Same shape as the SDK's `ac/->write-with-temp-buffer!`, which is what
  every stock profile except `gzip-buffered-writer-profile` uses: an
  `OutputStreamWriter` does not buffer, so appending field by field would put
  each `data: ` fragment through the encoder on its own."
  [^Writer writer event-type data-lines opts]
  (.append writer
           ^String (str (sse/write-event! (StringBuilder. 8192)
                                          event-type data-lines opts))))

(defn- ->send
  "Build the write function for one connection from its write profile, mirroring
  the SDK's generic ring adapter. 0-arity closes, 3-arity writes one event.

  **the framing extraction swap point** (the extraction design): the profile's `ac/write!` is no longer read.
  Every stock profile's `write!` is the same two decisions — buffer into a
  temporary StringBuilder or write straight through, then assemble the frame —
  and the buffering half of that is layer 4, which is already ours. So we make
  the buffering decision from the wrapped stream we just built (which is the
  thing the decision actually depends on) and call our own `sse/write-event!`
  for the frame. Bytes are unchanged either way; a buffered profile stops paying
  for a second copy it never needed.

  A custom profile that supplied its own `write!` would now be ignored. Nothing
  supplies one — and a profile that wants different *bytes* is asking for a
  different wire, not a different write."
  [^OutputStream raw-os opts]
  (let [{wrap   ac/wrap-output-stream
         custom ac/custom-flush} (ac/write-profile opts ac/basic-profile)
        wrapped (wrap raw-os)
        write!  (if (instance? BufferedWriter wrapped)
                  sse/write-event!             ; already buffered: write through
                  write-with-temp-buffer!)
        flush!  (or custom ac/simple-flush)]
    (fn send!
      ([] (.close ^Closeable wrapped))
      ([event-type data-lines event-opts]
       (write! wrapped event-type data-lines event-opts)
       (flush! wrapped raw-os)))))

;; `send!` doubles as the is-open? flag (nil => closed); a non-nil `on-close`
;; means that callback hasn't fired yet. Same trick as the SDK's ring adapter.
(deftype SSEGenerator [^:unsynchronized-mutable send!
                       ^ReentrantLock lock
                       ^:unsynchronized-mutable on-close-cb
                       ^:unsynchronized-mutable on-exception-cb
                       opts]
  http-proto/ResponseBody
  ;; Unknown length + streaming => chunked => events reach the browser as they
  ;; are flushed. An SSE stream genuinely cannot know its size in advance.
  (content-length [_] nil)
  (streaming? [_] true)
  (write-body! [this out]
    ;; Publish the initial state UNDER THE LOCK, even though nothing else can be
    ;; running yet: these are plain mutable fields, and another thread only gets
    ;; a happens-before edge to them by acquiring a lock we have released.
    ;; Without it, a thread handed `this` from inside `on-open` may legally read
    ;; a stale nil `send!` and silently drop events. (The SDK's ring adapter has
    ;; the same hole.)
    ;;
    ;; Hand-rolled rather than `u/lock!` because that expands to a `try`, and a
    ;; `try` in statement position inside a `deftype` method is compiled as a
    ;; closure — where `set!` on a mutable field is a compile error. Safe here:
    ;; everything that can throw is evaluated before we take the lock, and a
    ;; `set!` cannot, so there is nothing for a `finally` to protect.
    (let [send-fn (->send out opts)
          on-exc  (or (ac/on-exception opts) ac/default-on-exception)
          on-cls  (ac/on-close opts)
          ^ReentrantLock l lock]
      (.lock l)
      (set! send! send-fn)
      (set! on-exception-cb on-exc)
      (when on-cls (set! on-close-cb on-cls))
      (.unlock l))
    (.flush ^OutputStream out)              ; get the headers out immediately
    ;; The stream is live; hand it to user code. This call is synchronous — it
    ;; may block for as long as it likes (that is the point of virtual threads),
    ;; and the connection closes when it returns.
    ;;
    ;; The `finally` is what makes "the connection lives exactly as long as
    ;; on-open runs" true. `dj.web.http` closes the RAW stream after this
    ;; returns, which is not the same as closing the WRAPPED one — a gzip
    ;; profile would never get its trailer written — and would never fire
    ;; `on-close`, so a registry of live streams could not trust it. Closing
    ;; twice is a no-op, so an on-open that closed itself is unaffected.
    (try
      ((ac/on-open opts) this)
      (finally
        (p/close-sse! this))))

  p/SSEGenerator
  (send-event! [this event-type data-lines event-opts]
    (u/lock! lock
      (if send!
        (try
          (send! event-type data-lines event-opts)
          true
          (catch Exception e
            (when (on-exception-cb this e {:sse-gen    this
                                           :event-type event-type
                                           :data-lines data-lines
                                           :opts       event-opts})
              ;; Close the wrapped stream ourselves rather than letting
              ;; `close-sse!` do it, because we must not throw from here:
              ;; `ac/close-sse!` re-throws whatever the close threw, and on the
              ;; usual path here (broken pipe) writing a gzip trailer throws
              ;; again — turning a `false` return into an exception in the
              ;; caller's `on-open`. Best-effort, then hand off. (The SDK's ring
              ;; adapter nils `send!` first and so never closes the wrapper at
              ;; all; that leaks the gzip stream on exactly this path.)
              (let [close-io! send!]
                (set! send! nil)
                (try (close-io!) (catch Exception _ nil)))
              (p/close-sse! this))
            false))
        false)))

  (get-lock [_] lock)

  (close-sse! [this]
    (u/lock! lock
      (if (or send! on-close-cb)
        (try
          (ac/close-sse! #(when send! (send!))
                         #(when on-close-cb (on-close-cb this)))
          (finally
            (set! send! nil)
            (set! on-close-cb nil)))
        false)))

  (sse-gen? [_] true)

  Closeable
  (close [this] (p/close-sse! this)))

;; -----------------------------------------------------------------------------
;; The entry points — where the letters meet the envelopes
;; -----------------------------------------------------------------------------
;;
;; **This is the the wire extraction swap point** (the extraction design), and it is the whole of the
;; coupling. Until now every patch entered through `d*/patch-elements!`, which
;; did three things: build the data lines, normalize the event opts, and call
;; `p/send-event!`. We now own the first two, so we own the call that sequences
;; them — and the trap the plan warned about is right here in plain sight: drop
;; the `sse/normalize-event-opts` call and a blank `id` reaches the wire, with
;; no test that goes through a facade able to see it, because we ARE the facade
;; now.
;;
;; `p/send-event!` rather than reaching into the deftype: it takes the lock, so
;; two threads patching one connection cannot interleave garbage, and it is the
;; SDK protocol our generator already implements. That protocol is the entire
;; remaining coupling surface — four methods — and this is the one namespace
;; allowed to name it.

(defn patch-elements!
  "Patch `elements` (an HTML string) into the DOM over `sse-gen`. Returns true
  if the event was written, false if the connection was already closed.

  Same shape as `starfederation.datastar.clojure.api/patch-elements!`, built
  from `wire/->patch-elements` + `sse/normalize-event-opts` instead. `opts` are
  the SSE event options (`sse/id`, `sse/retry-duration`); the Datastar element
  options are the unsupported option surface and `wire/->patch-elements` throws on them rather than
  ignoring them.

  Unlike the SDK we do not wrap a failure in an `ex-info` — for the same reason
  `sse/write-event!` does not. `p/send-event!` already catches, hands the
  exception to `on-exception`, and answers false; an `ex-info` on top of that
  would only obscure the `IOException` that the default handler tests for."
  ([sse-gen elements] (patch-elements! sse-gen elements {}))
  ([sse-gen elements opts]
   (p/send-event! sse-gen
                  wire/event-type-patch-elements
                  (wire/->patch-elements elements opts)
                  (sse/normalize-event-opts opts))))

(defn patch-signals!
  "Patch `signals` (a JSON **string** — the caller owns the encoder) into the
  client's reactive store over `sse-gen`. Returns true if the event was written,
  false if the connection was already closed.

  Signals are merged, not replaced, and the browser's write path is a strict
  `!==` per key — an unchanged value propagates nothing at all, even as a second
  SSE event seconds later (a pinned-client inspection). So this is idempotent by value on the client,
  which is what makes conflating repeated patches safe and what makes a
  \"fire an effect\" signal need a nonce.

  `onlyIfMissing` is the unsupported option surface; `wire/->patch-signals` throws on it."
  ([sse-gen signals] (patch-signals! sse-gen signals {}))
  ([sse-gen signals opts]
   (p/send-event! sse-gen
                  wire/event-type-patch-signals
                  (wire/->patch-signals signals opts)
                  (sse/normalize-event-opts opts))))

;; -----------------------------------------------------------------------------
;; Layer 5 — the response headers, and the half of them that is layer 4's
;; -----------------------------------------------------------------------------
;;
;; **The the header extraction swap point** (the extraction design). The headers themselves are
;; `sse/headers` — `Cache-Control`, `Content-Type`, the `Connection` rule — and
;; nothing there needs this namespace. What needs this namespace is the one
;; header that is not about SSE at all: `Content-Encoding`, which is a fact
;; about the *transport*, i.e. about layer 4.

(defn- negotiated-write-profile
  "The write profile this connection will actually use: the one in `opts`,
  unless it compresses with a coding `request` has refused — then the plain
  `ac/basic-profile`.

  **This is the bug the header extraction exists to fix, and the reason layer 5 could not be
  just a header.** `ac/headers` derives `Content-Encoding` from the write
  profile and never reads `Accept-Encoding`; it takes the request only to check
  the protocol version. So `ac/gzip-profile` gzips a client that told us not to.

  The tempting fix — suppress the header when the client refused — is strictly
  worse than the bug. `wrap-output-stream` would still wrap the socket in a
  `GZIPOutputStream`, so the client would receive gzip bytes with nothing saying
  so: undeclared compression instead of unwanted compression. **The header and
  the wrapping are two halves of one decision**, and the SDK's arrangement can
  only express them as two, because the half that has the request
  (`ac/headers`) and the half that has the stream (the adapter's `->send`) are
  in different functions and read the profile independently. Owning the layer is
  what lets the decision be taken once, here, and handed to both: the profile we
  return is the one `->sse-response` puts in the generator's opts *and* the one
  it reads the header off.

  RFC 9110 §12.5.3 says to answer without a content coding rather than 406 when
  nothing on offer is acceptable, which is what falling back to the basic
  profile does. The fallback is total: the basic profile is byte-identical to
  the gzip one modulo compression, so a downgraded connection is a correct
  connection, not a degraded one.

  Browsers normally advertise gzip. Programmatic clients such as
  `java.net.http.HttpClient` and `curl` may not request or automatically
  decode it, so the negotiation must remain explicit."
  [request opts]
  (let [profile  (ac/write-profile opts ac/basic-profile)
        encoding (ac/content-encoding profile)]
    (if (or (nil? encoding) (sse/acceptable-encoding? request encoding))
      profile
      ac/basic-profile)))

(defn ->sse-response
  "An SSE response for `dj.web.http`. The connection lives for exactly as
  long as the `on-open` callback runs — return from it (or `d*/close-sse!`) and
  the stream ends.

  Opts are the SDK's own keys, from
  `starfederation.datastar.clojure.adapter.common` (`:as ac` below):
  - `ac/on-open` (required) `(fn [sse-gen])` — send patches from here.
  - `ac/on-close` `(fn [sse-gen])` — the client went away, or you closed.
  - `ac/on-exception` `(fn [sse-gen e ctx])` — defaults to closing on IOException.
  - `ac/write-profile` — e.g. `ac/gzip-profile`. Downgraded to
    `ac/basic-profile` if the request refused its content coding; see
    `negotiated-write-profile`.
  - `:status`, `:headers` — merged over the SSE headers."
  [request {:keys [status] :as opts}]
  {:pre [(ac/on-open opts)]}
  (let [profile (negotiated-write-profile request opts)]
    {:status  (or status 200)
     :headers (sse/headers request (ac/content-encoding profile) (:headers opts))
     :body    (SSEGenerator. nil (ReentrantLock.) nil nil
                             (assoc opts ac/write-profile profile))}))
