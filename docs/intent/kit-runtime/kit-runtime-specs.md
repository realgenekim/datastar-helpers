# Kit Runtime — Specs

Design: `kit-runtime-design.md`. "The kit runtime" is the script `resources/public/js/datastar-kit.js`. "The page's notification element" is an element with id `notification`; "the overlay" is the element with id `ds-notify` that the kit runtime creates.

## postJSON

- [x] **KIT-RUNTIME-001**: When page code calls `postJSON(url, body)`, the kit runtime shall call `fetch` with that URL, method `POST`, a `Content-Type: application/json` header, and `body` serialized as JSON, and shall return the promise `fetch` returned.

## showNotification

- [x] **KIT-RUNTIME-002**: When `showNotification(msg, isError)` is called on a page that has the page's notification element, the kit runtime shall set that element's text to `msg` and its class to `notification show`, with ` error` appended when `isError` is truthy, and shall not create the overlay.
- [x] **KIT-RUNTIME-003**: When `showNotification` is called on a page that has no notification element of its own, the kit runtime shall show `msg` in the overlay with opacity `1`, creating the overlay and appending it to `document.body` on the first call and reusing it on later calls.
- [x] **KIT-RUNTIME-004**: When `showNotification` is called without a duration, the kit runtime shall hide the notification after 3000 milliseconds, by setting the page's notification element's class to `notification` or the overlay's opacity to `0`.
- [x] **KIT-RUNTIME-005**: When `showNotification(msg, isError, durationMs)` is called with a positive `durationMs`, the kit runtime shall hide the notification after `durationMs` milliseconds.
- [x] **KIT-RUNTIME-006**: When `showNotification` is called with a `durationMs` of `0`, the kit runtime shall leave the notification visible and schedule no hide timer.
- [x] **KIT-RUNTIME-007**: When `showNotification` is called while an earlier notification's hide timer is pending, the kit runtime shall cancel that timer before showing the new message, whatever the new message's `durationMs`.
