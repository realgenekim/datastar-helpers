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
 *   createTextJournal(options)    — synchronous exact-byte browser WAL
 *   installTextJournalReloadGuard — settle journal before browser-reload
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

// ---------------------------------------------------------------------------
// Browser-owned text journal
//
// SSE morph protection and reload protection are different problems.
// ds/browser-owned prevents ordinary morphs from repainting a control;
// createTextJournal synchronously preserves each visible input before any
// asynchronous server sync can be interrupted by reload, crash, or navigation.
// ---------------------------------------------------------------------------

function dsTextFingerprint(text) {
  // FNV-1a over JavaScript UTF-16 code units. This is an acknowledgement token,
  // not a security primitive; the exact text remains in the journal record.
  var hash = 0x811c9dc5;
  for (var i = 0; i < text.length; i += 1) {
    hash ^= text.charCodeAt(i);
    hash = Math.imul(hash, 0x01000193);
  }
  return ('00000000' + (hash >>> 0).toString(16)).slice(-8);
}

function _dsJournalInstanceId(session, key) {
  var existing = session.getItem(key);
  if (existing) return existing;
  var id = (globalThis.crypto && typeof globalThis.crypto.randomUUID === 'function')
    ? globalThis.crypto.randomUUID()
    : Date.now().toString(36) + '-' + Math.random().toString(36).slice(2);
  session.setItem(key, id);
  return id;
}

function _dsJournalError(error, record) {
  console.error('[datastar-kit] text journal failed', error);
  if (typeof globalThis.dispatchEvent === 'function' && typeof CustomEvent !== 'undefined') {
    globalThis.dispatchEvent(new CustomEvent('datastar-kit:journal-error', {
      detail: { error: String(error), record: record || null }
    }));
  }
}

function listTextJournals(options) {
  options = options || {};
  var storage = options.storage || globalThis.localStorage;
  var prefix = options.storageKey || 'datastar-kit:text-journal';
  var wantedDocument = options.documentId == null ? null : String(options.documentId);
  var records = [];
  for (var i = 0; i < storage.length; i += 1) {
    var key = storage.key(i);
    if (!key || !key.startsWith(prefix + ':') || key === prefix + ':tab-instance') continue;
    try {
      var record = JSON.parse(storage.getItem(key));
      if (record && (wantedDocument === null || record.documentId === wantedDocument)) {
        records.push(record);
      }
    } catch (error) {
      _dsJournalError(error, null);
    }
  }
  return records.sort(function(a, b) { return a.savedAt - b.savedAt; });
}

function createTextJournal(options) {
  options = options || {};
  var element = typeof options.element === 'string'
    ? document.querySelector(options.element)
    : options.element;
  if (!element || typeof element.addEventListener !== 'function') {
    throw new Error('createTextJournal requires a textarea/input element');
  }

  var storage = options.storage || globalThis.localStorage;
  var session = options.sessionStorage || globalThis.sessionStorage;
  var prefix = options.storageKey || 'datastar-kit:text-journal';
  var tabKey = prefix + ':tab-instance';
  var instanceId = options.instanceId || _dsJournalInstanceId(session, tabKey);
  var identityProvider = typeof options.identity === 'function'
    ? options.identity
    : function() { return options.identity || {}; };
  var sequence = 0;

  function identity() {
    var value = identityProvider() || {};
    if (value.documentId === undefined || value.documentId === null || value.documentId === '') {
      throw new Error('text journal identity requires documentId');
    }
    return value;
  }

  function keyFor(documentId) {
    return prefix + ':' + encodeURIComponent(String(documentId)) + ':' + instanceId;
  }

  function readFor(meta) {
    var raw = storage.getItem(keyFor(meta.documentId));
    if (!raw) return null;
    try {
      return JSON.parse(raw);
    } catch (error) {
      _dsJournalError(error, null);
      return null;
    }
  }

  function pending() {
    return readFor(identity());
  }

  var initial = pending();
  if (initial && Number.isFinite(initial.sequence)) sequence = initial.sequence;

  function snapshot() {
    var meta = identity();
    var text = String(element.value == null ? '' : element.value);
    sequence += 1;
    var record = {
      version: 1,
      documentId: String(meta.documentId),
      revision: meta.revision == null ? null : meta.revision,
      editorKey: meta.editorKey == null ? null : String(meta.editorKey),
      instanceId: instanceId,
      sequence: sequence,
      text: text,
      hash: dsTextFingerprint(text),
      savedAt: Date.now()
    };
    record.token = instanceId + ':' + sequence + ':' + record.hash;
    storage.setItem(keyFor(record.documentId), JSON.stringify(record));
    return record;
  }

  function acknowledge(receipt) {
    receipt = receipt || {};
    var meta = identity();
    var record = readFor(meta);
    if (!record) return false;
    var matches = receipt.sequence === record.sequence &&
      receipt.hash === record.hash && receipt.token === record.token;
    if (receipt.documentId != null) matches = matches && String(receipt.documentId) === record.documentId;
    if (receipt.revision != null) matches = matches && receipt.revision === record.revision;
    if (!matches) return false;
    storage.removeItem(keyFor(record.documentId));
    return true;
  }

  async function settle(send) {
    if (typeof send !== 'function') throw new Error('text journal settle requires a send function');
    var record = snapshot();
    var receipt = await send(record);
    if (!acknowledge(receipt)) {
      throw new Error('text journal acknowledgement did not match exact visible bytes');
    }
    return record;
  }

  function onInput() {
    var record = null;
    try {
      record = snapshot();
      if (typeof options.onSnapshot === 'function') options.onSnapshot(record);
    } catch (error) {
      _dsJournalError(error, record);
      if (typeof options.onError === 'function') options.onError(error);
    }
  }

  element.addEventListener('input', onInput, true);

  return {
    instanceId: instanceId,
    snapshot: snapshot,
    pending: pending,
    listPending: function() {
      return listTextJournals({
        storage: storage,
        storageKey: prefix,
        documentId: identity().documentId
      });
    },
    acknowledge: acknowledge,
    settle: settle,
    destroy: function() { element.removeEventListener('input', onInput, true); }
  };
}

function installTextJournalReloadGuard(journal, send) {
  if (!journal || typeof journal.settle !== 'function') {
    throw new Error('installTextJournalReloadGuard requires a text journal');
  }
  var handler = function(event) {
    if (!event.detail || typeof event.detail.waitUntil !== 'function') return;
    event.detail.waitUntil(journal.settle(send));
  };
  globalThis.addEventListener('browser-reload:prepare', handler);
  return function() {
    globalThis.removeEventListener('browser-reload:prepare', handler);
  };
}

// CommonJS export is inert in browsers and makes the runtime behavior testable
// without introducing a bundler or a second implementation.
if (typeof module !== 'undefined' && module.exports) {
  module.exports = {
    dsTextFingerprint: dsTextFingerprint,
    listTextJournals: listTextJournals,
    createTextJournal: createTextJournal,
    installTextJournalReloadGuard: installTextJournalReloadGuard
  };
}
