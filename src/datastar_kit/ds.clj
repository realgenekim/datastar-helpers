(ns datastar-kit.ds
  "Datastar expression helpers — portable across all Datastar/SSE projects.

   Generates safe JavaScript expression strings for use in Hiccup data-star-*
   attributes. Prevents common Datastar bugs (camelCase parsing, true attribute,
   scope collisions).

   ## What's here (generic, reusable)
   - Signal arithmetic: signal-inc, signal-dec, signal-set, signal-clamp
   - Keyboard builders: on-key, on-alt, on-meta, on-ctrl, guard-input, keydown-expr
   - Payload & values: JsExpr, js, js-val, js-payload, $value, $checked, $key, $text
   - Server actions: post-action*, click-action, fetch-then-reload, fetch-swap
   - Binding: bind (prevents the true vs \"\" Datastar bug)
   - URL: replace-url (server-owned location bar via the replaceUrl plugin)
   - Clipboard: copy-nearest-text, copy-text, copy-text-js
   - Focus/scroll: js-focus-element, js-scroll-into-view
   - SSE event constructors: sse-event, sse-fragment, sse-inner, sse-raw

   ## What's NOT here (project-specific — put in your own ds.clj)
   - post-action with hardcoded URL prefix (e.g., /api/distillery/)
   - post-action using raw fetch() instead of postJSON()
   - switch-tab (project-specific DOM selectors)
   - scroll-to-focused with SSE delay timing
   - dropdown component (project-specific signal conventions)

   ## Runtime contract
   post-action* emits calls to postJSON(url, body).
   Clipboard helpers emit calls to showNotification(msg).
   Load datastar-kit.js BEFORE your app.js, or provide your own
   postJSON/showNotification implementations.

   See also: datastar-kit CLAUDE.md for full usage guide."
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Signal arithmetic — prevents the $foo-1 camelCase parsing bug
;;
;; Datastar's expression parser converts hyphens to camelCase for signal names.
;; $dIdx-1 becomes $dIdx1 (undefined -> 0), NOT $dIdx minus 1.
;; Always parenthesize: ($dIdx) - 1. These helpers do that for you.
;; ---------------------------------------------------------------------------

(defn signal-inc
  "Increment a signal, clamped to max-val.
   (signal-inc \"$dIdx\" 27) => \"$dIdx=Math.min(($dIdx) + 1, 27)\""
  [signal max-val]
  (str signal "=Math.min((" signal ") + 1," max-val ")"))

(defn signal-dec
  "Decrement a signal, clamped to 0.
   (signal-dec \"$dIdx\") => \"$dIdx=Math.max(($dIdx) - 1, 0)\""
  [signal]
  (str signal "=Math.max((" signal ") - 1, 0)"))

(defn signal-set
  "Set a signal to a value.
   (signal-set \"$dCol\" \"'left'\") => \"$dCol='left'\""
  [signal value]
  (str signal "=" value))

(defn signal-clamp
  "Clamp a signal to [0, max-val].
   (signal-clamp \"$dIdx\" 27) => \"$dIdx=Math.min($dIdx, 27)\""
  [signal max-val]
  (str signal "=Math.min(" signal "," max-val ")"))

;; ---------------------------------------------------------------------------
;; Keydown expression builder
;; ---------------------------------------------------------------------------

(defn on-key
  "Build a guarded keydown clause. Ignores Ctrl/Cmd so browser shortcuts pass through.
   (on-key \"j\" {} \"doSomething()\")
   => \"if(!evt.ctrlKey&&!evt.metaKey&&evt.key==='j'){evt.preventDefault();doSomething()}\"

   (on-key \"J\" {:shift true} \"reorderDown()\")
   => \"if(!evt.ctrlKey&&!evt.metaKey&&evt.shiftKey&&evt.key==='J'){...}\""
  [key {:keys [shift]} & body-strs]
  (str "if(!evt.ctrlKey&&!evt.metaKey&&"
       (when shift "evt.shiftKey&&")
       "evt.key==='" key "'"
       "){evt.preventDefault();"
       (apply str body-strs)
       "}"))

(defn guard-input
  "Skip keydown when focus is in a text input.
   Prepend to data-star-on:keydown expressions.
   NOTE: prefer `keydown-expr` which handles ordering automatically."
  []
  "if(evt.target.tagName==='INPUT'||evt.target.tagName==='TEXTAREA')return;")

;; Mac Alt/Option key produces dead characters (Alt+C -> c-cedilla,
;; Alt+L -> not sign). Always use evt.code for Alt+ shortcuts, not evt.key.
(def ^:private mac-alt-codes
  "Map of logical key name to evt.code value, for Alt+ shortcuts on Mac."
  {"x" "KeyX" "z" "KeyZ" "d" "KeyD" "s" "KeyS" "c" "KeyC" "v" "KeyV"
   "a" "KeyA" "e" "KeyE" "f" "KeyF" "k" "KeyK" "n" "KeyN" "p" "KeyP"
   "r" "KeyR" "t" "KeyT" "w" "KeyW" "q" "KeyQ" "o" "KeyO" "i" "KeyI"
   "l" "KeyL" "g" "KeyG" "h" "KeyH" "j" "KeyJ" "m" "KeyM" "b" "KeyB"
   "1" "Digit1" "2" "Digit2" "3" "Digit3" "4" "Digit4" "5" "Digit5"
   "6" "Digit6" "7" "Digit7" "8" "Digit8" "9" "Digit9" "0" "Digit0"})

(defn on-meta
  "Keydown clause for Cmd/Ctrl shortcuts. Uses evt.key.
   (on-meta \"z\" \"doUndo()\") => if((evt.metaKey||evt.ctrlKey)&&evt.key==='z'){...}"
  [key & body-strs]
  (str "if((evt.metaKey||evt.ctrlKey)&&evt.key==='" key "'){evt.preventDefault();"
       (apply str body-strs) ";return}"))

(defn on-ctrl
  "Keydown clause for Ctrl-only shortcuts (not Cmd).
   (on-ctrl \"1\" \"switchPanel()\") => if(evt.ctrlKey&&!evt.metaKey&&evt.key==='1'){...}"
  [key & body-strs]
  (str "if(evt.ctrlKey&&!evt.metaKey&&evt.key==='" key "'){evt.preventDefault();"
       (apply str body-strs) ";return}"))

(defn on-alt
  "Keydown clause for Alt/Option shortcuts. Uses evt.code for Mac safety.
   (on-alt \"x\" \"clearAI()\") => if(evt.altKey&&evt.code==='KeyX'){...}"
  [key & body-strs]
  (let [code (get mac-alt-codes key (str "Key" (str/upper-case key)))]
    (str "if(evt.altKey&&evt.code==='" code "'){evt.preventDefault();"
         (apply str body-strs) ";return}")))

(defn keydown-expr
  "Build a complete keydown expression with correct ordering:
   1. global-strs  — always fire (meta/alt shortcuts, work in textareas)
   2. guard-input  — skip rest if focus is in INPUT/TEXTAREA
   3. nav-strs     — only fire outside text inputs (j/k/h/l, etc.)

   (keydown-expr
     [(on-meta \"z\" undo-action) (on-alt \"x\" clear-action)]
     [(on-key \"j\" {} nav-down) (on-key \"k\" {} nav-up)])"
  [global-strs nav-strs]
  (apply str (concat global-strs [(guard-input)] nav-strs)))

;; ---------------------------------------------------------------------------
;; Datastar bind helper — prevents the `true` value attribute bug
;; ---------------------------------------------------------------------------

(defn bind
  "Return a Hiccup attribute map for data-star-bind on a signal name.
   Prevents the `true` vs `\"\"` Datastar bug — raw {:data-star-bind:foo true}
   renders `true` as the attribute value, which kills ALL Datastar processing
   on the page. This helper uses \"\" which Datastar interprets correctly.
   Usage: (merge {:id \"chat-input\"} (ds/bind :chat-msg))"
  [signal-name]
  {(keyword (str "data-star-bind:" (name signal-name))) ""})

;; ---------------------------------------------------------------------------
;; Raw JS expressions — tagged type for safe payload generation
;; ---------------------------------------------------------------------------

(defrecord JsExpr [expr])

(defn js
  "Mark a value as a raw JS expression (not auto-quoted).
   Use for signal refs, event properties, and JS variables:
     (ds/js \"$dIdx\")          => emits $dIdx (raw)
     (ds/js \"evt.target.value\") => emits evt.target.value (raw)
   Without ds/js, strings are auto-quoted as JS string literals:
     \"twitter\" => emits 'twitter'
     42        => emits 42"
  [expr]
  (->JsExpr expr))

;; Client param shortcuts (Hyper-inspired)
;; Use: {:fleet ds/$value} instead of {:fleet (ds/js "evt.target.value")}
(def $value   "evt.target.value — input/select/textarea" (->JsExpr "evt.target.value"))
(def $checked "evt.target.checked — checkbox/radio"      (->JsExpr "evt.target.checked"))
(def $key     "evt.key — keyboard event key name"        (->JsExpr "evt.key"))
(def $text    "evt.target.textContent — element text"    (->JsExpr "evt.target.textContent"))

;; ---------------------------------------------------------------------------
;; Server action helpers — inline postJSON() calls for Datastar expressions
;; Requires datastar-kit.js loaded (provides postJSON global function)
;; ---------------------------------------------------------------------------

(defn- js-val
  "Convert a Clojure value to a safe JS literal string.
   JsExpr values are emitted raw (for signals, event refs, variables).
   Everything else is auto-quoted/stringified."
  [v]
  (cond
    (instance? JsExpr v) (:expr v)
    (string? v)          (str "'" v "'")
    (number? v)          (str v)
    (boolean? v)         (str v)
    (nil? v)             "null"
    (keyword? v)         (str "'" (name v) "'")
    :else                (str "'" v "'")))

(defn- js-payload
  "Build JS object literal pairs. Keys are always quoted (prevents hyphen bugs).
   Values are auto-quoted unless wrapped in (ds/js ...).
     {:run-idx 0 :model \"qwen\"} => 'run-idx':0,'model':'qwen'
     {:idx (ds/js \"$dIdx\")}     => 'idx':$dIdx"
  [payload-map]
  (str/join ","
            (map (fn [[k v]] (str "'" (name k) "':" (js-val v))) payload-map)))

(defn post-action*
  "Generate an inline postJSON() call for Datastar on:click expressions.
   Takes a full URL path. Values are safe by default (auto-quoted).
   Use (ds/js expr) for raw JS expressions (signals, event refs).
   Requires postJSON() from datastar-kit.js.
   (post-action* \"/api/chat\" {:message (ds/js \"m.value.trim()\")})
   => postJSON('/api/chat',{'message':m.value.trim()}).catch(e=>console.error(e))"
  [url payload-map]
  (str "postJSON('" url "',{" (js-payload payload-map) "}).catch(e=>console.error(e))"))

(defn replace-url
  "Hiccup attribute map for data-star-replace-url (Datastar's replaceUrl plugin).
   On load/morph Datastar evaluates the value as a JS expression and
   history.replaceState's the result — so the SERVER owns the location bar: stamp
   this on a (morphed) element and the URL follows server state with NO client-side
   observer or DOM glue.

   A string is auto-quoted as a JS string literal; pass (ds/js \"...\") for a raw
   expression that references signals/JS (e.g. (ds/js \"'/p?s='+$sid\")).
   Requires the replaceUrl plugin in your Datastar build.
     (merge attrs (ds/replace-url \"/stories/for-you?s=abc\"))
     => {:data-star-replace-url \"'/stories/for-you?s=abc'\"}"
  [url-or-expr]
  {:data-star-replace-url (js-val url-or-expr)})

;; ---------------------------------------------------------------------------
;; Block-scoped click action — prevents `const`/`let` collisions in Datastar
;; ---------------------------------------------------------------------------

(defn click-action
  "Wrap a multi-statement click handler in a block scope.
   Datastar shares ONE scope across all data-star-on:click on a page.
   `let el=...` in one button collides with `let el=...` in another.
   This wraps in `{...}` so variables are block-scoped.
   (click-action \"let el=evt.target;\" (post-action* \"/api/foo\" {:id \"el.id\"}))
   => \"{let el=evt.target;postJSON('/api/foo',...)}\"  "
  [& strs]
  (str "{" (apply str strs) "}"))

;; ---------------------------------------------------------------------------
;; Focus/scroll helpers — generic browser primitives
;; ---------------------------------------------------------------------------

(defn js-focus-element
  "JS snippet: focus element by id."
  [element-id]
  (str "document.getElementById('" element-id "')?.focus()"))

(defn js-scroll-into-view
  "JS snippet: scroll element into view."
  [block]
  (str "if(f)f.scrollIntoView({block:'" block "',behavior:'smooth'})"))

;; ---------------------------------------------------------------------------
;; Clipboard helpers — must stay client-side (user gesture required)
;; ---------------------------------------------------------------------------

(defn copy-nearest-text
  "JS expression for data-star-on:click: walk up from the clicked element to
   `ancestor-sel`, find `text-sel` inside it, copy textContent to clipboard, notify.
   Uses `evt.currentTarget` (the element with the data-star-on:click attribute).
   Requires showNotification() from datastar-kit.js."
  [ancestor-sel text-sel]
  (str "evt.stopPropagation();"
       "navigator.clipboard.writeText("
       "evt.currentTarget.closest('" ancestor-sel "').querySelector('" text-sel "').textContent"
       ").then(()=>showNotification('Copied!'))"))

(defn copy-text
  "JS expression for onclick: copy a literal string to clipboard and notify.
   (copy-text \"my-issue-id\") => copies and shows 'Copied: my-issue-id'
   (copy-text path :msg \"Copied!\") => custom notification message"
  [text & {:keys [msg]}]
  (let [safe-text (str/replace (str text) "'" "\\'")
        notify-msg (or msg (str "Copied: " safe-text))]
    (str "navigator.clipboard.writeText('" safe-text "')"
         ".then(()=>showNotification('" (str/replace notify-msg "'" "\\'") "'))")))

(defn copy-text-js
  "Like copy-text but the text argument is a raw JS expression.
   (copy-text-js \"document.getElementById('x').value\") => copies dynamic value"
  [js-expr & {:keys [msg]}]
  (let [notify (or msg "Copied!")]
    (str "navigator.clipboard.writeText(" js-expr ")"
         ".then(()=>showNotification('" (str/replace notify "'" "\\'") "'))")))

;; ---------------------------------------------------------------------------
;; IDE-safe reload — works in both standalone and IDE mode
;; ---------------------------------------------------------------------------

(def reload-page
  "IDE-safe page reload expression. Uses IDE.reload() in IDE mode,
   falls back to window.location.reload() in standalone mode."
  "window.IDE?IDE.reload():window.location.reload()")

(defn fetch-then-reload
  "Fetch a URL (POST by default) then reload the page (IDE-safe).
   (fetch-then-reload \"/api/refresh\")
   (fetch-then-reload \"/foo\" :method \"GET\")"
  [url & {:keys [method] :or {method "POST"}}]
  (str "fetch('" url "',{method:'" method "'}).then(function(){" reload-page "})"))

(defn fetch-swap
  "Fetch a URL (GET) and swap the target element's outerHTML with the response.
   Replaces HTMX hx-get/hx-swap patterns with vanilla JS.
   (fetch-swap \"/api/table\" \"my-table\")"
  [url target-id]
  (str "fetch('" url "').then(r=>r.text()).then(h=>{var el=document.getElementById('" target-id "');if(el)el.outerHTML=h})"))

;; ---------------------------------------------------------------------------
;; SSE event constructors — data-oriented Datastar SSE formatting
;;
;; These are the ONLY functions that should produce SSE event strings.
;; Projects using the Datastar Clojure SDK (starfederation.datastar.clojure.api)
;; don't need these — the SDK handles formatting. These are for projects
;; that write raw SSE event strings by hand.
;;
;; Aliased build (v1.0.0-RC.8) renamed from original SDK:
;;   merge-fragments → patch-elements
;;   fragments       → elements
;;   mergeMode       → mode
;; ---------------------------------------------------------------------------

(def ^:const sse-event-type
  "The SSE event type for Datastar element patching.
   NEVER use 'datastar-merge-fragments' — renamed, silently ignored."
  "datastar-patch-elements")

(def ^:const sse-data-field
  "The SSE data field name for HTML fragments.
   NEVER use 'fragments' — renamed, silently ignored."
  "elements")

(s/def ::id (s/and string? #(not (str/blank? %))))
(s/def ::html (s/and string? #(not (str/blank? %))))
(s/def ::mode #{:morph :inner :outer :prepend :append :before :after})
(s/def ::fragment (s/keys :req-un [::id ::html]))
(s/def ::fragments (s/coll-of ::fragment :min-count 1))
(s/def ::sse-event (s/keys :req-un [::fragments] :opt-un [::mode]))

(defn sse-event
  "Build a Datastar SSE event string from a spec'd data map.
   Wrong structure fails fast (spec precondition), not silently on the wire.

   Input: {:fragments [{:id \"foo\" :html \"<b>x</b>\"}] :mode :inner}
   Output: \"event: datastar-patch-elements\\ndata: mode inner\\ndata: elements ...\""
  [{:keys [fragments mode] :as event}]
  {:pre [(s/valid? ::sse-event event)]}
  (str "event: " sse-event-type "\n"
       (when mode (str "data: mode " (name mode) "\n"))
       (str/join "\n"
                 (for [{:keys [id html]} fragments]
                   (str "data: " sse-data-field " <div id=\"" id "\">" html "</div>")))
       "\n\n"))

(defn sse-fragment
  "Convenience: single-fragment SSE event.
   (sse-fragment \"my-panel\" \"<h1>hello</h1>\")
   (sse-fragment \"my-panel\" \"<h1>hello</h1>\" :inner)"
  ([id html]
   (sse-event {:fragments [{:id id :html html}]}))
  ([id html mode]
   (sse-event {:fragments [{:id id :html html}] :mode mode})))

(defn sse-inner
  "SSE event for innerHTML replacement using selector + mode inner."
  [selector html]
  {:pre [(string? selector) (not (str/blank? selector))
         (string? html) (not (str/blank? html))]}
  (str "event: " sse-event-type "\n"
       "data: selector " selector "\n"
       "data: mode inner\n"
       "data: " sse-data-field " " html
       "\n\n"))

(defn sse-raw
  "Format pre-rendered HTML as a Datastar SSE event.
   HTML must contain an id attribute for morph matching.
   Use sse-event/sse-fragment for new code."
  [html-str]
  {:pre [(string? html-str)
         (re-find #"id=\"[^\"]+\"" html-str)]}
  (str "event: " sse-event-type "\n"
       "data: " sse-data-field " " html-str
       "\n\n"))
