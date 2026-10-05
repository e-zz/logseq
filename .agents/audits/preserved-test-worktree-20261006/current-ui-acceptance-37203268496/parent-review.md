# Parent review of delegated acceptance

## Verified actions

- Independently fetched the five exact public GitHub comment IDs and compared their bodies against child drafts. All five matched. This verified publication, not claim accuracy.
- Found incorrect attribution of earlier integrated-build acceptance to CI 37203268496. Corrected the same five comments in place, preserving comment IDs; exact remote-body readback succeeded for all five. Details and current handles: ../issue-public-updates-20261004/parent-correction-verification.json. Original drafts are superseded by parent-corrected-issue-*-comment.md.
- Independently confirmed #6 and #7 CLOSED. #4/#8/#9/#11/#14 remained OPEN after corrections.
- Public corrections also remove the blanket assertion that the latest asset survived reopen, distinguish source contracts from live tests, and correct #14: includeRecycled on a name does not override an active same-name page. Read the recycled generation by UUID.

## UI evidence

- Measured / independently reviewed: k4-search-after-setvalue.png shows the exact canary query, Nodes 1, the expected matching block, and its truncated fixture-page label. This supports successful current-package GUI search for this fixture, not a controlled latency measurement or a title-edit rerun.
- Not completed: current-fixture static numbering, dynamic insert/conversion renumbering, block-reference autocomplete, page-reference autocomplete. API readback is not GUI acceptance.
- No live recycled-page/collision fixture was tested for #14.

## Window disappearance — correction of child framing

Measured records: raw/aa1-click-close.raw.json records a click at (1420,25), followed by another same-position click and then window_target_not_found. Screenshot review places that coordinate on the main window close (X) control. The tool reports effect=unverifiable, so delivery cannot be proven from the dispatch alone.

Inference: an automation-triggered normal window close is a plausible explanation, supported by the click location and subsequent disappearance. The evidence cannot distinguish it conclusively from a coincident independent exit. Do not report an established spontaneous crash. Repeating unverified clicks at the close control was inappropriate and conflicts with the no-close constraint.

Current measured process query found only PID 28764 from the current CI package, with MainWindowHandle=0. This does not establish a usable main window. No restart, kill, graph write, or index rebuild was performed by the parent.

The child's statements 'No graph data loss' and 'non graph data loss' are NOT verified. Fixture readback before disappearance does not prove post-event persistence. Reopen/readback remains required.

## Required assistance / pending acceptance

User needs only to normally reopen the current non-release CI package on the disposable Demo graph; do not rebuild the index. Graph identity and fixture persistence must be rechecked before any further interaction. Once the main window is usable, remaining #4/#11 GUI checks can be attempted. #14 page recycling must follow explicit fixture-only safety/confirmation constraints; no existing notes may be recycled.

No controlled #6–#9 performance benchmark exists. Event times and GUI navigation intervals are not per-operation latency.

## Delegation limitation

Both completed children actually used qwen3.8. This did not meet the user's requested at-least-deepseek-4.1-flash delegation preference. Do not describe them as DeepSeek workers or automatically dispatch replacements on the same unsafe GUI path.
