---
parent: high-level-design
prefix: PICK
---

# Picker

## Context

A picker chooses ONE value out of a server-owned list: a category for an expense, a label for a row. It has a filter box the user types into and a list of matches the server renders beside it.

The first one we shipped was hand-written to `editable`'s contract, and the result was, in Gene's words, "totally confusing". The filter box opened PREFILLED with the current value, the submit posted the box's text, and the server's rule was "posted text wins when it names a value, otherwise the server's selection". A click on the list moved the server's selection and never the box, and the prefill always named a value — so a click on the list could never win. Nothing was broken in any one piece. **The chosen value had two owners** — the box text in the browser and the selection on the server — and a reconciler between them.

`picker` makes that composition unrepresentable.

## The ownership table

| Fact | Owner | How it moves | In the submit? |
|---|---|---|---|
| Filter draft (what is typed) | Browser | `oninput` posts it for filtering | **Never** |
| Selection (the chosen value) | Server | a click, or ↑/↓, posts; the server renders the highlight | No — the server already holds it |
| Editor identity | Server | a rendered `:command-id` literal | Yes |
| Gesture order | Browser counter `data-kit-seq` | stamped on every post | Yes (a fence, not a value) |

The submit carries the caller's literal payload, the command id and the sequence number. It carries neither the box text nor a value. There is nothing on the wire for a server to reconcile, so no reconciler can be written.

## Approach

**The filter is a filter, never a value.** It opens empty with a placeholder. A prefilled filter looks like an answer and is not one.

**Only the filter is protected from morphs.** The input sits in a `data-star-ignore-morph` wrapper so a push never touches the caret or the half-typed text. The item list, the highlight, the buttons' disabled state and the message render OUTSIDE the wrapper, so every push updates them. The wrapper's id is keyed by the command id, so a new edit session never inherits an old session's frozen input.

**One idiom.** Every handler is a plain `onclick`/`oninput`/`onkeydown` that calls `event.stopPropagation()`; every button is `type="button"`. Items are buttons too, so a focused item picks on Enter natively.

**The keyboard reuses the buttons.** Handlers inside the wrapper are frozen at first render, so they cannot know the CURRENT selection. Enter therefore clicks the submit button when it is not disabled, and Escape clicks cancel. Those buttons are outside the wrapper and always current.

**One piece of client state: the ordering fence.** Every gesture posts to its own endpoint and forgets, so two posts can arrive in either order. A late filter response must not undo a later pick. The picker keeps one integer, `data-kit-seq`, on the filter wrapper (the one element a push never replaces). Each gesture increments it and stamps the new value. This is not application state. It carries no value, only the order in which the user acted, and the server uses it to refuse what arrives out of order:

- `picker-accept?` — a filter, pick or move is applied only when its seq is greater than the last one applied.
- `picker-caught-up?` — a submit is applied only when its seq is exactly one more than the last one applied. A gap means an earlier gesture is still in flight, so the submit would commit a selection the user has already moved past. The server refuses it and says so beside the buttons.

**Feedback lives beside the control.** `:message` renders in the actions row, outside the wrapper, so a refusal or a *no change* is visible where the user pressed.

## Decisions & alternatives

| Decision | Chosen | Alternatives | Rationale |
|---|---|---|---|
| Who owns the chosen value | The server, only | "Posted text wins when it names a value" | Two owners need a reconciler; the reconciler is the bug. |
| Filter's initial content | Empty, placeholder only | Prefill with the current value | A prefill is indistinguishable from an answer. |
| Ordering | A per-picker seq, fenced on the server | Serialize posts in a client queue | A client queue is app state in JS; a seq is one integer and the decision stays on the server. |
| Enter / Escape | Click the (morphed) submit / cancel button | Bake the selection into the input's handler | The input's handlers are frozen by ignore-morph; the buttons are current. |
| Submit's seq | Required, and must be caught up | Submit carries only the command id | Without it a submit can overtake an in-flight pick and commit the previous selection. |

## References

- `src/datastar_kit/ds.clj` — `picker`.
- `src/datastar_kit/picker.clj` — `picker-accept?`, `picker-caught-up?`, `parse-seq`, `step`.
- `docs/intent/editable/` — the contract `picker` extends (ignore-morph, one idiom, literals, command id).
