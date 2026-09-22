/**
 * datastar-kit.js — Shared JS runtime for Datastar game engine projects.
 *
 * Portable primitives that pair with ds.clj Clojure expression helpers.
 * Every project using ds.clj's post-action* needs postJSON loaded.
 * Load this file BEFORE your app.js in the HTML <head>.
 *
 * Functions provided:
 *   postJSON(url, body)                       — fetch wrapper for game engine POSTs
 *   showNotification(msg, err?, durationMs?)  — overlay notification (upper right);
 *                                                3s auto-hide by default; a duration
 *                                                of 0 keeps the message until the
 *                                                next notification
 *   kitInstallKeepScroll()                    — gesture-anchored scroll keeping
 *                                                (installed automatically on load)
 *   kitKeepScrollDecide(input)                — the pure rule behind it
 */

// ---------------------------------------------------------------------------
// postJSON — unified fetch helper for POST + JSON body
// Every ds/post-action* call compiles to postJSON(url, body).
// Game engine pattern: POST and forget, server pushes result via SSE.
// ---------------------------------------------------------------------------
function postJSON(url, body) {
  // @spec KIT-RUNTIME-SCROLL-002, KIT-RUNTIME-SCROLL-003
  if (_kitKeepScrollInstalled) { _kitRecordGesture(); }
  return fetch(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body)
  });
}

