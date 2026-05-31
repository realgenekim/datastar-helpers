# datastar-helpers (`datastar-kit`)

Shared, battle-tested building blocks for **Clojure + [Datastar](https://data-star.dev) + http-kit** web apps: safe expression helpers, reliable Server-Sent Events broadcast, and fixes for the sharp edges that bite every Datastar app sooner or later. Write each pattern once, here, and reuse it everywhere — instead of copy-pasting helpers into each repo (which is how they drift).

**You'll want this if you:**
- Are building a Clojure server that owns UI state and pushes finished HTML to the browser over SSE (Datastar's "server is the game loop" model) and don't want to re-derive the reliability rules every time
- Have hit a *silent* Datastar failure — signals that won't react, SSE events the browser quietly ignores, a whole page that goes dead from one bad attribute — and want helpers that make those mistakes **impossible (or fail-fast)** instead of failing quietly in the browser
- Have a long-lived SSE stream that mysteriously freezes, or POSTs that intermittently return 503 under load — and want the heartbeat + off-thread-push + dead-connection reaping that make a stream *boring*
- Need Datastar's `@get`/`@post` to work behind **HTTP Basic Auth** (they don't out of the box — `fetch()` rejects credentialed URLs; this fixes it)
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
| `src/datastar_kit/ds.clj` | `datastar-kit.ds` | Signal helpers (`signal-inc/dec/set/clamp`), keydown builders (`on-key/on-meta/on-alt/keydown-expr`), `bind`, `post-action*`, `$value/$checked/…`, clipboard/scroll helpers, and **spec-validated SSE event constructors** (`sse-event`, `sse-fragment`, `sse-raw`). |
| `src/datastar_kit/sse.clj` | `datastar-kit.sse` | **Reliable SSE broadcast (raw-channel flavor)** for apps that write raw SSE strings: subscriber set, off-thread push agent, heartbeat, dead-connection reaping. |
| `src/datastar_kit/sse_sdk.clj` | `datastar-kit.sse-sdk` | **Same reliability, SDK flavor** — for apps using the Datastar Clojure SDK (`hk/->sse-response` + a `sse-gen` + `patch-elements!`): off-thread `push!`/`push-signals!`, idempotent heartbeat, reaping, and an `sse-response` helper with an `on-connect` hook. |
| `resources/public/vendor/datastar-aliased.js` | — | The vendored Datastar client (use this, not a CDN). |
| `resources/public/js/datastar-kit.js` | — | Small client runtime: `postJSON`, `showNotification`. |
| `resources/public/js/datastar-auth-fix.js` | — | **HTTP Basic Auth fix** — makes `fetch()`-based `@get`/`@post` work behind credentialed URLs. See below. |

The consumer provides `org.httpkit`, `taoensso.timbre`, and (for the SDK flavor) `dev.data-star.clojure/http-kit`; they're intentionally not pinned here so versions don't fight.

## The sharp edges these encode (the norms)

These are the things you'll otherwise rediscover the hard way. The library exists so you don't.

- **Signal arithmetic is camelCase-parsed.** Datastar reads `$idx-1` as the signal `$idx1`, *not* `$idx − 1`. Always parenthesize. `signal-inc`/`signal-dec` do it for you.
- **`data-star-bind` with a `true` value kills the whole page.** Hiccup renders `{:data-star-bind:foo true}` as `true`, which throws and halts *all* Datastar processing downstream. Use `(ds/bind :foo)` → it emits `""`.
- **SSE event names were renamed and old ones are silently ignored.** The aliased build wants `datastar-patch-elements` / `data: elements` / `data: mode`, not the old `merge-fragments`/`fragments`/`mergeMode`. Hand-written events with the old names do *nothing*, with no error. The `sse-*` constructors are the single source of truth for the names and **fail fast** (spec) on bad data.
- **Never put `data-star-on:*` on 10+ repeated elements.** Datastar recompiles every expression on each SSE morph; on a list this hangs the browser. Use plain `onclick` + `fetch()` for repeated elements; reserve `data-star-on:*` for singletons and `$signal` access.
- **A long-lived SSE stream needs three things or it's dangerous** (see `sse.clj`/`sse_sdk.clj`):
  1. **Heartbeat** — or an idle proxy / LB / Cloud Run silently reaps the stream and the display freezes with no error.
  2. **Off-thread push** — fan out broadcasts on an agent thread, never the request thread, or one slow client starves http-kit's worker pool and *unrelated* POSTs start returning 503. (This is the rule SDK apps most often miss.)
  3. **Subscriber set + reap-on-failed-write** — one shared set, not one watch per connection (which leaks). Every push sends the full fragment (idempotent), so a dropped+reconnected client just re-paints.
- **`fetch()` refuses credentialed URLs, so Datastar breaks behind HTTP Basic Auth.** Opening a page as `https://user:pass@host/…` makes `new Request(url)` throw *before* `fetch` runs (so wrapping `fetch` alone is too late). `datastar-auth-fix.js` patches both `window.Request` (via Proxy) and `fetch`, stripping credentials from the URL; it's a no-op unless the URL has `user:pass@`. HTMX never hit this (it uses XHR). Load it **before** the Datastar module:
  ```html
  <script src="/js/datastar-auth-fix.js"></script>
  <script type="module" src="/vendor/datastar-aliased.js"></script>
  ```
- **Match selection state to the workflow.** Server-authoritative selection (toggle → SSE morph) is great for single highlights; for *multi-select-then-batch-act*, a client-side `Set` is the right tool (0 ms local toggles vs a round-trip per click). Server-authoritative ≠ always better.

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
