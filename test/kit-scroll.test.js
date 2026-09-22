'use strict';

// Gesture-anchored scroll keeping in the kit runtime.
// @spec KIT-RUNTIME-SCROLL-001 .. KIT-RUNTIME-SCROLL-012
//
// There is no jsdom in this repo (and no network install), so this file follows
// the harness datastar-kit.test.js already uses: run the authored source in a
// vm context over a hand-built fake DOM, small enough to read and exact about
// the few APIs the runtime touches.

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const source = fs.readFileSync(
  path.join(__dirname, '../resources/public/js/datastar-kit.js'),
  'utf8'
);

// --- fake DOM -------------------------------------------------------------

function makeEl(tag, opts) {
  opts = opts || {};
  const el = {
    tagName: tag,
    id: opts.id || '',
    isConnected: opts.isConnected !== false,
    parentElement: opts.parent || null,
    isContentEditable: !!opts.contentEditable,
    attrs: opts.attrs || {},
    top: opts.top === undefined ? 0 : opts.top,
    rects: 0,
    getAttribute(name) {
      return Object.prototype.hasOwnProperty.call(this.attrs, name) ? this.attrs[name] : null;
    },
    getBoundingClientRect() {
      this.rects++;
      return { top: this.top, left: 0, bottom: this.top, right: 0, width: 0, height: 0 };
    }
  };
  return el;
}

function install(opts) {
  opts = opts || {};
  const listeners = {};          // type -> [fn]
  const byId = {};               // id -> element
  const observers = [];          // MutationObserver instances
  const frames = [];             // queued requestAnimationFrame callbacks
  const scrolls = [];            // window.scrollBy calls
  let clock = 1000;

  const documentElement = makeEl('HTML', { attrs: opts.htmlAttrs || {} });
  const body = makeEl('BODY', { parent: documentElement });

  const document = {
    documentElement,
    body,
    activeElement: null,
    addEventListener(type, fn) { (listeners[type] = listeners[type] || []).push(fn); },
    getElementById(id) {
      return Object.prototype.hasOwnProperty.call(byId, id) ? byId[id] : null;
    }
  };

  class MutationObserver {
    constructor(cb) { this.cb = cb; observers.push(this); }
    observe(target, options) { this.target = target; this.options = options; }
    disconnect() {}
  }

  const sandbox = {
    document,
    console,
    MutationObserver,
    Date: { now() { return clock; } },
    fetch() { return { mock: 'fetch-result' }; },
    setTimeout(fn, ms) { return 1; },
    clearTimeout() {},
    requestAnimationFrame(fn) { frames.push(fn); return frames.length; }
  };
  sandbox.window = sandbox;
  sandbox.globalThis = sandbox;
  if (opts.noScrollBy !== true) {
    sandbox.scrollBy = function (x, y) { scrolls.push({ x, y }); };
  }
  if (opts.noMutationObserver) { delete sandbox.MutationObserver; }
  if (opts.noRaf) { delete sandbox.requestAnimationFrame; }

  vm.runInNewContext(source, sandbox);

  const api = {
    sandbox, scrolls, frames, byId, document, body, documentElement, observers,
    now() { return clock; },
    advance(ms) { clock += ms; },
    register(el) { if (el.id) byId[el.id] = el; return el; },
    fire(type, event) {
      (listeners[type] || []).forEach((fn) => fn(event));
    },
    // One server push: notify the observer, then run the animation frame(s).
    morph(times) {
      for (let i = 0; i < (times || 1); i++) {
        observers.forEach((o) => o.cb([{ type: 'childList' }], o));
      }
      api.flushFrames();
    },
    notifyOnly(times) {
      for (let i = 0; i < (times || 1); i++) {
        observers.forEach((o) => o.cb([{ type: 'childList' }], o));
      }
    },
    flushFrames() {
      const queued = frames.splice(0, frames.length);
      queued.forEach((fn) => fn());
    },
    // A button inside body that the user clicks.
    button(id, top) {
      const el = makeEl('BUTTON', { id, top, parent: body });
      return api.register(el);
    },
    click(el) {
      document.activeElement = el;
      api.fire('pointerdown', { target: el });
    }
  };
  return api;
}

// --- the measured case ----------------------------------------------------

// @spec KIT-RUNTIME-SCROLL-002, KIT-RUNTIME-SCROLL-004
test('a push that grows content above the fold does not move the clicked row', () => {
  const t = install();
  const row = t.button('row-7', 500);
  t.click(row);
  t.sandbox.postJSON('/act', { id: 7 });

  row.top = 760;              // +260 px of new content rendered above it
  t.advance(120);
  t.morph();

  assert.deepEqual(t.scrolls, [{ x: 0, y: 260 }]);
});