// ---------------------------------------------------------------------------
// showNotification — overlay notification (upper right); 3s auto-hide by
// default; a duration of 0 keeps the message until the next notification.
// ds.clj clipboard helpers call showNotification('Copied!').
// ---------------------------------------------------------------------------
var _notifyTimer = null;
function showNotification(msg, isError, durationMs) {
  if (durationMs === undefined) { durationMs = 3000; }
  // Prefer server-rendered #notification element (project-specific CSS).
  var el = document.getElementById('notification');
  if (el) {
    clearTimeout(_notifyTimer);
    el.textContent = msg;
    el.className = 'notification show' + (isError ? ' error' : '');
    if (durationMs === 0) { return; }
    _notifyTimer = setTimeout(function() { el.className = 'notification'; }, durationMs);
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
  if (durationMs === 0) { return; }
  _notifyTimer = setTimeout(function() { el.style.opacity = '0'; }, durationMs);
}

// ---------------------------------------------------------------------------
// Gesture-anchored scroll keeping
//
// A server push is a whole new frame. When it grows content ABOVE the viewport
// -- an offer box on a row above the fold, a message bar at the top -- the row
// the user just clicked slides down out of the place they were looking at, and
// Chrome's own scroll anchoring recovers only part of it. Scroll position is
// browser-owned state no server can see, so the runtime keeps it: it remembers
// the element the last gesture came from, and after each applied frame puts
// that element's top edge back where it was.
//
// Hook: a MutationObserver over document.body, coalesced to one animation
// frame. Datastar's `datastar-patch-elements` is a plugin name, not an event,
// and `datastar-fetch` reports a Datastar fetch action -- on the game-loop
// shape the patches arrive down one long-lived SSE stream that never reaches
// `finished`, and postJSON's plain fetch never dispatches it at all. The mutation
// record is the one signal emitted once per applied frame.
//
// Opt out with data-kit-keep-scroll="off" on the element, a subtree, or <html>.
// Intent: docs/intent/kit-runtime/ (KIT-RUNTIME-SCROLL-001..012).
// ---------------------------------------------------------------------------
var KIT_KEEP_SCROLL_MAX_AGE_MS = 2000;   // a correction the user can still connect to their click
var KIT_KEEP_SCROLL_MIN_DELTA_PX = 1;    // below this, moving the page is worse than the drift

var _kitKeepScrollInstalled = false;
var _kitGesture = null;                  // {el, id, top, at} — only the most recent
var _kitLastPointerTarget = null;
var _kitLastUserScrollAt = 0;
var _kitRestorePending = false;

// Elements a click or a keyboard gesture actually lands on.
var _KIT_GESTURE_TAGS = { BUTTON: 1, INPUT: 1, SELECT: 1, TEXTAREA: 1, A: 1 };
// Keys that scroll the page -- but only outside a control, where they move a
// caret, change a value, or activate the thing that is focused.
var _KIT_SCROLL_KEYS = {
  ArrowUp: 1, ArrowDown: 1, PageUp: 1, PageDown: 1, Home: 1, End: 1,
  ' ': 1, Spacebar: 1
};

function _kitNow() { return Date.now(); }

// @spec KIT-RUNTIME-SCROLL-003, KIT-RUNTIME-SCROLL-010
function _kitOptedOut(el) {
  var node = el;
  while (node) {
    if (node.getAttribute && node.getAttribute('data-kit-keep-scroll') === 'off') { return true; }
    node = node.parentElement || null;
  }
  var root = document.documentElement;
  return !!(root && root.getAttribute && root.getAttribute('data-kit-keep-scroll') === 'off');
}

function _kitGestureElement() {
  var active = document.activeElement;
  if (active && active.tagName && _KIT_GESTURE_TAGS[active.tagName]) { return active; }
  return _kitLastPointerTarget || null;
}

// @spec KIT-RUNTIME-SCROLL-002, KIT-RUNTIME-SCROLL-003
function _kitRecordGesture() {
  var el = _kitGestureElement();
  if (!el || !el.getBoundingClientRect || _kitOptedOut(el)) {
    _kitGesture = null;
    return null;
  }
  _kitGesture = { el: el, id: el.id || null, top: el.getBoundingClientRect().top, at: _kitNow() };
  return _kitGesture;
}

/**
 * kitKeepScrollDecide — the whole rule, as a pure function.
 *
 * input: {gestureAt, recordedTop, newTop, now, lastUserScrollAt, optedOut, found,
 *         maxAgeMs?, minDeltaPx?}
 * returns {action:'scroll', by} | {action:'forget', reason} | {action:'none', reason}
 *
 * @spec KIT-RUNTIME-SCROLL-012
 */
function kitKeepScrollDecide(input) {
  if (!input || input.gestureAt === undefined || input.gestureAt === null) {
    return { action: 'none', reason: 'no-gesture' };
  }
  var maxAge = (input.maxAgeMs === undefined || input.maxAgeMs === null)
    ? KIT_KEEP_SCROLL_MAX_AGE_MS : input.maxAgeMs;
  var minDelta = (input.minDeltaPx === undefined || input.minDeltaPx === null)
    ? KIT_KEEP_SCROLL_MIN_DELTA_PX : input.minDeltaPx;

  // Order matters: a stale gesture and a user who has taken the scroll back are
  // reasons to forget whatever else is true of the element.
  if (input.now - input.gestureAt > maxAge) { return { action: 'forget', reason: 'expired' }; }
  if (input.lastUserScrollAt && input.lastUserScrollAt >= input.gestureAt) {
    return { action: 'forget', reason: 'user-scrolled' };
  }
  if (input.optedOut) { return { action: 'forget', reason: 'opted-out' }; }
  if (!input.found) { return { action: 'forget', reason: 'element-gone' }; }

  var delta = input.newTop - input.recordedTop;
  if (Math.abs(delta) <= minDelta) { return { action: 'none', reason: 'within-tolerance' }; }
  return { action: 'scroll', by: delta };
}

// @spec KIT-RUNTIME-SCROLL-004, KIT-RUNTIME-SCROLL-005, KIT-RUNTIME-SCROLL-006,
//       KIT-RUNTIME-SCROLL-007, KIT-RUNTIME-SCROLL-008, KIT-RUNTIME-SCROLL-011
function _kitRestoreScroll() {
  var gesture = _kitGesture;
  if (!gesture) { return; }
  // The morph may have replaced the node; the id is how we find it again.
  var el = (gesture.el && gesture.el.isConnected)
    ? gesture.el
    : (gesture.id ? document.getElementById(gesture.id) : null);
  var decision = kitKeepScrollDecide({
    gestureAt: gesture.at,
    recordedTop: gesture.top,
    newTop: (el && el.getBoundingClientRect) ? el.getBoundingClientRect().top : 0,
    now: _kitNow(),
    lastUserScrollAt: _kitLastUserScrollAt,
    optedOut: el ? _kitOptedOut(el) : false,
    found: !!(el && el.getBoundingClientRect)
  });
  if (decision.action === 'forget') { _kitGesture = null; return; }
  if (decision.action !== 'scroll') { return; }
  // gesture.top is deliberately left as recorded, so a burst of pushes returns
  // the element to the same screen position instead of drifting frame by frame.
  gesture.el = el;
  window.scrollBy(0, decision.by);
}

function _kitScheduleRestore() {
  if (_kitRestorePending) { return; }
  _kitRestorePending = true;
  requestAnimationFrame(function () {
    _kitRestorePending = false;
    _kitRestoreScroll();
  });
}

function _kitOnUserScroll() { _kitLastUserScrollAt = _kitNow(); }

// @spec KIT-RUNTIME-SCROLL-009
function _kitOnKeyDown(e) {
  if (!e || !_KIT_SCROLL_KEYS[e.key]) { return; }
  var target = e.target;
  if (target && target.tagName && _KIT_GESTURE_TAGS[target.tagName]) { return; }
  if (target && target.isContentEditable) { return; }
  _kitLastUserScrollAt = _kitNow();
}

/**
 * kitInstallKeepScroll — wire up scroll keeping. Called once on load; safe to
 * call again. Returns false, without throwing, on a browser that lacks any of
 * the four APIs this needs.
 *
 * @spec KIT-RUNTIME-SCROLL-001
 */
function kitInstallKeepScroll() {
  if (_kitKeepScrollInstalled) { return true; }
  if (typeof document === 'undefined' || !document.addEventListener) { return false; }
  if (typeof MutationObserver === 'undefined') { return false; }
  if (typeof requestAnimationFrame === 'undefined') { return false; }
  if (typeof window === 'undefined' || !window.scrollBy) { return false; }
  _kitKeepScrollInstalled = true;

  // Capture phase: a handler that stops propagation must not hide the gesture.
  document.addEventListener('pointerdown', function (e) {
    _kitLastPointerTarget = (e && e.target) ? e.target : null;
  }, true);
  document.addEventListener('wheel', _kitOnUserScroll, true);
  document.addEventListener('touchmove', _kitOnUserScroll, true);
  document.addEventListener('keydown', _kitOnKeyDown, true);

  var observeBody = function () {
    if (!document.body) { return; }
    new MutationObserver(_kitScheduleRestore).observe(document.body, {
      childList: true, subtree: true, characterData: true
    });
  };
  if (document.body) { observeBody(); }
  else { document.addEventListener('DOMContentLoaded', observeBody); }
  return true;
}

kitInstallKeepScroll();
