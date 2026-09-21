# Consumer Contract — Specs

Design: `consumer-contract-design.md`. "A kit asset path" is one of `public/vendor/datastar-aliased.js`, `public/js/datastar-kit.js`, `public/js/datastar-auth-fix.js`, `public/js/keyboard-chords.js`. "A provider" is a classpath resource at that path as resolved by the thread's context class loader. "Embedded bytes" are the UTF-8 bytes of the asset text the kit read at compile time.

## Copy audit

- [x] **CONTRACT-001**: When more than one provider exists for a kit asset path, `datastar-kit.assets/copy-audit` shall return a finding for that path with `:problem :shadowed`, listing every provider's URL and SHA-256.
- [x] **CONTRACT-002**: When exactly one provider exists for a kit asset path and its bytes differ from the embedded bytes, `datastar-kit.assets/copy-audit` shall return a finding for that path with `:problem :stale-copy`, the kit's SHA-256, and the provider's URL and SHA-256.
- [x] **CONTRACT-003**: When a kit asset path has no provider, or exactly one provider whose bytes equal the embedded bytes, `datastar-kit.assets/copy-audit` shall return no finding for that path.

## Runtime warning

- [x] **CONTRACT-010**: When `datastar-kit.assets/script-tags` is called for the first time in a process, it shall run the copy audit and print one line to standard error for each finding, naming the path, the problem, the kit's SHA-256, and each provider's URL and SHA-256.
- [x] **CONTRACT-011**: When `datastar-kit.assets/script-tags` is called again in the same process, it shall not run the copy audit or print its findings again.
- [x] **CONTRACT-012**: If the copy audit throws, then `datastar-kit.assets/script-tags` shall return the same script tags it returns when the audit succeeds.

## Contract tests

- [x] **CONTRACT-020**: When `datastar-kit.testing/shadowed-public-files` is called with a public root directory, it shall return, for each file under that directory that more than one classpath root provides at `public/<relative path>`, the relative path and the providers' URLs.
- [x] **CONTRACT-021**: When `datastar-kit.testing/unauthorized-js` is called with a public root directory and a manifest, it shall return the scripts (`.js`, `.mjs`) under the root that the manifest does not list, the manifest entries with no file, and the third-party entries that lack `:source` or `:version` or whose file differs from its pinned SHA-256.
- [x] **CONTRACT-022**: When `datastar-kit.testing/defcontract-tests` is called with `:public-root`, it shall define in the calling namespace one `clojure.test` test that fails on any copy-audit finding and one that fails on any shadowed public file.
- [x] **CONTRACT-023**: Where `datastar-kit.testing/defcontract-tests` is also given `:js-manifest`, it shall define a third test that fails on any result of `unauthorized-js` for that manifest.
