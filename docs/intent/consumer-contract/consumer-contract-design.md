---
parent: high-level-design
prefix: CONTRACT
---

# Consumer Contract

## Context and Design Philosophy

The kit's promise — a failure fixed here stays fixed in every app — holds only while an app actually runs the kit's bytes. Two things break that silently. An app can carry its own copy of a kit browser asset; and because an app's `resources/` precedes its dependencies on the classpath, that copy wins over the kit's file at the same path without any signal. Both were found in production: a stale Datastar client served for weeks while every repository check passed.

Checks that live in each app do not scale: copied test files drift exactly like copied assets. So the checks live here, and reach apps two ways: through a function every app already calls, and through a test an app adopts in one line.

## The copy audit

`(datastar-kit.assets/copy-audit)` inspects, through the thread's context class loader, every classpath resource at a kit asset's resource path (`public/vendor/datastar-aliased.js`, `public/js/datastar-kit.js`, `public/js/datastar-auth-fix.js`, `public/js/keyboard-chords.js`) and compares it with the bytes the kit embedded at compile time.

| Condition | Finding |
|---|---|
| More than one classpath root provides the path | `:shadowed` — one of them is silently winning |
| A provider's bytes differ from the embedded bytes | `:stale-copy` |
| One provider, identical bytes (the kit's own file in development; a harmless identical copy in an image) | none |
| No provider (a thin-JAR image of an app with no copies) | none |

A finding is data: `{:path … :problem … :kit-sha … :providers [{:url … :sha …}]}`.

The two conditions cover the two environments. In development and tests the kit's resource directory is on the classpath, so any app copy makes the path `:shadowed`, stale or not. In a thin-JAR image the kit's directory is absent, so an app copy is the only provider and is reported only when its bytes are stale — which is exactly when it matters there.

## Reach without adoption

`script-tags` runs the audit the first time it is called in a process and prints one warning per finding to standard error, naming the path, the problem, and both hashes. Every consuming app calls `script-tags`, so an app learns it is serving a stale copy the moment it bumps the kit, in development and in production, without adding anything.

The audit never affects rendering: it runs once, inside a guard, and `script-tags` returns its tags whatever the audit does.

## Reach by adoption: the contract tests

`datastar-kit.testing` provides the checks as plain functions that return data, and one macro that defines `clojure.test` tests in the calling namespace, so an app's entire test file is a namespace form and one call.

| Check | Fails when |
|---|---|
| No kit copies | `copy-audit` returns a finding |
| No shadowed public files | any file under the app's public root is also provided at the same path by another classpath root |
| Only authorized JavaScript (when the app names a manifest) | a script under the public root is not listed in the manifest, a listed script is missing, or a third-party entry lacks `:source`/`:version` or differs from its pinned SHA-256 |

The manifest is EDN: `{:app #{"js/app.js" …} :third-party {"path" {:sha256 … :source … :version …}}}`. App entries carry no hash, because those files change with ordinary work.

## Decisions & Alternatives

| Decision | Chosen | Alternatives Considered | Rationale |
|----------|--------|------------------------|-----------|
| Where the checks live | In the kit | A test file each app copies; a documented checklist | Copied tests drift like copied assets, and a checklist is not executed. |
| How apps that adopt nothing are reached | A one-time audit inside `script-tags` | Only the adopted test; audit in `wrap-kit-assets` | Every app calls `script-tags`; not every app has mounted the middleware or added the test. |
| What the runtime audit does on a finding | Warn on standard error, once | Throw; log through Timbre | Throwing on a page render turns a hygiene problem into an outage. The assets namespace has no logging dependency, and standard error reaches every log collector. |
| Class loader | Thread context class loader | The kit's defining class loader | The context loader is what resolves an app's resources in every deployment shape, and it lets a test substitute a classpath. |
| App-file entries in the manifest | Path only | Path plus hash | A hash on a file edited daily makes every edit a two-file change and teaches people to regenerate the manifest without reading it. |

## Open Questions & Future Decisions

### Deferred
1. A fleet census (which apps pin which kit SHA, carry copies, or lack the contract test) is the only thing that reaches an app that never bumps its pin. It belongs outside this repository.
2. Asserting that every script tag on a rendered page is app-owned, kit-owned, or carries an integrity hash needs the app's rendered HTML; a helper that takes an HTML string is a natural addition.

## References

- `docs/intent/kit-assets/kit-assets-design.md`
