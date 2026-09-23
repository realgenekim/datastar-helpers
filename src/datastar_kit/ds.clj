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
   - Binding: bind, signal-ref (one kebab-case name, both spellings)
   - Browser-owned text: editable (the one open input inside a pushed region)
   - Choosing one value: picker (server-owned selection; fence in datastar-kit.picker)
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
  (:require
   [clojure.spec.alpha :as s]
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

(defn- kebab-signal
  "Return the signal's name, or throw when it carries an uppercase letter.

   HTML attribute names are ASCII-lowercased by the parser, so
   `data-star-bind:noteText` reaches Datastar as `data-star-bind:notetext` and
   binds the signal `notetext`. An expression elsewhere reading `$noteText`
   then reads a DIFFERENT, always-empty signal: every submit arrives blank,
   with no error anywhere. One kebab-case name plus `signal-ref` is the only
   spelling an app writes; the two forms are derived, never hand-typed."
  [signal-name]
  (let [nm (name signal-name)]
    (when (re-find #"[A-Z]" nm)
      (throw (ex-info
              (str "signal " (pr-str nm) " has an uppercase letter. The browser lowercases "
                   "attribute names, so data-star-bind:" nm " binds \"" (str/lower-case nm)
                   "\", not \"" nm "\" — and every expression reading $" nm " sees an empty "
                   "signal, silently. Name the signal in kebab-case (:note-text) and read it "
                   "with (ds/signal-ref :note-text) => \"$noteText\".")
              {:type :ds/camel-case-signal :signal nm})))
    nm))

(defn signal-ref
  "The expression spelling of a kebab-case signal name, using Datastar's own
   camelCase rule (`-` followed by a lowercase letter upcases that letter).
   Pair it with `bind` so ONE name yields both spellings and neither is typed
   by hand — the mismatch between them is invisible at runtime.
     (signal-ref :note-text) => \"$noteText\"
     (signal-ref :chat)      => \"$chat\""
  [signal-name]
  (let [nm (kebab-signal signal-name)]
    (str "$" (str/replace nm #"-[a-z]" #(str/upper-case (subs % 1))))))

(defn bind
  "Return a Hiccup attribute map for data-star-bind on a signal name.
   Prevents the `true` vs `\"\"` Datastar bug — raw {:data-star-bind:foo true}
   renders `true` as the attribute value, which kills ALL Datastar processing
   on the page. This helper uses \"\" which Datastar interprets correctly.

   Refuses an uppercase letter in the name (`:ds/camel-case-signal`): the
   browser lowercases attribute names, so a camelCase bind and a `$camelCase`
   read silently address two different signals. Use kebab-case plus
   `signal-ref`.
   Usage: (merge {:id \"chat-input\"} (ds/bind :chat-msg))"
  [signal-name]
  {(keyword (str "data-star-bind:" (kebab-signal signal-name))) ""})

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

;; ---------------------------------------------------------------------------
;; editable — THE one open text input inside a server-pushed region
;; ---------------------------------------------------------------------------

(def ^:private editable-draft-js
  "The browser-owned draft, read out of the DOM at gesture time. `this` is the
   element carrying the inline handler, so this works from a button and from
   the input itself."
  (->JsExpr "this.closest('.ds-editable').querySelector('input').value"))

(defn- editable-action-js
  "The postJSON call one editable gesture makes: the server's own literal
   payload, the edit session's command id when there is one, and the draft read
   out of the DOM at click time."
  [{:keys [url payload]} command-id]
  (post-action* url (cond-> (or payload {})
                      command-id (assoc :command-id command-id)
                      :always (assoc :text editable-draft-js))))

(defn- editable-cancel-js
  "The postJSON call that closes the editor. It carries no draft — cancel
   discards it — so its body is the command id or nothing at all."
  [{:keys [url]} command-id]
  (post-action* url (cond-> {} command-id (assoc :command-id command-id))))

(defn- editable-gesture
  "A gesture handler for an inline `onclick`: post, then contain the click.
   `return false` keeps a stray default out of an ancestor form."
  [js]
  (str js ";event.stopPropagation();return false"))

(defn- editable-keydown-js
  "Enter submits the FIRST action, Escape cancels, and every keystroke stops
   there. The leading stopPropagation is the point: a page-level
   `data-star-on:keydown__window` map listens on the window, so without it the
   page's own j/k/Escape bindings fire while the user is typing.

   `keydown-expr`/`on-key` deliberately do NOT fit here — they are built for a
   page-level Datastar keymap: they speak `evt` (this element speaks `event`,
   the one idiom inside an editable) and `guard-input` exists to SKIP a text
   input, which is exactly the element we are on."
  [actions cancel command-id]
  (str "event.stopPropagation();"
       (when-let [a (first actions)]
         (str "if(event.key==='Enter'){event.preventDefault();"
              (editable-action-js a command-id) "}"))
       (when cancel
         (str "if(event.key==='Escape'){event.preventDefault();"
              (editable-cancel-js cancel command-id) "}"))))

(defn editable
  "Hiccup for THE one open text input inside a server-pushed region, plus its
   action buttons. The server owns committed state; the browser owns the active
   draft, focus, selection, composition and undo. This is the one element in a
   server-is-the-game-loop page where that second half is true, so the kit owns
   it rather than leaving each app to rediscover the three ways it breaks.

   Options
     :actions     [{:label \"begins here\" :url \"/r/1/note/submit\"
                    :payload {:tx-id \"t1\" :date \"2026-09-01\" :kind \"begins\"}} ...]
     :cancel      {:label \"cancel\" :url \"/r/1/note/cancel\"} — optional
     :command-id  a server-minted id for THIS edit session, stamped on every
                  gesture — optional
     :value       the existing text, when editing an existing item
     :placeholder :class :autofocus?  (autofocus? defaults true)

   Open and close
     Render it to open; render NOTHING to close. `data-star-ignore-morph` (the
     aliased spelling this kit's pinned client reads) makes a later push skip
     this subtree entirely as long as both the old and the incoming element
     carry it — so the server never has to add \"freeze the region while typing\"
     logic of its own. That hand-rolled freeze is the first bug this prevents:
     it also froze the render that OPENS the input.

   Why no signal
     The draft is NOT a Datastar signal. A bind of `:noteText` becomes
     `data-star-bind:notetext` in the parser while the submit expression reads
     `$noteText`, and every submit arrives blank with no error. Each gesture
     reads its sibling input at click time instead:
     `this.closest('.ds-editable').querySelector('input').value`. The wrapper
     class is therefore part of the contract, not decoration.

   Why plain onclick everywhere
     One event idiom inside the editor — `event` and `this`, never Datastar's
     `evt` — so no handler here can be read in the wrong dialect. Every gesture,
     and the wrapper itself, calls `event.stopPropagation()`: these controls sit
     inside a cell whose own onclick OPENS the editor, and without it one click
     sends the submit AND a racing open.

   Payload discipline
     `:payload` values are the SERVER's, rendered as literals. Never put
     `evt`/cursor/selection state in them: the click may land after focus has
     moved, and the gesture would then act on the wrong row. `:text` is the one
     key the kit adds, and its name is fixed.

   Replay
     A fire-and-forget client can always turn one click into two POSTs
     (bubbling, a double tap, a retry). Give the edit session a `:command-id`
     and make the endpoint commit that id once; prove it with
     `datastar-kit.testing/assert-command-replay`."
  [{:keys [placeholder value actions cancel class autofocus? command-id]
    :or {autofocus? true}}]
  (doseq [{:keys [url label]} actions]
    (assert (and (string? url) (not (str/blank? url)))
            "editable :actions entry needs a :url")
    (assert (and (string? label) (not (str/blank? label)))
            "editable :actions entry needs a :label"))
  (assert (or (nil? cancel) (and (string? (:url cancel)) (not (str/blank? (:url cancel)))))
          "editable :cancel needs a :url")
  ;; NOTE: the `.ds-editable` in this tag and in `editable-draft-js` are one
  ;; contract — a gesture finds its input through that class. Change both.
  [:div.ds-editable
   (cond-> {:data-star-ignore-morph ""
            :onclick "event.stopPropagation()"}
     class (assoc :class class))
   [:input (cond-> {:type "text"
                    :class "ds-editable-input"
                    :onkeydown (editable-keydown-js actions cancel command-id)}
             placeholder (assoc :placeholder placeholder)
             (some? value) (assoc :value value)
             autofocus? (assoc :autofocus true))]
   (into [:div.ds-editable-actions]
         (concat
          (for [{:keys [label] :as action} actions]
            [:button {:type "button"
                      :onclick (editable-gesture (editable-action-js action command-id))}
             label])
          (when cancel
            [[:button {:type "button"
                       :onclick (editable-gesture (editable-cancel-js cancel command-id))}
              (or (:label cancel) "cancel")]])))])

;; ---------------------------------------------------------------------------
;; picker — choose ONE value from a server-owned list, with ONE owner for it
;; ---------------------------------------------------------------------------

(defn- js-str
  "A Clojure string as a single-quoted JS string literal, escaped so a quote,
   a backslash or a line break in server data cannot end the literal early.
   (`js-val` quotes without escaping; picker values are data, not code.)"
  [s]
  (str "'"
       (-> (str s)
           (str/replace "\\" "\\\\")
           (str/replace "'" "\\'")
           (str/replace "\n" "\\n")
           (str/replace "\r" "\\r")
           (str/replace " " "\\u2028")
           (str/replace " " "\\u2029"))
       "'"))

(def ^:private picker-seq-js
  "The ordering fence: read `data-kit-seq` off this picker's filter wrapper, add
   one, write it back, return it. The wrapper is the one element a push never
   replaces (it carries data-star-ignore-morph), so the counter survives every
   push for the life of the edit session. It is NOT application state -- it
   carries no value, only the order the user acted in -- and the server uses it
   to refuse what arrives out of order (datastar-kit.picker)."
  (->JsExpr (str "(function(w){var s=(parseInt(w.dataset.kitSeq,10)||0)+1;"
                 "w.dataset.kitSeq=String(s);return s})"
                 "(this.closest('.ds-picker').querySelector('.ds-picker-filter'))")))

(defn- picker-post
  "One picker gesture's postJSON call: `body` plus the next seq and, when there
   is one, the session's command id."
  [url body command-id]
  (post-action* url (cond-> (assoc body :seq picker-seq-js)
                      command-id (assoc :command-id command-id))))

(defn- picker-click-js
  "Keyboard shortcut -> the CURRENT button. The filter's handlers are frozen by
   ignore-morph at first render and cannot know today's selection; the buttons
   render outside the island and are always current, so Enter/Escape click
   them instead of re-deriving what they would post."
  [cls]
  (str "{var b=this.closest('.ds-picker').querySelector('." cls "');"
       "if(b&&!b.disabled)b.click()}"))

(defn- picker-keydown-js
  [{:keys [move-url cancel]} command-id]
  (str "event.stopPropagation();"
       "if(event.key==='ArrowDown'){event.preventDefault();"
       (picker-post move-url {:dir "down"} command-id) "}"
       "if(event.key==='ArrowUp'){event.preventDefault();"
       (picker-post move-url {:dir "up"} command-id) "}"
       "if(event.key==='Enter'){event.preventDefault();"
       (picker-click-js "ds-picker-submit") "}"
       (when cancel
         (str "if(event.key==='Escape'){event.preventDefault();"
              (picker-click-js "ds-picker-cancel") "}"))))

(defn picker
  "Hiccup for a picker: a filter box, the server's matches, and submit/cancel.
   The chosen value has ONE owner -- the server. The box is a filter, never a
   value, and the submit carries no value at all.

   Options
     :filter-url   POST {q, seq, command-id} on every keystroke in the filter
     :pick-url     POST {value, seq, command-id} on a click on an item (and on
                   Enter on a focused item: items are buttons)
     :move-url     POST {dir, seq, command-id} on ArrowDown/ArrowUp in the filter
     :submit       {:label :url :payload} -- POSTs the literal :payload plus
                   seq and command-id, and NOTHING else: never the box text,
                   never a value. The server commits its own selection.
     :cancel       {:label :url} -- optional; POSTs {command-id}
     :command-id   the server-minted edit-session id
     :placeholder  the filter's placeholder; :autofocus? (default true)
     :items        [{:value :label} ...] -- the server's current matches
     :selected     the server's selection (a :value), or nil
     :message      feedback, rendered beside the buttons
     :seq          the last seq the server APPLIED for this session (default 0).
                   The counter starts here, so a page reloaded mid-session
                   continues the server's order instead of restarting below it
                   and having every gesture fenced out.
     :class        extra class on the root

   Why this shape (the bug it makes unrepresentable)
     The first picker we shipped prefilled the box with the current value,
     posted the box text on submit, and let \"text that names a value\" beat the
     server's selection. A click on the list moved the selection and never the
     box, and the prefill always named a value -- so a click could never win.
     Two owners, one reconciler, one bug. Here the box opens EMPTY, only the
     server's selection is ever committed, and the submit's body has no field a
     reconciler could read.

   What survives a push, and what is re-rendered
     Only the filter wrapper (`.ds-picker-filter`) carries
     `data-star-ignore-morph`: the caret and the half-typed filter survive. The
     item list, the highlight, the submit's `disabled` (true exactly when
     :selected is nil) and :message render OUTSIDE it, so every push updates
     them. The wrapper's id is keyed by :command-id, so a new session never
     inherits an old session's frozen input.

   The ordering fence (`data-kit-seq`)
     The one piece of client state. Every gesture increments an integer on the
     filter wrapper and posts it as `seq`; the server applies a filter, pick or
     move only when `datastar-kit.picker/picker-accept?` (later than the last
     applied) and a submit only when `picker-caught-up?` (exactly the next one),
     so a late filter response cannot undo a later pick and a submit cannot
     overtake a pick still in flight.

   Keys in the filter: ArrowDown/ArrowUp move, Enter clicks submit when it is
   enabled, Escape clicks cancel; every keystroke stops propagating so a page
   keymap never sees typing. Every handler is a plain onclick/oninput/onkeydown
   that calls event.stopPropagation(); every button is type=\"button\".
   Intent and specs: docs/intent/picker/."
  [{:keys [filter-url pick-url move-url submit cancel command-id placeholder
           items selected message class autofocus? seq]
    :or {autofocus? true}
    :as opts}]
  (doseq [[k v] [[:filter-url filter-url] [:pick-url pick-url] [:move-url move-url]
                 [:submit-url (:url submit)]]]
    (assert (and (string? v) (not (str/blank? v)))
            (str "picker needs a non-blank " k)))
  (assert (or (nil? cancel) (and (string? (:url cancel)) (not (str/blank? (:url cancel)))))
          "picker :cancel needs a :url")
  (let [gesture (fn [js] (str js ";event.stopPropagation();return false"))]
    [:div (cond-> {:class (str/join " " (remove nil? ["ds-picker" class]))
                   :onclick "event.stopPropagation()"})
     ;; NOTE: `.ds-picker` and `.ds-picker-filter` are read by picker-seq-js and
     ;; picker-click-js -- the classes are a contract, not decoration.
     [:div (cond-> {:class "ds-picker-filter"
                    :data-star-ignore-morph ""
                    :data-kit-seq (str (or seq 0))}
             command-id (assoc :id (str "ds-picker-" command-id)))
      [:input (cond-> {:type "text"
                       :class "ds-picker-input"
                       :autocomplete "off"
                       :oninput (str (picker-post filter-url {:q (->JsExpr "this.value")} command-id)
                                     ";event.stopPropagation()")
                       :onkeydown (picker-keydown-js opts command-id)}
                placeholder (assoc :placeholder placeholder)
                autofocus? (assoc :autofocus true))]]
     (into [:ul {:class "ds-picker-items"}]
           (for [{:keys [value label]} items
                 :let [sel? (= value selected)]]
             [:li
              [:button (cond-> {:type "button"
                                :class (if sel? "ds-picker-item ds-picker-selected" "ds-picker-item")
                                :onclick (gesture (picker-post pick-url {:value (->JsExpr (js-str value))}
                                                               command-id))}
                         sel? (assoc :aria-selected "true"))
               (str (or label value))]]))
     (into [:div {:class "ds-picker-actions"}
            [:button (cond-> {:type "button"
                              :class "ds-picker-submit"
                              :onclick (gesture (picker-post (:url submit) (or (:payload submit) {})
                                                             command-id))}
                       (nil? selected) (assoc :disabled true))
             (or (:label submit) "submit")]]
           (concat
            (when cancel
              [[:button {:type "button"
                         :class "ds-picker-cancel"
                         :onclick (gesture (post-action* (:url cancel)
                                                         (cond-> {} command-id (assoc :command-id command-id))))}
                (or (:label cancel) "cancel")]])
            (when-not (str/blank? (str (or message "")))
              [[:span {:class "ds-picker-message"} (str message)]])))]))

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
  ([ancestor-sel text-sel]
   (copy-nearest-text ancestor-sel text-sel "Copied!"))
  ([ancestor-sel text-sel message]
   (str "evt.stopPropagation();"
        "navigator.clipboard.writeText("
        "evt.currentTarget.closest('" ancestor-sel "').querySelector('" text-sel "').textContent"
        ").then(()=>showNotification('" message "'))")))

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

(defn set-select
  "JS (for execute-script!) that sets a <select>'s value server-authoritatively, so a
   server-driven change (an SSE morph, keyboard nav, etc.) is reflected in the VISIBLE
   control — the missing half when the server, not a user click, picks the value.

   Crucially handles a **Fomantic-UI dropdown** initialized over the select: setting the
   native `.value` does NOT update Fomantic's rendered widget, so we drive it through its
   API with the change-trigger SUPPRESSED (so it won't re-fire a data-star-on:change @get
   loop). Falls back to the native element when no widget is attached.

     el-id    — the <select> element id
     value-js — a JS expression for the value (e.g. \"'asian'\", a $signal, or a pr-str'd
                string literal like (pr-str \"asian\") => \\\"asian\\\")

   (set-select \"mq-lane\" (pr-str lane))   ;; inside (d*/execute-script! sse-gen ...)"
  [el-id value-js]
  (str "(function(){var el=document.getElementById('" el-id "');if(!el)return;"
       "var v=" value-js ",$=window.jQuery,"
       ;; Fomantic wraps <select> in a parent .ui.dropdown div and hides the select; the
       ;; module + visible .menu/.text live on the WRAPPER, not the select. Drive the wrapper.
       "$dd=($&&$.fn&&$.fn.dropdown)?$(el).closest('.ui.dropdown'):null;"
       "if($dd&&$dd.length&&$dd.find('.menu').length){"
       "$dd.dropdown('set selected',v,false,true);}"            ; (values, $item, preventChangeTrigger, keepSearchTerm)
       "else{el.value=v;}})()"))

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

(defn sse-mount-url
  "Attrs for an app-lifetime SSE stream. Hidden tabs release their connection;
   visible tabs reconnect after failures and deliberate clean closes."
  [url]
  {:data-star-init
   (str "@get('" url
        "',{openWhenHidden:false,retry:'always',retryMaxCount:1000000})")})

(defn sse-mount
  "Organizer SSE mount for `event-id`, using the shared lifecycle policy."
  [event-id]
  (sse-mount-url (str "/api/sse?event-id=" event-id)))

(defn live-scrub
  "Attrs for a continuous control that patches a region through one-shot SSE.

   Owns the binding and throttled input action together so callers cannot mix
   in debounce or a legacy change/submit handler. Signal names must be a single
   word because Datastar camelCases hyphens in expressions."
  ([signal url-prefix]
   (live-scrub signal url-prefix 150))
  ([signal url-prefix throttle-ms]
   (let [nm (name signal)]
     (assert (not (str/includes? nm "-"))
             (str "live-scrub signal must be a single word: " nm))
     (assert (pos-int? throttle-ms)
             (str "live-scrub throttle must be a positive integer: " throttle-ms))
     {(keyword (str "data-star-bind:" nm)) ""
      (keyword (str "data-star-on:input__throttle." throttle-ms "ms"))
      (str "@get('" url-prefix "' + $" nm ")")})))

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

(defn- data-lines
  "Render an SSE data payload as one-or-more `data:` lines. The browser rejoins multiple
   `data:` lines with \\n, so newlines embedded in HTML survive LOSSLESSLY. A single
   `data: <html-with-a-newline>` would otherwise be TRUNCATED at the first newline on the
   wire (a silent footgun — page-load HTML looks fine, only the live push cuts off). Use
   this for any data field that may carry HTML."
  [payload]
  (->> (str/split (str payload) #"\n" -1)
       (map #(str "data: " %))
       (str/join "\n")))

(defn sse-event
  "Build a Datastar SSE event string from a spec'd data map.
   Wrong structure fails fast (spec precondition), not silently on the wire.
   Newlines in fragment HTML are preserved via multi-line `data:` continuation.

   Input: {:fragments [{:id \"foo\" :html \"<b>x</b>\"}] :mode :inner}
   Output: \"event: datastar-patch-elements\\ndata: mode inner\\ndata: elements ...\""
  [{:keys [fragments mode] :as event}]
  {:pre [(s/valid? ::sse-event event)]}
  (str "event: " sse-event-type "\n"
       (when mode (str "data: mode " (name mode) "\n"))
       (str/join "\n"
                 (for [{:keys [id html]} fragments]
                   (data-lines (str sse-data-field " <div id=\"" id "\">" html "</div>"))))
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
       (data-lines (str sse-data-field " " html))
       "\n\n"))

(defn sse-raw
  "Format pre-rendered HTML as a Datastar SSE event.
   HTML must contain an id attribute for morph matching.
   Use sse-event/sse-fragment for new code."
  [html-str]
  {:pre [(string? html-str)
         (re-find #"id=\"[^\"]+\"" html-str)]}
  (str "event: " sse-event-type "\n"
       (data-lines (str sse-data-field " " html-str))
       "\n\n"))
