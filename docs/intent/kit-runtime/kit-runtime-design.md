---
parent: high-level-design
prefix: KIT-RUNTIME
---

# Kit Runtime

## Context and Design Philosophy

Expressions built by `datastar-kit.ds` compile to calls on a small set of browser globals: `ds/post-action*` compiles to `postJSON(url, body)`, and the clipboard helpers call `showNotification('Copied!')`. The **kit runtime** (`resources/public/js/datastar-kit.js`) is the script that defines those globals. It is the browser half of a contract whose other half is Clojure, so it lives in the kit and reaches pages through `datastar-kit.assets/script-tags` (delivery is specified in `kit-assets`).

The runtime holds what `ds`-generated expressions call, and the browser-owned state those calls would otherwise disturb — scroll position, which no server-pushed frame can restore because no server can see it. It is deliberately not a place for app conveniences: a function one app needs belongs in that app's own script, where its owner can change it without a kit release. An app that adds functions to a copy of this file has forked it, and the fork stops receiving kit fixes.

The server is the game loop: `postJSON` sends and forgets, and what the user sees next arrives over SSE. `showNotification` is the one piece of client-side feedback the kit provides, for confirmations of browser-owned operations (a clipboard write) that the server never hears about.

## postJSON

`postJSON(url, body)` calls `fetch` with method `POST`, a JSON content type, and the body serialized as JSON, and returns the `fetch` promise so a caller can attach a `catch`. It does not read the response. On a page opened from a credentialed URL, the Basic-Auth bootstrap has already wrapped `fetch`, so `postJSON` needs no credential handling of its own.

## showNotification

`showNotification(msg, isError, durationMs)` shows one transient message.

- **Where.** If the page has an element with id `notification`, the runtime uses it and drives it by class (`notification show`, plus `error`), so the app's CSS owns the look. Otherwise it creates one fixed-position overlay element with id `ds-notify`, appends it to the body, and reuses it on later calls, so the function works on a page that has made no provision for it.
- **How long.** The message hides after `durationMs` milliseconds, 3000 when omitted. A `durationMs` of `0` leaves it visible until the next notification replaces it, for a message that reports a state rather than an event (for example "saving…" followed later by "saved").
- **One at a time.** A new notification cancels the pending hide timer of the previous one before showing, including when the new one has no timer of its own. Otherwise the earlier timer would hide the newer message early.

The hide timer is the only timer in the kit runtime and it touches only the notification element. It is a presentation detail of a browser-owned confirmation, not UI state: nothing reads it back and no server-rendered element depends on it.

## Gesture-anchored scroll keeping

The server is the game loop, so what the user sees next is a whole frame the server pushed. When that frame grows content *above* the viewport — an offer box rendered on a row above the fold, a message bar at the top — everything below it slides down, and the row the user just clicked leaves the place they were looking at. Chrome's own scroll anchoring is a partial mitigation: measured in a live tab on 2026-09-22, a +288 px growth above the fold was recovered by only 100 px, and the clicked row moved from y≈520 to y≈784. Stable element ids do not help; the morph is already in place by the time the browser re-lays-out.

Scroll position is browser-owned state the server cannot see, so keeping it is one of the legitimate uses of client JS: the runtime does not decide *what* to show, only that the thing the user last touched stays where they last saw it.

- **What is anchored.** The gesture element: `document.activeElement` when it is a `BUTTON`, `INPUT`, `SELECT`, `TEXTAREA`, or `A` — the elements a keyboard or click gesture actually lands on — otherwise the target of the most recent `pointerdown`, tracked on `document` in the capture phase so a handler that stops propagation cannot hide it. `postJSON` records that element, its `id`, and its `getBoundingClientRect().top` at send time, because the gesture is the moment the user chose a reference point. Only the most recent gesture is kept.
- **When the correction runs.** On a `MutationObserver` over `document.body` (`childList`, `subtree`, `characterData`), coalesced to one `requestAnimationFrame` callback. Datastar's own DOM events do not fit: `datastar-patch-elements` is a watcher name, not an event, and the only fetch event, `datastar-fetch`, reports the lifecycle of a *Datastar* fetch action — on the game-loop shape the patches arrive down one long-lived SSE stream that never reaches `finished`, and kit gestures go out through `postJSON`'s plain `fetch`, which dispatches no Datastar event at all. The mutation record is the one signal that is actually emitted once per applied frame, and the animation frame is the first moment layout is settled.
- **The correction.** If the recorded element is still connected it is used as-is; if it is gone but was recorded with an `id`, the element now carrying that id takes its place and is remembered. The new top minus the recorded top is the delta, and a delta of more than 1 px is applied with `window.scrollBy(0, delta)`. The *recorded* top never changes, so a burst of pushes returns the element to the same screen position each time rather than drifting.
- **When it refuses.** After 2000 ms the gesture is stale and is forgotten — a correction the user did not connect to their own click reads as the page fighting them. A `wheel`, `touchmove`, or scrolling key press at or after the gesture means the user has taken the scroll back, and the gesture is forgotten immediately; scrolling keys are ignored while focus is in a form control, a link, or a contenteditable element, where those keys move a caret or activate the control instead. A page or subtree carrying `data-kit-keep-scroll="off"` is left with native behavior.
- **Where the decision lives.** `kitKeepScrollDecide(input)` is pure: it takes the recorded gesture, the current time, the element's current top, the last user-scroll time, the opt-out state, and whether the element was found, and returns `{action: 'scroll', by}`, `{action: 'forget', reason}`, or `{action: 'none', reason}`. The DOM half only reads and applies. Iterating on the rule therefore costs no browser.
- **When it does not install.** A browser missing `MutationObserver`, `requestAnimationFrame`, `document.addEventListener`, or `window.scrollBy` gets no scroll keeping and no error; `postJSON` records nothing in that case, so the feature is one switch.