// @spec KIT-RUNTIME-SCROLL-005
test('the recorded top is the anchor for every push in the window, not the last one', () => {
  const t = install();
  const row = t.button('row-7', 500);
  t.click(row);
  t.sandbox.postJSON('/act', {});

  row.top = 760;
  t.advance(100);
  t.morph();
  row.top = 900;              // a second push, measured before our correction lands
  t.advance(100);
  t.morph();

  assert.deepEqual(t.scrolls, [{ x: 0, y: 260 }, { x: 0, y: 400 }]);
});

// @spec KIT-RUNTIME-SCROLL-004
test('many mutations in one frame produce one correction', () => {
  const t = install();
  const row = t.button('row-7', 500);
  t.click(row);
  t.sandbox.postJSON('/act', {});

  row.top = 760;
  t.notifyOnly(5);
  assert.equal(t.frames.length, 1);
  t.flushFrames();
  assert.deepEqual(t.scrolls, [{ x: 0, y: 260 }]);
});

// @spec KIT-RUNTIME-SCROLL-004
test('a delta of 1 px or less is left alone', () => {
  const t = install();
  const row = t.button('row-7', 500);
  t.click(row);
  t.sandbox.postJSON('/act', {});
  row.top = 501;
  t.morph();
  assert.deepEqual(t.scrolls, []);
});

// @spec KIT-RUNTIME-SCROLL-004
test('content that shrinks above the fold scrolls the other way', () => {
  const t = install();
  const row = t.button('row-7', 500);
  t.click(row);
  t.sandbox.postJSON('/act', {});
  row.top = 380;
  t.morph();
  assert.deepEqual(t.scrolls, [{ x: 0, y: -120 }]);
});

// --- which element is the gesture ----------------------------------------

// @spec KIT-RUNTIME-SCROLL-002
test('a non-interactive activeElement falls back to the last pointerdown target', () => {
  const t = install();
  const row = t.button('row-7', 500);
  t.fire('pointerdown', { target: row });
  t.document.activeElement = t.body;   // click on a div leaves focus on the body
  t.sandbox.postJSON('/act', {});
  row.top = 760;
  t.morph();
  assert.deepEqual(t.scrolls, [{ x: 0, y: 260 }]);
});

// @spec KIT-RUNTIME-SCROLL-002
test('a focused control wins over a stale pointerdown target', () => {
  const t = install();
  const stale = t.button('row-1', 100);
  const focused = makeEl('INPUT', { id: 'row-9', top: 500, parent: t.body });
  t.register(focused);
  t.fire('pointerdown', { target: stale });
  t.document.activeElement = focused;  // keyboard moved on; Enter fired the gesture
  t.sandbox.postJSON('/act', {});
  focused.top = 700;
  stale.top = 300;
  t.morph();
  assert.deepEqual(t.scrolls, [{ x: 0, y: 200 }]);
});

// @spec KIT-RUNTIME-SCROLL-003, KIT-RUNTIME-SCROLL-011
test('no gesture element means nothing is recorded and nothing scrolls', () => {
  const t = install();
  t.sandbox.postJSON('/act', {});
  t.morph();
  assert.deepEqual(t.scrolls, []);
});

// @spec KIT-RUNTIME-SCROLL-011
test('a push with no gesture at all does not scroll', () => {
  const t = install();
  t.morph();
  assert.deepEqual(t.scrolls, []);
});

// @spec KIT-RUNTIME-SCROLL-002
test('a new gesture replaces the previous one', () => {
  const t = install();
  const first = t.button('row-1', 200);
  const second = t.button('row-2', 500);
  t.click(first);
  t.sandbox.postJSON('/act', {});
  t.click(second);
  t.sandbox.postJSON('/act', {});
  first.top = 900;
  second.top = 560;
  t.morph();
  assert.deepEqual(t.scrolls, [{ x: 0, y: 60 }]);
});

// --- the element moved or vanished ---------------------------------------

// @spec KIT-RUNTIME-SCROLL-006
test('an element replaced by the morph is found again by id', () => {
  const t = install();
  const row = t.button('row-7', 500);
  t.click(row);
  t.sandbox.postJSON('/act', {});

  row.isConnected = false;                       // outer-morphed away
  const replacement = t.button('row-7', 760);    // same id, new node
  t.morph();
  assert.deepEqual(t.scrolls, [{ x: 0, y: 260 }]);

  // and the replacement is what later pushes anchor to
  replacement.top = 860;
  t.morph();
  assert.deepEqual(t.scrolls, [{ x: 0, y: 260 }, { x: 0, y: 360 }]);
});

