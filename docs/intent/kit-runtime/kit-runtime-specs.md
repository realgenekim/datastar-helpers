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

## Gesture-anchored scroll keeping

"The gesture element" is the element the user last acted on: `document.activeElement` when it is a `BUTTON`, `INPUT`, `SELECT`, `TEXTAREA`, or `A`, otherwise the target of the most recent `pointerdown`. "Opted out" means the element, one of its ancestors, or `document.documentElement` carries `data-kit-keep-scroll="off"`. IDs read `RUNTIME-SCROLL-nnn` under the node's `KIT-RUNTIME` prefix.

- [x] **KIT-RUNTIME-SCROLL-001**: When the kit runtime loads on a page whose browser provides `document.addEventListener`, `MutationObserver`, `requestAnimationFrame`, and `window.scrollBy`, the kit runtime shall install scroll keeping; where any of those is missing, the kit runtime shall install nothing and shall not throw.
- [x] **KIT-RUNTIME-SCROLL-002**: When `postJSON` is called while scroll keeping is installed, the kit runtime shall record the gesture element, its `id`, its `getBoundingClientRect().top`, and the current time, replacing whatever gesture was recorded before.
- [x] **KIT-RUNTIME-SCROLL-003**: When `postJSON` is called and no gesture element can be determined, or the gesture element is opted out, the kit runtime shall record no gesture and shall forget any earlier one.
- [x] **KIT-RUNTIME-SCROLL-004**: When the DOM under `document.body` changes while a gesture is remembered, the kit runtime shall, once on the next animation frame however many mutations arrived, read the gesture element's current `getBoundingClientRect().top` and, when it differs from the recorded top by more than 1 pixel, call `window.scrollBy(0, currentTop - recordedTop)`.
- [x] **KIT-RUNTIME-SCROLL-005**: While a gesture is remembered, the kit runtime shall hold the recorded top unchanged across restores, so that no push moves the element the user last acted on, measured at its top edge, within 2000 milliseconds of the gesture.
- [x] **KIT-RUNTIME-SCROLL-006**: When the gesture element is no longer connected to the document and was recorded with a non-empty `id`, the kit runtime shall anchor to the element `document.getElementById` returns for that id and shall remember that element for later restores.
- [x] **KIT-RUNTIME-SCROLL-007**: When the gesture element is no longer connected and no element with its recorded id is in the document, the kit runtime shall forget the gesture and shall not scroll.
- [x] **KIT-RUNTIME-SCROLL-008**: When more than 2000 milliseconds have passed since the gesture was recorded, the kit runtime shall forget the gesture and shall not scroll.
- [x] **KIT-RUNTIME-SCROLL-009**: When the user scrolls with `wheel`, `touchmove`, or a scrolling key (`ArrowUp`, `ArrowDown`, `PageUp`, `PageDown`, `Home`, `End`, space) pressed outside a form control, link, or contenteditable element, at or after the moment the gesture was recorded, the kit runtime shall forget the gesture and shall not scroll.
- [x] **KIT-RUNTIME-SCROLL-010**: When the gesture element is opted out at restore time, the kit runtime shall forget the gesture and shall not scroll.
- [x] **KIT-RUNTIME-SCROLL-011**: When the DOM changes and no gesture is remembered, the kit runtime shall not scroll.
- [x] **KIT-RUNTIME-SCROLL-012**: The kit runtime shall decide between scrolling, forgetting, and doing nothing in one pure function, `kitKeepScrollDecide`, of the recorded gesture, the current time, the element's current top, the last user-scroll time, the opt-out state, and whether the element was found, so the decision is exercisable without a browser.

## Scroll into view

"The marked element" is the first element in document order, at restore time, carrying a `data-kit-scroll-into-view` attribute. "The viewport" is `{height: window.innerHeight}`.

- [x] **KIT-RUNTIME-SCROLL-020**: On the same animation frame scroll keeping uses (`KIT-RUNTIME-SCROLL-004`), when the marked element exists and is not fully inside the viewport, the kit runtime shall call `el.scrollIntoView({block: 'nearest'})` on it exactly once for that frame.
- [x] **KIT-RUNTIME-SCROLL-021**: When the marked element exists and is fully inside the viewport, the kit runtime shall not call `scrollIntoView`.
- [x] **KIT-RUNTIME-SCROLL-022**: When the marked element or `document.documentElement` carries `data-kit-scroll-margin="<px>"`, the kit runtime shall treat that many pixels below the viewport's top edge as off-screen for that element, preferring a value on the element itself over one on `<html>`, and shall treat the margin as `0` when neither carries it.
- [x] **KIT-RUNTIME-SCROLL-023**: When `document.documentElement` carries `data-kit-scroll-into-view="off"`, the kit runtime shall not look for a marked element and shall not call `scrollIntoView`. This opt-out is independent of `data-kit-keep-scroll`.
- [x] **KIT-RUNTIME-SCROLL-024**: When more than one element on the page carries `data-kit-scroll-into-view`, the kit runtime shall consider only the first in document order.
- [x] **KIT-RUNTIME-SCROLL-025**: On a frame where both gesture-anchored scroll keeping and scroll-into-view have a correction to make, the kit runtime shall apply the scroll-into-view correction after the scroll-keep correction, so it is the one that determines the final scroll position.
- [x] **KIT-RUNTIME-SCROLL-026**: The kit runtime shall decide whether the marked element is off-screen in one pure function, `kitScrollIntoViewDecide(rect, viewport, margin)`, of the element's current `getBoundingClientRect()`, the viewport, and the margin, so the decision is exercisable without a browser.
