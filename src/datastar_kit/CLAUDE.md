# Datastar Kit — Shared Infrastructure for Datastar/SSE Projects

Portable, project-agnostic helpers for Clojure + Datastar + SSE applications.

## Files

| File | Purpose |
|------|---------|
| `ds.clj` | Generates safe JS expressions for Hiccup `data-star-*` attributes |
| `sse.clj` | Reliable SSE broadcast, raw-channel flavor (`ds/sse-event` strings): off-thread push, heartbeat, reaping |
| `sse_sdk.clj` | Same reliability, **SDK flavor** (`hk/->sse-response` + `patch-elements!`): `push!`/`push-signals!`, `sse-response` with `on-connect` |
| `resources/public/js/datastar-kit.js` | Runtime JS: `postJSON()`, `showNotification()` |
| `resources/public/js/datastar-auth-fix.js` | HTTP Basic Auth fix: makes `fetch()`-based `@get`/`@post` work behind credentialed URLs; load BEFORE the Datastar module |
| `resources/public/vendor/datastar-aliased.js` | Vendored Datastar (CDN returns 404) |

## Architecture: Server is the Game Loop

```
Input → POST → Server state mutation → SSE push HTML → 204 No Content
```

Server owns ALL state in a Clojure atom. DOM is a display terminal.
Client fires POST and forgets. Zero JSON parsing. Zero client rendering.

## Quick Reference

```clojure
(require '[datastar-kit.ds :as ds])

;; Signal arithmetic (prevents $signal-1 camelCase bug)
(ds/signal-inc "$idx" 27)     ;; $idx=Math.min(($idx) + 1, 27)
(ds/signal-dec "$idx")        ;; $idx=Math.max(($idx) - 1, 0)

;; Keyboard handlers
(ds/on-key "j" {} (ds/signal-inc "$idx" 27))
(ds/on-alt "x" "clearAI()")
(ds/on-meta "z" "doUndo()")
(ds/keydown-expr [global-shortcuts] [nav-shortcuts])

;; Click handlers (requires datastar-kit.js for postJSON)
(ds/post-action* "/api/delete" {:id item-id})
(ds/post-action* "/api/move" {:idx (ds/js "$dIdx")})

;; Bind (prevents the true attribute bug)
(merge {:id "input"} (ds/bind :chat-msg))

;; Clipboard (browser-native, user gesture required)
(ds/copy-text "some-id")
(ds/copy-nearest-text ".card" ".card-body")
```

## NEVER Do These

1. **NEVER use `{:data-star-bind:foo true}`** — kills ALL Datastar processing on the page. Use `(ds/bind :foo)`.

2. **NEVER write `$signal-1`** — Datastar parses hyphens as camelCase: `$dIdx-1` becomes `$dIdx1`. Use `(ds/signal-dec "$dIdx")` or `($dIdx) - 1`.

3. **NEVER use raw JS strings** — Use ds.clj helpers. If you need new JS behavior, add a helper.
   ```clojure
   ;; WRONG
   {:onclick "fetch('/api/foo',{method:'POST',...})"}
   ;; RIGHT
   {:onclick (ds/post-action* "/api/foo" {:bar "baz"})}
   ```

4. **NEVER use `data-star-on:click` on 10+ repeated elements** — Datastar recompiles every expression on SSE morph. Use plain `onclick` with `fetch()` for repeated elements.

## SSE Toast Pattern

Every page should have `[:div#error-toast]`. Push toasts with `pm-outer`:

```clojure
(sse/push-fragment! "#error-toast"
                    #(views/success-toast "Done!")
                    d*/pm-outer)
```

**MUST use `pm-outer`** — toast HTML includes the `#error-toast` wrapper. With `pm-inner`, Idiomorph sees matching IDs and no-ops.

## Server Keymap Pattern (Advanced)

For keyboard-heavy apps, prefer data-driven dispatch over ds.clj string builders:

```clojure
;; PURE function: context map → action vectors (testable, no side effects)
(defn dispatch-key [{:keys [key cursor project-id]}]
  (case key
    "j" [{:type :cursor :pos (inc cursor)}]
    "p" [{:type :set-pending :value :priority}
         {:type :toast :msg "p…"}]
    nil))

;; TRIVIAL executor: case on :type, calls well-tested functions
(defn execute-action! [action]
  (case (:type action)
    :cursor (update-cursor! (:pos action))
    :toast  (push-toast! (:msg action))
    ...))
```

This is safer than assembling JS strings — errors surface at compile/test time, not in the browser console. Use ds.clj's `post-action*` for simple onclick handlers; use the server keymap pattern for keyboard shortcuts.

## Projects Using This Kit

- `joe-payne-app` (ESR Dashboard) — member management, Datastar SDK
- `mothership` — IDE, raw SSE, full keyboard dispatch
- `social-media-writer` — content creation, raw SSE

## Provenance

Superset extracted from mothership (34 helpers), social-media-writer (33), and ESR dashboard. Battle-tested across 3+ production apps since 2025.
