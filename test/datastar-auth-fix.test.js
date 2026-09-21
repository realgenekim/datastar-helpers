'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const source = fs.readFileSync(
  path.join(__dirname, '../resources/public/js/datastar-auth-fix.js'),
  'utf8'
);

// A faithful model of the real browser rule for history.pushState/
// replaceState: the new URL, resolved against the DOCUMENT URL (which keeps
// userinfo even when location.href redacts it), must match the document URL
// in protocol/username/password/host -- only path/query/fragment may differ.
// A previous fake got this backwards (it threw when the URL contained '@',
// the OPPOSITE of the real rule) -- see the pinning test below.
//
// A fresh class per call: the bootstrap patches History.prototype, so a class
// shared between sandboxes would carry one sandbox's wrappers into the next --
// and into the unwrapped fake the pinning test depends on.
function makeHistoryFake() {
return class HistoryFake {
  constructor(documentUrl) {
    this.documentUrl = documentUrl;
    this.state = null;
    this.calls = [];
  }

  _apply(method, state, title, url) {
    if (state && state.__forceNativeError) {
      throw new TypeError('native history failure (test sentinel)');
    }
    if (url == null) {
      this.calls.push({ method, state, title, url });
      this.state = state;
      return;
    }
    const resolved = new URL(String(url), this.documentUrl);
    const here = new URL(this.documentUrl);
    if (
      resolved.protocol !== here.protocol ||
      resolved.username !== here.username ||
      resolved.password !== here.password ||
      resolved.host !== here.host
    ) {
      const err = new Error(
        "Failed to execute '" + method + "' on 'History': A history state object " +
        'with URL \'' + resolved.href + '\' cannot be created in a document with ' +
        "origin/userinfo '" + here.href + "'."
      );
      err.name = 'SecurityError';
      throw err;
    }
    this.calls.push({ method, state, title, url, resolved: resolved.href });
    this.state = state;
    this.documentUrl = resolved.href;
  }

  pushState(state, title, url) {
    this._apply('pushState', state, title, url);
  }

  replaceState(state, title, url) {
    this._apply('replaceState', state, title, url);
  }
};
}

