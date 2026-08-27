(ns dj.web.datastar.assets
  "Construct the Datastar client `<script>` tag.

  The no-argument form uses Datastar's version-pinned CDN URL. Consumers own
  browser-asset deployment: pass a self-hosted URL when CDN delivery is not the
  application's policy. dj.web does not locate, bundle, or serve the JS file.")

(def ^:private default-src
  "https://cdn.jsdelivr.net/gh/starfederation/datastar@v1.0.2/bundles/datastar.js")

(defn script
  "The Datastar ES-module `<script>` tag to put in `<head>`.

  With no argument, use Datastar's version-pinned CDN bundle. Pass a URL such as
  `/assets/datastar.js` when the consuming application self-hosts the file."
  ([] (script default-src))
  ([src] [:script {:type "module" :src src}]))
