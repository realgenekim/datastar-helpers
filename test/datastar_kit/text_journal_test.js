const assert = require('assert').strict;
const {
  dsTextFingerprint,
  listTextJournals,
  createTextJournal,
  installTextJournalReloadGuard
} = require('../../resources/public/js/datastar-kit.js');

class MemoryStorage {
  constructor() { this.values = new Map(); }
  getItem(key) { return this.values.has(key) ? this.values.get(key) : null; }
  setItem(key, value) { this.values.set(key, String(value)); }
  removeItem(key) { this.values.delete(key); }
  get length() { return this.values.size; }
  key(index) { return Array.from(this.values.keys())[index] || null; }
}

class FakeInput {
  constructor(value = '') { this.value = value; this.listeners = new Map(); }
  addEventListener(type, fn) { this.listeners.set(type, fn); }
  removeEventListener(type, fn) {
    if (this.listeners.get(type) === fn) this.listeners.delete(type);
  }
  input() { this.listeners.get('input')(); }
}

async function run() {
{
  const storage = new MemoryStorage();
  const sessionStorage = new MemoryStorage();
  const element = new FakeInput('first');
  const journal = createTextJournal({
    element, storage, sessionStorage, instanceId: 'tab-1',
    identity: () => ({ documentId: 'node-1', revision: 7, editorKey: 'book-node:node-1' })
  });

  element.input();
  const first = journal.pending();
  assert.equal(first.text, 'first');
  assert.equal(first.hash, dsTextFingerprint('first'));
  assert.equal(journal.acknowledge({
    sequence: first.sequence, hash: 'wrong', token: first.token
  }), false);
  assert.equal(journal.pending().text, 'first');

  assert.equal(journal.acknowledge({
    documentId: 'node-1', revision: 7,
    sequence: first.sequence, hash: first.hash, token: first.token
  }), true);
  assert.equal(journal.pending(), null);
}

{
  const storage = new MemoryStorage();
  const element = new FakeInput('old');
  const journal = createTextJournal({
    element, storage, sessionStorage: new MemoryStorage(), instanceId: 'tab-2',
    identity: { documentId: 'node-2', revision: 3 }
  });

  let release;
  const settling = journal.settle(record => new Promise(resolve => {
    release = () => resolve({
      sequence: record.sequence, hash: record.hash, token: record.token
    });
  }));
  element.value = 'newer';
  element.input();
  release();

  await assert.rejects(settling, /did not match exact visible bytes/);
  assert.equal(journal.pending().text, 'newer');
}

{
  const storage = new MemoryStorage();
  storage.setItem('journal:node-3:dead-tab', JSON.stringify({
    documentId: 'node-3', instanceId: 'dead-tab', sequence: 8,
    text: 'survived crash', hash: '12345678', token: 'dead-tab:8:12345678', savedAt: 10
  }));
  storage.setItem('journal:node-4:other-tab', JSON.stringify({
    documentId: 'node-4', instanceId: 'other-tab', sequence: 1,
    text: 'other', hash: '87654321', token: 'other-tab:1:87654321', savedAt: 11
  }));
  const recovered = listTextJournals({ storage, storageKey: 'journal', documentId: 'node-3' });
  assert.equal(recovered.length, 1);
  assert.equal(recovered[0].text, 'survived crash');
}

{
  const oldAdd = globalThis.addEventListener;
  const oldRemove = globalThis.removeEventListener;
  let handler;
  globalThis.addEventListener = (type, fn) => {
    assert.equal(type, 'browser-reload:prepare');
    handler = fn;
  };
  globalThis.removeEventListener = () => {};

  try {
    const record = { sequence: 4, hash: 'abcd' };
    const journal = { settle: async send => send(record) };
    installTextJournalReloadGuard(journal, async value => value);
    let promise;
    handler({ detail: { waitUntil(value) { promise = value; } } });
    assert.deepEqual(await promise, record);
  } finally {
    globalThis.addEventListener = oldAdd;
    globalThis.removeEventListener = oldRemove;
  }
}

console.log('4 text journal tests passed');
}

run().catch(error => {
  console.error(error);
  process.exitCode = 1;
});
