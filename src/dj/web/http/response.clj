(ns dj.web.http.response
  "Constructors for response maps — the *vocabulary* half of `dj.web.http`,
  which is otherwise pure mechanism.

  Nothing downstream requires that these were used: a response is an open plain map
  `{:status :headers :body}`, and `http/respond!` sends one just as happily when it
  came from a literal or an `assoc`. Delete this namespace and no semantics change.
  That is exactly why the helpers are safe to have — they construct values, they
  don't decide anything.

  (`:headers` values may be a string or a vector of strings, for the headers HTTP
  allows more than once — `{\"Set-Cookie\" [\"a=1\" \"b=2\"]}`.)"
  (:require [clojure.java.io :as io]))

(defn response
  "`{:status 200 :body body}`, plus a Content-Type when given."
  ([body] (response body nil))
  ([body content-type]
   (cond-> {:status 200 :body body}
     content-type (assoc :headers {"Content-Type" content-type}))))

(defn html-response [body] (response body "text/html; charset=utf-8"))
(defn text-response [body] (response body "text/plain; charset=utf-8"))

(def not-found {:status 404 :body "not found"})

(defn file-response
  "Serve `f` (a path or File), or `nil` when it isn't there.

  Nil rather than a 404 on purpose: the handler contract is already
  `(or (handler req) not-found)`, so a constructor that constructs nothing lands on
  a 404 by the rule that exists — and this stays a constructor instead of quietly
  owning an error policy (and echoing the requested path back to the client)."
  [f content-type]
  (let [file (some-> f io/as-file)]
    (when (and file (.isFile file))
      (response file content-type))))
