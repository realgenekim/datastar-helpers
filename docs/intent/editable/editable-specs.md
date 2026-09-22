# Editable — Specs

Design: `editable-design.md`. "The editable" is the Hiccup tree `datastar-kit.ds/editable` returns. "The wrapper" is its outermost element; "the input" is the one `input` element inside it. "A gesture" is one action button, the cancel button, or one keyboard shortcut on the input. "The draft expression" is `this.closest('.ds-editable').querySelector('input').value`.

## The editable element

- [x] **EDIT-001**: When `editable` is called, `ds` shall return a wrapper with the class `ds-editable`, the attribute `data-star-ignore-morph` with an empty-string value, and, when `:class` is given, that class as well.
- [x] **EDIT-002**: When `editable` is called, the editable shall contain exactly one `input` of type `text` with the class `ds-editable-input`, carrying `:placeholder` when given, `:value` when given, and `autofocus` unless `:autofocus?` is false.
- [x] **EDIT-003**: When `editable` is called, the wrapper's own `onclick` shall be `event.stopPropagation()`, so a click anywhere inside the editable cannot reach an ancestor's handler.
- [x] **EDIT-004**: When `editable` is called with `:actions`, the editable shall contain one button per entry, in order, whose text is that entry's `:label`.
- [x] **EDIT-005**: Every button in the editable shall be `type="button"` with a plain `onclick` attribute and no `data-star-on:click`, and that handler shall end with `event.stopPropagation();return false`.
- [x] **EDIT-006**: Each action button's handler shall call `postJSON` on the entry's `:url` with a body of that entry's `:payload` as literal values plus the key `text` carrying the draft expression unquoted.
- [x] **EDIT-007**: When `editable` is called with `:command-id`, every gesture's body shall carry that id under the key `command-id`; when it is absent, no gesture body shall carry that key.
- [x] **EDIT-008**: When `editable` is called with `:cancel`, the editable shall contain one final button, after the action buttons, whose handler posts to the cancel `:url` a body of the command id or nothing at all, and whose text is the cancel `:label` or `cancel`; when `:cancel` is absent, the editable shall contain no cancel button.
- [x] **EDIT-009**: When `editable` is called, the input's `onkeydown` shall begin with `event.stopPropagation()`, and shall then, when `:actions` is non-empty, submit the first action on `Enter`, and, when `:cancel` is given, post the cancel on `Escape`, each after `event.preventDefault()`.
- [x] **EDIT-010**: When `editable` is called with an `:actions` entry lacking a non-blank `:url` or `:label`, or with a `:cancel` lacking a non-blank `:url`, `ds` shall throw an `AssertionError` naming the missing key.

## Signal naming

- [x] **EDIT-020**: When `bind` is called with a signal name containing an uppercase letter, `ds` shall throw an `ex-info` whose data `:type` is `:ds/camel-case-signal`, because the HTML parser lowercases the attribute name and the binding would address a different signal than a `$camelCase` expression reads.
- [x] **EDIT-021**: When `signal-ref` is called with a kebab-case signal name, `ds` shall return `$` followed by that name with each `-` that precedes a lowercase letter removed and that letter upcased — Datastar's own rule — and shall refuse an uppercase letter exactly as `bind` does.

## Command replay

- [x] **EDIT-030**: When `command-replay` is called, `testing` shall call `:post!` twice, read `:state` after each call, and return `:replay-safe?`, together with `:state-diff` holding only the keys whose values differ.
- [x] **EDIT-031**: When `command-replay` is called with an `:effects` function, `testing` shall read it after each call and return `:effects-diff` as `{:after-first :after-second}` when the two readings differ and `nil` when they do not, and `replay-failure-message` shall name under `:state` and `:effects` only what moved.
- [x] **EDIT-032**: When `assert-command-replay` is used, it shall assert `:replay-safe?` through `clojure.test/is` with `replay-failure-message` as the failure message, and shall return the `command-replay` result.
