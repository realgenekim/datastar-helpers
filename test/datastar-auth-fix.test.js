'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const source = fs.readFileSync(
  path.join(__dirname, '../resources/public/js/datastar-auth-fix.js'),
  'utf8'
);

function install(pageUrl) {
  const fetchCalls = [];
  const sandbox = {
    URL,
    console: { log() {} },
    location: { href: pageUrl },
    history: { state: null, replaceState() {} },
    Request: class NativeRequest {
      constructor(input, init) {
        this.url = typeof input === 'string' ? input : input.url;
        this.init = init;
      }
    },
    fetch(input, init) {
      // Reproduce the browser failure hidden by a redacted location.href:
      // native fetch rejects a relative string against its credentialed document base.
      if (typeof input === 'string' && input.startsWith('/')) {
        throw new TypeError('relative URL inherited hidden credentials');
      }
      fetchCalls.push({ input, init });
      return Promise.resolve({ ok: true });
    }
  };
  sandbox.window = sandbox;
  sandbox.globalThis = sandbox;
  vm.runInNewContext(source, sandbox);
  return { sandbox, fetchCalls };
}

async function main() {
  const redacted = install('https://example.test/stories?starred=1');
  await redacted.sandbox.fetch('/api/ds/stories/key');
  assert.equal(redacted.fetchCalls[0].input, 'https://example.test/api/ds/stories/key');
  assert.equal(redacted.fetchCalls[0].init.credentials, 'same-origin');
  assert.equal(
    new redacted.sandbox.Request('/api/ds/stories/key').url,
    'https://example.test/api/ds/stories/key'
  );

  const visible = install('https://user:pass@example.test/stories?starred=1');
  await visible.sandbox.fetch('/api/ds/stories/key');
  assert.equal(visible.fetchCalls[0].input, 'https://example.test/api/ds/stories/key');
  assert.equal(visible.sandbox.__datastarAuthFixVersion, '3');

  process.stdout.write('datastar-auth-fix regression tests passed\n');
}

main().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
