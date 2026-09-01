# dj.web

A small server-driven web stack for Clojure: JDK HTTP, generic Server-Sent
Events, Datastar transports, and opinionated current-state subscriptions.

> Status: early. The extracted stack is working and extensively tested, but the
> public API may still change.

`dj.web` is for applications where the backend owns authoritative state and the
browser applies HTML and signal patches. It uses the JDK's built-in
`HttpServer`, Java 21 virtual threads, and plain request/response maps—there is
no Ring or external web-server dependency.

## What is included

- `dj.web.http` — a small JDK HTTP server with virtual-thread request handling.
- `dj.web.http.response` — HTML, text, file, and not-found response values.
- `dj.web.sse` — generic SSE framing, headers, comments, and encoding negotiation.
- `dj.web.datastar` — compatibility transport for the official Datastar Clojure SDK.
- `dj.web.datastar.reference` — a readable, independent Datastar transport.
- `dj.web.datastar.fused` — direct, scoped writing with identity or streaming gzip.
- `dj.web.datastar.subscribed` — long-lived current-state rendering with burst
  conflation, bounded settling, heartbeats, and connection cleanup.
- `dj.web.html` and `dj.web.html.chassis` — small Hiccup and compiled Chassis
  rendering boundaries.
- `dj.web.datastar.assets` — construct a script tag for a pinned CDN bundle or
  a consumer-supplied URL.
- `dj.web.datastar.mobile-resume` — optional Datastar v1.0.2 compatibility for
  long-lived current-state subscriptions after deep mobile suspension.

The architecture and application opinions behind this stack are preserved in
[Datastar application guidance](docs/datastar-guidance.md). The central idea is
simple: commit authoritative state, wake the relevant subscriptions, render the
latest view, stream it with compression, and let Datastar morph it into the
browser DOM. The gzip mechanism, measured bounds, and escalation order are in
[Streaming compression: mechanism, evidence, and escalation](docs/streaming-compression.md).

Concrete REPL, lifecycle, incremental-adoption, and gzip-test findings are in
[Internal lessons from a real consumer](docs/internal-lessons.md).

## Requirements

- JDK 21 or newer (virtual threads)
- Clojure 1.12 or newer

## Installation

Until a Clojars release exists, pin a Git commit:

```clojure
io.github.bmillare/dj.web {:git/sha "<sha>"}
```

The prepared Clojars coordinate is:

```clojure
net.clojars.bmillare/dj.web {:mvn/version "0.1.0-alpha1"}
```

## Minimal HTTP server

```clojure
(require '[dj.web.http :as http]
         '[dj.web.http.response :as response])

(defn app [request]
  (case (:uri request)
    "/" (response/html-response "<h1>Hello</h1>")
    response/not-found))

(def server (http/start! #'app {:port 8080}))
(http/stop! server)
```

Handlers receive a Ring-flavoured request map and return an open response map:

```clojure
{:status 200
 :headers {"Content-Type" "text/plain; charset=utf-8"}
 :body "hello"}
```

It is intentionally not Ring: there is no middleware protocol or adapter
dependency. `dj.web.http.protocols/ResponseBody` is the only extension seam for
new response body types.

## Current-state Datastar subscriptions

The subscribed transport keeps socket ownership inside the request's virtual
thread. Producers never render or write to a socket; after committing state,
they only call `mark-dirty!`. Bursts are conflated because each render reads the
latest authoritative state.

```clojure
(require '[dj.web.datastar.fused :as fused]
         '[dj.web.datastar.subscribed :as subscribed]
         '[dj.web.html :as html]
         '[dj.web.http :as http]
         '[dj.web.http.response :as response])

(def state (atom {:count 0}))
(def subscriptions (subscribed/registry))

(defn render-main! [writer]
  (fused/write-patch-elements!
   writer
   (html/html
    [:main#app
     [:p "Count: " (:count @state)]
     [:button {:data-on:click "@post('/increment')"} "+"]])))

(defn app [request]
  (case [(:request-method request) (:uri request)]
    [:get "/updates"]
    (subscribed/subscription-response request subscriptions render-main!)

    [:post "/increment"]
    (do (swap! state update :count inc) ; commit authoritative state first
        (subscribed/mark-dirty! subscriptions)
        {:status 204})

    response/not-found))

(def server (http/start! #'app {:port 8080}))
```

The default scheduling policy waits for a 10 ms quiet period, forces progress
after 100 ms of continuous mutation, sends a heartbeat after 15 seconds idle,
and negotiates one connection-long gzip stream. These are options to
`subscription-response`, not global settings.

