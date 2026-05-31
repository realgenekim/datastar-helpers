// ============================================================================
// DATASTAR + HTTP BASIC AUTH FIX
// ============================================================================
// Background
// ----------
// This app sits behind HTTP Basic Auth. When a page is opened with credentials
// embedded in the URL (https://user:pass@host/...), two browser behaviors break
// libraries that drive the UI from the client:
//
//   1. history.pushState/replaceState throw SecurityError  -> handled by
//      /js/history-patch.js
//   2. The Fetch API REJECTS any URL that contains credentials, throwing
//      "TypeError: Failed to construct 'Request': Request cannot be constructed
//       from a URL that includes credentials".
//
// HTMX uses XMLHttpRequest, which tolerates credentialed URLs, so HTMX never hit
// this. Datastar's @get()/@post() use fetch(), so it broke -- which is why the
// first Datastar experiment was reverted (see docs/datastar-basic-auth.md).
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
// Load order: this file MUST execute before /vendor/datastar-aliased.js.
// ============================================================================

(function () {
  'use strict';

  // Strip username:password from a URL string, resolving relative URLs against
  // the current document. Returns the cleaned string, or the original on parse
  // failure (e.g. opaque inputs we shouldn't touch).
  function stripCreds(urlString) {
    try {
      var u = new URL(urlString, window.location.href);
      if (u.username || u.password) {
        u.username = '';
        u.password = '';
        return u.toString();
      }
    } catch (e) {
      /* not a parseable URL -- leave untouched */
    }
    return urlString;
  }

  // Only patch when the page actually carries credentials. In the normal case
  // (browser auth dialog, no creds in URL) fetch works fine and we stay out of
  // the way entirely.
  var pageHasCreds = window.location.href.indexOf('@') !== -1;
  if (!pageHasCreds) {
    return;
  }

  console.log('[Datastar Auth Fix] Basic Auth credentials in URL detected; patching Request + fetch');

  // --- 1. Best-effort: scrub credentials from the visible URL ---------------
  // If history.replaceState is available (history-patch.js may have disabled it
  // when it also threw SecurityError), clean location so document.baseURI and
  // future relative-URL resolution never reintroduce credentials.
  try {
    var clean = stripCreds(window.location.href);
    if (clean !== window.location.href) {
      window.history.replaceState(window.history.state, '', clean);
    }
  } catch (e) {
    /* replaceState blocked (SecurityError) -- the Request/fetch patches below
       are the real safety net, so this is fine. */
  }

  // --- 2. Patch the Request constructor (the throw site) --------------------
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

  // --- 3. Patch fetch (defense in depth + force credential sending) ---------
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
