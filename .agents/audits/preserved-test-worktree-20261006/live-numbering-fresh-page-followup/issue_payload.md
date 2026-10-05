## Observed behavior (user-tested)
In a DB graph, inserting an item into an ordered list does not reliably update the displayed numbers of existing items. The inserted item may display a number already used by another item.

Navigating to another page and returning does not correct the numbering. Restarting the application restores correct numbering.

## Reproduction
1. Open a page with several ordered-list items.
2. Insert an item between existing items.
3. Observe the displayed numbering after exiting edit mode.
4. Navigate to another page, then return: the incorrect numbering remains.
5. Restart the application and reopen the page: the numbering is correct.

## Expected behavior
Displayed ordered-list numbering updates after insertion without navigating away or restarting the application.

## Scope and attribution
This is separate from #4, which requests MCP access to actual ordered-list properties. The reporter reproduced this numbering defect in an earlier version too; the earlier version identifier is not yet recorded and no regression bisect was performed. We are therefore tracking it as a pre-existing defect rather than attributing it to the current MCP changes.

Root cause is unconfirmed. Restart recovery alone does not establish whether the defect is in frontend rendering, backend state, or their update propagation.

## Current validation environment
Windows 11, Logseq DB graph, non-release Windows CI build: https://github.com/e-zz/logseq/actions/runs/37203268496 (commit fb5eb4eb43cdb3be7a29d816b969d45c127d4861).
