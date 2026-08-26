# Agent guide

`dj.web` is a standalone Clojure library. It provides a small JDK HTTP server,
generic SSE framing, Datastar transports and current-state subscriptions, and
small Hiccup/Chassis rendering helpers.

## Development

- Enter the toolchain with `nix develop`.
- Run the full suite with `clojure -X:test`.
- Build the jar with `clojure -T:build jar`.
- Keep runtime namespaces under `src/dj/web/` and tests under `test/dj/web/`.
- Do not introduce a Ring or web-server dependency into `dj.web.http`; it is a
  plain function-to-response-map server built on the JDK's `HttpServer`.
- Keep `dj.web.sse` generic. Datastar wire semantics belong under
  `dj.web.datastar`.
- The official Datastar SDK is both the compatibility transport and the oracle
  for differential tests. The reference, fused, and subscribed transports must
  continue to work without requiring SDK namespaces themselves.

The repository is licensed under EPL-2.0. Public source and docs should describe
the current design and must not depend on private workspace notes.
