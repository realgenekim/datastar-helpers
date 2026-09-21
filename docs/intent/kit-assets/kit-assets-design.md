---
parent: high-level-design
prefix: KIT-ASSETS
---

# Kit Assets

## Context and Design Philosophy

The kit ships browser files that pages load by URL: the vendored Datastar client (`datastar-aliased.js`, ~32 KB) and the kit runtime (`datastar-kit.js`). They are too large to inline into every page the way the Basic-Auth bootstrap and the keyboard chord engine are, so they must be served and cached.

Consuming apps pin the kit as a git dependency. A thin-JAR or container build compiles a git dependency's namespaces into the image but leaves its resource directory behind, so in production the kit's files are not on the classpath. Apps answered that by copying the files into their own `resources/`, where the copies drift from the kit without any signal, and where, in development, a stale copy precedes the kit's file on the classpath and hides the drift.

This component removes the need for a copy: the kit carries these assets inside its compiled namespace, serves them itself, and tells the page where they are. An app has no file to copy and no path to write.

## Embedding

`datastar-kit.assets` reads each served asset when the namespace compiles and keeps its bytes. A JVM class file limits one string constant to 65,535 bytes, and the Datastar client is already half of that, so the text is emitted as a sequence of constants below the limit and joined when the namespace loads.

For each asset the kit derives a **content hash**: the first 12 hex characters of the SHA-256 of its bytes.

## Addressing

An asset's URL is `/_kit/<content-hash>/<name>`, returned by `(asset-path name)`. The hash makes the URL change exactly when the bytes change, which allows permanent caching and makes version skew visible in page source. Apps do not pass these URLs through their own cache-buster.

## Serving

`(wrap-kit-assets handler)` is plain Ring middleware with no dependencies beyond the request and response maps, so it works under reitit, compojure, or bare http-kit.

| Request | Response |
|---|---|
| `GET`/`HEAD` `/_kit/<hash>/<name>`, known name, hash matches | 200, exact bytes, `Content-Type: text/javascript; charset=utf-8`, `Cache-Control: public, max-age=31536000, immutable` |
| Same, hash does not match | 200, current bytes, `Cache-Control: no-cache` |
| Anything else (other path, unknown name, other method) | passed to the wrapped handler unchanged |

A mismatched hash is served rather than refused because, during a rolling deploy, a page rendered by one revision can request its URL from another revision that embeds different bytes; a 404 there would leave the page without Datastar. It is not marked immutable, so the mismatch does not get pinned in a cache.

`HEAD` returns the headers with no body.

The middleware supports both Ring handler shapes (one-argument synchronous, three-argument asynchronous) and delegates in the shape it was called with. It does not adapt one shape to the other: the wrapped handler must itself support whichever shape the server uses.

## Script tags

`script-tags` emits the Datastar module and the kit runtime from `asset-path` **when the middleware has been installed in this process**, and from the app-served paths (`/vendor/datastar-aliased.js`, `/js/datastar-kit.js`, passed through the app's `:asset-url`) when it has not. Installing the middleware is the single switch: an app cannot emit kit URLs that nothing serves, and cannot install the middleware yet keep loading a stale local copy through `script-tags`. An explicit `:datastar-path` still overrides the module path.

## Decisions & Alternatives

| Decision | Chosen | Alternatives Considered | Rationale |
|----------|--------|------------------------|-----------|
| How the bytes reach production | Embedded in the compiled namespace | Read `io/resource` at runtime; document a per-app copy step | The resource is absent from thin-JAR images, so a runtime read returns nothing there. Copies drift silently and shadow the kit's file in development. |
| Working under the 64 KB constant limit | Emit the text as several constants, join at load | Base64-encode into chunks; keep assets under 64 KB | Joining plain chunks keeps the embedded text greppable in the class and needs no decode step. The Datastar client's size is not ours to bound. |
| URL scheme | Content hash in the path | App cache-buster query (`?v=<build>`); unversioned path with ETag | A build-scoped query string re-downloads unchanged assets on every deploy. A hashed path is cacheable forever and shows which bytes a page asked for. |
| Hash mismatch | Serve current bytes, `no-cache` | 404; redirect to the current URL | A 404 breaks pages mid-deploy. A redirect adds a round trip to fix a condition that lasts seconds. |
| What switches `script-tags` to kit URLs | Installing the middleware | An option on `script-tags`; always emit kit URLs | Two settings that must agree can disagree. Always emitting kit URLs breaks every app that bumps the kit before mounting the middleware, silently, in the browser. |
| Requests the middleware does not own | Pass through | 404 for unknown names under `/_kit/` | The app's own not-found handling stays in charge of what a miss looks like. |

## Open Questions & Future Decisions

### Deferred
1. `ETag`/`304` on the mismatch path. Not needed while the condition is transient.
2. Serving `keyboard-chords.js` and the Basic-Auth bootstrap this way instead of inline. The bootstrap must stay inline (it has to run before any script fetch); the chord engine could move if page weight matters.

## References

- `docs/intent/basic-auth-bootstrap/basic-auth-bootstrap-design.md` — the inline sibling of this component.
