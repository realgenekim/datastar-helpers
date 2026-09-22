(ns datastar-kit.ds-test
  "Unit tests for the SSE event constructors — centered on the multi-line `data:`
   fix (PR #1): raw newlines in fragment HTML must NOT truncate on the wire."
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
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

(deftest persistent-sse-mounts-own-the-browser-lifecycle
  (is (= {:data-star-init
          "@get('/events',{openWhenHidden:false,retry:'always',retryMaxCount:1000000})"}
         (ds/sse-mount-url "/events"))))

(deftest live-scrub-owns-continuous-one-shot-wiring
  (is (= {:data-star-bind:atidx ""
          :data-star-on:input__throttle.150ms
          "@get('/fragment?at-index=' + $atidx)"}
         (ds/live-scrub :atidx "/fragment?at-index=")))
  (is (= {:data-star-bind:scrub ""
          :data-star-on:input__throttle.75ms
          "@get('/fragment?at=' + $scrub)"}
         (ds/live-scrub :scrub "/fragment?at=" 75)))
  (is (thrown? AssertionError
               (ds/live-scrub :at-index "/fragment?at="))))

(deftest copy-nearest-text-keeps-the-legacy-custom-message-arity
  (is (str/includes? (ds/copy-nearest-text "dd" ".url")
                     "showNotification('Copied!')"))
  (is (str/includes? (ds/copy-nearest-text "dd" ".url" "Copied to clipboard")
                     "showNotification('Copied to clipboard')")))

;; ---------------------------------------------------------------------------
;; bind / signal-ref — the browser lowercases attribute names
;; ---------------------------------------------------------------------------

;; @spec EDIT-020
(deftest bind-refuses-a-signal-the-browser-would-lowercase
  (testing "data-star-bind:noteText reaches Datastar as data-star-bind:notetext"
    (let [e (try (ds/bind :noteText) (catch clojure.lang.ExceptionInfo e e))]
      (is (instance? clojure.lang.ExceptionInfo e))
      (is (= :ds/camel-case-signal (:type (ex-data e))))
      (is (str/includes? (ex-message e) "note-text"))))
  (testing "kebab-case is the one spelling an app writes"
    (is (= {:data-star-bind:note-text ""} (ds/bind :note-text)))
    (is (= {:data-star-bind:chat ""} (ds/bind "chat")))))

;; @spec EDIT-021
(deftest signal-ref-applies-datastars-own-camel-rule
  (is (= "$noteText" (ds/signal-ref :note-text)))
  (is (= "$chat" (ds/signal-ref :chat)))
  (is (= "$aBCD" (ds/signal-ref :a-b-c-d)))
  (testing "digits are not word starts for Datastar, so they are not upcased"
    (is (= "$row-1idx" (ds/signal-ref :row-1idx))))
  (testing "one name, both spellings — so neither is ever hand-typed"
    (is (thrown? clojure.lang.ExceptionInfo (ds/signal-ref :noteText)))))

;; ---------------------------------------------------------------------------
;; editable — THE one open text input inside a server-pushed region
;; ---------------------------------------------------------------------------

(defn- nodes
  "Every hiccup element vector in `tree` whose tag is `tag`."
  [tree tag]
  (filter #(and (vector? %) (= tag (first %)))
          (tree-seq sequential? seq tree)))

(defn- attrs
  "The attribute map of a hiccup element vector, or {} when it has none."
  [node]
  (let [a (second node)] (if (map? a) a {})))

(def ^:private three-actions
  [{:label "begins here" :url "/r/1/note/submit" :payload {:tx-id "t1" :date "2026-09-01" :kind "begins"}}
   {:label "ends here"   :url "/r/1/note/submit" :payload {:tx-id "t1" :date "2026-09-01" :kind "ends"}}
   {:label "just a note" :url "/r/1/note/submit" :payload {:tx-id "t1" :date "2026-09-01" :kind "note"}}])

(defn- note-editable [& {:as overrides}]
  (ds/editable (merge {:placeholder "a trip label, or a note"
                       :actions three-actions
                       :cancel {:label "cancel" :url "/r/1/note/cancel"}}
                      overrides)))

;; @spec EDIT-001
(deftest editable-wrapper-freezes-its-own-subtree
  (let [a (attrs (note-editable))]
    (testing "the pinned client reads the ALIASED attribute name"
      (is (contains? a :data-star-ignore-morph))
      (is (= "" (:data-star-ignore-morph a)))
      (is (not (contains? a :data-ignore-morph))))
    (testing "no signal, no binding — the browser owns the draft outright"
      (is (nil? (:data-star-signals__ifmissing a)))
      (is (empty? (filter #(str/starts-with? (name %) "data-star-bind") (keys a)))))
    (testing "the wrapper class is a contract, not decoration: the buttons query it"
      (is (= :div.ds-editable (first (note-editable)))))))

;; @spec EDIT-003
(deftest editable-wrapper-contains-every-click-inside-itself
  (testing "a click on the input itself must not reach the ancestor cell's opener"
    (is (= "event.stopPropagation()" (:onclick (attrs (note-editable)))))))

;; @spec EDIT-001
(deftest editable-merges-a-caller-class
  (is (= "tl-note-open" (:class (attrs (note-editable :class "tl-note-open")))))
  (is (nil? (find (attrs (note-editable)) :class))))

;; @spec EDIT-002
(deftest editable-renders-exactly-one-plain-input
  (let [inputs (nodes (note-editable) :input)]
    (is (= 1 (count inputs)))
    (let [a (attrs (first inputs))]
      (is (= "text" (:type a)))
      (is (= "ds-editable-input" (:class a)))
      (is (= "a trip label, or a note" (:placeholder a)))
      (is (true? (:autofocus a))))
    (testing "autofocus? false omits the attribute entirely"
      (is (nil? (find (attrs (first (nodes (note-editable :autofocus? false) :input)))
                      :autofocus))))
    (testing "no placeholder means no placeholder attribute"
      (is (nil? (find (attrs (first (nodes (note-editable :placeholder nil) :input)))
                      :placeholder))))
    (testing "an existing note is the input's own value — no signal to seed"
      (is (= "Gene's c:\\temp" (:value (attrs (first (nodes (note-editable :value "Gene's c:\\temp") :input))))))
      (is (nil? (find (attrs (first (nodes (note-editable) :input))) :value))))))

;; @spec EDIT-004, EDIT-005
(deftest editable-renders-one-button-per-action-plus-cancel
  (let [buttons (nodes (note-editable) :button)]
    (is (= 4 (count buttons)))
    (is (= ["begins here" "ends here" "just a note" "cancel"]
           (map last buttons)))
    (testing "ONE event idiom: every button is a plain onclick, never data-star-on:click"
      (doseq [b buttons]
        (is (string? (:onclick (attrs b))))
        (is (nil? (:data-star-on:click (attrs b))))
        (is (= "button" (:type (attrs b))))))
    (testing "every gesture stops the click from reaching the cell's own onclick"
      (doseq [b buttons]
        (is (str/includes? (:onclick (attrs b)) "event.stopPropagation()")
            (str "no stopPropagation in: " (pr-str (:onclick (attrs b)))))))))

;; @spec EDIT-006
(deftest editable-reads-the-text-from-the-input-at-click-time
  (let [expr (:onclick (attrs (first (nodes (note-editable) :button))))]
    (testing "the browser-owned draft is read from the DOM, never from a signal"
      (is (str/includes?
           expr
           "'text':this.closest('.ds-editable').querySelector('input').value"))
      (is (not (str/includes? expr "$"))))
    (testing "the row's identity is the SERVER's, rendered as literals"
      (is (str/includes? expr "'tx-id':'t1'"))
      (is (str/includes? expr "'date':'2026-09-01'"))
      (is (str/includes? expr "'kind':'begins'")))
    (is (str/includes? expr "postJSON('/r/1/note/submit'"))))

;; @spec EDIT-007
(deftest editable-stamps-one-command-id-on-every-action
  (let [buttons (nodes (note-editable :command-id "cmd-7") :button)]
    (testing "one id per open editor, so a double-fire is a REPLAY, not a second command"
      (doseq [b (butlast buttons)]
        (is (str/includes? (:onclick (attrs b)) "'command-id':'cmd-7'"))))
    (testing "cancel carries it too"
      (is (str/includes? (:onclick (attrs (last buttons))) "'command-id':'cmd-7'"))))
  (testing "without one, nothing is stamped — the endpoint must be naturally replay-safe"
    (is (not (str/includes? (:onclick (attrs (first (nodes (note-editable) :button))))
                            "command-id")))))

;; @spec EDIT-008
(deftest editable-cancel-posts-nothing-but-the-close
  (let [cancel (last (nodes (note-editable) :button))]
    (is (= (str "postJSON('/r/1/note/cancel',{}).catch(e=>console.error(e))"
                ";event.stopPropagation();return false")
           (:onclick (attrs cancel)))))
  (testing "no :cancel renders no cancel button"
    (is (= 3 (count (nodes (note-editable :cancel nil) :button))))))

;; @spec EDIT-009
(deftest editable-keyboard-submits-and-cancels-without-waking-the-page-keymap
  (let [expr (:onkeydown (attrs (first (nodes (note-editable) :input))))]
    (is (str/starts-with? expr "event.stopPropagation();")
        "a page-level keydown__window must not also see these keystrokes")
    (testing "one event idiom here too — `event`, never Datastar's `evt`"
      (is (not (str/includes? expr "evt."))))
    (testing "Enter submits the FIRST action"
      (is (str/includes? expr "event.key==='Enter'"))
      (is (str/includes? expr "'kind':'begins'"))
      (is (not (str/includes? expr "'kind':'ends'"))))
    (testing "Escape cancels"
      (is (str/includes? expr "event.key==='Escape'"))
      (is (str/includes? expr "postJSON('/r/1/note/cancel',{})"))))
  (testing "no actions and no cancel means nothing but the propagation stop"
    (is (= "event.stopPropagation();"
           (:onkeydown (attrs (first (nodes (ds/editable {:actions [] :cancel nil}) :input))))))))

;; @spec EDIT-010
(deftest editable-refuses-an-action-it-could-not-render
  (is (thrown? AssertionError (ds/editable {:actions [{:label "go"}]})))
  (is (thrown? AssertionError (ds/editable {:actions [{:url "/go"}]})))
  (is (thrown? AssertionError (ds/editable {:actions [] :cancel {:label "cancel"}}))))

;; @spec EDIT-004, EDIT-006
(deftest editable-expressions-survive-hiccup-2-rendering
  (let [h2 (requiring-resolve 'hiccup2.core/html)   ; a macro var: eval, don't invoke
        html (str (eval (list h2 (note-editable :value "Gene's \"note\""))))]
    (testing "attribute-value entities are the browser's problem, not ours"
      (is (str/includes? html "data-star-ignore-morph=\"\""))
      (is (str/includes? html "postJSON(&apos;/r/1/note/submit&apos;"))
      (is (str/includes? html "querySelector(&apos;input&apos;).value")))
    (is (str/includes? html "class=\"ds-editable\""))
    (testing "a quote in the existing note is escaped as an attribute, not as JS"
      (is (str/includes? html "value=\"Gene&apos;s &quot;note&quot;\"")))))
