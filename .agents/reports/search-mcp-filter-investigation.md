# Interim investigation — MCP search filter isolation / upstream compatibility

Status: interim, written before running the differential test suite. All items
below are either *source observations* (verified by reading current tree and
`git show` of the anchor) or explicitly marked *hypothesis* / *unverified*.

Worktree: `D:/orca/workspaces/logseq/issues-human-test-20261003`
Branch: `test/issues-mcp-20261003`
Plan HEAD at investigation start: `c835800f29ed22a158b31af79af106fe274cf0b6`
Integrated upstream anchor: `22a29b30dee3b3930cf49bba50454650c31d2a07`

## 1. Call chain (source observation)

源码核验：MCP and the inspected Cmd-K path share the lower-level worker engine, NOT necessarily the renderer aggregation handler.
- MCP: `electron.mcp-server/api-search-blocks` -> `electron.mcp-search/search-call-args` -> `logseq.app.search` -> `src/main/logseq/api.cljs:187-192` -> `frontend.handler.search/search` -> `frontend.search/block-search` -> agency/protocol -> worker.
- Inspected Cmd-K: `components/cmdk/core.cljs` -> `frontend.search/block-search` with Cmd-K options -> worker. It does not use the renderer aggregation handler on this inspected path.
- No Cmd-K reader of `:search/result` was found. Publishing this shared key from MCP is an isolation concern, not verified visible GUI corruption.
- Upstream anchor already publishes `:search/result` / `:search/more-result` in the renderer handler. This publication was not introduced by this fork.

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

=> 源码核验：the anchor's inspected shared search predicates exclude recycled entities. This is NOT a runtime comparison and does not explain the user's earlier GUI result. Previous build/config/path differences and indirect fork indexing effects remain unverified. Preserve upstream predicates while investigating; do not label the original discrepancy ruled out.

## 3. What the fork actually changed (source observation)

Diff `22a29b30..HEAD` on the candidate files only:

- `handler/search.cljs`: adds `page-uuid`/`block-uuid` scope options, scope
  validation, page/blob resolution through the worker, and preserves the upstream
  `state/swap-state!` publication of `:search/result` / `:search/more-result`
  for every aggregation-handler caller (including MCP).
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

## 4. Hypotheses (not root-cause conclusions)

1. Source visibility predicates match the anchor, but an indirect shared-index/runtime difference remains possible. Only a controlled anchor/current path or old/current package comparison can establish the observed GUI difference.
2. Existing aggregation-handler state publication is undesirable for MCP. A caller-specific opt-out is a bounded isolation improvement; no GUI visual impact or fork-introduction is established.
3. `runtime-write` listener changes may alter index freshness for MCP-created fixtures and subsequently affect GUI query results. This possibility has not been excluded by same-current-worker option-set tests.

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
