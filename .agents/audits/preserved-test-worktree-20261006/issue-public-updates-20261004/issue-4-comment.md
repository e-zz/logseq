# Acceptance update — ordered/numbered list support (2026-10-04)

Measured against the Windows CI build [run 37203268496](https://github.com/e-zz/logseq/actions/runs/37203268496), commit `fb5eb4eb43cdb3be7a29d816b969d45c127d4861` (non-release test build), on a disposable DB graph via the built-in MCP server. No source changes.

## What now works

**List type is discoverable.** `listProperties(expand=true)` returns the built-in `List type` property (`logseq.property/order-list-type`), so an agent can resolve its stable UUID without guessing.

**Numbered-list property writes work.** Writing the value `number` (via the generic properties map from #9) on a block marks it as a numbered list item. Confirmed three ways:

1. `upsertNodes` with `receipt=true` returned `mode=verified` for the write.
2. Independent `getBlock`/`getPage` readback shows the `order-list-type` reference on the block.
3. The app UI renders the real numbers (1/2/3) on the three numbered items — confirmed from a screenshot of the running app, not inferred from the DB.

A plain block (no `order-list-type` attribute) is the default bullet state.

## What is not supported yet (measured, explicit error)

Writing the value `bullet` on the `List type` property is rejected with a clear error:

> `List-type value must be the string "number" (a numbered/ordered list). "bullet" requires removal, which the import path does not support yet.`

Removing a property value via `null` is also rejected (`removal is not supported`). Consequence: **explicit number → bullet conversion is not supported by the import path** — not merely untested. A block's numbered state can currently only be removed through the UI (right-click → "Toggle number list").

Measured atomicity for this path: a batch containing a valid title edit followed by the rejected `bullet` value was rejected as a whole; the title edit did not land (verified before/after block readback identical, failed title absent from search). This is evidence for this one error path, not a guarantee for all error paths.

## Not yet verified (not claimed)

- **Dynamic renumbering**: inserting/removing/moving items within an existing numbered list and observing the UI renumber. Static 1/2/3 rendering is verified; dynamic insert/remove renumbering is not.
- Numbered-list behavior for nested children of a numbered item (only top-level siblings were rendered and checked).

## Summary

The core request of this issue — being able to *create* real ordered/numbered lists through the MCP (option 1/2 in the requested-behavior list) — is satisfied for the `number` direction and has verified UI rendering. The `bullet` direction is an API limitation with an explicit error message, not a silent failure. Closing this issue is left to the maintainer's judgment on how much of the bullet direction matters.