// @spec KIT-RUNTIME-SCROLL-007
test('an element gone with no id to find it by is forgotten', () => {
  const t = install();
  const row = makeEl('BUTTON', { top: 500, parent: t.body });   // no id
  t.click(row);
  t.sandbox.postJSON('/act', {});
  row.isConnected = false;
  row.top = 760;
  t.morph();
  assert.deepEqual(t.scrolls, []);
});

// @spec KIT-RUNTIME-SCROLL-007
test('an id that no longer exists is forgotten, not retried', () => {
  const t = install();
  const row = t.button('row-7', 500);
  t.click(row);
  t.sandbox.postJSON('/act', {});
  row.isConnected = false;
  delete t.byId['row-7'];
  t.morph();
  assert.deepEqual(t.scrolls, []);

  t.byId['row-7'] = t.button('row-7', 760);   // it comes back later
  t.morph();
  assert.deepEqual(t.scrolls, []);            // the gesture is already forgotten
});

// --- when it refuses ------------------------------------------------------

// @spec KIT-RUNTIME-SCROLL-008
test('a gesture older than 2000 ms is forgotten', () => {
  const t = install();
  const row = t.button('row-7', 500);
  t.click(row);
  t.sandbox.postJSON('/act', {});
  row.top = 760;
  t.advance(2001);
  t.morph();
  assert.deepEqual(t.scrolls, []);
});

// @spec KIT-RUNTIME-SCROLL-008
test('a gesture at the 2000 ms boundary still anchors', () => {
  const t = install();
  const row = t.button('row-7', 500);
  t.click(row);
  t.sandbox.postJSON('/act', {});
  row.top = 760;
  t.advance(2000);
  t.morph();
  assert.deepEqual(t.scrolls, [{ x: 0, y: 260 }]);
});

// @spec KIT-RUNTIME-SCROLL-009
for (const type of ['wheel', 'touchmove']) {
  test(`a ${type} after the gesture cancels the correction`, () => {
    const t = install();
    const row = t.button('row-7', 500);
    t.click(row);
    t.sandbox.postJSON('/act', {});
    t.advance(50);
    t.fire(type, { target: t.body });
    row.top = 760;
    t.morph();
    assert.deepEqual(t.scrolls, []);
  });
}

// @spec KIT-RUNTIME-SCROLL-009
test('a wheel before the gesture does not cancel it', () => {
  const t = install();
  const row = t.button('row-7', 500);
  t.fire('wheel', { target: t.body });
  t.advance(50);
  t.click(row);
  t.sandbox.postJSON('/act', {});
  row.top = 760;
  t.morph();
  assert.deepEqual(t.scrolls, [{ x: 0, y: 260 }]);
});

// @spec KIT-RUNTIME-SCROLL-009
test('a scrolling key pressed on the page cancels the correction', () => {
  const t = install();
  const row = t.button('row-7', 500);
  t.click(row);
  t.sandbox.postJSON('/act', {});
  t.advance(50);
  t.fire('keydown', { key: 'PageDown', target: t.body });
  row.top = 760;
  t.morph();
  assert.deepEqual(t.scrolls, []);
});

// @spec KIT-RUNTIME-SCROLL-009
test('arrow keys inside a text field are caret moves, not scrolling', () => {
  const t = install();
  const field = makeEl('INPUT', { id: 'q', top: 500, parent: t.body });
  t.register(field);
  t.click(field);
  t.sandbox.postJSON('/act', {});
  t.advance(50);
  t.fire('keydown', { key: 'ArrowDown', target: field });
  field.top = 760;
  t.morph();
  assert.deepEqual(t.scrolls, [{ x: 0, y: 260 }]);
});

// @spec KIT-RUNTIME-SCROLL-009
test('a non-scrolling key does not cancel the correction', () => {
  const t = install();
  const row = t.button('row-7', 500);
  t.click(row);
  t.sandbox.postJSON('/act', {});
  t.advance(50);
  t.fire('keydown', { key: 'a', target: t.body });
  row.top = 760;
  t.morph();
  assert.deepEqual(t.scrolls, [{ x: 0, y: 260 }]);
});

// --- opt-out --------------------------------------------------------------

// @spec KIT-RUNTIME-SCROLL-003
test('data-kit-keep-scroll=off on an ancestor opts the subtree out', () => {
  const t = install();
  const region = makeEl('DIV', { parent: t.body, attrs: { 'data-kit-keep-scroll': 'off' } });
  const row = makeEl('BUTTON', { id: 'row-7', top: 500, parent: region });
  t.register(row);
  t.click(row);
  t.sandbox.postJSON('/act', {});
  row.top = 760;
  t.morph();
  assert.deepEqual(t.scrolls, []);
});

