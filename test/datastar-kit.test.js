'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const rawSource = fs.readFileSync(
  path.join(__dirname, '../resources/public/js/datastar-kit.js'),
  'utf8'
);

// The kit serves this script by URL, byte for byte (kit-assets), so the authored
// source is the only form a browser ever runs.
const variants = [['as authored', rawSource]];
let source = rawSource;


function makeElement(tag) {
  return {
    tagName: tag,
    id: '',
    style: { cssText: '', background: '', color: '', opacity: '' },
    textContent: '',
    className: ''
  };
}

function install() {
  const elements = {};
  const appended = [];
  const timers = new Map();
  let nextTimer = 1;
  const fetchCalls = [];
  const fetchResult = { mock: 'fetch-result' };
  const document = {
    getElementById(id) {
      return Object.prototype.hasOwnProperty.call(elements, id) ? elements[id] : null;
    },
    createElement(tag) { return makeElement(tag); },
    body: {
      appendChild(el) {
        appended.push(el);
        if (el.id) elements[el.id] = el;
      }
    }
  };
  const sandbox = {
    document,
    console,
    fetch(url, opts) {
      fetchCalls.push({ url, opts });
      return fetchResult;
    },
    setTimeout(fn, ms) {
      const id = nextTimer++;
      timers.set(id, { fn, ms });
      return id;
    },
    clearTimeout(id) { timers.delete(id); }
  };
  sandbox.window = sandbox;
  sandbox.globalThis = sandbox;
  vm.runInNewContext(source, sandbox);
  return { sandbox, elements, appended, timers, fetchCalls, fetchResult };
}

function addNotificationElement(elements) {
  const notif = makeElement('div');
  notif.id = 'notification';
  elements.notification = notif;
  return notif;
}

function main() {
  // @spec KIT-RUNTIME-001
  {
    const { sandbox, fetchCalls, fetchResult } = install();
    const result = sandbox.postJSON('/api/foo', { a: 1 });
    assert.equal(fetchCalls.length, 1);
    const { url, opts } = fetchCalls[0];
    assert.equal(url, '/api/foo');
    assert.equal(opts.method, 'POST');
    // opts.headers is an object literal created inside the vm sandbox's
    // realm, so it has a different Object.prototype than one built here --
    // assert/strict's deepEqual would call it "not reference-equal" on that
    // basis alone. Compare shape and values instead.
    assert.deepEqual(Object.keys(opts.headers), ['Content-Type']);
    assert.equal(opts.headers['Content-Type'], 'application/json');
    assert.equal(opts.body, JSON.stringify({ a: 1 }));
    assert.equal(result, fetchResult);
  }

  // @spec KIT-RUNTIME-002
  {
    const { sandbox, elements, appended } = install();
    const notif = addNotificationElement(elements);
    sandbox.showNotification('Saved!', false);
    assert.equal(notif.textContent, 'Saved!');
    assert.equal(notif.className, 'notification show');
    assert.equal(appended.length, 0);
    assert.equal(elements['ds-notify'], undefined);

    sandbox.showNotification('Broke!', true);
    assert.equal(notif.textContent, 'Broke!');
    assert.equal(notif.className, 'notification show error');
    assert.equal(appended.length, 0);
  }

  // @spec KIT-RUNTIME-003
  {
    const { sandbox, elements, appended } = install();
    sandbox.showNotification('Hello', false);
    assert.equal(appended.length, 1);
    const overlay = appended[0];
    assert.equal(overlay.id, 'ds-notify');
    assert.equal(overlay.textContent, 'Hello');
    assert.equal(overlay.style.opacity, '1');
    assert.equal(elements['ds-notify'], overlay);

    sandbox.showNotification('World', false);
    assert.equal(appended.length, 1);
    assert.equal(overlay.textContent, 'World');
    assert.equal(overlay.style.opacity, '1');
  }

  // @spec KIT-RUNTIME-004
  {
    const { sandbox, elements, timers } = install();
    const notif = addNotificationElement(elements);
    sandbox.showNotification('Msg');
    assert.equal(timers.size, 1);
    const notifTimer = [...timers.values()][0];
    assert.equal(notifTimer.ms, 3000);
    notifTimer.fn();
    assert.equal(notif.className, 'notification');
  }
  {
    const { sandbox, appended, timers } = install();
    sandbox.showNotification('Msg');
    assert.equal(timers.size, 1);
    const overlayTimer = [...timers.values()][0];
    assert.equal(overlayTimer.ms, 3000);
    overlayTimer.fn();
    assert.equal(appended[0].style.opacity, '0');
  }

  // @spec KIT-RUNTIME-005
  {
    const { sandbox, elements, timers } = install();
    const notif = addNotificationElement(elements);
    sandbox.showNotification('Msg', false, 1500);
    assert.equal(timers.size, 1);
    const notifTimer = [...timers.values()][0];
    assert.equal(notifTimer.ms, 1500);
    notifTimer.fn();
    assert.equal(notif.className, 'notification');
  }
  {
    const { sandbox, appended, timers } = install();
    sandbox.showNotification('Msg', false, 750);
    const overlayTimer = [...timers.values()][0];
    assert.equal(overlayTimer.ms, 750);
    overlayTimer.fn();
    assert.equal(appended[0].style.opacity, '0');
  }

  // @spec KIT-RUNTIME-006
  {
    const { sandbox, elements, timers } = install();
    const notif = addNotificationElement(elements);
    sandbox.showNotification('Msg', false, 0);
    assert.equal(timers.size, 0);
    assert.equal(notif.className, 'notification show');
  }
  {
    const { sandbox, appended, timers } = install();
    sandbox.showNotification('Msg', false, 0);
    assert.equal(timers.size, 0);
    assert.equal(appended[0].style.opacity, '1');
  }

  // @spec KIT-RUNTIME-007
  {
    const { sandbox, elements, timers } = install();
    addNotificationElement(elements);
    sandbox.showNotification('first');
    assert.equal(timers.size, 1);
    const firstTimerId = [...timers.keys()][0];
    sandbox.showNotification('second');
    assert.equal(timers.has(firstTimerId), false);
    assert.equal(timers.size, 1);
  }
  {
    // The newer call's own durationMs (0, no timer) must still cancel the
    // earlier pending timer -- the cancel happens before the 0-duration
    // early return, in both the #notification and overlay branches.
    const { sandbox, elements, timers } = install();
    addNotificationElement(elements);
    sandbox.showNotification('first');
    assert.equal(timers.size, 1);
    const firstTimerId = [...timers.keys()][0];
    sandbox.showNotification('second', false, 0);
    assert.equal(timers.has(firstTimerId), false);
    assert.equal(timers.size, 0);
  }
  {
    const { sandbox, timers } = install();
    sandbox.showNotification('first');
    assert.equal(timers.size, 1);
    const firstTimerId = [...timers.keys()][0];
    sandbox.showNotification('second', false, 0);
    assert.equal(timers.has(firstTimerId), false);
    assert.equal(timers.size, 0);
  }

  process.stdout.write('datastar-kit regression tests passed\n');
}

for (const [label, text] of variants) {
  source = text;
  main();
  process.stdout.write('  (' + label + ')\n');
}
