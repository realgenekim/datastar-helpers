---
parent: high-level-design
prefix: EDIT
---

# Editable

## Context and Design Philosophy

In a server-is-the-game-loop page the server owns state and the DOM is a display terminal. There is one standing exception, and it is not optional: **the server owns committed state; the browser owns the active draft, focus, selection, composition and undo.** A text input the user is typing into holds a caret, an IME composition buffer, a selection, and an undo stack that exist nowhere on the server and cannot be re-created by a push. An application that re-renders over an open input destroys all five.

Every app therefore reaches the same crossing, and until now the kit only warned about it. `editable` is that crossing as one primitive, plus the test helper that proves the endpoint behind it survives a repeated gesture.

## What breaks without it

One feature, in one app, hit three failures in a day.

- **The hand-rolled freeze froze the wrong thing.** The app kept the open input alive by suppressing re-renders of the region while the user typed. That suppression also swallowed the render that *opens* the input, so the first click appeared to do nothing.
- **The gesture fired twice.** The action buttons sat inside a cell whose own `onclick` opens the editor. One click bubbled, so the browser sent the submit and a racing open, and the two POSTs landed in an order nobody chose.
- **The binding and the read disagreed.** `(bind :noteText)` renders `data-star-bind:noteText`; the HTML parser lowercases attribute names, so Datastar bound the signal `notetext` while the submit expression read `$noteText`. Every submit arrived blank, with no error in the console, on the wire, or in the server log.

## Approach

**Close by not rendering.** The wrapper carries `data-star-ignore-morph`, the attribute this kit's pinned Datastar client reads (its alias function makes every attribute `data-star-…`). The client skips a subtree when the existing element and the incoming element both carry it, so a later push of the surrounding region leaves the open input, its caret and its draft untouched — for as long as the server keeps rendering the wrapper. The server closes the editor by rendering nothing in its place, which the surrounding morph then removes. Open is a render, close is the absence of one, and no freeze logic exists to have a bug in.

**No signal for the draft.** The draft is not Datastar state. Each gesture reads its sibling input at gesture time through `this.closest('.ds-editable').querySelector('input').value`. This removes the camelCase mismatch as a class rather than documenting it, and it removes the question of what a push does to a half-typed signal. The wrapper class is part of the contract, not decoration.

**One event idiom.** Every handler inside an editable is a plain inline `onclick` / `onkeydown` speaking `event` and `this`. Mixing `data-star-on:click` (`evt`) with plain `onclick` (`event`) inside one small element is how a handler gets written in the wrong dialect. Plain handlers are also the kit's existing rule for repeated elements, and the editor's buttons are re-rendered on every push of the region.

**Containment at every level.** Each gesture stops propagation, and so does the wrapper itself, so a click on the input's own whitespace cannot reach the ancestor cell that opens the editor.

**Literals, never cursor state.** `:payload` values are the server's, rendered into the handler as literals. A click can land after focus has moved; a payload read from the cursor at submit time acts on the wrong row. `:text` is the single key the kit adds and its name is fixed.

## Command replay, not idempotence

A fire-and-forget client can always turn one gesture into two POSTs: a bubbled click, a double tap, a retry after a dropped response. The tempting rule — *every gesture endpoint must be idempotent* — is wrong: move-down and undo are legitimately repeatable, and demanding idempotence of them either weakens the rule to nothing or forbids the feature.

The rule that holds is about identity, not arithmetic: **a replay of one command has one effect; a new command carries a new id.** `editable` supports it with `:command-id`, a server-minted id for the edit session stamped on every gesture the editor makes, so the server can recognise the second POST as the same command.

`datastar-kit.testing/command-replay` checks it from the outside: send the same body twice, read the state between and after, and read a caller-chosen **effects** count as well. Effects are not decoration. A handler that appends a second durable line while writing the same projection value is invisible to state equality — the atom holds the identical map both times — and that is exactly the duplicate-write bug this helper exists to catch. The failure message names only what moved, because a projection can be large and the differing keys are the finding.

## Decisions & Alternatives

| Decision | Chosen | Alternatives Considered | Rationale |
|----------|--------|------------------------|-----------|
| Keeping the open input alive | `data-star-ignore-morph` on the wrapper | Server-side "freeze the region while typing" | The freeze is app state that must be entered and left correctly, and the one app that wrote it also froze the render that opens the input. The attribute needs no state machine. |
| Where the draft lives | The input's own `value`, read at gesture time | A Datastar signal with `__ifmissing` | The browser lowercases attribute names, so a camelCase bind and a `$camelCase` read silently address two signals. No signal, no mismatch. It also makes a half-typed draft immune to a push by construction. |
| Handler idiom | Plain `onclick`/`onkeydown` throughout | `data-star-on:click` for buttons that read a signal | With no signal there is nothing Datastar's expression compiler is needed for, and one idiom (`event`, `this`) cannot be written in the wrong dialect. Matches the kit's existing rule for re-rendered elements. |
| Keyboard | A local expression on the input | `ds/keydown-expr` + `ds/on-key` | Those build a page-level Datastar keymap: they speak `evt`, and `guard-input` exists to skip a text input — precisely the element here. |
| The gesture contract | Command replay with an id | "Every gesture endpoint must be idempotent" | Repeatable commands (move-down, undo) are legitimate; the invariant is that one command commits once, not that every endpoint is a no-op on repeat. |
| Proving replay safety | State diff **and** a caller-chosen effects count | State equality alone | Writing the same value twice leaves an identical projection while appending a second durable row. State alone reports a clean bill. |
| `bind` on a camelCase name | Throw `:ds/camel-case-signal` | Silently lowercase it; warn | Silently lowercasing changes which signal an app's other expressions read. The failure it prevents is invisible at runtime, so the call site is the only place it can be caught. |

## Open Questions & Future Decisions

### Deferred
1. `editable` renders one `<input type="text">`. A multi-line `<textarea>` variant shares every rule here; it is not built until an app needs it.
2. `:command-id` is minted by the app, not by the kit. The kit has no session concept and should not acquire one to hold an id.

## References

- `src/datastar_kit/ds.clj` — `editable`, `bind`, `signal-ref`.
- `src/datastar_kit/testing.clj` — `command-replay`, `assert-command-replay`.
- `resources/public/vendor/datastar-aliased.js` — the pinned client, whose alias function `data-star-${…}` fixes the attribute spelling.
