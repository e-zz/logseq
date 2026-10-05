# 03 LIST4 / Task7 inventory (report was previously missing)

This file reconstructs the missing inventory explicitly. It distinguishes
**parent-verified**, **source-observed**, and **unverified** items. It does not
claim runtime evidence produced by this worker for the parent's changes.

## Parent-verified focused bundle results (quoted, not re-run here)

- property-write: 11 tests / 75 assertions
- issue6: 10 tests / 41 assertions
- tools: 35 tests / 183 assertions
- cli: 18 tests / 67 assertions
- electron.mcp-server: 1 test / 3 assertions
- all zero failures / zero errors

These prove focused results on the then-current compiled bundle. They do NOT prove
freshness, HTTP/MCP transport behavior, or on-disk persistence.

## Source-observed on this worktree (f2958757d2 + WIP)

- Real `electron/mcp_upsert.cljs` exists and `mcp_server.cljs` wires
  `:upsertNodes :fn mcp-upsert/api-upsert-nodes`.
- `upsertNodes` schema includes `:parent-id`, `:receipt`, `:dry-run`.
- `listProperties` description advertises `expand=true` discovery incl. closed
  values with uuids.
- Numeric user-property writes and built-in Task status / list-type "number"
  writes are described in the `:properties` field docs.

## This worker's slice (SCOPE-8)

See `04-scoped-search-implementation.md` for the scoped-search change list, compile,
focused test runs, lint result, and gaps.

## Unverified gaps (do not claim complete)

- No UI/runtime exercise of numeric/status/list property writes, parent placement,
  or receipt readback in this worker slice.
- No HTTP/MCP transport round-trip (full registered server) exercised. Blocker:
  the CLI graph-open safety gate (SAFE-12) remains unresolved; opening a real graph
  for persistence/HTTP tests was not attempted. No synthetic HTTP harness run.
- No graph reopen/persistence verification.
- List-type removal (nil -> bullet) is structurally unsupported through the importer
  path (see property_write_test.cljs NOTE at EOF); not a regression.
- Receipt combined with property writes is still rejected by contract.

## Pending follow-up (source issue, recorded not fixed)

`logseq.api.db-based.tools/list-properties` (expand branch) starts from `(into {} e)`
and only `assoc`es `:property/closed-values` when `(seq closed-values)`. When the
filtered closed-value list is empty, the original raw `:property/closed-values` from
the entity is never dissoc'd, so hidden/recycled raw refs could leak through. A
focused test/fix was judged out of this slice's scope; tracked here as follow-up.
