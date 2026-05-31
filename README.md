# datastar-helpers (`datastar-kit`)

Shared Datastar + SSE building blocks for all of Gene's Clojure web apps. The goal:
**write a reusable Datastar/SSE pattern once, here, and let every repo benefit** —
instead of copy-pasting `ds.clj` into each project (which is how it drifted).

## What's in here

| File | Namespace / asset | What it gives you |
|---|---|---|
| `src/datastar_kit/ds.clj` | `datastar-kit.ds` | Signal helpers (`signal-inc/dec/set/clamp`), keydown builders (`on-key/on-meta/on-alt/keydown-expr`), `bind`, `post-action*`, `$value/$checked/...`, clipboard/scroll helpers, **and spec-validated SSE event constructors** (`sse-event`, `sse-fragment`, `sse-inner`, `sse-raw`). |
| `src/datastar_kit/sse.clj` | `datastar-kit.sse` | **Reliable SSE broadcast for http-kit (raw-channel flavor)**: a subscriber set, an **off-thread push agent**, **heartbeats**, and dead-connection **reaping**. The three things that make a long-lived SSE stream *boring* instead of dangerous. Use when you write raw SSE strings (`ds/sse-event`). |
| `src/datastar_kit/sse_sdk.clj` | `datastar-kit.sse-sdk` | **Same reliability, SDK flavor** — for apps using the Datastar Clojure SDK (`hk/->sse-response` + a `sse-gen` + `patch-elements!`). Off-thread `push!`/`push-signals!`, idempotent heartbeat, reaping, and an `sse-response` helper with an `on-connect` hook to stream initial state to a newly-connected client. |
| `resources/public/vendor/datastar-aliased.js` | — | The vendored Datastar client (never use a CDN). |
| `resources/public/js/datastar-kit.js` | — | Small client helpers (`postJSON`, `showNotification`). |
| `resources/public/js/datastar-auth-fix.js` | — | **HTTP Basic Auth fix.** Lets Datastar's `fetch()`-based `@get`/`@post` work when the page is opened with credentials in the URL (`https://user:pass@host/…`). Load it BEFORE the Datastar module. See below. |

## How to consume it (the symlink / shared-source model)

**Don't copy `datastar_kit/` into your repo** (that's the drift trap). Instead, add it
as a **`:local/root` dependency** so edits here are picked up everywhere immediately:

```clojure
;; deps.edn in the consuming repo
{:deps {genek/datastar-helpers {:local/root "../datastar-helpers"}}}
```

`:local/root` merges this repo's `:paths` (`src` + `resources`) into the consumer —
it's the Clojure-idiomatic "symlink." (Functionally equivalent to
`ln -s ../datastar-helpers/src/datastar_kit src/datastar_kit`, but cleaner and it
also pulls in `resources/` for the vendored JS.)

- For **deploys** (jib / Cloud Run), `:local/root` works as long as the sibling repo
  exists at build time. The long-term-portable option is a **git dep**
  (`{:git/url "https://github.com/realgenekim/datastar-helpers" :git/sha "..."}`) — use
  that for boxes/CI where the sibling isn't checked out.

`datastar-kit.sse` references `org.httpkit.server` and `taoensso.timbre` — the
**consumer provides those** (every SSE app already has them); they're intentionally
not pinned here so versions don't fight.

## HTTP Basic Auth + Datastar (`datastar-auth-fix.js`)

If your app sits behind HTTP Basic Auth **and** the page can be opened with
credentials in the URL (`https://user:pass@host/…`), Datastar's `@get`/`@post`
silently break. Two browser behaviors are the cause:

1. `new Request(url)` **throws** `Request cannot be constructed from a URL that
   includes credentials` — and the browser constructs a `Request` *before*
   calling `fetch`, so patching `fetch` alone is too late.
2. `fetch()` then refuses the credentialed URL.

HTMX never hit this (it uses `XMLHttpRequest`, which tolerates credentialed URLs).
Datastar uses `fetch()`, so it broke — this is the bug that got an earlier Datastar
experiment reverted before the fix existed.

`datastar-auth-fix.js` patches **both** `window.Request` (via a `Proxy`, so
`instanceof`/prototype chain stay intact) and `window.fetch` to strip credentials
from the URL before the native code sees them, and best-effort scrubs creds from
the visible URL. The browser still sends the cached `Authorization` header, so
requests stay authenticated. It is a **no-op** unless the page URL actually
contains `user:pass@` — safe to always include.

```clojure
;; In your layout/head — auth-fix MUST run BEFORE the Datastar module:
[:script {:src "/js/datastar-auth-fix.js"}]
[:script {:type "module" :src "/vendor/datastar-aliased.js"}]
```

Verified in production (reddit-scraper-fulcro, 2026-05-31) behind Cloud Run Basic
Auth with credentialed URLs and a long-lived SSE stream.

## How to contribute

When you solve a Datastar/SSE problem that *any* app would hit (a signal-arithmetic
footgun, an SSE reliability pattern, a clipboard quirk): **add it here, not in the
app.** Keep it generic (no app-specific state/view names). Then every repo that
`:local/root`s this gets it on the next reload.

Provenance for `sse.clj`: the heartbeat + off-thread-push + reaping patterns were
hardened in `marvin-voice-remote` (2026-05-30) after a long debugging session where
a silently-dying SSE stream + synchronous in-request fan-out caused frozen displays
and 503s. See that repo's `docs/sse-datastar-design.md` for the full story.
