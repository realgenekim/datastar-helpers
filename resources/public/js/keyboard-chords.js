// Reusable two-key chord state machine for browser-owned shortcuts.
// Applications own their bindings and actions; this helper owns normalization,
// prefix lifetime, editable-field suppression, and deterministic reset behavior.
(function (root) {
  "use strict";

  if (root.DatastarKeyboardChords) return;

  var modifierKeys = ["shift", "control", "alt", "meta"];

  function isEditable(target) {
    if (!target) return false;
    var tag = String(target.tagName || "").toLowerCase();
    return tag === "input" || tag === "textarea" || tag === "select" || target.isContentEditable === true;
  }

  function tokenFor(event) {
    var key = String(event.key || "").toLowerCase();
    if (!key || modifierKeys.indexOf(key) !== -1) return null;
    if (event.metaKey || event.ctrlKey || event.altKey) return null;
    if (event.shiftKey && /^[a-z]$/.test(key)) return "shift+" + key;
    return key;
  }

  function create(options) {
    options = options || {};
    var bindings = options.bindings || {};
    var timeoutMs = options.timeoutMs == null ? 1000 : options.timeoutMs;
    var eventTarget = options.eventTarget || root.document;
    var windowTarget = options.windowTarget || root;
    var prefixes = {};
    var pending = null;
    var timeoutId = null;

    Object.keys(bindings).forEach(function (chord) {
      var parts = chord.trim().split(/\s+/);
      if (parts.length !== 2) throw new Error("Keyboard chord must contain exactly two keys: " + chord);
      prefixes[parts[0]] = true;
    });

    function reset(reason) {
      if (timeoutId != null) root.clearTimeout(timeoutId);
      timeoutId = null;
      var previous = pending;
      pending = null;
      if (previous && typeof options.onReset === "function") options.onReset(previous, reason);
    }

    function begin(prefix, event) {
      reset("replaced");
      pending = prefix;
      timeoutId = root.setTimeout(function () { reset("timeout"); }, timeoutMs);
      if (options.preventDefault !== false && event.preventDefault) event.preventDefault();
      if (typeof options.onPrefix === "function") options.onPrefix(prefix, event);
    }

    function handle(event) {
      var rawKey = String(event.key || "").toLowerCase();

      if (isEditable(event.target)) {
        reset("editable");
        return false;
      }

      // Browsers emit a standalone Shift keydown between `g` and shifted `S`.
      // It must not consume or cancel the pending prefix.
      if (modifierKeys.indexOf(rawKey) !== -1) return pending !== null;

      if (rawKey === "escape") {
        var hadPending = pending !== null;
        reset("escape");
        return hadPending;
      }

      if (event.repeat) return pending !== null;

      var token = tokenFor(event);
      if (!token) {
        reset("modified-key");
        return false;
      }

      if (pending !== null) {
        var chord = pending + " " + token;
        var action = bindings[chord];
        reset(action ? "matched" : "unknown");
        if (action) {
          if (options.preventDefault !== false && event.preventDefault) event.preventDefault();
          action(event, chord);
        } else if (typeof options.onUnknown === "function") {
          options.onUnknown(chord, event);
        }
        return true;
      }

      if (prefixes[token]) {
        begin(token, event);
        return true;
      }

      return false;
    }

    function resetOnBlur() { reset("blur"); }
    function resetOnVisibility() {
      if (!eventTarget || eventTarget.hidden !== false) reset("visibilitychange");
    }

    if (windowTarget && windowTarget.addEventListener) windowTarget.addEventListener("blur", resetOnBlur);
    if (eventTarget && eventTarget.addEventListener) {
      eventTarget.addEventListener("visibilitychange", resetOnVisibility);
    }

    return {
      handle: handle,
      reset: reset,
      pending: function () { return pending; },
      destroy: function () {
        reset("destroy");
        if (windowTarget && windowTarget.removeEventListener) windowTarget.removeEventListener("blur", resetOnBlur);
        if (eventTarget && eventTarget.removeEventListener) {
          eventTarget.removeEventListener("visibilitychange", resetOnVisibility);
        }
      }
    };
  }

  root.DatastarKeyboardChords = Object.freeze({
    create: create,
    isEditable: isEditable,
    tokenFor: tokenFor
  });
})(typeof window !== "undefined" ? window : globalThis);
