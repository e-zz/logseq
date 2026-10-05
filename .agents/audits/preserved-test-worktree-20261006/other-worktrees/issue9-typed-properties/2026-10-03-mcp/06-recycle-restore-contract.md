# 06 Recycle/Restore tool contract (design, pre-implementation)

Status: agreed design for this slice. Not yet implemented. This document is the
source of truth for tool names, inputs, result schemas, and no-op/error choices.
It is written before RED tests and implementation, as required by
`06-recycle-restore-dispatch.md`.

## Scope

Agent-only, ordinary-block, root + multi-level subtree soft recycle and restore,
plus explicit inspection of the retained recycled root/subtree.

Explicitly out of scope: pages/tags/properties, property-value pseudochildren,
permanent deletion, GUI ordinary-delete routing changes, new schema/property/
class/model. Retention remains 30 days with GC; this is not infinite undo.

## Tools

All three tools are strict one-root tools. `blockUuid` must be a valid UUID
string. All three are registered only on the MCP API server.

| MCP tool          | API method                    | renderer export        | worker read                 |
|-------------------|-------------------------------|------------------------|-----------------------------|
| `recycleBlock`    | `logseq.cli.recycleBlock`     | `recycle_block`        | `api-get-recycled-block`    |
| `restoreBlock`    | `logseq.cli.restoreBlock`     | `restore_block`        | `api-get-block` (active)    |
| `getRecycledBlock`| `logseq.cli.getRecycledBlock` | `get_recycled_block`   | `api-get-recycled-block`    |

Method resolution: `logseq.cli.<camel>` -> `cli@<snake>` -> renderer
`js/window.logseq.api["<snake>"]` (see `electron.server/resolve-real-api-method`
and `electron.listener`).

## Mutation path (normal outliner/worker path)

Recycle is a **new semantic outliner op `:recycle-blocks`**, symmetric with the
existing `:restore-recycled`:

- renderer `frontend.modules.outliner.op/recycle-blocks!` emits
  `[:recycle-blocks [root-uuid opts]]`, where `opts` carries `:deleted-by-uuid`
  from the current user (same helper as `delete-blocks!`).
- `logseq.outliner.op` validates the op, and `apply-op!` calls a new
  `logseq.outliner.recycle/recycle!`.
- `recycle!` validates the whole target subtree on the pre-transaction db and
  then calls `recycle-blocks-tx-data` + `ldb/transact!` with
  `:outliner-op :recycle-blocks`, inside the `apply-ops!` temp conn. Validation
  runs before any write, so a rejected target leaves datoms unchanged (no
  partial write).
- `logseq.outliner.op.construct/semantic-outliner-ops` gains `:recycle-blocks`;
  canonicalize normalizes the root id to a stable block uuid. No semantic
  inverse is provided (like `:restore-recycled`/`:recycle-delete-permanently`),
  so undo falls back to the existing raw reversed-tx path.

Restore reuses the **existing** `:restore-recycled` semantic op and backend
`logseq.outliner.recycle/restore!` unchanged. No second recycle mechanism, no
raw SQLite/DataScript writes from Electron, no follow-up non-atomic mutation.

Renderer functions run the op through `frontend.modules.outliner.ui/transact!`,
then verify with a worker readback and build the result map. A worker readback
is the verification; it is not a claim of crash durability.

## Result schemas

Success and no-op results are plain maps; the MCP layer stringifies them.
Expected user errors are returned as `{:error <string>}`, never a generic
success. Errors are validated as far as possible before the transaction; a
nil/absent backend result is always an error, never success.

### recycleBlock

```
{:operation      "recycle"
 :state          "recycled"
 :no-op          false
 :root-uuid      "<root uuid>"
 :affected-uuids ["<root>" ...descendants]   ; stable block uuids
 :affected-count <n>
 :deleted-at     <ms>
 :page-uuid      "<Recycle built-in page uuid>"}   ; verified location
```

Repeated recycle of an already-retained root (no-op; timestamp, original
parent/page/order and subtree are NOT rewritten):

```
{:operation      "recycle"
 :state          "recycled"
 :no-op          true
 :reason         "already-recycled"
 :root-uuid      "<root uuid>"
 :affected-uuids [...]
 :affected-count <n>
 :deleted-at     <existing ms>
 :page-uuid      "<Recycle built-in page uuid>"}
```

