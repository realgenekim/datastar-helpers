# datastar-helpers (`datastar-kit`)

Shared Datastar + SSE building blocks for all of Gene's Clojure web apps. The goal:
**write a reusable Datastar/SSE pattern once, here, and let every repo benefit** —
instead of copy-pasting `ds.clj` into each project (which is how it drifted).

## What's in here

| File | Namespace / asset | What it gives you |
|---|---|---|
| `src/datastar_kit/ds.clj` | `datastar-kit.ds` | Signal helpers (`signal-inc/dec/set/clamp`), keydown builders (`on-key/on-meta/on-alt/keydown-expr`), `bind`, `post-action*`, `$value/$checked/...`, clipboard/scroll helpers, **and spec-validated SSE event constructors** (`sse-event`, `sse-fragment`, `sse-inner`, `sse-raw`). |
| `src/datastar_kit/sse.clj` | `datastar-kit.sse` | **Reliable SSE broadcast for http-kit**: a subscriber set, an **off-thread push agent**, **heartbeats**, and dead-connection **reaping**. The three things that make a long-lived SSE stream *boring* instead of dangerous. |
| `resources/public/vendor/datastar-aliased.js` | — | The vendored Datastar client (never use a CDN). |
| `resources/public/js/datastar-kit.js` | — | Small client helpers (`postJSON`, `showNotification`). |

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

## How to contribute

When you solve a Datastar/SSE problem that *any* app would hit (a signal-arithmetic
footgun, an SSE reliability pattern, a clipboard quirk): **add it here, not in the
app.** Keep it generic (no app-specific state/view names). Then every repo that
`:local/root`s this gets it on the next reload.

Provenance for `sse.clj`: the heartbeat + off-thread-push + reaping patterns were
hardened in `marvin-voice-remote` (2026-05-30) after a long debugging session where
a silently-dying SSE stream + synchronous in-request fan-out caused frozen displays
and 503s. See that repo's `docs/sse-datastar-design.md` for the full story.
