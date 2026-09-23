# datastar-helpers (`datastar-kit`)

Shared, battle-tested building blocks for **Clojure + [Datastar](https://data-star.dev) + http-kit** web apps: safe expression helpers, reliable Server-Sent Events broadcast, and fixes for the sharp edges that bite every Datastar app sooner or later. Write each pattern once, here, and reuse it everywhere — instead of copy-pasting helpers into each repo (which is how they drift).

**You'll want this if you:**
- Are building a Clojure server that owns UI state and pushes finished HTML to the browser over SSE (Datastar's "server is the game loop" model) and don't want to re-derive the reliability rules every time
- Have hit a *silent* Datastar failure — signals that won't react, SSE events the browser quietly ignores, a whole page that goes dead from one bad attribute — and want helpers that make those mistakes **impossible (or fail-fast)** instead of failing quietly in the browser
- Have a long-lived SSE stream that mysteriously freezes, or POSTs that intermittently return 503 under load — and want the heartbeat + off-thread-push + dead-connection reaping that make a stream *boring*
- Need Datastar's `@get`/`@post` **and htmx's `hx-push-url`** to work behind **HTTP Basic Auth** (they don't out of the box — `fetch()` rejects credentialed URLs and `history.replaceState` throws on them; this fixes both)
- Are tired of copy-pasting `ds.clj`/`sse.clj` into every app and watching them drift apart

**Why this exists:** Datastar lets a Clojure server own all UI state and push finished HTML to the browser over SSE — the client becomes a display terminal. It's wonderful. But the surface has **sharp edges a Clojure compiler can't catch**: with no static types, a wrong signal expression, a mis-named SSE event, or a synchronous fan-out all compile fine and then fail *silently in the browser* or *intermittently in production*. This library is, in effect, **the guardrails the type system doesn't give you** — each helper either makes a wrong call impossible by construction, or makes it fail fast and loud (spec preconditions) instead of silently on the wire. The comments cite the specific failure each one prevents.

## Architecture: the server is the game loop

```
Input → POST → server mutates state (one atom) → push HTML via SSE → 204
```

The server owns all state. The DOM is a display terminal. The client fires a POST and forgets — no JSON parsing, no client-side render decisions. Internalize this and most "do I need JS for this?" questions answer themselves (almost always: no).

## What's in it

| File | Namespace / asset | What it gives you |
|---|---|---|
| `src/datastar_kit/ds.clj` | `datastar-kit.ds` | Signal helpers, safe persistent mounts (`sse-mount-url`), continuous one-shot controls (`live-scrub`), the one browser-owned text input (`editable`), the single-owner `picker`, keydown builders, `bind`/`signal-ref`, `post-action*`, clipboard/scroll helpers, and **spec-validated SSE event constructors**. |
| `src/datastar_kit/picker.clj` | `datastar-kit.picker` | The server half of `ds/picker`: the pure ordering fence (`picker-accept?`, `picker-caught-up?`, `parse-seq`) and `step` for ↑/↓. |
| `src/datastar_kit/sse.clj` | `datastar-kit.sse` | **Reliable SSE broadcast (raw-channel flavor)** for apps that write raw SSE strings: subscriber set, off-thread push agent, heartbeat, dead-connection reaping. |
| `src/datastar_kit/sse_sdk.clj` | `datastar-kit.sse-sdk` | **Same reliability, SDK flavor** — for apps using the Datastar Clojure SDK (`hk/->sse-response` + a `sse-gen` + `patch-elements!`): off-thread broadcast or targeted `push!`/`push-to!`, idempotent heartbeat, reaping, and an `sse-response` helper with a per-connection `on-connect` hook. |
| `src/datastar_kit/assets.clj` | `datastar-kit.assets` | Ordered Hiccup script tags with app-supplied cache-busting; embeds the Basic-Auth bootstrap and the keyboard chord engine so apps copy neither, and emits the bootstrap first. Also embeds and serves the vendored Datastar client and the kit runtime itself (`wrap-kit-assets`, `asset-path`), so apps don't need their own copies of those either. `(copy-audit)` catches a stale or shadowed app copy of any of these. |
| `src/datastar_kit/testing.clj` | `datastar-kit.testing` | The consumer contract as `clojure.test`: `defcontract-tests` defines the copy-audit, shadowed-file, and (optional) authorized-JS checks in an app's own test namespace in one line. Also `command-replay`/`assert-command-replay`, the gesture contract for a fire-and-forget client. |
| `resources/public/vendor/datastar-aliased.js` | — | The vendored Datastar client (use this, not a CDN). |
| `resources/public/js/datastar-kit.js` | — | Small client runtime: `postJSON`, `showNotification`, and **scroll keeping** — a server push does not move the element the user just clicked. |
| `resources/public/js/datastar-auth-fix.js` | — | **HTTP Basic Auth bootstrap** — makes `Request`/`fetch` (Datastar `@get`/`@post`) and `history.pushState`/`replaceState` (htmx `hx-push-url`, Datastar) work on a page opened from a credentialed URL. See below. |
| `resources/public/js/keyboard-chords.js` | — | Reusable two-key browser shortcut engine with shifted-key normalization, editable-field suppression, timeout, and lifecycle resets. |

### Scroll stays put

A server push is a whole new frame, so when it grows content *above* the viewport — an offer box on a row above the fold, a message bar at the top — everything below slides down and the row the user just clicked leaves the place they were looking at. Chrome's own scroll anchoring only partly recovers it (measured 2026-09-22 in a live tab: +288 px of new content above the fold, 100 px recovered, the clicked row moved from y≈520 to y≈784), and stable element ids do not help, because the morph is already in place by the time layout runs.

`datastar-kit.js` fixes it, on by default, with no page cooperation. `postJSON` remembers which element the gesture came from — the focused control, or the last `pointerdown` target — and its top edge. After each applied frame (a `MutationObserver` on `document.body`, coalesced to one animation frame) the runtime puts that element's top edge back where it was, finding it again by `id` if the morph replaced the node. It refuses in the cases where a correction would be the page fighting the user: more than 2 s after the gesture, or after any `wheel`, `touchmove`, or scrolling key press. Opt a page or a subtree out with `data-kit-keep-scroll="off"`.

Intent and specs: `docs/intent/kit-runtime/` (`KIT-RUNTIME-SCROLL-001`…`012`).

Scroll keeping only anchors a *click*. A cursor the server itself moves — a j/k row-move — has no gesture to anchor to, because the user never touched the row that moved. For that, mark the row with `data-kit-scroll-into-view=""`; on the same animation frame, if it is not fully inside the viewport, the runtime calls `scrollIntoView({block: 'nearest'})` on it — instant, not smooth, like vim. Fully visible, nothing happens. An optional `data-kit-scroll-margin="<px>"` on the element or on `<html>` narrows the top of the viewport for this check, for a page with a sticky header. Only the first marked element in document order is honoured, and when both features have a correction to make in the same frame, scroll-into-view runs last and wins — the user asked for the cursor. Opt out with `data-kit-scroll-into-view="off"` on `<html>` (independent of `data-kit-keep-scroll`).

Intent and specs: `docs/intent/kit-runtime/` (`KIT-RUNTIME-SCROLL-020`…`026`).

### Browser-owned keyboard chords

Keep route and action maps in the consuming application; share only the state machine:

```html
<script src="/js/keyboard-chords.js"></script>
<script src="/js/my-app-keyboard.js"></script>
```

Clojure consumers should use `(datastar-kit.assets/keyboard-chords-script)`. It embeds the runtime during compilation,
so it also works in thin-JAR/container builds that omit resource directories belonging to git dependencies.

```js
const chords = DatastarKeyboardChords.create({
  bindings: {
    "g shift+s": () => { window.location.href = "/starred-infinite"; },
    "s shift+r": () => sortBy("random")
  }
});

document.addEventListener("keydown", (event) => {
  if (chords.handle(event)) return;
  // Application-owned single-key shortcuts continue here.
});
```

The controller ignores the standalone `Shift` event browsers emit inside `g S`, suppresses shortcuts in editable
elements, expires prefixes after one second, and resets on Escape, window blur, and document visibility changes.

The consumer provides `org.httpkit`, `taoensso.timbre`, and (for the SDK flavor) `dev.data-star.clojure/http-kit`; they're intentionally not pinned here so versions don't fight.

## The sharp edges these encode (the norms)

These are the things you'll otherwise rediscover the hard way. The library exists so you don't.

- **A push moves what the user is looking at, and scroll anchoring will not save you.** Content that grows above the viewport pushes the clicked row down the screen; the browser recovers only part of it, and ids on the rows change nothing. Scroll is browser-owned state the server cannot see — the kit runtime keeps it (see "Scroll stays put"), so don't try to solve it server-side with a scroll signal or a `scrollIntoView` in the pushed HTML.
- **Signal arithmetic is camelCase-parsed.** Datastar reads `$idx-1` as the signal `$idx1`, *not* `$idx − 1`. Always parenthesize. `signal-inc`/`signal-dec` do it for you.
- **`data-star-bind` with a `true` value kills the whole page.** Hiccup renders `{:data-star-bind:foo true}` as `true`, which throws and halts *all* Datastar processing downstream. Use `(ds/bind :foo)` → it emits `""`.
- **SSE event names were renamed and old ones are silently ignored.** The aliased build wants `datastar-patch-elements` / `data: elements` / `data: mode`, not the old `merge-fragments`/`fragments`/`mergeMode`. Hand-written events with the old names do *nothing*, with no error. The `sse-*` constructors are the single source of truth for the names and **fail fast** (spec) on bad data.
- **Never put `data-star-on:*` on 10+ repeated elements.** Datastar recompiles every expression on each SSE morph; on a list this hangs the browser. Use plain `onclick` + `fetch()` for repeated elements; reserve `data-star-on:*` for singletons and `$signal` access.
- **Persistent streams must close in hidden tabs.** HTTP/1.1 shares a small per-origin connection pool across tabs; enough never-ending SSE requests can block navigation and static assets. Use `sse-mount-url`, which emits `openWhenHidden:false` plus app-lifetime retry.
- **Tabs born hidden must not open the first stream.** The upstream client installs a visibility listener but opens the initial fetch unconditionally. This vendored bundle guards that first fetch with `openWhenHidden || !document.hidden`; a hidden tab waits for its first visible transition, then Datastar owns close/reopen normally. Reapply the guarded transform with `scripts/guard-initial-hidden-sse.mjs` whenever replacing the upstream bundle.
- **Continuous controls use one-shot SSE.** Use `live-scrub` for sliders and scrubbers. It enforces a single-word signal plus throttled `input`; never combine it with debounce, `onchange`, or form-submit handlers that reload over live patches.
- **A long-lived SSE stream needs three things or it's dangerous** (see `sse.clj`/`sse_sdk.clj`):
  1. **Heartbeat** — or an idle proxy / LB / Cloud Run silently reaps the stream and the display freezes with no error.
  2. **Off-thread push** — fan out broadcasts on an agent thread, never the request thread, or one slow client starves http-kit's worker pool and *unrelated* POSTs start returning 503. (This is the rule SDK apps most often miss.)
  3. **Subscriber set + reap-on-failed-write** — one shared set, not one watch per connection (which leaks). Every push sends the full fragment (idempotent), so a dropped+reconnected client just re-paints.
- **Stateful tabs need targeted streams.** Register a connection with
  `(sse-response request tab-id on-connect)`, then use `(push-to! tab-id patches)`.
  The 2-arity `sse-response` and `push!` remain broadcast APIs. Initial reconnect
  state belongs in `on-connect`, which writes only to that connection.
- **A page opened as `https://user:pass@host/…` breaks two browser URL APIs, in opposite directions.** The document URL keeps the userinfo while `location.href` shows it redacted, and each API checks against the document URL:
  - **`Request`/`fetch` reject any URL that resolves to one with userinfo** — including a *relative* URL, which inherits it. `new Request(url)` throws *before* `fetch` runs, so wrapping `fetch` alone is too late. They need an **absolute URL with userinfo stripped**.
  - **`history.pushState`/`replaceState` require the new URL to match the document's userinfo.** `replaceState(state, title, location.href)` — what htmx does before every `hx-push-url` swap — passes a redacted absolute URL and throws `SecurityError` inside the XHR `onload` handler: the swap is aborted, the element's request lock stays held, and every later trigger is silently dropped (pagination keys "just stop working"). They need a **relative URL**.

  `datastar-auth-fix.js` is the one owner of this failure class. It idempotently wraps `window.Request` (via Proxy), `fetch`, and both History methods; it installs **unconditionally, with no load-time probe** (a probe exercises one call shape; callers use others), and it is a no-op on a page without credentials. A History `SecurityError` becomes a console warning, never an exception in the caller.

  Use `(datastar-kit.assets/basic-auth-script)`, or `(datastar-kit.assets/script-tags {:asset-url views/static :basic-auth? true})`. Both embed the script at compile time, so thin-JAR builds need no copy. **It must be the first script on the page** — before htmx, Datastar, and anything else that touches History, Request, or fetch. **Do not keep an app-local copy of `datastar-auth-fix.js` or a separate History shim**; a second owner is how this class regressed. Intent and specs: `docs/intent/basic-auth-bootstrap/`.
- **The one place the browser owns state is a text input — use `ds/editable`, don't hand-roll it.** The server owns committed state; **the browser owns the active draft, focus, selection, composition and undo**. A caret, an IME buffer and an undo stack exist nowhere on the server, and no push can restore them. One app hand-rolled this and hit three bugs on one feature: its own "freeze the region while typing" logic also froze the render that *opens* the input; the action buttons bubbled their click into the cell's own `onclick`, so one click sent the submit **and** a racing open; and `(ds/bind :noteText)` rendered `data-star-bind:noteText`, which the HTML parser lowercases to `notetext`, while the submit read `$noteText` — every submit arrived blank, silently.

  ```clojure
  (when (:open? note)
    (ds/editable
      {:placeholder "a trip label, or a note"
       :value       (:text note)                      ; editing an existing note
       :command-id  (:edit-id note)                   ; server-minted, one per edit session
       :actions     [{:label "begins here" :url "/r/1/note/submit"
                      :payload {:tx-id (:id r) :date (:date r) :kind "begins"}}
                     {:label "ends here"   :url "/r/1/note/submit"
                      :payload {:tx-id (:id r) :date (:date r) :kind "ends"}}
                     {:label "just a note" :url "/r/1/note/submit"
                      :payload {:tx-id (:id r) :date (:date r) :kind "note"}}]
       :cancel      {:label "cancel" :url "/r/1/note/cancel"}}))
  ```

  **Open by rendering it; close by rendering nothing.** The wrapper carries `data-star-ignore-morph` — the aliased spelling the pinned client actually reads — so a later push of the surrounding region skips the subtree and leaves the caret and the half-typed draft alone. Stop rendering it and the surrounding morph removes it. There is no freeze logic to get wrong.

  **The draft is not a signal.** Every gesture reads its sibling input at click time (`this.closest('.ds-editable').querySelector('input').value`), which removes the camelCase mismatch as a class. The `text` key is fixed; `:payload` values are the **server's**, rendered as literals — never `evt`/cursor state, because the click can land after focus has moved and the gesture would act on the wrong row. Every button is a plain `type="button"` `onclick` (one event idiom: `event` and `this`, never Datastar's `evt`), each stopping propagation, and so does the wrapper itself. Enter submits the first action; Escape cancels; the input's keydown stops propagation so a page-level `keydown__window` map doesn't also see the typing. Intent and specs: `docs/intent/editable/`.
- **A picker has ONE owner for the chosen value — use `ds/picker`, don't compose it from `editable`.** The first picker we shipped prefilled its filter box with the current value, posted the box text on submit, and let "text that names a value" beat the server's selection. A click on the list moved the selection and never the box, and the prefill always named a value — so a click on the list could never win. Two owners (box text in the browser, selection on the server) need a reconciler, and the reconciler was the bug.

  ```clojure
  (ds/picker {:filter-url "/r/1/reassign/scrub"   ; POST {q, seq, command-id} per keystroke
              :pick-url   "/r/1/reassign/pick"    ; POST {value, seq, command-id} on a click
              :move-url   "/r/1/reassign/move"    ; POST {dir, seq, command-id} on ArrowUp/Down
              :submit     {:label "reassign" :url "/r/1/reassign/submit" :payload {:tx-id "t1"}}
              :cancel     {:label "cancel" :url "/r/1/reassign/cancel"}
              :command-id "evt-7"
              :placeholder "type to filter…"
              :items      [{:value "Hosting & Internet"} {:value "Office Expenses"}]
              :selected   "Office Expenses"          ; the server's selection, or nil
              :message    nil})                      ; feedback, rendered beside the buttons
  ```

  The filter opens **empty** (it is a filter, never a value) inside the one `data-star-ignore-morph` island. The list, the highlight, the submit's `disabled` (exactly when `:selected` is nil) and `:message` render outside it, so pushes update them. **The submit posts the literal payload, `command-id` and `seq` — never the box text, never a value**; the server commits its own selection. Enter clicks the (current) submit button, Escape clicks cancel, ↑/↓ post a move. The one piece of client state is `data-kit-seq`, an integer on the island stamped on every gesture as `seq`: the server applies a filter/pick/move only when `(datastar-kit.picker/picker-accept? last-seq seq)` and a submit only when `(picker-caught-up? last-seq seq)`, so a late filter response cannot undo a later pick and a submit cannot overtake a pick still in flight. Intent and specs: `docs/intent/picker/`.
- **Name signals in kebab-case and derive both spellings.** The HTML parser lowercases attribute names, so `data-star-bind:noteText` binds `notetext` while `$noteText` reads an always-empty signal, with no error anywhere. `(ds/bind :note-text)` emits the attribute and `(ds/signal-ref :note-text)` returns `"$noteText"` (Datastar's own camelCase rule) — one name, both spellings, neither hand-typed. `bind` and `signal-ref` now **refuse** an uppercase letter (`ex-info`, `:type :ds/camel-case-signal`) rather than letting the mismatch reach the browser.
- **Gesture endpoints need command replay, not blanket idempotence.** A fire-and-forget client can always turn one click into two POSTs — a bubbled click, a double tap, a retry after a dropped response. "Every endpoint must be idempotent" is the wrong rule: move-down and undo are legitimately repeatable. The rule that holds is **a replay of one command has one effect; a new command carries a new id** — hence `editable`'s `:command-id`. Prove it from the outside:

  ```clojure
  (ns my-app.note-test
    (:require [clojure.test :refer [deftest]]
              [datastar-kit.testing :as kit]))

  (deftest submitting-a-note-twice-commits-once
    (kit/assert-command-replay
      {:post!   #(app/handler (submit-request {:command-id "cmd-7" :text "Portland"}))
       :state   #(select-keys @app/db [:notes])
       :effects #(count @app/event-log)}))
  ```

  It sends the same body twice and reports `:replay-safe?`, `:state-diff` (differing keys only — never the whole state) and `:effects-diff`. **The `:effects` count is not optional in spirit:** a handler that appends a second durable row while writing the same projection value is invisible to state equality, and that duplicate write is the bug most worth catching.
- **Match selection state to the workflow.** Server-authoritative selection (toggle → SSE morph) is great for single highlights; for *multi-select-then-batch-act*, a client-side `Set` is the right tool (0 ms local toggles vs a round-trip per click). Server-authoritative ≠ always better.
- **Thin-JAR builds leave the kit's resources behind, so apps copied them — and the copies drifted.** A git dependency's compiled namespaces land in a thin-JAR/container image, but its `resources/` dir does not, so a runtime `io/resource` read for the vendored Datastar client or the kit runtime returns nothing there; in dev, a stale local copy on the classpath precedes the kit's file and hides the drift entirely. Fix: call `(datastar-kit.assets/wrap-kit-assets handler)` once in the app's handler chain — `script-tags` then emits `/_kit/<hash>/…` URLs for both files automatically — and delete the app's own copies of `vendor/datastar-aliased.js` and `js/datastar-kit.js`. The hashed URL is cacheable forever (`immutable`) and changes exactly when the bytes do. Intent and specs: `docs/intent/kit-assets/`.
- **An app's `resources/` silently shadows the kit's files — so the kit audits for copies.** An app's `resources/` precedes its dependencies on the classpath, so an app-local copy of a kit asset wins over the kit's own file at the same path with no signal — a stale Datastar client was served for weeks this way, with every repo check green. `(datastar-kit.assets/copy-audit)` inspects every classpath provider of each kit asset and returns `:shadowed`/`:stale-copy` findings as data; `script-tags` runs it once per process and warns on standard error for free, no adoption needed. To make it fail a test run instead, adopt the contract in one line:
  ```clojure
  (ns my-app.kit-contract-test
    (:require [datastar-kit.testing :as kit]))
  (kit/defcontract-tests {:public-root "resources/public" :js-manifest "test/authorized-js.edn"})
  ```
  `:js-manifest` is optional (map or EDN file path) and adds a third check that only authorized JavaScript is served: `{:app #{"js/app.js" ...} :third-party {"vendor/x.js" {:sha256 "…" :source "…" :version "…"}}}` — app entries carry no hash (they change with ordinary work); third-party entries are pinned. Intent and specs: `docs/intent/consumer-contract/`.

## How to consume it — dev vs CI

Two modes, and you'll usually want both:

**Local development — `:local/root` (low friction, instant edits).** Edit a helper here, reload the consuming app, done — no commit/push/bump cycle. This is the day-to-day mode.
```clojure
;; deps.edn in the consuming repo — a :dev alias
{:aliases
 {:dev {:override-deps {genek/datastar-helpers {:local/root "../datastar-helpers"}}}}}
```
(Functionally a symlink of `src/datastar_kit` into your project, but it also pulls in `resources/`.)

**CI / deploy — git dep pinned to a SHA (hermetic).** A symlink/`:local/root` only works where the sibling repo is checked out — CI, Jib, and fresh clones don't have it, and the build fails with `Could not locate datastar_kit/…`. Pin a SHA in the default deps so builds are reproducible *and* still share this one source of truth:
```clojure
{:deps
 {genek/datastar-helpers
  {:git/url "https://github.com/realgenekim/datastar-helpers"
   :git/sha "<commit-sha>"}}}
```

### ⚠️ The trade-off you must stay vigilant about

This hybrid is delightful but has one real hazard: **dev runs your latest local code (`:local/root`); CI/prod runs the pinned SHA.** If you improve a helper locally and forget to push + bump the SHA, **dev and production silently diverge** — "works on my machine" in its purest form.

So the rule: **after any meaningful change here, push it and bump `:git/sha` in every consumer.** When debugging a prod-only issue, first check whether the deployed SHA matches what you have locally. Treat a stale SHA as a likely cause, not an afterthought.

## How to contribute

When you solve a Datastar/SSE problem that *any* app would hit — an expression footgun, an SSE reliability pattern, a clipboard quirk — **add it here, not in the app.** Keep it generic (no app-specific state or view names), and have the comment name the failure it prevents. Then every repo that depends on this gets it on the next SHA bump.

## License

[MIT](./LICENSE) © Gene Kim
