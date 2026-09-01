# Internal lessons from a real consumer

These are operational lessons learned while adopting dj.web inside an existing
Clojure application. They supplement the architectural source material in
`datastar-guidance.md` with concrete library-specific behavior.

## Separate convenience helpers from compatibility infrastructure

Long-lived browser subscriptions need an explicit expression such as
`@get('/updates', {retry: 'always', retryMaxCount: 1000})`. Ordinary wrappers and
general expression builders remain application-owned until their shape is earned
across independent applications and developer feedback. Local repetition alone
does not justify a shared `util` namespace.

Compatibility infrastructure has a different threshold. A severe upstream gap
that defeats dj.web's foundational current-state contract can justify a narrow
bridge before multi-application repetition. Such a bridge must be opt-in,
isolated from the server core, coupled to an explicit upstream version, tested at
that seam, and designed for removal. `dj.web.datastar.mobile-resume` is that kind
of bridge for Datastar v1.0.2's pending-but-silent mobile-resume case; it is not
precedent for a general expression-building API. See the README's mobile section
for its bounded consumer contract.

## Keep both long-lived boundaries indirect at the REPL

Pass `#'app` to `dj.web.http/start!` and `#'render-main!` to
`subscription-response`. A function value captured when a subscription opens
continues calling that old function for the life of the connection; a Var is
dereferenced on every request or render, so namespace reloads reach existing
browser streams without restarting the server.

## Connection count lags; heartbeats reap

`active-count` reports registered request owners, not an instantaneous TCP
truth. With an orderly client close, a write may first succeed into a socket
buffer and only a later write reveal the departure. The default 15-second
heartbeat is therefore both a browser-ignored liveness frame and the mechanism
that bounds cleanup lag for idle connections. Disabling heartbeats also removes
that bound.

## Adopt beside the existing server

dj.web can listen on a separate port in the same JVM and nREPL as an existing
server. The two servers can share domain state and rendering namespaces while
keeping their request executors and lifecycles independent. This is a useful
one-page-at-a-time migration shape: start the dj.web server, compare the new
page against the old one, then stop it with `http/stop!` without disturbing the
existing service.

## Read live gzip streams byte-wise in JVM tests

A long-lived subscription uses one connection-long gzip member with sync flush.
Do not use a `Reader` over a live `GZIPInputStream` to decide whether an SSE frame
is ready: `InflaterInputStream.available()` can optimistically report one byte,
and buffered character reads can then wait for input beyond the flushed frame.
For integration tests, consume decompressed bytes until the SSE `\n\n` delimiter
and decode that completed frame. The private helper in `subscribed_test.clj`
demonstrates the pattern across multiple dirty marks.
