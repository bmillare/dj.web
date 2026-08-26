(ns dj.web.http.error
  "Application-selectable reporting policy for `dj.web.http`.

  The server namespace owns HTTP mechanics. This namespace owns the decision to
  report a failure or recognize a routine long-lived-client disconnect."
  (:import [java.io IOException PrintWriter]))

(defn report [^Throwable throwable request]
  (let [w (PrintWriter. ^java.io.Writer *err*)]
    (.println w (str "dj.web.http: handler threw for "
                     (:request-method request) " " (:uri request)))
    (.printStackTrace throwable w)
    (.flush w))
  nil)

(def ^:private client-disconnect-messages
  ;; Socket writes do not use one portable exception subtype. JDK HttpServer on
  ;; macOS surfaces a plain IOException for a reset peer, while other operating
  ;; systems commonly use SocketException and different native error text.
  #{"Broken pipe"
    "Connection reset"
    "Connection reset by peer"
    "An established connection was aborted by the software in your host machine"
    "An existing connection was forcibly closed by the remote host"})

(defn client-disconnect?
  "True for a direct I/O failure known to mean that the remote HTTP client left.

  Deliberately does not inspect causes: an application failure which happens to
  wrap an IOException is still an application failure and must be reported. The
  recognized-message check also avoids swallowing a direct application I/O
  failure merely because response headers have already reached the wire."
  [error]
  (and (instance? IOException error)
       (contains? client-disconnect-messages (.getMessage ^Throwable error))))

(defn ignore-client-disconnects
  "Wrap an error callback, suppressing recognized remote-client disconnects.

  Every unrecognized throwable is passed to `on-error` unchanged. The no-arg
  form delegates those failures to `report`."
  ([] (ignore-client-disconnects report))
  ([on-error]
   {:pre [(ifn? on-error)]}
   (fn [error request]
     (when-not (client-disconnect? error)
       (on-error error request)))))
