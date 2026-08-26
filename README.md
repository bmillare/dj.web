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
- `dj.web.datastar.assets` — serve a pinned local Datastar browser bundle.

The architecture and application opinions behind this stack are preserved in
[Datastar application guidance](docs/datastar-guidance.md). The central idea is
simple: commit authoritative state, wake the relevant subscriptions, render the
latest view, and let Datastar morph it into the browser DOM.

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

## Choosing a Datastar transport

| namespace | ownership model | compression | intended use |
|---|---|---|---|
| `dj.web.datastar` | published SDK-compatible generator | SDK profiles | compatibility with the official SDK API |
| `dj.web.datastar.reference` | independently closable stream | identity | readable mechanism and one-shot responses |
| `dj.web.datastar.fused` | one lexical writer scope | identity or gzip | one-shot batches and low-allocation writing |
| `dj.web.datastar.subscribed` | one request thread owns the writer | identity or gzip | long-lived current-state UI streams |

The fused and subscribed writers support `selector` and `mode`. Other Datastar
element options, and `onlyIfMissing` for signal patches, fail explicitly rather
than disappearing silently.

## Datastar browser asset

`dj.web.datastar.assets` serves `datastar.js` from `$DATASTAR_JS`, falling back
to `resources/public/datastar.js`. The Nix development shell pins Datastar
v1.0.2 by hash and exports the path automatically. Applications may serve the
bundle by any other deployment mechanism and ignore this namespace.

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
