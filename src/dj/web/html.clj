(ns dj.web.html
  "Hiccup rendering. Nothing here knows about Datastar, SSE, or HTTP.

  It sits next to `dj.web.datastar` because a patch-the-DOM UI needs HTML
  fragments — but that is an affinity, not a dependency: every server-rendered
  thing wants this, and requiring it should not drag in an SSE adapter."
  (:require [hiccup2.core :as hiccup]
            [hiccup.util :as hu]))

(defn html
  "Render a hiccup form to an HTML string. Strings are escaped; `raw` opts out.

  Note on Datastar attributes: `:data-on:click` is a legal Clojure keyword and
  renders verbatim, and `{:data-json-signals true}` renders as a bare attribute
  — no special syntax needed. The `'` in `@get('/foo')` renders as `&apos;`,
  which looks wrong next to a JS expression and is not: the browser decodes
  entities before Datastar reads the attribute, so `getAttribute` returns
  `@get('/foo')` (verified in Chrome, a Chrome round trip). Don't \"fix\" it with `raw`."
  [form]
  (str (hiccup/html {:mode :html} form)))

(def raw
  "Wrap a string so `html` emits it verbatim — for CSS, inline JS, or HTML you
  already rendered."
  hu/raw-string)

(defn page
  "`html` plus an HTML5 doctype — for a full-page response."
  [form]
  (str "<!DOCTYPE html>\n" (html form)))
