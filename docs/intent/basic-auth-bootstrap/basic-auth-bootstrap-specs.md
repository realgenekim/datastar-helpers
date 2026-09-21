# Basic-Auth Bootstrap — Specs

Design: `basic-auth-bootstrap-design.md`. "The Basic-Auth bootstrap" is the script `resources/public/js/datastar-auth-fix.js`. "The page's scheme and host" are those of `location.href`.

## History

- [x] **BASIC-AUTH-HISTORY-001**: When page code calls `history.pushState` or `history.replaceState` with a URL whose scheme and host match the page's, the Basic-Auth bootstrap shall pass the native History method that URL's path, query, and fragment as a relative URL.
- [x] **BASIC-AUTH-HISTORY-002**: If the native `history.pushState` or `history.replaceState` throws a `SecurityError`, then the Basic-Auth bootstrap shall log a console warning naming the method and the URL and return to the caller without throwing.
- [x] **BASIC-AUTH-HISTORY-003**: The Basic-Auth bootstrap shall install its `history.pushState` and `history.replaceState` wrappers on every page load without first testing whether the native History methods succeed.
- [x] **BASIC-AUTH-HISTORY-004**: When page code calls `history.pushState` or `history.replaceState` with a null or omitted URL, the Basic-Auth bootstrap shall pass that value to the native History method unchanged.
- [x] **BASIC-AUTH-HISTORY-005**: When page code calls `history.pushState` or `history.replaceState` with a URL whose scheme or host differs from the page's, the Basic-Auth bootstrap shall pass that URL to the native History method unchanged.
- [x] **BASIC-AUTH-HISTORY-006**: If the native `history.pushState` or `history.replaceState` throws an error other than a `SecurityError`, then the Basic-Auth bootstrap shall let that error propagate to the caller.

## Request and fetch

- [x] **BASIC-AUTH-FETCH-001**: When page code constructs a `Request` from a URL string, the Basic-Auth bootstrap shall resolve the URL to absolute against `location.href` and remove its username and password before the native `Request` constructor receives it.
- [x] **BASIC-AUTH-FETCH-002**: When page code calls `fetch` with a URL string, the Basic-Auth bootstrap shall resolve the URL to absolute against `location.href` and remove its username and password before the native `fetch` receives it.
- [x] **BASIC-AUTH-FETCH-003**: When page code passes `fetch` or the `Request` constructor a `Request` whose URL carries a username or password, the Basic-Auth bootstrap shall substitute a `Request` with the same options and the username and password removed.
- [x] **BASIC-AUTH-FETCH-004**: When page code calls `fetch` without a `credentials` option, the Basic-Auth bootstrap shall set `credentials` to `same-origin`.

## Loading and delivery

- [x] **BASIC-AUTH-LOAD-001**: When the Basic-Auth bootstrap executes on a page where it has already executed, it shall leave the installed Request, fetch, and History wrappers unchanged.
- [x] **BASIC-AUTH-LOAD-002**: The function `datastar-kit.assets/basic-auth-script` shall return a Hiccup `:script` element whose body is the complete Basic-Auth bootstrap source embedded when the namespace is compiled.
- [x] **BASIC-AUTH-LOAD-003**: When `datastar-kit.assets/script-tags` is called with `:basic-auth? true`, it shall emit the embedded Basic-Auth bootstrap script element as the first element, ahead of the Datastar module.
- [x] **BASIC-AUTH-LOAD-004**: The Basic-Auth bootstrap shall set `window.__datastarAuthFixVersion` to its version string when it installs.