## Scroll into view

Scroll keeping holds a *clicked* element in place; it says nothing about a cursor the server itself moves — a j/k row-move that lands somewhere off-screen has no gesture to anchor to, because the user never touched the row that moved. The server marks that row instead: one element per page carries `data-kit-scroll-into-view=""`, and on the same animation frame scroll keeping already uses, the runtime checks whether that element is fully inside the viewport and, if not, calls `el.scrollIntoView({block: 'nearest'})` — instant, not smooth, matching how vim itself repositions the view. A fully visible element gets no call, so there is no jitter on frames that don't need one.

- **What is marked.** The first element in document order carrying the attribute; a second one on the same page is ignored, so the server names one cursor, not a set.
- **The viewport check.** `{top, bottom} = el.getBoundingClientRect()` against `{height: window.innerHeight}`, narrowed at the top by an optional `data-kit-scroll-margin="<px>"` on the element itself or on `<html>` (the element wins if both are present) — a page with a sticky header sets it to the header's height, so a row tucked just under the header still counts as needing a scroll.
- **Where the decision lives.** `kitScrollIntoViewDecide(rect, viewport, margin)` is pure, mirroring `kitKeepScrollDecide`: it takes the element's rect, the viewport, and the margin, and returns whether the element is off-screen. The DOM half only reads and applies.
- **Composing with scroll keeping.** Both corrections run in the same `requestAnimationFrame` callback, scroll keeping first. When a frame has both a clicked row sliding down *and* a server-moved cursor off-screen, scroll-into-view runs last and its `scrollIntoView` call is what the browser ends the frame on — the user asked for the cursor, so it wins.
- **Opting out.** `data-kit-scroll-into-view="off"` on `<html>` suppresses the whole feature. It is a separate switch from `data-kit-keep-scroll`: the two features protect different things (a click vs. a server-moved cursor) and a page may want one without the other.
- **Failure mode.** It shares scroll keeping's install gate — a browser without `MutationObserver`/`requestAnimationFrame` gets neither feature, and no error either way.

## Decisions & Alternatives

| Decision | Chosen | Alternatives Considered | Rationale |
|----------|--------|------------------------|-----------|
| What the runtime contains | Only globals that `ds`-generated expressions call | Also a server-keymap keyboard dispatcher, an action logger, and app helpers | The kit already owns keyboard handling as a separate tested engine (`keyboard-chords`), bound in the app. Functions no `ds` expression calls have no contract to hold them steady, and every consuming app would download them. |
| How a persistent message is expressed | `durationMs` of `0` | A separate `showStickyNotification`; a `hideNotification` function | One function keeps the one-at-a-time rule in one place. The next notification is the natural dismissal, so no explicit hide is needed. |
| Notification element | Prefer the page's `#notification`, else create `#ds-notify` | Always create the overlay | Apps with a designed notification keep their styling; apps without one still get feedback. |
| Morph-completion hook | `MutationObserver` on `document.body`, coalesced to one animation frame | `datastar-fetch` `finished`; a `datastar-patch-elements` event | `datastar-patch-elements` is a plugin name, not a DOM event. `datastar-fetch` describes a Datastar fetch action: on a long-lived SSE stream it reaches `finished` only at disconnect, and `postJSON` gestures are a plain `fetch` that never dispatches it at all. The mutation record fires once per applied frame in both shapes. |
| What is anchored | The element the gesture came from | The scroll offset; the first element in the viewport; a caller-named anchor id | An offset is meaningless once content above it grows. The first visible element is not what the user is watching; the thing they just clicked is, and it needs no page cooperation. |
| How long the anchor lives | 2000 ms from the gesture | Until the next gesture; forever | A correction the user cannot connect to their own click reads as the page fighting them. Two seconds covers a server round trip and the pushes that follow it. |
| Default | On, with `data-kit-keep-scroll="off"` to opt out | Off, with an opt-in attribute | The jump is a defect on every page that pushes frames; a fix that each page must remember to switch on is a fix most pages will not get. |
| How the server names the cursor for scroll-into-view | One `data-kit-scroll-into-view=""` attribute, first match wins | A signal the runtime reads; a dedicated SSE event | An attribute is a fact about a rendered element, the same shape the kit already uses for opt-outs, and needs no new wire format — the server just renders it on the row it wants seen. |
| Whether scroll-into-view shares scroll keeping's opt-out | No — its own `data-kit-scroll-into-view="off"` | Reuse `data-kit-keep-scroll="off"` | The two features protect different things (a click the user made vs. a cursor the server moved); a page may legitimately want the server-driven one without the click-anchored one, or vice versa. |

## Open Questions & Future Decisions

### Deferred
1. The runtime's globals are unnamespaced (`postJSON`, `showNotification`) because `ds`-generated expressions and existing app scripts call them by those names.

## References

- `docs/intent/kit-assets/kit-assets-design.md` — how this file reaches the page.
- `src/datastar_kit/ds.clj` — the expressions that call these globals.
