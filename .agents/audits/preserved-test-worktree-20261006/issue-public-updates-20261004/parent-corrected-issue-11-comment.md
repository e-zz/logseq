# Acceptance update — MCP-written block searchability (parent-reviewed correction)

The earlier version incorrectly attributed earlier MCP acceptance to the latest CI run and claimed a latest-build rerun that the evidence does not establish. The corrected split is:

## Earlier packaged-build live evidence

Earlier integrated-build acceptance, including [run 37134185100](https://github.com/e-zz/logseq/actions/runs/37134185100), verified same-session MCP add/search, title-edit/search freshness, and retained search results after normal reopen. These results are not being presented as reruns on the newer package.

## Current-package GUI search — measured passing

On the Windows non-release package from [run 37203268496](https://github.com/e-zz/logseq/actions/runs/37203268496), commit `fb5eb4eb43cdb3be7a29d816b969d45c127d4861`, a new fixture page and blocks were added via the built-in MCP server and independently read back. A subsequent app search for the exact unique block title showed the expected block under its fixture page, with `Nodes 1`. The parent reviewed the screenshot and input/readback evidence. No manual index rebuild was performed.

This verifies that this newly MCP-written block became visible in the GUI search. It does not measure indexing latency: the interval contained unrelated automation/navigation work. It also does not establish a latest-package title-edit/search rerun or crash durability.

## Pending / blocked

- `((...))` block-reference autocomplete was not completed.
- `[[...]]` page-reference autocomplete for the new page was not completed. Ordinary blocks are not page-reference candidates.
- GUI editing/keyboard routing blocked those checks; they are not recorded as functional failures.
- The test window disappeared after automation issued clicks at the window-close control. The dispatch effect was unverified; an independent application crash is not established. Post-event graph integrity has not been checked.

No controlled per-operation latency benchmark was collected. This issue remains open.
