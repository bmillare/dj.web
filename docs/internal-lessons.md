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

## Deep dive: notification, unchanged state, and application-owned identity

This section records lessons from a live-terminal consumer of
`dj.web.datastar.subscribed`. It is deliberately an input to a later documentation
pass, not a change to the public guidance yet. In particular, it does **not**
propose a keyed-registry API.

### Correct the overloaded meaning of "render"

An application commonly gives its subscription a function that does three
conceptually different things:

1. reads or constructs the current page model;
2. projects that model to HTML (`view = f(state)`);
3. writes the HTML as an SSE patch.

Only the second operation is rendering in the narrow sense. It can and usually
should be a pure state-to-HTML function. Calling the whole callback a render is
convenient, but it can hide expensive or side-effecting model acquisition inside
the callback.

The consumer that exposed this distinction displays terminal panes. Observing a
pane requires external I/O: enumerate the relevant host or workspace, run a tmux
capture, and classify the captured screen. That capture is the expensive
operation; turning the resulting immutable value into HTML is not. Descriptions
such as "an expensive page" or "an expensive render" should therefore be read as
shorthand and avoided when precision matters. The actual concern is expensive
state acquisition combined with coarse invalidation.

### `mark-dirty!` is an invalidation permit, not a state publication

`subscribed/mark-dirty!` carries neither application state nor an identity for
what changed. It non-blockingly tells every connection in one registry that some
authoritative state may have changed. Dirty windows conflate bursts; after a
window is claimed, each request owner invokes its callback and rereads current
truth.

Consequently, the basic flow is pull-after-invalidation:

```text
application commits authoritative state
  -> mark-dirty!
  -> each connection in the registry rereads its current model
  -> view = f(state)
  -> write an SSE patch
```

This is intentionally simple and is a good default when connections share a
view, mutations are relatively infrequent, or rereading and rendering unrelated
views is negligible. Time-based conflation and connection-long streaming
compression make even coarse broadcasts surprisingly inexpensive. They are not,
however, content-change detection.

The important ordering contract is application-owned: commit coherent state
first, notify second. A dirty mark is permission to reread the committed state;
it must not be the operation that creates that state, nor should subscribers
independently repeat an expensive observation merely because they were woken.

### Suppress known no-op publications before involving SSE

`subscribed` does not compare the previous and next model or HTML. If an
application calls `mark-dirty!`, every scheduled connection callback renders and
writes a complete patch. Datastar's morph may find no DOM difference, and gzip
may reduce repeated HTML to very few wire bytes, but the server still performed
the model read, HTML construction, and write.

The cheap and generally useful optimization is earlier:

```text
observe or mutate
  -> compare old and new render-relevant application state
  -> if unchanged: commit any diagnostics, but do not mark dirty
  -> if changed: commit new state, then mark dirty
```

Strictly speaking, "if the render did not change, do not mark dirty" cannot be
implemented in that order because the mark is what schedules the render. An
application can instead compare the render-relevant source state before marking,
or a transport could speculatively render and suppress identical HTML. The
consumer used the first option because it avoids model reconstruction, rendering,
and transmission together.

For terminal snapshots, the meaningful comparison includes the pane's status,
text, classified state, detected agent, window, alternate-screen flag, and
working directory. Observation timestamps and changing diagnostic text are
excluded. An identical capture therefore refreshes observation metadata without
producing an SSE event. This optimization complements batching and compression:
those mechanisms make genuine repeated broadcasts cheap, while change detection
eliminates broadcasts that contain no new view information.

This comparison belongs at the layer that knows what is meaningful. Generic
HTML equality may be useful in some systems, but dj.web cannot infer whether a
timestamp, error detail, authorization fact, query result, or other application
value is semantically relevant to a view.

### What one consumer stores per terminal identity

The terminal consumer maintains an application-owned map keyed by canonical pane
identity. Workspace-addressed and host-addressed panes have different identity
forms; both describe the external resource being observed rather than a DOM
component.

Each entry contains approximately:

```clojure
{:key             <canonical pane identity>
 :params          <enough provenance to observe the pane again>
 :registry        <ordinary dj.web subscription registry>
 :snapshot        <latest terminal observation>
 :last-good       <latest successful observation>
 :created-at      <lifecycle timestamp>
 :touched-at      <last request reference>
 :captures        <diagnostic count>
 :last-capture-ms <diagnostic duration>
 :last-tick       <diagnostic timestamp>
 :last-error      <diagnostic error>}
```

