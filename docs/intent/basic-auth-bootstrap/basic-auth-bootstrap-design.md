---
parent: high-level-design
prefix: BASIC-AUTH
---

# Basic-Auth Bootstrap

## Context and Design Philosophy

Apps built on this kit sit behind HTTP Basic Auth and are routinely opened from a URL with embedded credentials: `https://user:pass@host/path`. The browser keeps that userinfo on the **document URL** for the life of the page, while `location.href` presents it **redacted**. Every browser API that takes a URL resolves or compares against the document URL, not against what `location.href` shows, and each API has its own rule. Libraries that drive the page (Datastar, htmx, the kit runtime) are written against an uncredentialed document and break.

The bootstrap is one script, `resources/public/js/datastar-auth-fix.js`, that wraps every such URL sink so page libraries need no knowledge of the credentialed document. It is the single owner of this failure class: one URL resolver, one formatter per sink.

## The browser rules

Document URL `https://user:pass@host/a?x=1`; `location.href` reads `https://host/a?x=1`.

| Sink | Browser rule | URL form the sink needs |
|---|---|---|
| `new Request(url)` / `fetch(url)` | Rejects any URL that resolves to one carrying userinfo. A relative URL resolves against the document URL and inherits its userinfo, so it is rejected too. | Absolute, userinfo removed |
| `history.pushState` / `replaceState` | The new URL, resolved against the document URL, must equal it in scheme, username, password, host, and port. | Relative (inherits the document's userinfo) |
| `XMLHttpRequest` | Tolerates credentialed URLs. | No wrapping |

The two wrapped sinks need opposite forms. History cases:

| URL passed to History | Resolves to | Browser |
|---|---|---|
| relative `/a?x=2` | `https://user:pass@host/a?x=2` | allowed |
| absolute with the document's userinfo | same userinfo | allowed |
| absolute without userinfo, e.g. `location.href` | empty username/password ≠ document's | `SecurityError` |

`history.replaceState(state, title, location.href)` is a common library idiom (htmx saves the current page this way before an `hx-push-url` swap). On a credentialed document it always throws.

## History wrapping

`History.prototype.pushState` and `replaceState` are replaced with wrappers that:

1. Pass a null or omitted URL through unchanged.
2. Resolve any other URL against `location.href`. When its scheme and host match the page's, pass the native method `pathname + search + hash`. Otherwise pass the URL unchanged.
3. Catch a `SecurityError` from the native method, log a console warning naming the method and the URL, and return normally. Any other error propagates.

Step 3 exists because of where callers sit. htmx calls History inside its XHR `onload` handler; an exception there aborts the swap and leaves the element's request lock held, so every later trigger on that element is silently dropped. A history entry that could not be written is a cosmetic loss; a dead request pipeline is not.

A relative URL is equivalent to the absolute one on an uncredentialed document, so the wrappers are behavior-preserving there.

The History API cannot change a document's userinfo, so the bootstrap does not attempt to scrub credentials from the visible URL.

## Request and fetch wrapping

`window.Request` is replaced by a `Proxy` whose `construct` trap resolves a string URL to absolute, strips userinfo, and then calls the native constructor; a `Request` passed as input is rebuilt only when its URL carries userinfo. The Proxy keeps `instanceof` and the prototype chain intact. `window.fetch` applies the same sanitizer to string and `Request` inputs and defaults `credentials` to `same-origin`, so the browser still attaches its cached Basic-Auth header. Wrapping `fetch` alone is insufficient: callers construct a `Request` first, and the constructor is the throw site.

## Installation and delivery

- The script installs every wrapper unconditionally on load. It sets `window.__datastarAuthFixVersion` and returns immediately when that is already set, so a second execution changes nothing.
- `datastar-kit.assets/basic-auth-script` returns a `[:script …]` tag whose body is the script source embedded when the namespace is compiled. `script-tags` with `:basic-auth? true` emits that tag first.
- **Consumer contract:** the tag is the first script on the page, ahead of htmx, Datastar, and any script that touches History, Request, or fetch. A consuming app keeps no copy of the script and no History shim of its own.

## Test model

Tests run the script in a `node:vm` sandbox against fakes that implement the browser rules in *The browser rules* above, with the sandbox `location.href` redacted and the fake's document URL credentialed, as in a real browser. A fake that does not throw for an uncredentialed absolute History URL on a credentialed document is wrong, and a test asserts that the bare fake does throw there, pinning the model to the browser rule before any wrapper is exercised.

## Decisions & Alternatives

| Decision | Chosen | Alternatives Considered | Rationale |
|----------|--------|------------------------|-----------|
| When to install the History wrappers | Always | Probe `replaceState` at load and wrap only if it throws | A probe exercises one call shape. A relative probe succeeds on a credentialed document while a library's absolute `location.href` call throws, so the probe reports "history works" exactly where the wrapper is needed. An absolute probe fails on every credentialed document and would disable history updates that relative URLs could have made. |
| History URL form | Same-host URLs rewritten to relative | Re-insert the document's userinfo into absolute URLs; no-op every History call on a credentialed document | The page cannot read the document's userinfo (`location.href` is redacted), so it cannot re-insert it. No-op'ing loses the address-bar page number that pagination and reload depend on. |
| A `SecurityError` from native History | Warn and return | Let it propagate | Callers run inside request-completion handlers; a throw there kills the caller's pipeline (htmx swap and request lock). |
| Non-`SecurityError` from native History | Propagate | Swallow everything | Other errors (e.g. an uncloneable state object) are caller bugs and should stay loud. |
| One script for all sinks | Single `datastar-auth-fix.js` | One script per sink | The sinks share the cause, the resolver, and the load-order requirement. Separate scripts allowed one to be fixed while another regressed. |
| Delivery | Embedded at compile time via `assets` | `src=` tag to a file the app copies into its own static dir | Thin-JAR builds omit a git dependency's resource dirs, which forces a per-app copy; copies drift. |
| Test doubles | Fakes that implement the browser rule, self-checked | Fakes keyed on surface features of the URL string (e.g. contains `@`) | A surface-feature fake can encode the rule backwards and pass while the browser fails. |

## Open Questions & Future Decisions

### Deferred
1. A real-browser check (load a page from a credentialed URL, press a pagination key, assert the page advanced) would catch a browser-rule change the fakes cannot. Not built; consuming apps verify manually after a SHA bump.
2. `navigation.navigate()` (Navigation API) and `location.replace()` are not wrapped; no library in use calls them with a redacted absolute URL.

## References

- HTML Standard, *URL and history update steps* — the rewritable-URL rule.
- Fetch Standard, *Request constructor* — "includes credentials" rejection.
- `README.md` § sharp edges — Basic Auth.
