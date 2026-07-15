(ns datastar-kit.ds-test
  "Unit tests for the SSE event constructors — centered on the multi-line `data:`
   fix (PR #1): raw newlines in fragment HTML must NOT truncate on the wire."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [datastar-kit.ds :as ds]))

(def ^:private data-lines #'ds/data-lines)   ; private helper under test

;; Simulate how a browser's EventSource rejoins an SSE event's `data:` lines into the
;; single logical data payload: consecutive `data:` fields, "data: " prefix stripped,
;; joined back with \n.
(defn- wire->payload [event-str]
  (->> (str/split-lines event-str)
       (filter #(str/starts-with? % "data:"))
       (map #(subs % (count "data: ")))
       (str/join "\n")))

;; ---- the private helper ----

(deftest data-lines-single-line
  (is (= "data: hello world" (data-lines "hello world"))))

(deftest data-lines-splits-each-newline
  (is (= "data: a\ndata: b\ndata: c" (data-lines "a\nb\nc"))))

(deftest data-lines-preserves-trailing-newline
  ;; the -1 limit on split keeps trailing empty fields, so a trailing \n survives
  (is (= "data: a\ndata: " (data-lines "a\n"))))

;; ---- THE regression guard ----

(deftest every-wire-line-is-a-valid-sse-field
  (testing "no fragment-HTML newline may leak a bare (un-prefixed) line onto the wire"
    (doseq [[label ev] {"sse-inner"    (ds/sse-inner "#x" "<div id=\"x\">l1\nl2\nl3</div>")
                        "sse-raw"      (ds/sse-raw "<div id=\"m\">a\nb\nc</div>")
                        "sse-fragment" (ds/sse-fragment "panel" "<b>x</b>\n<b>y</b>")
                        "sse-event"    (ds/sse-event {:fragments [{:id "p" :html "<p>1</p>\n<p>2</p>"}]})}]
      (doseq [line (remove str/blank? (str/split-lines ev))]
        (is (or (str/starts-with? line "event:")
                (str/starts-with? line "data:"))
            (str label ": bare continuation line leaked to the wire: " (pr-str line)))))))

;; ---- lossless round-trip (the bug = truncation; here we prove nothing is lost) ----

(deftest multiline-html-round-trips-losslessly
  (testing "sse-inner keeps full multi-paragraph HTML"
    (let [html "<p>para1</p>\n<p>para2</p>\n<p>para3</p>"]
      (is (str/includes? (wire->payload (ds/sse-inner "#x" html)) html))))
  (testing "sse-raw keeps full multi-line HTML"
    (let [html "<div id=\"m\">a\nb\nc</div>"]
      (is (str/includes? (wire->payload (ds/sse-raw html)) html))))
  (testing "sse-fragment keeps full multi-line HTML"
    (let [html "<ul>\n<li>one</li>\n<li>two</li>\n</ul>"]
      (is (str/includes? (wire->payload (ds/sse-fragment "list" html)) html)))))

;; ---- single-line behavior unchanged ----

(deftest single-line-html-still-one-data-field
  (let [ev (ds/sse-inner "#x" "<b>hi</b>")
        data-count (count (filter #(str/starts-with? % "data:") (str/split-lines ev)))]
    ;; selector + mode + elements = exactly 3 data: lines, no extra continuation
    (is (= 3 data-count))))