A successful raw snapshot contains the capture time, terminal text, pane status,
agent classification, alternate-screen flag, working directory, and window
name. An ended snapshot records that the pane no longer exists. An unavailable
snapshot records a transport failure; `:last-good` lets the application continue
showing the previous terminal screen explicitly marked stale.

This is not merely subscription routing. The identity model provides application
behavior beyond rendering:

- two tabs for one pane share one external capture;
- observation runs only for pane identities with active viewers;
- commands and follow-up observations address the same external pane;
- provenance stored with an entry is sufficient to recapture it;
- last-known-good state survives temporary host failure;
- idle entries are retired according to application policy; and
- capture counts, timings, and failures are attributable to one pane.

The per-entry registry is only one field in this domain-specific observed-pane
repository. Treating the whole repository as a rendering facility would lose the
reason for most of its data and lifecycle rules.

### What per-identity registries add beyond no-op suppression

Change detection and notification routing solve separate problems. If pane A has
not changed, the pre-notification comparison avoids every downstream cost. If
pane A genuinely changes and all pages share one registry, that global mark still
wakes tabs for pane B and unrelated pages. Those tabs reread and resend their own
views even though their inputs did not change.

Giving each observed pane its own ordinary registry limits a genuine pane-A
notification to pane-A viewers. It also exposes viewer activity per pane, which
the application uses to decide what to poll. This is useful for independent,
high-frequency, externally observed resources. It is additional machinery, not
a prerequisite for the simple no-op optimization, and it is unnecessary when
unrelated renders are acceptably cheap.

The terminal consumer must consequently manage entry creation before the first
subscription render, sharing across tabs, registry selection, active-viewer lag,
snapshot commitment before notification, and eventual idle retirement. Those
rules are consequences of its observation domain, not evidence that every
dj.web application wants the same identity abstraction.

### Why this should remain a reference rather than a dj.web abstraction

A general keyed-registry abstraction would have to choose—or expose policy for—
questions on which applications legitimately differ:

- Is identity an entity, query, topic, tenant, user, page, or external resource?
- Who canonicalizes identities and creates entries?
- Does one observation serve multiple viewers?
- Is production continuous, periodic, mutation-driven, or conditional on demand?
- What constitutes a meaningful change?
- Does unavailable state replace, supplement, or preserve the last good value?
- What provenance and diagnostics live beside a snapshot?
- When is an inactive entry retired?
- Should equality be defined over source state, a page model, or rendered HTML?
- Can one connection depend on several identities, and can one change affect
  several registries?

The answers in the terminal consumer are internally coherent but unusually
specific: its registry key is also an external-observation key, a capture-sharing
key, a command-addressing key, and a lifecycle key. Standardizing that package
from one example would encode application policy in the transport library.

The smaller reusable contract is enough:

- authoritative state and its identity remain application-owned;
- producers observe or mutate on their own cadence and rules;
- producers commit before notifying;
- applications avoid marking when render-relevant state is unchanged;
- a registry defines the audience awakened by one mark; and
- applications may use one registry or partition their audience with multiple
  ordinary registries.

The terminal design is valuable as an advanced reference implementation of
partitioned notification and demand-driven observation. It should not imply a
commitment to a general keyed-registry API without independent consumers that
demonstrate a stable common shape.

### Documentation handoff: claims to preserve and questions to place carefully

A later documentation agent should preserve these distinctions when updating
README or guidance material:

1. Keep coarse current-state morphs, batching, and streaming compression as the
   strong default. This lesson does not argue for fine-grained DOM patches or
   server-side HTML diffing.
2. State explicitly that batching conflates notifications and compression shrinks
   repeated frames; neither detects unchanged application state.
3. Recommend committing state before `mark-dirty!`, and recommend withholding a
   mark when the producer already knows render-relevant state did not change.
4. Describe `mark-dirty!` as invalidation or permission to reread, not as the
   publication of state itself.
5. Separate pure view projection from model acquisition when discussing render
   cost.
6. Present multiple registries as an application-owned audience-partitioning
   option for measured or structurally obvious fan-out, not as the default.
7. Use the observed-pane repository as a reference showing why identity,
   observation, caching, failure continuity, and lifecycle often belong together
   in the application.
8. Do not promise a keyed-registry abstraction based on this example alone.

Open editorial judgment remains about how prominently to recommend no-op
suppression. It is cheap when a producer naturally has old and new values, but a
forced deep comparison can itself be more expensive than a coarse compressed
broadcast. The guidance should describe the semantic opportunity rather than
mandate an equality strategy for every application.
