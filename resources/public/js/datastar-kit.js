/**
 * datastar-kit.js — Shared JS runtime for Datastar game engine projects.
 *
 * Portable primitives that pair with ds.clj Clojure expression helpers.
 * Every project using ds.clj's post-action* needs postJSON loaded.
 * Load this file BEFORE your app.js in the HTML <head>.
 *
 * Functions provided:
 *   postJSON(url, body)           — fetch wrapper for game engine POSTs
 *   showNotification(msg, err?)   — overlay notification (upper right)
 */

// ---------------------------------------------------------------------------
// postJSON — unified fetch helper for POST + JSON body
// Every ds/post-action* call compiles to postJSON(url, body).
// Game engine pattern: POST and forget, server pushes result via SSE.
// ---------------------------------------------------------------------------
function postJSON(url, body) {
  return fetch(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body)
  });
}

// ---------------------------------------------------------------------------
// showNotification — overlay notification (upper right, 3s auto-hide)
// ds.clj clipboard helpers call showNotification('Copied!').
// ---------------------------------------------------------------------------
var _notifyTimer = null;
function showNotification(msg, isError) {
  // Prefer server-rendered #notification element (project-specific CSS).
  var el = document.getElementById('notification');
  if (el) {
    clearTimeout(_notifyTimer);
    el.textContent = msg;
    el.className = 'notification show' + (isError ? ' error' : '');
    _notifyTimer = setTimeout(function() { el.className = 'notification'; }, 3000);
    return;
  }
  // Fallback: create floating notification
  el = document.getElementById('ds-notify');
  if (!el) {
    el = document.createElement('div');
    el.id = 'ds-notify';
    el.style.cssText = 'position:fixed;top:12px;right:12px;z-index:10000;max-width:50vw;padding:10px 18px;border-radius:6px;font-size:13px;font-weight:600;pointer-events:none;opacity:0;transition:opacity 0.3s;';
    document.body.appendChild(el);
  }
  clearTimeout(_notifyTimer);
  el.textContent = msg;
  el.style.background = isError ? '#e74c3c' : '#2ecc71';
  el.style.color = '#fff';
  el.style.opacity = '1';
  _notifyTimer = setTimeout(function() { el.style.opacity = '0'; }, 3000);
}
