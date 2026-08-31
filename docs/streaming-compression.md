# Streaming compression: mechanism, evidence, and escalation

The short rule in the application guidance is intentionally opinionated:
default to coarse current-state morphs over a long-lived compressed SSE stream.
This document explains the evidence and qualifications behind that rule. It is
not a competing architecture or a checklist that must be completed before using
coarse morphs.

## Why compression is the default

Traditional web instincts treat a complete HTML view as waste and encourage the
server to calculate a minimal delta. That apparent efficiency has a systems
cost: the application must identify every target, maintain more routes or
protocol messages, decide which clients need which delta, and recover correctly
when a delta is missed.

Datastar applications can instead send the latest view. HTML repeats tag names,
attributes, classes, layout, and usually most content from one render to the
next. A connection-long compressor can exploit those bytes, while Idiomorph
performs the fine-grained DOM reconciliation in the browser. Experienced
Datastar users report very large savings from this combination. The exact ratios
vary, but the recurring architectural finding is more important: compression
often removes the bandwidth motivation for application-managed view diffs.

That is why measurement comes after the coarse design is working. It validates
the default and identifies exceptions; it should not be used to justify
speculative fragmentation.

## What connection-long gzip actually remembers

dj.web's fused and subscribed transports can write one gzip member for the life
of the response and use sync flushes to make each SSE frame available without
ending that member. The DEFLATE compressor retains a 32 KiB sliding history.

When a complete frame and the matching bytes in its successor fit within that
history, the successor can refer back to the earlier frame. A small change may
then have delta-like wire cost even though the server rendered and sent the
complete view.

When the distance to matching bytes exceeds 32 KiB, those earlier bytes are no
longer available. Cross-frame reuse falls away sharply. This does **not** mean a
view over 32 KiB is uncompressed or necessarily expensive: DEFLATE still finds
repetition inside the current frame.

The resulting two-part model is:

- within the history window, changed frames may exploit both prior-frame and
  current-frame redundancy;
- beyond it, cost is dominated by how well each current frame compresses on its
  own.

## Consumer measurements

A dj.web consumer first varied a rendered document view while keeping the
connection and encoding fixed:

| rendered `<main>` | SSE payload | first frame | later changed frame |
| --: | --: | --: | --: |
| 18 KiB | 22 KiB | 7,941 B | about 250 B |
| 34 KiB | 42 KiB | 9,458 B | about 8,600 B |
| 56 KiB | 68 KiB | 20,297 B | about 19,300 B |
| 112 KiB | 140 KiB | 26,569 B | about 25,900 B |

This experiment exposed the DEFLATE window boundary, but size by itself gave an
incomplete decision rule. A second experiment used live terminal-shaped HTML
with sharply different content:

| rendered `<main>` | content | changed frame on wire | ratio |
| --: | :-- | --: | --: |
| 15.5 KiB | 355 short changing lines | 213 B | 72.8x |
| 64.9 KiB | 345 repeated-sentence lines | 1,784 B | 37.1x |
| 103.8 KiB | random base64 across 1,062 spans | 43,287 B | 2.4x |

The 64.9 KiB view is well beyond DEFLATE's cross-frame window and remains
cheap because it is highly repetitive within one frame. The 103.8 KiB view is
both beyond the window and deliberately high entropy, so it pays nearly the
full cost of its changing content. In the consuming application, real agent
terminal views were only about 5–10 KiB; the high-entropy fixture was a boundary
test rather than the normal workload.

These results support—not weaken—the coarse-rendering default. They explain why
headline compression ratios cannot be projected onto every workload and what to
inspect if a real workload is unexpectedly expensive.

## How to measure the decision that matters

Measure wire bytes for representative **changed** frames on the same long-lived
connection and encoding used in production. Include realistic content rather
than a placeholder string, then multiply by the expected update cadence. First
frame size, decoded HTML size, and a browser network panel's cumulative stream
total do not answer that question by themselves.

Keep the other costs separate:

- backend query or observation time;
- HTML render time;
- compressed wire bytes per changed frame and per unit time;
- browser morph time and DOM size.

Compression only addresses the third item. A system whose wire cost is already
small should optimize an expensive query or render rather than add a delta
protocol.

## Escalation order

When measurement finds a material wire bottleneck, preserve the current-state
model as long as practical:

1. Batch or conflate updates that arrive faster than the UI needs to change.
2. Bound high-entropy content that is not useful at unlimited size, such as a
   terminal or log history.
3. Partition at a natural ownership or update-frequency boundary when that
   boundary also simplifies the application.
4. Evaluate a compressor with a larger history window before taking on manual
   server-side diffs.

Brotli is the obvious next compression experiment for large frames because its
window can be much larger than DEFLATE's. Whether it wins depends on compression
level, flush behavior, CPU cost, browser and intermediary compatibility, and
the actual content. dj.web currently negotiates gzip rather than Brotli. This is
a present library capability boundary, not a Datastar principle: a consumer can
add Brotli at its HTTP boundary, and dj.web can add negotiated support when a
measured application supplies the required design evidence.

Manual fine-grained patches remain available, but they are the last escalation,
not the prudent starting point. Use them when a measured constraint outweighs
the additional routing, state, and recovery complexity.
