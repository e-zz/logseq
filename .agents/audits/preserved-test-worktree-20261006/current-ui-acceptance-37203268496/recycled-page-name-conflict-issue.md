## Bug description

When MCP `upsertNodes` is asked to add a page whose title matches a recycled page, the tested runtime reuses the recycled page instead of creating a new active page or reporting a collision. Blocks added through the new page's temporary ID are appended to the recycled page.

The request reports success (`Added: {:page 1, :block 1}.`), but the page retains its original UUID and `deleted-at` marker. The newly written block is therefore invisible to normal page reads and search. This is a write-target resolution problem, not evidence of lost data or a failed search-index update.

## Steps to reproduce

Use a disposable DB graph. The names below are illustrative, not user note content.

1. Create a page titled `recycled-name-repro` containing an ordinary block. Record the page UUID.
2. Move that page into the recycle bin through the desktop UI. Do not permanently delete it.
3. Verify that default `getPage` rejects the page, while `getPage` with `includeRecycled=true` returns the original UUID, a `deleted-at` marker, and the retained block.
4. Call `upsertNodes` without receipt mode:

```json
{
  "operations": [
    {
      "operation": "add",
      "entityType": "page",
      "id": "new-page",
      "data": {
        "title": "recycled-name-repro"
      }
    },
    {
      "operation": "add",
      "entityType": "block",
      "data": {
        "page-id": "new-page",
        "title": "new-active-page-canary"
      }
    }
  ]
}
```

5. Read the page by name and by the recorded original UUID, using `includeRecycled=true` where needed. Search for the new block title.

## Actual behavior — runtime verified

- The write returns `Added: {:page 1, :block 1}.`.
- Default `getPage` by name still reports that the page is in the recycle bin.
- Opt-in reads by name and original UUID return the same original page UUID and unchanged `deleted-at` marker.
- Both the old block and the new canary are present under that recycled page.
- The new canary is not returned by ordinary search, consistent with its recycled-page location.
- The expected independent active page was not obtained. The operation did not fail cleanly or roll back: it actually appended content to a recycled target.

Only this exact-title, one-recycled-page, page-add-plus-child-add path was runtime tested. Receipt mode, multiple recycled generations, GUI page creation, and regression attribution have not been tested here.

## Expected behavior

A normal runtime page-add operation must not silently resolve to a recycled page and write into it.

Preferred safe default: detect the recycled-title collision and reject the entire batch before any mutation, with an actionable error identifying the conflict and explaining that explicit restoration or a different title is required.

If creating a distinct active page with the same title is supported, that behavior must be explicit and yield a new active page identity while leaving the recycled generation untouched. Neither silent merge into the recycled page nor implicit restoration should be the default.

The response must distinguish a genuinely created page from reuse/merge; counting input operations does not establish that a new page entity was created.

## Source findings — checked against the current artifact commit

The source path is consistent with the observed UUID reuse:

- `src/main/logseq/api/db_based/cli.cljs` sends the runtime write through `batch-import-edn`; the non-receipt response summarizes input operations.
- `deps/outliner/src/logseq/outliner/op.cljs` invokes `sqlite-export/build-import`.
- `deps/db/src/logseq/db/sqlite/export.cljs`, `add-uuid-to-page-if-exists`, looks up the incoming page title using `ldb/get-case-page`, then substitutes the existing page UUID when a match is found. There is no recycled-target rejection in that function.
- `deps/db/src/logseq/db.cljs`, `get-case-page`, delegates title resolution to `common-initial-data/get-first-page-by-title`.
- `deps/db/src/logseq/db/common/initial_data.cljs`, `get-first-page-by-title`, selects the oldest page entity with that exact title without filtering `deleted-at`/recycled state.

Any fix should distinguish ordinary agent runtime writes from intentional import/merge workflows, rather than globally changing import semantics without reviewing those callers.

## Related issues and scope

- #14 concerns default reads and explicit inspection of recycled pages. This issue concerns **write-time title collision and unintended recycled-target reuse**. A real active/recycled same-name coexistence case was not created by this operation, so this does not demonstrate a failure of #14's active-first read resolver.
- #11 concerns indexing of active MCP-created content. The canary here was written under a recycled page; its absence from ordinary search must not be misclassified as the same indexing defect.
- #8 tracks verifiable write receipts; the success-style input count here illustrates why independent identity/state readback matters.

## Environment

- Windows 11; Logseq desktop DB graph; built-in desktop MCP.
- Non-release Windows CI build: https://github.com/e-zz/logseq/actions/runs/37203268496
- Artifact commit: `fb5eb4eb43cdb3be7a29d816b969d45c127d4861`.
- Runtime evidence came from independent native MCP reads after a user-performed soft recycle. No direct SQLite edits, source recompilation, or legacy CLI substitution.