Errors (`{:error ...}`): invalid/blank uuid; not found; page/tag/property;
recycled *page* entity; built-in/hidden block; property-value pseudochild
(`:block/closed-value-property` or `:logseq.property/created-from-property`);
non-ordinary root (e.g. Recycle page itself); target whose subtree cannot be
fully resolved. Rejecting any of these leaves datoms unchanged.

### restoreBlock

```
{:operation      "restore"
 :state          "active"
 :no-op          false
 :root-uuid      "<root uuid>"
 :affected-uuids ["<root>" ...descendants]
 :affected-count <n>
 :page-uuid      "<resolved live page uuid>"
 :parent-uuid    "<resolved live parent/page uuid>"
 :position       "original" | "original-page-fallback"
 :order          "original" | "regenerated"}
```

`position`/`order` are derived from the actual worker readback compared with the
root's stored `:logseq.property.recycle/original-*` metadata, not assumed.

Choice: restoring an **active** (non-recycled) root is an actionable error
`{:error "..."}`, not a silent no-op. Rationale: an active block has no defined
insertion point and no retained original location; failing fast avoids inventing
a position. (Recycle is idempotent because its end state is well defined; restore
of an active block is not.) This asymmetry is intentional and documented here.

Errors: invalid/blank uuid; not found; page/tag/property; not recycled; recycled
root whose original parent and original page are both missing/gone -> explicit
error with unchanged datoms, never a placeholder page.

Order collision: if the stored `original-order` is already occupied in the live
target at restore time, the backend regenerates a sibling key via the existing
`db-order/gen-key` at the actual insertion point (no duplicate sibling keys).

**Status of the defect claim: design expectation, NOT yet reproduced.** Reading
the pre-edit `restore-target`/`restore-order` suggests the stored
`original-order` was reused unconditionally, but no test has produced a duplicate
sibling order. A focused legacy-contrast reproduction test must pass before the
fix is claimed. Likewise, malformed `original-order` values (non-string / invalid
base-62 key) and a nil insertion target in the page-root path are open gaps that
need explicit validation + regressions; the current edit does not yet handle
them.

Independently recycled descendants stay recycled; only the explicitly recycled
root's subtree is restored (matches current `restore-tx-data` semantics, which
reuses `:block/page` for descendants without clearing their own deleted-at).

### getRecycledBlock

Reads the retained ordinary root/subtree of an explicitly recycled root.

```
{:operation      "get-recycled"
 :state          "recycled"
 :root-uuid      "<root uuid>"
 :deleted-at     <ms>
 :page-uuid      "<current Recycle location page uuid>"
 :original-page-uuid  "<uuid or nil>"
 :original-parent-uuid "<uuid or nil>"
 :original-order "<stored order or nil>"
 :subtree        [ "<root>" ...descendants ]   ; stable uuids, order preserved
 :subtree-count  <n>}
```

If the uuid is not an explicitly recycled ordinary block root -> `{:error ...}`.
No raw EIDs are serialized; uuid values are strings.

## Read/search invisibility (design expectation; must be tested, not assumed)

**Status: design expectation, NOT tested yet.** The behaviors below follow from
source reading of the `:logseq.property/deleted-at` / hidden Recycle-page path.
No test currently asserts exclusion or reappearance. Treat all four bullets as
hypotheses until an executed test proves them.

- `getBlock`: a recycled ordinary root/descendant has `:logseq.property/deleted-at`
  (root) or an ancestor with it, so the existing parent-chain check reports it
  as hidden. To be tested explicitly.
- `getPage`/`listPages`: content is moved under the hidden built-in Recycle page;
  must not leak. To be tested explicitly.
- `list/search`: recycle/restore drive the existing tx-report datoms
  (`:logseq.property/deleted-at`, `:block/page`), so search exclusion/
  reappearance must be asserted by test. To be tested explicitly.
- Existing read/lifecycle/parent/property/receipt/search contracts must stay
  green.

## Verification / acceptance

- RED tests fail for genuine behavior reasons before implementation.
- Backend namespace `logseq.outliner.recycle-test` plus new MCP recycle tests,
  and existing focused regressions, run separately with verified Testing
  namespace / nonzero counts.
- Strict registered MCP schema and actual adapter wiring tested; API->worker->
  transaction chain reachable.
- HTTP synthetic-graph test and SQLite close/reopen persistence test attempted
  via existing harness if safely available; otherwise the exact missing seam is
  reported and the slice marked partial, not complete.
- Deterministic time fixture for retention/GC; retained vs collected documented.
- No `RECYCLE-8`/`RESTORE-8`/issue8 completion claim without all gates.
