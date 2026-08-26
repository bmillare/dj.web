(ns dj.web.html.chassis
  "Application-owned boundary around Chassis compilation and rendering."
  (:require [dev.onionpancakes.chassis.compiler :as compiler]
            [dev.onionpancakes.chassis.core :as chassis]))

(defmacro template
  "Compile a view where its literal template structure is visible."
  [form]
  `(compiler/compile ~form))

(defn html
  "Render a compiled Chassis view to an HTML string."
  [view]
  (chassis/html view))

(defn write-html!
  "Render a compiled Chassis view directly to an application-owned Appendable."
  [appendable view]
  (chassis/write-html appendable view))

(def raw
  "Wrap content that is already safe HTML and must not be escaped."
  chassis/raw)

(defn page
  "Render a compiled full-document view with an HTML5 doctype."
  [view]
  (str "<!DOCTYPE html>\n" (html view)))
