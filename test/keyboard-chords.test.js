'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const source = fs.readFileSync(
  path.join(__dirname, '../resources/public/js/keyboard-chords.js'),
  'utf8'
);

function install() {
  const listeners = {};
  const timers = new Map();
  let nextTimer = 1;
  const document = {
    hidden: false,
    addEventListener(type, fn) { listeners['document:' + type] = fn; },
    removeEventListener(type, fn) {
      if (listeners['document:' + type] === fn) delete listeners['document:' + type];
    }
  };
  const sandbox = {
    document,
    console,
    Object,
    setTimeout(fn) { const id = nextTimer++; timers.set(id, fn); return id; },
    clearTimeout(id) { timers.delete(id); },
    addEventListener(type, fn) { listeners['window:' + type] = fn; },
    removeEventListener(type, fn) {
      if (listeners['window:' + type] === fn) delete listeners['window:' + type];
    }
  };
  sandbox.window = sandbox;
  sandbox.globalThis = sandbox;
  vm.runInNewContext(source, sandbox);
  return { sandbox, listeners, timers };
}

function key(name, options = {}) {
  let prevented = false;
  return {
    key: name,
    shiftKey: false,
    ctrlKey: false,
    altKey: false,
    metaKey: false,
    repeat: false,
    target: { tagName: 'BODY', isContentEditable: false },
    preventDefault() { prevented = true; },
    wasPrevented() { return prevented; },
    ...options
  };
}

function main() {
  const { sandbox, listeners, timers } = install();
  const calls = [];
  const resets = [];
  const chords = sandbox.DatastarKeyboardChords.create({
    bindings: {
      'g shift+s': () => calls.push('starred-infinite'),
      's shift+r': () => calls.push('random'),
      'g o': () => calls.push('stories'),
      'shift+p a': () => calls.push('period-all')
    },
    onReset(prefix, reason) { resets.push([prefix, reason]); }
  });

  assert.equal(chords.handle(key('g')), true);
  assert.equal(chords.pending(), 'g');
  assert.equal(chords.handle(key('Shift', { shiftKey: true })), true);
  assert.equal(chords.pending(), 'g');
  assert.equal(chords.handle(key('S', { shiftKey: true })), true);
  assert.deepEqual(calls, ['starred-infinite']);

  chords.handle(key('s'));
  chords.handle(key('Shift', { shiftKey: true }));
  chords.handle(key('R', { shiftKey: true }));
  chords.handle(key('g'));
  chords.handle(key('o'));
  chords.handle(key('P', { shiftKey: true }));
  chords.handle(key('a'));
  assert.deepEqual(calls, ['starred-infinite', 'random', 'stories', 'period-all']);

  chords.handle(key('g'));
  assert.equal(chords.handle(key('x', { target: { tagName: 'INPUT' } })), false);
  assert.equal(chords.pending(), null);

  chords.handle(key('g'));
  const timeout = Array.from(timers.values())[0];
  timeout();
  assert.equal(chords.pending(), null);

  chords.handle(key('g'));
  chords.handle(key('Escape'));
  assert.equal(chords.pending(), null);

  chords.handle(key('g'));
  listeners['window:blur']();
  assert.equal(chords.pending(), null);

  chords.handle(key('g'));
  sandbox.document.hidden = true;
  listeners['document:visibilitychange']();
  assert.equal(chords.pending(), null);

  assert.equal(sandbox.DatastarKeyboardChords.tokenFor(key('S', { shiftKey: true })), 'shift+s');
  assert.equal(sandbox.DatastarKeyboardChords.tokenFor(key('?', { shiftKey: true })), '?');
  assert.equal(sandbox.DatastarKeyboardChords.tokenFor(key('l', { metaKey: true })), null);
  assert.ok(resets.some(([, reason]) => reason === 'timeout'));

  chords.destroy();
  process.stdout.write('keyboard-chords regression tests passed\n');
}

main();
