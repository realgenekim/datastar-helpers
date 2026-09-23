# Picker — Specs

Design: `picker-design.md`. "The picker" is the Hiccup tree `datastar-kit.ds/picker` returns. "The root" is its outermost element. "The filter wrapper" is the element carrying `data-star-ignore-morph`. "The filter" is the one `input` inside it. "The seq expression" is the JS that increments `data-kit-seq` on the filter wrapper and returns the new value.

## Rendering

- [x] **PICK-001**: When `picker` is called, `ds` shall return a root with the class `ds-picker` whose own `onclick` is `event.stopPropagation()`.
- [x] **PICK-002**: The root shall contain exactly one filter wrapper, with the class `ds-picker-filter`, the attribute `data-star-ignore-morph` with an empty-string value, the attribute `data-kit-seq` with the value of `:seq` (the last seq the server applied) or `0` when it is absent — so a page reloaded mid-session continues the server's order rather than restarting below it — and, when `:command-id` is given, the id `ds-picker-<command-id>`.
- [x] **PICK-003**: The filter wrapper shall contain exactly one `input` of type `text` with the class `ds-picker-input`, carrying `:placeholder` when given, `autofocus` unless `:autofocus?` is false, and NO `value` attribute — the filter always opens empty.
- [x] **PICK-004**: OUTSIDE the filter wrapper the root shall render a `ul.ds-picker-items` holding one `li` per `:items` entry, in order, each holding one `type="button"` button with the class `ds-picker-item` whose text is the entry's `:label` (or its `:value` when there is no label); the item whose `:value` equals `:selected` shall carry the class `ds-picker-selected` and `aria-selected="true"`, and no other item shall.
- [x] **PICK-005**: OUTSIDE the filter wrapper the root shall render a `div.ds-picker-actions` holding the submit button (class `ds-picker-submit`, text `:submit :label`), then the cancel button when `:cancel` is given (class `ds-picker-cancel`, text `:cancel :label` or `cancel`), then, when `:message` is non-blank, one `span.ds-picker-message` holding it.
- [x] **PICK-006**: The submit button shall carry `disabled` exactly when `:selected` is nil.

## Gestures

- [x] **PICK-010**: Every handler in the picker shall be a plain `onclick`, `oninput` or `onkeydown` attribute — never `data-star-on:` — and shall call `event.stopPropagation()`; every button shall be `type="button"`.
- [x] **PICK-011**: The filter's `oninput` shall post to `:filter-url` a body of `q` (the filter's own `this.value`), `seq` (the seq expression) and, when given, `command-id`.
- [x] **PICK-012**: Each item's `onclick` shall post to `:pick-url` a body of `value` (the item's `:value` as an escaped JS string literal), `seq` and, when given, `command-id`.
- [x] **PICK-013**: The filter's `onkeydown` shall begin with `event.stopPropagation()`; `ArrowDown`/`ArrowUp` shall post to `:move-url` a body of `dir` (`down`/`up`), `seq` and `command-id`; `Enter` shall click the root's submit button when it is not disabled; `Escape` shall click the root's cancel button when there is one; each after `event.preventDefault()`.
- [x] **PICK-014**: The submit button's `onclick` shall post to `:submit :url` the `:submit :payload` as literals plus `seq` and, when given, `command-id`, and NO other key — never `q`, `value` or `text`, and no read of any input.
- [x] **PICK-015**: The cancel button's `onclick` shall post to `:cancel :url` a body of `command-id` or nothing at all.
- [x] **PICK-016**: The seq expression shall read `data-kit-seq` from the root's filter wrapper, add one, write the result back, and return it, so the counter lives on the one element a push never replaces.
- [x] **PICK-017**: When `picker` is called without a non-blank `:filter-url`, `:pick-url`, `:move-url` or `:submit :url`, or with a `:cancel` lacking a non-blank `:url`, `ds` shall throw an `AssertionError` naming the missing key.

## The ordering fence (`datastar-kit.picker`)

- [x] **PICK-020**: `picker-accept?` shall return true exactly when `seq` is an integer and `last-seq` is nil or an integer less than `seq`.
- [x] **PICK-021**: `picker-caught-up?` shall return true exactly when `seq` is an integer and equals `(inc (or last-seq 0))`.
- [x] **PICK-022**: `parse-seq` shall return the integer a JSON body carried under `seq` (a number, or a string of digits), and nil for anything else.
- [x] **PICK-023**: `step` shall return the value one place before (`:up`) or after (`:down`) `selected` in `values`, stopping at the ends; when `selected` is not in `values` it shall return the first value, and nil when `values` is empty.
