(ns dj.web.http.protocols
  "The one seam between `dj.web.http` and the things it can send.

  Kept in its own namespace so a body implementation (e.g. `dj.web.datastar`'s
  SSE generator) can extend it without depending on the server."
  (:import [java.io OutputStream]))

(defprotocol ResponseBody
  "How a body gets onto the wire. Three *independent* questions — deliberately not
  fused into one answer, because HTTP allows all four combinations of the first two
  and the caller shouldn't have to encode a policy in a length."
  (content-length [body]
    "The body's size in bytes, or `nil` when it isn't known.

     A fact about the body, nothing more: it does NOT decide the framing. A stream
     may well know its length, and `respond!` will then send a Content-Length and
     still let the body write incrementally.")
  (streaming? [body]
    "True when bytes should reach the client as they are flushed, rather than being
     one value handed over at once. Chunked transfer-encoding is how `respond!`
     delivers that when the length is unknown — but that translation is `respond!`'s
     business, not the body's.")
  (write-body! [body ^OutputStream out]
    "Write the body to `out`. The caller closes the stream afterwards.

     A body that needs options carries them itself (see `datastar`'s `SSEGenerator`,
     which holds its own `opts`) — this method is deliberately not handed the
     surrounding response map."))
