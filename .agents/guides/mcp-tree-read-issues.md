# Logseq MCP server — missing tree-level capabilities

Date: 2026-09-26
Environment: Logseq desktop, DB graph, app 2.0.x, Windows
MCP endpoint: `http://127.0.0.1:12315/mcp`

Two independent defects found while reading/restructuring a real page.
Both were confirmed against the live graph with repeatable calls.

---

## Issue 1 — `getPage` returns a one-level slice but reads like "the whole page"

### Symptom

`getPage` gives only a **single level** of the block tree, and nothing in the
response says so. A caller who asks for a page receives a structure that looks
complete (blocks with parent ids, orders, levels) but silently omits every
descendant below level 1.

Measured on one page:

| what was read | blocks returned |
|---|---|
| `getPage("<page name>")` — top level only | 11 |
| two levels | 34 |
| **full tree (descent to `blocks: []`)** | **81** (max depth 4, 82 calls) |

**58% of the page was missing from the level-1 read**, and the omitted part was
the deep structure — subblocks nested three and four levels down, including a
whole subordinate section. The specific content varies per page; the shape does
not: depth-1 reads omit exactly the subblocks a caller descends into a page to
find.

The same applies per block: `getPage("<block uuid>")` returns that block's
**direct children only** — it does not recurse. Since block numeric ids are not
resolvable (`getPage("<block id>")` → `Page "<block id>" not found`; only uuids
work), walking a page requires a uuid round-trip **per node**, with no pagination
and no batch call.

### Evidence

```
getPage("<page name>")                 -> 11 blocks, all level:1
getPage("<top-level block uuid>")      -> its direct children only
```

Blocks at depth >= 3 are unreachable from any level-1 read; a recursive walk
reached 81 blocks, all of them nested three or four levels down.

### Impact

Any consumer that treats `getPage` output as the page content produces
**confidently wrong answers about absence** — "this page has no X" — while the
data is present but deeper. This is a silent completeness failure, not an error:
the response is well-formed and gives no hint that it is partial.

### Suggested direction (already solved elsewhere in this codebase)

The app itself does not recurse node-by-node. See:
`src/main/frontend/worker/handler/page.cljs:286` `:thread-api/get-page-blocks-tree`

```clojure
(otree/blocks->vec-tree db (ldb/get-page-blocks db (:db/id page)) (:db/id page))
```

i.e. **one flat query for all page blocks, then assemble the tree in memory**
(`deps/outliner/src/logseq/outliner/tree.cljs:58` `blocks->vec-tree`).
`src/main/logseq/api/db_based/cli.cljs:34` even documents its own
`get-page-data` as *"Like `get_page_blocks_tree` but for API clients"* — the
tree function already exists; the API surface exposes a reduced variant.

---

## Issue 2 — a deleted page is still fully readable, with no recycle indicator

### Symptom

`listPages` omits pages that are in the trash, but `getPage` returns such a page
**with its entire block list** and **no field marking it deleted**.

### Evidence

| page generation | in `listPages` | `getPage` result |
|---|---|---|
| active page | yes | normal |
| deprecated generation A | **no** | **59 blocks returned** |
| deprecated generation B | **no** | **62 blocks returned** |

Both trashed pages expose `deleted-at` in their entity JSON — so the information
exists on the entity, it is simply not surfaced as a status, and `getPage` does
not act on it.

`listPages` (even with `expand: true`) returns only
`{uuid, title, created-at, updated-at}` — it carries **no** deleted flag, so a
client cannot distinguish "not in the list because deleted" from "never existed".

The GUI marks trashed items with a **Recycle** badge, so the state is
user-visible in the app but not in the MCP surface.

### Reproduce

1. Take a page name that has both a live and a trashed generation (any page
   renamed, or superseded by a rewrite, will do).
2. `listPages` → confirm the trashed generation is absent from the returned set.
3. `getPage("<that page name>")` → returns a full block list, indistinguishable
   from a live page.

### Impact

- A client can silently read and act on content the user believes they deleted.
- When a name has several generations, `getPage("<name>")` returns **whichever
  generation it resolves to, with no way to enumerate or disambiguate them** —
  the same lookup can surface a live page or a trashed one.

### Suggested direction

Either filter trashed entities in `getPage` (matching `listPages`), or expose an
explicit status field (and a way to opt in to trashed content) so the two
surfaces agree and the caller can tell which it received.

---

## Common root

Both defects are **visibility/scope problems on the same read path**: `getPage`
does not filter by depth (Issue 1) and does not filter by lifecycle (Issue 2),
and its response carries no metadata that would let a caller detect either.
A single addition — a completeness/status envelope on `getPage` — would let
clients fail loudly instead of confidently returning partial or trashed data.

## Not a defect: `parent` as a numeric id

The `parent: {id: N}` returned on each block is **not** a broken reference. It is
an internal join key: the app reads a page by pulling **all** its blocks in one
flat query and joining them in memory (`blocks->vec-tree`), so numeric ids are
self-sufficient within that query and never need to be externally dereferenceable.
Block ids being unresolvable through `getPage("<id>")` is a consequence of the
MCP surface exposing a per-node lookup for an API designed around bulk reads —
a surface mismatch, not a data-model fault. Recorded here so it is not filed as
a separate defect.

## Workaround used meanwhile

For full-tree reads, descend one level at a time with `getPage(<block uuid>)`
until `blocks: []`, bounding recursion by a depth limit and reporting truncation
explicitly. This costs one round-trip per node (81 blocks → 82 calls).
