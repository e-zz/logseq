# Recycled-content search: scope and attribution correction

## User question
User reports that a previous app GUI search displayed matching recycled pages/blocks with a recycle label, and asks whether the issue fixes now suppress all recycled search results. This historical GUI behavior is a user observation, not disproved by the present MCP tests.

## Direct source/Git verification
Dedicated worktree HEAD is fb5eb4eb43cdb3be7a29d816b969d45c127d4861, matching the current artifact's recorded CI SHA.
- frontend/worker/search.cljs:600-613 hidden-search-node?/hidden-entity? consult ldb/hidden? for the node/page.
- frontend/worker/search.cljs:843-846 merge-blocks removes hidden entities from merged keyword/vector results.
- deps/db/src/logseq/db/frontend/entity_util.cljs:69-88 hidden?/recycled? include deleted-at on self or parent chain.
- Git diff from upstream anchor 22a29b30dee3b3930cf49bba50454650c31d2a07 to current SHA: entity_util.cljs unchanged; search.cljs changes add page/block scoping and corresponding SQL bindings/options, not a new global recycled-content filter. Frontend search handler changes likewise introduce scoped argument validation; worker scoped resolvers require active/visible targets.
- Git blame shows hidden-search-node? from upstream de070c1b034; hidden-entity? predates local integration. Upstream 25650bd2559 refactors hidden?/recycled? ancestor traversal; its before/after source both already include deleted-at checks, so do NOT attribute the behavioral difference to that refactor without runtime evidence.
- No includeRecycled search option was observed in the current ordinary search result filtering path. This does not mean recycled data is globally inaccessible: getPage/getBlock/listPages have opt-in semantics, as do separate recycled-root tools.

## Correct interpretation
Source-verified: the current shared ordinary block-search path excludes entities treated as hidden/recycled. Source/Git-verified: this filtering was inherited from the synchronized upstream baseline rather than newly implemented as a global switch by this local issue fix.
Measured this session: MCP search for the recycled test page/canary returns no hits; getPage opt-in retains the page/canary and deleted-at; user reports current GUI target absent.
Not established: exact old-build runtime SHA/search mode, reason old GUI displayed recycled-labelled results, whether any indirect local/upstream/index/runtime change explains the difference. No old/new side-by-side runtime reproduction or bisect was performed.

Correction to previous acceptance framing: #14 default MCP read filtering/opt-in marker/content retention passes its measured cases. Current GUI suppression must NOT automatically be described as the user's intended GUI behavior or as fully accepted GUI compatibility. The reported loss of prior GUI discoverability remains an unresolved compatibility question. Normal GUI search and agent default search may intentionally have different product requirements; this question must be settled before changing a shared filter.

No source/app/config changes performed. No rebuild/restart. No new public issue or comment in this diagnostic turn.
