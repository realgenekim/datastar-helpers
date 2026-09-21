// ============================================================================
// DATASTAR + HTTP BASIC AUTH FIX
// ============================================================================
// Background
// ----------
// This app sits behind HTTP Basic Auth. When a page is opened with credentials
// embedded in the URL (https://user:pass@host/...), two browser behaviors break
// libraries that drive the UI from the client:
//
//   1. history.pushState/replaceState throw SecurityError -> handled IN THIS
//      FILE. The browser rule: the new URL, resolved against the DOCUMENT URL,
//      must match the document URL in scheme, username, password, host, and
//      port -- only path/query/fragment may differ. The document URL keeps the
//      userinfo even though location.href REDACTS it. So:
//        - a relative URL ("/x?page=4") inherits the document's userinfo ->
//          ALLOWED
//        - an absolute URL carrying the same userinfo -> ALLOWED
//        - an absolute URL with NO userinfo (e.g. location.href itself, which
//          is what htmx passes to history.replaceState) -> userinfo differs ->
//          throws SecurityError
//   2. The Fetch API REJECTS any URL that contains credentials, throwing
//      "TypeError: Failed to construct 'Request': Request cannot be constructed
//       from a URL that includes credentials".
//
// HTMX uses XMLHttpRequest, which tolerates credentialed URLs, so HTMX never hit
// case 2 -- but it hits case 1: it calls history.replaceState(state, title,
// location.href) inside its XHR onload handler, and the uncaught SecurityError
// aborts the swap and leaves htmx's per-element request lock held. Datastar's
// @get()/@post() use fetch(), so it broke case 2 -- which is why the first
// Datastar experiment was reverted (see docs/datastar-basic-auth.md).
//
// Why the previous fix was insufficient
// --------------------------------------
// The original fetch-credential-strip.js wrapped ONLY window.fetch. But Datastar
// (and the browser) often construct `new Request(url, init)` BEFORE calling
// fetch(input, init). That Request constructor is where the TypeError is thrown,
// so wrapping fetch alone never gets a chance to run. The fix below patches BOTH:
//
//   * window.Request  (via Proxy, so `instanceof` and the prototype chain stay
//     intact) -- strips credentials from the URL before the native constructor
//     ever sees them.
//   * window.fetch    -- strips credentials from string / Request inputs as a
//     second line of defense, and forces credentials:'same-origin' so the
//     browser's cached Basic Auth header is still sent.
//
// The browser caches the Basic Auth credentials for the session after the first
// challenge, so stripping them from request URLs is safe: the Authorization
// header is still attached automatically by the browser.
//
// Load order: this file MUST be the first script on the page -- before htmx,
// /vendor/datastar-aliased.js, and anything else that touches History, Request,
// or fetch. Clojure consumers get that from datastar-kit.assets/basic-auth-script.
// ============================================================================

(function () {
  'use strict';

  // @spec BASIC-AUTH-LOAD-001, BASIC-AUTH-LOAD-004
  // Loading this bootstrap twice must not wrap Request/fetch/History twice. Expose the
  // installed version so production diagnostics can prove that the bootstrap
  // ran before Datastar initialized.
  if (window.__datastarAuthFixVersion) {
    return;
  }
  window.__datastarAuthFixVersion = '4';

  // Resolve every parseable URL to an absolute string, then strip userinfo. The
  // absolute conversion is essential even when location.href LOOKS clean: some
  // browsers redact its userinfo while native fetch still resolves relative
  // strings against the credentialed document URL and rejects them.
  // Returns the original only on parse failure (opaque inputs we shouldn't touch).
  function stripCreds(urlString) {
    try {
      var u = new URL(urlString, window.location.href);
      if (u.username || u.password) {
        u.username = '';
        u.password = '';
      }
      return u.toString();
    } catch (e) {
      /* not a parseable URL -- leave untouched */
    }
    return urlString;
  }

  // Install unconditionally. Some browsers redact URL userinfo from
  // window.location.href even though relative Request/fetch resolution still
  // inherits it from the document URL. stripCreds() is already a no-op for
  // ordinary absolute URLs, so an early "contains @" guard only creates a
  // false-negative failure mode.
  console.log('[Datastar Auth Fix] v4 installed; sanitizing Request/fetch URLs and normalizing History URLs');

  // --- 1. History: always normalize same-host URLs to RELATIVE ---------------
  // @spec BASIC-AUTH-HISTORY-001, BASIC-AUTH-HISTORY-002, BASIC-AUTH-HISTORY-003,
  //       BASIC-AUTH-HISTORY-004, BASIC-AUTH-HISTORY-005, BASIC-AUTH-HISTORY-006
  // A same-host URL is applied as a relative URL, so it inherits the document's
  // userinfo and is always legal. A SecurityError never escapes to the caller:
  // an uncaught throw inside htmx's XHR onload aborts the swap and leaves its
  // request lock held. Installed with no load-time probe -- a probe exercises
  // one call shape, and callers use others.
  function toRelativeHistoryUrl(url) {
    if (url == null) return url;
    try {
      var u = new URL(String(url), window.location.href);
      var here = new URL(window.location.href);
      if (u.protocol === here.protocol && u.host === here.host) {
        return u.pathname + u.search + u.hash;
      }
    } catch (e) { /* unparseable -- leave untouched */ }
    return url;
  }

  if (typeof window.History === 'function' && window.History.prototype) {
    ['pushState', 'replaceState'].forEach(function (method) {
      var nativeMethod = window.History.prototype[method];
      if (typeof nativeMethod !== 'function') return;
      window.History.prototype[method] = function (state, title, url) {
        try {
          return nativeMethod.call(this, state, title, toRelativeHistoryUrl(url));
        } catch (e) {
          if (e && e.name === 'SecurityError') {
            if (window.console && console.warn) {
              console.warn('[Datastar Auth Fix] history.' + method + ' refused; continuing', String(url));
            }
            return undefined;
          }
          throw e;
        }
      };
    });
  }

  // --- 2. Patch the Request constructor (the throw site) --------------------
  // @spec BASIC-AUTH-FETCH-001, BASIC-AUTH-FETCH-003
  if (typeof window.Request === 'function') {
    var NativeRequest = window.Request;
    window.Request = new Proxy(NativeRequest, {
      construct: function (target, args) {
        if (typeof args[0] === 'string') {
          args[0] = stripCreds(args[0]);
        } else if (args[0] && typeof args[0].url === 'string') {
          // A Request was passed as input -- rebuild only if it carries creds.
          var cleaned = stripCreds(args[0].url);
          if (cleaned !== args[0].url) {
            args[0] = new NativeRequest(cleaned, args[0]);
          }
        }
        return Reflect.construct(target, args, this.newTarget || target);
      }
    });
  }

  // --- 3. Patch fetch (defense in depth + force credential sending) ----------
  // @spec BASIC-AUTH-FETCH-002, BASIC-AUTH-FETCH-003, BASIC-AUTH-FETCH-004
  var nativeFetch = window.fetch;
  window.fetch = function (input, init) {
    init = init || {};
    if (!('credentials' in init)) {
      init.credentials = 'same-origin';
    }
    if (typeof input === 'string') {
      input = stripCreds(input);
    } else if (input && typeof input.url === 'string') {
      var cleaned = stripCreds(input.url);
      if (cleaned !== input.url) {
        input = new Request(cleaned, input); // uses patched Request above
      }
    }
    return nativeFetch.call(this, input, init);
  };
})();
