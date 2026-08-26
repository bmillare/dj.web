(ns dj.web.datastar.assets
  "Serving the one Datastar client asset — `datastar.js`.

  This is deployment *policy* (where the file lives, what URL it hangs off),
  not part of the SSE adapter, so it is deliberately its own namespace: nothing
  in `dj.web.datastar` depends on it, and a caller who wires up assets
  differently can ignore it entirely."
  (:require [dj.web.http.response :as resp]))

(def ^:private js-fallback
  "Resolved against the JVM's working directory, so it only finds the file when
  the process was launched from the project root — the plain-JVM convenience
  path, not the supported one. `$DATASTAR_JS` is the supported one."
  "resources/public/datastar.js")

(defn js-file
  "Path to `datastar.js`. The Nix devshell pins it by hash and exports
  `$DATASTAR_JS`; the fallback lets a plain (non-Nix) JVM work if someone drops
  the file in `resources/public/` and starts the JVM there. The helper adds no
  runtime CDN dependency."
  []
  (or (System/getenv "DATASTAR_JS") js-fallback))

(defn js-response
  "Response serving the client bundle, or `nil` if the file isn't there (which the
  server turns into a 404). Route it at whatever path your `script` tag points to."
  []
  (resp/file-response (js-file) "text/javascript"))

(defn script
  "The `<script>` tag to put in `<head>`. Datastar is an ES module."
  ([] (script "/datastar.js"))
  ([src] [:script {:type "module" :src src}]))
