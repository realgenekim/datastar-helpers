# Kit Assets — Specs

Design: `kit-assets-design.md`. "A kit asset" is a browser file the kit serves by URL: `datastar-aliased.js` (the vendored Datastar client) and `datastar-kit.js` (the kit runtime). "The middleware" is the handler returned by `datastar-kit.assets/wrap-kit-assets`.

## Embedding and addressing

- [x] **KIT-ASSETS-001**: The namespace `datastar-kit.assets` shall embed the complete bytes of each kit asset when it is compiled, so that the asset is available where the kit's resource directory is not on the classpath.
- [x] **KIT-ASSETS-002**: The namespace `datastar-kit.assets` shall embed a kit asset of any size, including one larger than 65,535 bytes.
- [x] **KIT-ASSETS-003**: When `datastar-kit.assets/asset-path` is called with the name of a kit asset, it shall return `/_kit/<hash>/<name>`, where `<hash>` is the first 12 hexadecimal characters of the SHA-256 of that asset's embedded bytes.
- [x] **KIT-ASSETS-004**: If `datastar-kit.assets/asset-path` is called with a name that is not a kit asset, then it shall throw an exception naming the unknown asset.

## Serving

- [x] **KIT-ASSETS-010**: When the middleware receives a GET request for `/_kit/<hash>/<name>` where `<name>` is a kit asset and `<hash>` equals that asset's content hash, it shall respond 200 with the asset's exact embedded bytes, `Content-Type: text/javascript; charset=utf-8`, and `Cache-Control: public, max-age=31536000, immutable`.
- [x] **KIT-ASSETS-011**: When the middleware receives a GET request for `/_kit/<hash>/<name>` where `<name>` is a kit asset and `<hash>` differs from that asset's content hash, it shall respond 200 with the asset's current embedded bytes and `Cache-Control: no-cache`.
- [x] **KIT-ASSETS-012**: When the middleware receives a HEAD request that would be answered by KIT-ASSETS-010 or KIT-ASSETS-011, it shall respond with the same status and headers and no body.
- [x] **KIT-ASSETS-013**: When the middleware receives a request whose path is not `/_kit/<hash>/<name>` for a kit asset, or whose method is neither GET nor HEAD, it shall pass the request to the wrapped handler and return that handler's response unchanged.

## Script tags

- [x] **KIT-ASSETS-020**: While the middleware has been installed in the current process, `datastar-kit.assets/script-tags` shall emit the Datastar module tag and the kit runtime tag with `src` equal to `asset-path` of the asset, without applying the caller's `:asset-url`.
- [x] **KIT-ASSETS-021**: While the middleware has not been installed in the current process, `datastar-kit.assets/script-tags` shall emit the Datastar module tag with `src` `/vendor/datastar-aliased.js` and the kit runtime tag with `src` `/js/datastar-kit.js`, each passed through the caller's `:asset-url`.
- [x] **KIT-ASSETS-022**: When `datastar-kit.assets/script-tags` is called with `:datastar-path`, it shall emit the Datastar module tag with that path passed through the caller's `:asset-url`, whether or not the middleware has been installed.