// @spec KIT-RUNTIME-SCROLL-003
test('data-kit-keep-scroll=off on the page opts the whole page out', () => {
  const t = install({ htmlAttrs: { 'data-kit-keep-scroll': 'off' } });
  const row = t.button('row-7', 500);
  t.click(row);
  t.sandbox.postJSON('/act', {});
  row.top = 760;
  t.morph();
  assert.deepEqual(t.scrolls, []);
});

// @spec KIT-RUNTIME-SCROLL-010
test('an opt-out that arrives with the morph is honoured', () => {
  const t = install();
  const row = t.button('row-7', 500);
  t.click(row);
  t.sandbox.postJSON('/act', {});
  row.attrs['data-kit-keep-scroll'] = 'off';
  row.top = 760;
  t.morph();
  assert.deepEqual(t.scrolls, []);
});

// @spec KIT-RUNTIME-SCROLL-003
test('any other value of the attribute leaves scroll keeping on', () => {
  const t = install();
  const row = t.button('row-7', 500);
  row.attrs['data-kit-keep-scroll'] = 'on';
  t.click(row);
  t.sandbox.postJSON('/act', {});
  row.top = 760;
  t.morph();
  assert.deepEqual(t.scrolls, [{ x: 0, y: 260 }]);
});

// --- installation ---------------------------------------------------------

// @spec KIT-RUNTIME-SCROLL-001
test('scroll keeping installs when the browser provides what it needs', () => {
  const t = install();
  assert.equal(t.sandbox.kitInstallKeepScroll(), true);
  assert.equal(t.observers.length, 1);
  assert.equal(t.observers[0].target, t.body);
  assert.equal(t.observers[0].options.subtree, true);
  assert.equal(t.observers[0].options.childList, true);
});

// @spec KIT-RUNTIME-SCROLL-001
for (const missing of ['noMutationObserver', 'noRaf', 'noScrollBy']) {
  test(`a browser without ${missing.slice(2)} gets no scroll keeping and no error`, () => {
    const opts = {}; opts[missing] = true;
    const t = install(opts);
    assert.equal(t.sandbox.kitInstallKeepScroll(), false);
    const row = t.button('row-7', 500);
    t.click(row);
    // postJSON still works, and records nothing
    t.sandbox.postJSON('/act', {});
    row.top = 760;
    t.morph();
    assert.deepEqual(t.scrolls, []);
  });
}

// --- the pure decision ----------------------------------------------------

// @spec KIT-RUNTIME-SCROLL-012
test('kitKeepScrollDecide is a pure function of the situation', () => {
  const { sandbox } = install();
  const decide = sandbox.kitKeepScrollDecide;
  const base = {
    gestureAt: 1000, recordedTop: 500, newTop: 760, now: 1100,
    lastUserScrollAt: 0, optedOut: false, found: true
  };
  // Objects the vm context returns carry that realm's Object.prototype, which
  // deepStrictEqual counts as a difference on its own -- copy into this realm.
  const plain = (o) => (o === null || o === undefined ? o : Object.assign({}, o));
  const at = (over) => plain(decide(Object.assign({}, base, over)));

  assert.deepEqual(at({}), { action: 'scroll', by: 260 });
  assert.deepEqual(at({ newTop: 380 }), { action: 'scroll', by: -120 });
  assert.deepEqual(at({ newTop: 501 }), { action: 'none', reason: 'within-tolerance' });
  assert.deepEqual(at({ newTop: 500 }), { action: 'none', reason: 'within-tolerance' });
  assert.deepEqual(at({ now: 3001 }), { action: 'forget', reason: 'expired' });
  assert.deepEqual(at({ now: 3000 }), { action: 'scroll', by: 260 });
  assert.deepEqual(at({ lastUserScrollAt: 1000 }), { action: 'forget', reason: 'user-scrolled' });
  assert.deepEqual(at({ lastUserScrollAt: 999 }), { action: 'scroll', by: 260 });
  assert.deepEqual(at({ optedOut: true }), { action: 'forget', reason: 'opted-out' });
  assert.deepEqual(at({ found: false }), { action: 'forget', reason: 'element-gone' });
  assert.deepEqual(plain(decide(null)), { action: 'none', reason: 'no-gesture' });
  assert.deepEqual(plain(decide({})), { action: 'none', reason: 'no-gesture' });

  // staleness and the user's own scroll outrank a missing element
  assert.deepEqual(at({ now: 3001, found: false }), { action: 'forget', reason: 'expired' });
});