For a complete page shell, durable browser subscription, command route, dirty
mark, and current-state render, run the tested example:

```bash
nix develop --command clojure -M:hello-world
```

Then open `http://localhost:8080`. The example lives under `dev/`, so it is not
included in the library jar. It opts into the mobile-resume compatibility path
described below so its shell demonstrates the production-safe mobile shape.

### Optional deep-mobile-resume recovery

Durable retry, ordinary visibility handling, and deep mobile resume solve three
different lifecycle states. `retry: 'always'` reopens a request after it throws
or ends. Datastar ordinarily closes a hidden subscription and opens current truth
when the page becomes visible. After deep OS suspension, however, a browser can
retain a Fetch that is still logically pending but no longer receives bytes, so
there is no settled request for retry to act on.

Mobile applications using the current-state contract can opt into the bounded
Datastar v1.0.2 compatibility adapter:

```clojure
(require '[dj.web.datastar.assets :as assets]
         '[dj.web.datastar.mobile-resume :as mobile-resume])

[:html
 [:head
  (assets/script "/assets/datastar.js")
  (mobile-resume/script)]
 [:body (mobile-resume/subscription-attrs "/updates")
  [:main#app ...]]]
```

`subscription-attrs` owns both the initial durable GET and the recovery action,
ensuring their URL spellings are identical. The inline module coalesces visible
resume signals and re-invokes that action. With dj.web's pinned Datastar v1.0.2,
default same-method/same-URL cancellation aborts the prior Fetch before opening
its replacement. This behavior is browser-conformance-tested but is not claimed
as a stable cross-version Datastar API; rerun the conformance gate on every
client upgrade.

Opt in only when all of these are true:

- the endpoint is an idempotent GET that immediately renders current truth and
  needs no replay cursor;
- every owner on a page has a distinct subscription URL;
- Datastar's default request cancellation remains enabled;
- hidden pages should remain stream-free; and
- server heartbeats eventually expose aborted writers and bound cleanup.

Include `(mobile-resume/script)` once per page. For nonce-authorized inline
modules use `(mobile-resume/script {:nonce nonce})`. A CSP that forbids inline
scripts even with a nonce is outside this helper's support boundary; retain an
application-owned external adapter in that case. Desktop or controlled-kiosk
applications can omit the helper. The namespace is isolated compatibility
infrastructure and should migrate or disappear when Datastar provides supported
deep-resume/restart semantics; it is not a general expression-building API.

## Choosing a Datastar transport

| namespace | ownership model | compression | intended use |
|---|---|---|---|
| `dj.web.datastar` | published SDK-compatible generator | SDK profiles | compatibility with the official SDK API |
| `dj.web.datastar.reference` | independently closable stream | identity | readable mechanism and one-shot responses |
| `dj.web.datastar.fused` | one lexical writer scope | identity or gzip | one-shot batches and low-allocation writing |
| `dj.web.datastar.subscribed` | one request thread owns the writer | identity or gzip | long-lived current-state UI streams |

The fused and subscribed writers support `selector` and `mode`. Other Datastar
element options, unknown keys, unsupported patch modes, and `onlyIfMissing` for
signal patches fail explicitly rather than disappearing silently. Options use
the exported namespaced keys such as `wire/selector` and `wire/patch-mode`; plain
`:selector` and `:mode` are not aliases.

## Incremental adoption

An existing application can start dj.web on a separate port inside the same JVM
and nREPL as its current server. Both servers can share domain namespaces while
keeping handlers, executors, and stop/start lifecycles separate. This makes it
possible to migrate one page at a time without mounting dj.web into the existing
HTTP stack; stop the dj.web server with `http/stop!` when the trial ends.

For REPL-driven development, pass Vars at both long-lived boundaries: `#'app`
to `http/start!` and `#'render-main!` to `subscription-response`. Reloading a
namespace then reaches both new requests and already-open subscriptions.

## Datastar browser asset

The consuming application owns browser-asset delivery. `(assets/script)` points
to Datastar v1.0.2's version-pinned jsDelivr bundle. An application that avoids
runtime CDN dependencies downloads or builds the bundle, serves it using its own
static-asset policy, and passes that URL explicitly:

```clojure
(assets/script "/assets/datastar.js")
```

dj.web does not bundle, locate, or serve `datastar.js`, and its Nix development
shell does not provision the file.

## Development

```bash
nix develop
clojure -X:test
clojure -T:build jar
```

Build output goes under `target/`. Publishing is configured for
`net.clojars.bmillare/dj.web`, but no release is implied by the repository's
existence.

## License

Copyright © Brent Millare. Distributed under the Eclipse Public License 2.0.
