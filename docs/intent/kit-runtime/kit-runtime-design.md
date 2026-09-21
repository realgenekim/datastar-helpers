---
parent: high-level-design
prefix: KIT-RUNTIME
---

# Kit Runtime

## Context and Design Philosophy

Expressions built by `datastar-kit.ds` compile to calls on a small set of browser globals: `ds/post-action*` compiles to `postJSON(url, body)`, and the clipboard helpers call `showNotification('Copied!')`. The **kit runtime** (`resources/public/js/datastar-kit.js`) is the script that defines those globals. It is the browser half of a contract whose other half is Clojure, so it lives in the kit and reaches pages through `datastar-kit.assets/script-tags` (delivery is specified in `kit-assets`).

The runtime holds only what `ds`-generated expressions call. It is deliberately not a place for app conveniences: a function one app needs belongs in that app's own script, where its owner can change it without a kit release. An app that adds functions to a copy of this file has forked it, and the fork stops receiving kit fixes.

The server is the game loop: `postJSON` sends and forgets, and what the user sees next arrives over SSE. `showNotification` is the one piece of client-side feedback the kit provides, for confirmations of browser-owned operations (a clipboard write) that the server never hears about.

## postJSON

`postJSON(url, body)` calls `fetch` with method `POST`, a JSON content type, and the body serialized as JSON, and returns the `fetch` promise so a caller can attach a `catch`. It does not read the response. On a page opened from a credentialed URL, the Basic-Auth bootstrap has already wrapped `fetch`, so `postJSON` needs no credential handling of its own.

## showNotification

`showNotification(msg, isError, durationMs)` shows one transient message.

- **Where.** If the page has an element with id `notification`, the runtime uses it and drives it by class (`notification show`, plus `error`), so the app's CSS owns the look. Otherwise it creates one fixed-position overlay element with id `ds-notify`, appends it to the body, and reuses it on later calls, so the function works on a page that has made no provision for it.
- **How long.** The message hides after `durationMs` milliseconds, 3000 when omitted. A `durationMs` of `0` leaves it visible until the next notification replaces it, for a message that reports a state rather than an event (for example "saving…" followed later by "saved").
- **One at a time.** A new notification cancels the pending hide timer of the previous one before showing, including when the new one has no timer of its own. Otherwise the earlier timer would hide the newer message early.

The hide timer is the only timer in the kit runtime and it touches only the notification element. It is a presentation detail of a browser-owned confirmation, not UI state: nothing reads it back and no server-rendered element depends on it.

## Decisions & Alternatives

| Decision | Chosen | Alternatives Considered | Rationale |
|----------|--------|------------------------|-----------|
| What the runtime contains | Only globals that `ds`-generated expressions call | Also a server-keymap keyboard dispatcher, an action logger, and app helpers | The kit already owns keyboard handling as a separate tested engine (`keyboard-chords`), bound in the app. Functions no `ds` expression calls have no contract to hold them steady, and every consuming app would download them. |
| How a persistent message is expressed | `durationMs` of `0` | A separate `showStickyNotification`; a `hideNotification` function | One function keeps the one-at-a-time rule in one place. The next notification is the natural dismissal, so no explicit hide is needed. |
| Notification element | Prefer the page's `#notification`, else create `#ds-notify` | Always create the overlay | Apps with a designed notification keep their styling; apps without one still get feedback. |

## Open Questions & Future Decisions

### Deferred
1. The runtime's globals are unnamespaced (`postJSON`, `showNotification`) because `ds`-generated expressions and existing app scripts call them by those names.

## References

- `docs/intent/kit-assets/kit-assets-design.md` — how this file reaches the page.
- `src/datastar_kit/ds.clj` — the expressions that call these globals.