function install(pageUrl) {
  const fetchCalls = [];
  const warns = [];
  const HistoryFake = makeHistoryFake();
  const historyFake = new HistoryFake(pageUrl);
  const redactedHref = (function () {
    const u = new URL(pageUrl);
    u.username = '';
    u.password = '';
    return u.toString();
  })();
  const sandbox = {
    URL,
    console: {
      log() {},
      warn(...args) { warns.push(args); }
    },
    location: { href: redactedHref },
    History: HistoryFake,
    history: historyFake,
    Request: class NativeRequest {
      constructor(input, init) {
        this.url = typeof input === 'string' ? input : input.url;
        // Like the browser: a Request built from a Request inherits its options.
        this.init = init === undefined && typeof input !== 'string' ? input.init : init;
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
  return { sandbox, fetchCalls, warns, historyFake };
}

function runSource(sandbox) {
  vm.runInContext(source, sandbox);
}

async function main() {
  // @spec BASIC-AUTH-FETCH-001, BASIC-AUTH-FETCH-002, BASIC-AUTH-FETCH-004
  const redacted = install('https://example.test/stories?starred=1');
  await redacted.sandbox.fetch('/api/ds/stories/key');
  assert.equal(redacted.fetchCalls[0].input, 'https://example.test/api/ds/stories/key');
  assert.equal(redacted.fetchCalls[0].init.credentials, 'same-origin');
  assert.equal(
    new redacted.sandbox.Request('/api/ds/stories/key').url,
    'https://example.test/api/ds/stories/key'
  );

  // @spec BASIC-AUTH-FETCH-002, BASIC-AUTH-LOAD-004
  const visible = install('https://user:pass@example.test/stories?starred=1');
  await visible.sandbox.fetch('/api/ds/stories/key');
  assert.equal(visible.fetchCalls[0].input, 'https://example.test/api/ds/stories/key');
  assert.equal(visible.sandbox.__datastarAuthFixVersion, '4');

  // --- A Request input that carries userinfo is rebuilt without it -----------
  // @spec BASIC-AUTH-FETCH-003
  {
    const { sandbox, fetchCalls } = install('https://user:pass@example.test/stories');
    const credentialed = { url: 'https://user:pass@example.test/api/x?y=1', method: 'POST' };

    await sandbox.fetch(credentialed);
    assert.equal(fetchCalls[0].input.url, 'https://example.test/api/x?y=1');
    assert.equal(fetchCalls[0].input.init.method, 'POST', 'fetch must keep the Request options');

    const rebuilt = new sandbox.Request(credentialed);
    assert.equal(rebuilt.url, 'https://example.test/api/x?y=1');
    assert.equal(rebuilt.init.method, 'POST', 'Request must keep the Request options');

    const clean = { url: 'https://example.test/api/x', method: 'GET' };
    await sandbox.fetch(clean);
    assert.equal(fetchCalls[1].input, clean, 'a Request without userinfo is passed through untouched');
  }

  // --- Pin the model to the real browser rule, without the shim -----------
  {
    const docUrl = 'https://admin:secret@example.test/subreddit-posts?page=3';
    const raw = new (makeHistoryFake())(docUrl);
    const redactedLocationHref = 'https://example.test/subreddit-posts?page=3';
    assert.throws(
      () => raw.replaceState(null, '', redactedLocationHref),
      { name: 'SecurityError' },
      'a redacted absolute location.href must reproduce the real SecurityError'
    );
    const raw2 = new (makeHistoryFake())(docUrl);
    assert.doesNotThrow(() => raw2.replaceState(null, '', '/subreddit-posts?page=4'));
  }

  // The table is shared verbatim between the credentialed-document and
  // non-credentialed-document runs below: expectNative is always the
  // relative-normalized pathname+search+hash, independent of the document's
  // own userinfo.
  const historyRows = [
    {
      name: 'redacted absolute same-host URL',
      url: 'https://example.test/subreddit-posts?page=4#x',
      expectNative: '/subreddit-posts?page=4#x',
      expectResolvedHasCreds: true
    },
    {
      name: 'relative URL',
      url: '/subreddit-posts?page=4',
      expectNative: '/subreddit-posts?page=4'
    },
    {
      name: 'credentialed absolute same-host URL',
      url: 'https://admin:secret@example.test/subreddit-posts?page=4',
      expectNative: '/subreddit-posts?page=4'
    },
    {
      name: 'query-only relative URL',
      url: '?page=5',
      expectNative: '/subreddit-posts?page=5'
    },
    {
      name: 'undefined url',
      url: undefined,
      expectNative: undefined
    },
    {
      name: 'null url',
      url: null,
      expectNative: null
    }
  ];

  // --- Table-driven: credentialed document, shim installed -----------------
  // @spec BASIC-AUTH-HISTORY-001, BASIC-AUTH-HISTORY-003, BASIC-AUTH-HISTORY-004
  {
    const docUrl = 'https://admin:secret@example.test/subreddit-posts?page=3';
    const rows = historyRows;

    for (const method of ['pushState', 'replaceState']) {
      for (const row of rows) {
        const { sandbox, historyFake, warns } = install(docUrl);
        assert.doesNotThrow(
          () => sandbox.history[method]({ s: 1 }, '', row.url),
          method + ' / ' + row.name + ' must not throw with the shim installed'
        );
        const call = historyFake.calls[historyFake.calls.length - 1];
        assert.ok(call, method + ' / ' + row.name + ': native History refused the URL the bootstrap passed it');
        assert.equal(call.method, method, method + ' / ' + row.name);
        assert.equal(call.url, row.expectNative, method + ' / ' + row.name + ' native url');
        if (row.expectResolvedHasCreds) {
          assert.match(call.resolved, /^https:\/\/admin:secret@/, method + ' / ' + row.name + ' resolved href');
        }
        assert.deepEqual(warns, [], method + ' / ' + row.name + ' must not warn');
      }

      // cross-origin: never throws to the caller, nothing recorded, exactly one warn
      // @spec BASIC-AUTH-HISTORY-002, BASIC-AUTH-HISTORY-005
      {
        const { sandbox, historyFake, warns } = install(docUrl);
        assert.doesNotThrow(
          () => sandbox.history[method]({ s: 1 }, '', 'https://evil.test/x'),
          method + ' / cross-origin must not throw to the caller'
        );
        assert.equal(historyFake.calls.length, 0, method + ' / cross-origin must not be recorded');
        assert.equal(warns.length, 1, method + ' / cross-origin must warn exactly once');
      }
    }
  }

  // --- Same rows, non-credentialed document: behave like native, no warns --
  // @spec BASIC-AUTH-HISTORY-001, BASIC-AUTH-HISTORY-003
  {
    const docUrl = 'https://example.test/subreddit-posts?page=3';
    const rows = historyRows;
    for (const method of ['pushState', 'replaceState']) {
      for (const row of rows) {
        const { sandbox, historyFake, warns } = install(docUrl);
        assert.doesNotThrow(
          () => sandbox.history[method]({ s: 1 }, '', row.url),
          method + ' / ' + row.name + ' (no-creds doc) must not throw'
        );
        const call = historyFake.calls[historyFake.calls.length - 1];
        assert.ok(call, method + ' / ' + row.name + ' (no-creds doc): native History refused the URL the bootstrap passed it');
        assert.equal(call.url, row.expectNative, method + ' / ' + row.name + ' (no-creds doc) native url');
        assert.deepEqual(warns, [], method + ' / ' + row.name + ' (no-creds doc) must not warn');
      }
    }
  }

  // --- A non-SecurityError thrown by the native method still propagates ----
  // @spec BASIC-AUTH-HISTORY-006
  {
    const docUrl = 'https://admin:secret@example.test/subreddit-posts?page=3';
    const { sandbox } = install(docUrl);
    assert.throws(
      () => sandbox.history.pushState({ __forceNativeError: true }, '', '/x'),
      TypeError,
      'a non-SecurityError thrown by the native method must propagate to the caller'
    );
  }

  // --- Running twice does not double-wrap (version guard) ------------------
  // @spec BASIC-AUTH-LOAD-001
  {
    const { sandbox } = install('https://example.test/stories?starred=1');
    const first = sandbox.History.prototype.pushState;
    runSource(sandbox);
    const second = sandbox.History.prototype.pushState;
    assert.equal(first, second, 'History.prototype.pushState must be the same function after a second install');
  }

  process.stdout.write('datastar-auth-fix regression tests passed\n');
}

main().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
