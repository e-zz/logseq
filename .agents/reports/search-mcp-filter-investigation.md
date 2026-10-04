# Interim investigation — MCP search filter isolation / upstream compatibility

Status: interim, written before running the differential test suite. All items
below are either *source observations* (verified by reading current tree and
`git show` of the anchor) or explicitly marked *hypothesis* / *unverified*.

Worktree: `D:/orca/workspaces/logseq/issues-human-test-20261003`
Branch: `test/issues-mcp-20261003`
Plan HEAD at investigation start: `c835800f29ed22a158b31af79af106fe274cf0b6`
Integrated upstream anchor: `22a29b30dee3b3930cf49bba50454650c31d2a07`

## 1. Call chain (source observation)

Both the GUI search entry and the MCP `searchBlocks` tool funnel through the
same renderer handler and the same worker query function.

- MCP Electron adapter:
  `src/electron/electron/mcp_server.cljs:130` `api-search-blocks` calls
  `call-api-fn "logseq.app.search"` with args built by
  `src/electron/electron/mcp_search.cljs:3` `search-call-args`
  (`:enable-snippet? false`, optional `page-uuid` / `block-uuid`, optional
  `limit`; the JS keys `pageUuid`/`blockUuid` are emitted as `page-uuid` /
  `block-uuid`).
- API bridge: `src/main/logseq/api.cljs:187` `search` →
  `src/main/frontend/handler/search.cljs:27` `search`.
- GUI search also calls the same `frontend.handler.search/search`
  (e.g. plugin/`logseq.app.search` path in `api.cljs`; the Cmd-K UI in this
  tree ultimately drives the same handler. *Unverified:* I did not confirm a
  GUI component that consumes `:search/result` state in this tree; only
  `frontend.components.query.cljs` consumes a different key
  `:search/result-count`.)
- Handler → `src/main/frontend/search.cljs:21` `block-search` →
  `frontend.search.protocol/query` agency engine → worker
  `src/main/frontend/worker/handler/search.cljs:193` `search-blocks` →
  `src/main/frontend/worker/search.cljs:1013` `search-blocks`.

## 2. Visibility predicate findings (source observation, key result)

Recycled-entity exclusion in search is **already present at the upstream
anchor** and is **not part of the fork diff**:

- `frontend.worker.search/hidden-entity?` (`worker/search.cljs:608`) →
  `hidden-search-node?` (`worker/search.cljs:600`) → `logseq.db/hidden?` →
  `logseq.db.frontend.entity-util/hidden?` (`entity_util.cljs:69`) which returns
  true when `:logseq.property/deleted-at` is set on the entity or any parent.
- `combine-results` drops `hidden-entity?` rows (`worker/search.cljs:845`);
  `search-blocks` drops hidden via `(remove hidden-entity?)`
  (`worker/search.cljs:1138`).
- `get-affected-blocks` (`worker/search.cljs:1269`) already lists
  `:logseq.property/deleted-at` among indexed-change attrs and re-indexes the
  affected entity tree, so recycle removes rows and restore re-adds them.
- `git diff 22a29b30..HEAD -- src/main/frontend/worker/search.cljs`
  does **not** touch `hidden-entity?`, `hidden-search-node?`, `combine-results`,
  `get-affected-blocks`, or the `remove hidden-entity?` call sites. They are
  byte-identical to the anchor.
- The existing test
  `src/test/frontend/worker/search_test.cljs:195 hidden-entity-includes-recycled-entities`
  and `:2094 recycled-subtree-excluded-from-global-page-and-block-search`
  already assert recycled subtrees are excluded from global/page/block search.

=> Source observation: upstream anchor default **excludes** recycled entities
from shared search. Any GUI that showed recycled rows must predate this
behavior or run a different configuration. Per the plan safety boundary, this
default must be preserved, not disabled.

## 3. What the fork actually changed (source observation)

Diff `22a29b30..HEAD` on the candidate files only:

- `handler/search.cljs`: adds `page-uuid`/`block-uuid` scope options, scope
  validation, page/blob resolution through the worker, and a shared
  `state/swap-state!` publication of `:search/result` / `:search/more-result`
  for **every** caller (GUI and MCP).
- `worker/handler/search.cljs`: adds `:thread-api/search-page-uuid` and
  `:thread-api/search-block-uuid`, which **reject** recycled/hidden scope
  (`resolve-active-visible-page-uuid` / `resolve-active-visible-block`).
- `worker/search.cljs`: adds a `:block` exact-scope option threaded through the
  SQL binding, exact-title, fuzzy, and `include-search-block?` gates, plus the
  added `search-block-in-page?` scope check. No visibility predicate change.
- `electron/mcp_search.cljs`: new, builds MCP search args.
- `worker/db_listener.cljs`: `skip-search-sync?` no longer skips
  `::sqlite-export/imported-data?` txns when
  `:logseq.outliner.op/runtime-write?` is set (MCP `upsert-nodes` runtime
  writes now index incrementally).

## 4. Ranked hypotheses

1. **No fork-only GUI regression (source-supported, needs test confirmation).**
   Recycled entities are already filtered at the anchor; the fork diff adds no
   visibility change. The remembered "GUI showed recycled" is an older/default
   mismatch, not a fork regression. This matches the plan's conditional branch:
   keep upstream behavior and report the conflict.
2. **MCP/GUI shared result-state contamination (source-supported, separate
   concern).** `handler/search.cljs` unconditionally publishes to
   `:search/result`, so an MCP call can overwrite GUI search state. This is an
   isolation bug independent of recycled visibility; a minimal caller-boundary
   fix may be justified.
3. **Indirect index-timing change (unverified).** The `runtime-write` marker in
   `db_listener.cljs` makes MCP runtime writes re-index where they were
   previously skipped. This only affects MCP-originated writes; it does not
   change GUI recycle visibility by itself.

## 5. Uncertainty / what cannot be proved here

- Exact previous application SHA and GUI search mode are not established.
- No packaged-GUI differential acceptance is authorized/performed; all evidence
  below the anchor line is source plus unit tests.
- The GUI Cmd-K consumer of `:search/result` was not definitively located in
  this tree; the Cmd-K runtime path is therefore *unverified* at the component
  layer.
- Whether hypothesis 2 is user-visible is not yet established.

## 6. Next steps

- Run the focused differential/regression suites (handler search, worker search,
  worker handler search, mcp-search, electron mcp-server) and record real
  outputs; attempt to reproduce a GUI-vs-MCP difference on the same synthetic
  fixture.
- Decide the conditional minimal repair (likely MCP-only isolation of result
  publication), or record a justified no-change result.
