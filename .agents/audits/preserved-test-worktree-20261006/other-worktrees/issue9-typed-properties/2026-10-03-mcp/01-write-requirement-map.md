# 01 — MCP WRITE path requirement→code map (2026-10-03)

Read-only audit of the MCP **property-write** path in the write baseline.
No files modified; no code executed against any graph; no tests run.
Every claim is labeled 实测 (source-verified by reading the named file:line) /
推断 (inference from 实测) / 推测 (weaker inference) / UNCERTAIN.

## 0. Scope, source commit, and how to read this map

**Baseline (实测).** Worktree `D:/orca/workspaces/logseq/issue9-typed-properties`,
HEAD `f2958757d2` ("wip: support numeric MCP property writes"), stacked on
`76c7a22327` (#17 parented writes). Verified via `git log --oneline -5`:

```
f2958757d2 wip: support numeric MCP property writes
76c7a22327 wip: support parented MCP block writes
44ce435eab fix: keep local-only fixes on top of upstream
```

**The one write-reachable MCP tool (实测).** The only registered MCP tool that
writes is `upsertNodes`, declared in
`src/electron/electron/mcp_server.cljs:171` (`:upsertNodes` in the
`api-tools` map). Its handler is `mcp-upsert/api-upsert-nodes`
(`src/electron/electron/mcp_upsert.cljs:3-8`), which calls
`"logseq.cli.upsertNodes"`. That API string routes to
`logseq.api.db-based.cli/upsert-nodes` (`src/main/logseq/api/db_based/cli.cljs:95`),
reached through `logseq.api/upsert_nodes`
(`src/main/logseq/api.cljs:240`). All other `api-tools` entries
(getBlock, listPages, getPage, searchBlocks, listTags, listProperties) are reads.

So "wired to the registered MCP tool schema" means: the capability must flow
`mcp_server.cljs` tool decl → `mcp_upsert.cljs` → `cli/upsert-nodes` →
`build-upsert-nodes-edn` (tools.cljs) → outliner `:batch-import-edn` op →
`sqlite.export/build-import` → `sqlite.build/build-blocks-tx` (in the local dep
`deps/db/src/logseq/db/sqlite/build.cljs`), with validation by
`sqlite.export/validate-import-txs` (tx-scoped) before the transaction.

**"Helper exists in tools.cljs" ≠ "reachable through the MCP tool".** The two
claims are kept separate throughout. The numeric contract is reachable; the
page/status/list/reference writers are not, for the reasons below.

### The full write chain, with file:line (实测)

| Stage | file:line | what it does |
| --- | --- | --- |
| MCP tool decl | `mcp_server.cljs:171-235` | `upsertNodes`; `:data` is a passthrough object, so **no MCP-level schema gate on `:properties` values** |
| MCP→CLI bridge | `mcp_upsert.cljs:5-8` | forwards `operations` + `{dry-run, receipt}` to `logseq.cli.upsertNodes` |
| CLI dispatch | `cli.cljs:95-213` | `upsert-nodes`; builds receipt uuids, calls build-edn, transacts |
| EDN builder | `tools.cljs:774-830` | `build-upsert-nodes-edn`: malli op-schema, `resolve-numeric-properties`, page-edit guard, `ops->pages-and-blocks` |
| Numeric resolver | `tools.cljs:356-394` | `resolve-numeric-properties` — the narrow contract |
| Import op | `op.cljs:161-164` (`frontend/modules/outliner`) | `batch-import-edn!` → `[:batch-import-edn [edn opts]]` |
| Import reducer | `deps/outliner/.../op.cljs:213-234` | `import-edn-data`: `build-import` then `validate-import-txs` then `transact!` |
| tx builder | `deps/db/.../sqlite/build.cljs:241-276` (`->block-tx`), `79-90` (`->block-properties`), `172-189` (`->property-value-tx-m`) | consumes `:build/properties` |
| tx validation | `deps/db/.../sqlite/export.cljs:1396-1433` (`validate-import-txs`), `1453-1468` (`validate-import-tx-data`), `deps/db/.../validate.cljs:130-160` (`validate-local-db!`) | dry-runs tx, validates touched entities |

Note the importer lives in **`deps/db` and `deps/outliner`** (local shadow/local
deps), not `src/main`. `resolve-numeric-properties` (tools.cljs) only *produces*
the `:build/properties` map; the *consumer* is `build.cljs`.

---

## 1. Per-ID verdicts

Verdict labels:
- **EXISTS_AND_WIRED** — capability exists AND is reachable through the registered MCP tool, with real (non-forged) value encoding.
- **EXISTS_NOT_WIRED** — a real helper/capability exists in the codebase but is not reachable through the registered MCP write tool (or reaches it only by a path the tool refuses).
- **PARTIAL** — partially reachable / partially correct; some sub-requirements met, others missing.
- **MISSING** — no code path exists to deliver the acceptance criterion.

| ID | Verdict | One-line basis |
| --- | --- | --- |
| LIST-4 | **MISSING** | `:logseq.property/order-list-type` is a built-in, hidden, non-user property; the resolver rejects it, and the importer has no list-type support for writes. |
| TASK-7 | **MISSING** | Status is a built-in closed-value property; the resolver explicitly refuses closed-value properties; writing it via the tool would either throw at preflight or be rejected by tx validation. |
| SCALAR-9 | **PARTIAL** | Only the `:number` scalar type is reachable; text/date/url/checkbox scalars are refused by the resolver (type `= :number` guard). |
| REF-9 | **MISSING** | Reference (`:node`/`:asset`) and many-value fields are refused at preflight; the importer can encode refs but the tool never emits them. |
| PAGE-9 | **PARTIAL** | Page **add** is reachable (incl. nested block adds); page **edit** and **property-only page edit** are hard-refused at `tools.cljs:782-783`. |
| CLOSED-9 | **EXISTS_NOT_WIRED** | The closed-value validation path (schema + tx-scope) exists and is real, but the tool's resolver refuses all closed-value props before the tx, and no public support/reject table ships in the API. |
| RECEIPT-8 | **PARTIAL** | A real verified readback receipt exists for block add/edit, but (a) it is explicitly disabled when `:properties` is present, and (b) the readback only checks uuid/page/parent/title — **never property content** — so a property write can't be `:verified`. |

### Detail and evidence per ID

#### LIST-4 — real numbered/ordered list (MISSING)

- **What a numbered list actually is in the DB (实测).** It is the block
  property `:logseq.property/order-list-type`, set to `"number"` (string), on the
  block. Evidence:
  - Property def: `deps/db/.../property.cljs:249-252`
    ```
    ;; FIXME: :logseq.property/order-list-type should updated to closed values
    :logseq.property/order-list-type {:title "List type"
                                       :schema {:type :default
                                                :hide? true}}
    ```
  - UI read: `src/main/frontend/handler/block.cljs:32-37`
    ```
    (let [type (pu/lookup block :logseq.property/order-list-type)
          own-order-list-type  (some-> type str string/lower-case)
      (assoc config :own-order-list-type own-order-list-type
      :own-order-number-list? (= own-order-list-type "number")))
    ```
  - UI setter: `src/main/frontend/handler/editor.cljs:74-89`
    ```
    (defn get-block-own-order-list-type [block] (pu/lookup block :logseq.property/order-list-type))
    (defn set-block-own-order-list-type! [block type] ... (property-handler/set-block-property! uuid :logseq.property/order-list-type (name type)) ...)
    (defn own-order-number-list? [block] (= (get-block-own-order-list-type block) "number"))
    ```
- **Why the tool cannot write it (实测).** The resolver gate is
  `= "user.property" (namespace ident)` (`tools.cljs:382`) plus type
  `= :number` (`:383`). `:logseq.property/order-list-type` has namespace
  `logseq.property` (built-in) **and** `:hide? true`, so it fails `user.property`
  and `hidden?` both. It is rejected with "Property must be an existing
  single-valued user number property UUID" (`tools.cljs:387`). The MCP tool
  description also states it directly: "Task status and list-format properties
  are not supported by this narrow contract." (`mcp_server.cljs:187`).
- **Readback of a real list property (实测/推断).** A read (`get-block`/
  `get-page`) would surface `:logseq.property/order-list-type` only if present;
  but it is a *hidden* property, and the read path drops hidden props
  (`remove-hidden-properties`, tools.cljs:64,100,150,263,352). So even after a
  list is written, the registered read tool is expected to hide it. 推断: a
  numbered list cannot be produced *and* read back through the MCP surface.
- **Verdict: MISSING.** No MCP-reachable path writes `order-list-type`; the
  acceptance "genuine list semantics … stored list property … viewer/editor
  numbering" is not delivered. Not a title prefix (good — the plan forbids
  forging), but nothing is delivered at all.

#### TASK-7 — Task identity + todo/doing/done (MISSING)

- **What task status actually is in the DB (实测).** It is the built-in property
  `:logseq.property/status`, a **closed-value** property. Def:
  `deps/db/.../property.cljs:300-322`
  ```
  :logseq.property/status {:title "Status"
                           :schema {:type :default :public? true :ui-position :block-left}
                           :closed-values
                           (mapv ... [[:logseq.property/status.backlog "Backlog" ...]
                                      [:logseq.property/status.todo "Todo" "Todo" false]
                                      [:logseq.property/status.doing "Doing" ...]
                                      ... [:logseq.property/status.done "Done" ... true] ...])
                           :properties {:logseq.property/hide-empty-value true
                                        :logseq.property/default-value :logseq.property/status.todo ...}
                           :queryable? true}
  ```
- **How a block references a closed value (实测).** A block property value for a
  closed-value property is a **ref to the closed-value entity's `:db/id`** (an
  int eid), where the closed-value entity carries `:block/closed-value-property`
  = the property eid, `:logseq.property/value`, `:block/order`. Evidence:
  - `deps/db/.../view.cljs:498`
    `closed-eids (when prop-eid (mapv :e (d/datoms db :avet :block/closed-value-property prop-eid)))`
    and orders them by `:block/order` (`:499-505`).
  - `deps/db/.../malli_schema.cljs:581-582` dispatches an entity to
    `:closed-value-block` when it has `:block/closed-value-property`.
  - Closed-value schema `closed-value-block*` (`:471-483`) requires
    `:block/closed-value-property` and `:logseq.property/value [:or :string :double]`.
- **Why the tool cannot write it (实测).** Two independent refusals:
  1. Preflight: the resolver rejects closed-value properties —
     `(not (seq (:property/closed-values prop)))` (`tools.cljs:385`). Status has
     closed values, so it is thrown out before the batch.
  2. Even if it reached the importer as a string `"Doing"`, the tx-scope
     validation would reject it: `:logseq.property/status` is a ref property
     (`:default` ∈ `closed-value-property-types`, `property/type.cljs:30-32`), so
     the block property value must be a ref to a closed-value entity id, not a
     bare string; `validate-local-db!` runs with `*closed-values-validate?* true`
     (`validate.cljs:149`) and `validate-property-value`
     (`malli_schema.cljs:101-112`) would fail a non-closed-value id.
- **Task identity / query / UI recognition (实测).** Setting
  `:logseq.property/status` on a block auto-adds the `:logseq.class/Task` tag in
  the outliner (`deps/outliner/.../property.cljs:108-118,205-206`
  `should-add-task-tag-for-property?` → `(assoc :block/tags :logseq.class/Task)`).
  That is a real, existing UI/query recognition path — but it is only reachable
  through the outliner's `set-block-property!`, **not** through the MCP write tool.
- **Verdict: MISSING.** No MCP-reachable path writes status; a task's
  todo/doing/done state cannot be created/updated via `upsertNodes`. The backend
  semantics exist (EXISTS at the outliner layer) but are not wired to the tool.

#### SCALAR-9 — number/text/date/url etc., schema-verified (PARTIAL)

- **What is reachable (实测).** Only `:number`. The resolver accepts a property
  iff (all 实测, `tools.cljs:381-391`):
  ```
  (entity-util/property? prop)
  (= "user.property" (namespace ident))          ; user, not built-in
  (= :number (:logseq.property/type prop))        ; numeric type only
  (= :db.cardinality/one (:db/cardinality prop))  ; single-valued only
  (not (seq (:property/closed-values prop)))      ; not closed-value
  (not (entity-util/hidden? prop))                ; not hidden
  ```
  and the value must be `(and (number? value) (js/Number.isFinite value))`.
- **What is refused (实测).** text (`:default`/`:string`), date, datetime, url,
  checkbox, node — all fail the `:number` type guard and are thrown out at
  preflight with the same message. The MCP description: "Only single-valued
  number properties are supported." (`mcp_server.cljs:187`).
- **Schema-verified, not name-guessed (实测).** The type/cardinality are read
  from the property entity's real schema (`:logseq.property/type`,
  `:db/cardinality`) at `tools.cljs:383-384` — not inferred from the field name.
  This satisfies the plan's "do not guess types from field names" for the number
  case. But since only one type is accepted, the broader "existing user scalar
  value types … by their real schema" acceptance is only partially met.
- **The importer itself is broader (实测/推断).** `build.cljs` would happily
  encode text/date/url/checkbox scalars if the resolver let them through
  (see §2b). The limitation is at the resolver, not the importer. 推断: widening
  SCALAR-9 is a resolver change, not an importer change.
- **Verdict: PARTIAL.** Number scalar exists and is wired; the other scalar
  types are not reachable.

#### REF-9 — reference + many-value fields, stable identity readback (MISSING)

- **What the tool refuses (实测).** `:node`/`:asset`/`date` (ref types,
  `property/type.cljs:58-66`) fail the `:number` guard; `:many` cardinality
  fails `= :db.cardinality/one`. The MCP description: "reference values are
  rejected before the batch writes." (`mcp_server.cljs:187`).
- **What the importer CAN encode (实测).** `build.cljs` fully supports ref and
  many values:
  - `translate-property-value` (`:37-56`) maps `[:build/page {:block/title ...}]`
    → `[:block/uuid <uuid>]` (a stable-identity ref).
  - `->block-properties` (`:78-90`) maps a **set** to a `:many` value.
  - `->property-value-tx-m` (`:172-189`) + `build-property-map-for-pvalue-tx`
    (`:106-130`) create the property-value entity (ref) when the type is a
    value-ref type.
  - Schema `::property-value` (`:542-549`) accepts ref values
    (`::build-page-property-value`, `::block-uuid-property-value`,
    `::block-property-value`) **and** `:any` scalars.
  So the downstream can write a reference to a stable page/block UUID and a many
  set — but the tool never emits them.
- **Stable-identity readback (实测/推断).** The read path
  (`entity->serializable`, tools.cljs:95-121) *does* project ref attributes to
  stable `:block/title`/`:block/uuid`/`:db/ident` (`reference->serializable`,
  `:70-83`), so a reference written by another path would be read back by stable
  identity. But since the write path can't produce a reference, the REF-9
  acceptance (write ref/many, read back by stable identity, verified against real
  property metadata) is not delivered by the tool.
- **Verdict: MISSING** at the MCP boundary (the importer capability EXISTS but is
  NOT_WIRED to the tool).

#### PAGE-9 — page add/edit + property-only page edit (PARTIAL)

- **Page ADD is reachable (实测).** `add/page` is a valid op in the malli op-schema
  (`tools.cljs:666`) and in `ops->pages-and-blocks` (`:515-533`, `:521-532`);
  `assert-add-block-page-ids!` (`:681-695`) accepts a same-call `:id` or an
  existing page uuid. The MCP example even shows adding a page (`mcp_server.cljs:213-222`).
- **Page EDIT is hard-refused (实测).**
  `tools.cljs:782-783`:
  ```
  (when (seq (filter #(and (= "page" (:entityType %)) (= "edit" (:operation %))) operations*))
    (throw (ex-info "Editing a page, tag or property isn't supported yet" {})))
  ```
  So "content page add/edit" is only half met: add yes, edit no.
- **Property-only page edit is impossible (实测).** A page op's `:data` schema is
  `add-non-block-schema` = `{:data [:map [:title :string]]}`
  (`tools.cljs:628-631`) — `:properties` is not a legal key for a page. So a page
  cannot carry properties at all through this tool, property-only or otherwise.
- **Preserves title / other properties / structure (实测/推断).** For the block
  property-only **block** edit (the only property-edit path that exists), the
  title is preserved because `ops->existing-pages-and-blocks` re-reads the
  existing block title when `:title` is absent (`tools.cljs:507-509`) and the
  importer's `build-existing-tx?` keeps the block and only overwrites the named
  properties (`build.cljs:241-258`, `cli.cljs:177-178` `:build-existing-tx? true`).
  But this is *block* property-only edit; *page* property-only edit does not
  exist. 推断: the "actual person/paper metadata scenario" (page-level metadata)
  is not deliverable.
- **Verdict: PARTIAL.** Add (with optional nested blocks) is wired; page edit and
  page property-only edit are not.

#### CLOSED-9 — built-in/closed values via existing validation; reject before tx; public table (EXISTS_NOT_WIRED)

- **The validation path exists and is real (实测).**
  - Closed-value schema: `malli_schema.cljs:471-483` (`closed-value-block`),
    dispatched by `entity-dispatch-key` `:581-582`.
  - Closed-value membership check: `validate-property-value`
    `malli_schema.cljs:101-112` (`closed-value-valid?`), gated by
    `closed-value-property-types` (`property/type.cljs:30-32`) and
    `*closed-values-validate?*` binding `true` (`validate.cljs:149`).
  - Enforcement: `validate-import-txs` (`export.cljs:1396-1433`) →
    `validate-import-tx-data` (`:1453-1468`) → `validate-local-db!`
    (`validate.cljs:130-160`) with `:validate-scope :tx` (set by
    `cli.cljs:176-178`), which dry-runs the tx and validates only touched
    entities **before** `transact!` (`op.cljs:227-231`). This is the
    "reject before the transaction" path the plan wants.
  - Read-only / protected guards exist in the outliner layer
    (`deps/outliner/.../property.cljs:27-92`: `throw-error-if-read-only-property`,
    `throw-error-if-deleting-protected-property`,
    `throw-error-if-deleting-required-property`).
- **But the tool refuses closed values before it reaches that path (实测).**
  `tools.cljs:385` `(not (seq (:property/closed-values prop)))` throws at
  preflight, so the tx-scope closed-value validation never gets to do its job for
  a user request. The preflight is a coarser, earlier gate.
- **No public support/reject table ships in the API (实测).** The only
  documentation is the free-text `:properties` description in the `upsertNodes`
  config (`mcp_server.cljs:187`), which narrates what is/ isn't supported. There
  is no structured per-type/per-value table exposed to the agent, and it does not
  enumerate closed-value identities or reference identities. The plan's
  "public support/reject table" acceptance is not met.
- **Verdict: EXISTS_NOT_WIRED.** The closed-value validation path exists and is
  correct, but (a) it is shadowed by the resolver's blanket closed-value refusal,
  and (b) no public support/reject table is shipped.

#### RECEIPT-8 — real persisted UUID, write result, verification level; dry-run/no-op/error differ; never verified when content didn't land (PARTIAL)

- **Real verified readback exists (实测).** `cli.cljs:95-213` builds a receipt:
  - `:verified` — post-transaction worker readback via
    `api-read-upsert-blocks` → `read-upsert-blocks` (tools.cljs:747-772), which
    re-reads each block by UUID and checks page, parent, and title.
  - `:dry-run` — `cli.cljs:189-190`, no transaction, planned uuids.
  - `:no-op` — `cli.cljs:181-182`.
  - readback count/identity mismatch throws "transaction completed but no
    receipt is available" (`cli.cljs:196-199, 208-211`).
  - It never reports `:verified` without a successful readback comparison.
- **Properties are explicitly excluded from the receipt (实测).**
  `resolve-numeric-properties` throws when `receipt?` is true:
  `tools.cljs:365-366`
  ```
  (when receipt? (throw (ex-info "Property writes do not support receipt mode yet" {})))
  ```
  So a property write and `receipt=true` cannot be combined — the tool refuses the
  combination (consistent with plan §4B "reject unsupported receipt
  combinations").
- **The readback never checks property content (实测).** `read-upsert-blocks`
  (tools.cljs:747-772) validates only `:block/uuid`, `:block/page`,
  `:block/parent`, and `:block/title`. It does **not** read back any
  `:build/properties` value. Consequence: for a *non-property* block write the
  receipt is sound, but for a property write there is no verified level at all —
  the only path is `receipt=false`, which returns the legacy summary string
  (`cli.cljs:184-187`, `api-util/summarize-upsert-operations`). 推断: an agent
  that wants to know a property value actually landed must use an independent
  MCP read (getPage/getBlock), which is exactly the plan's fallback.
- **"Never treat an input count as the result" (实测/推断).** The receipt derives
  per-op results from the readback, not from `count(operations)`; the count is
  only used to align `receipt-uuids` to ops (`cli.cljs:115-117, 802-803`). 推断:
  this specific anti-pattern is avoided, but the property-content verification
  gap remains the central RECEIPT-8 coverage hole.
- **Verdict: PARTIAL.** Verified block-identity receipt is real and wired for
  non-property writes; property writes have no verified receipt and must fall
  back to independent reads.

---

## 2. Three specific investigations (a/b/c)

### (a) How block property values are represented in the DB for closed-value
### and list properties — what LIST-4 / TASK-7 would actually have to write

**Closed-value (status/priority) property values (实测).**
- The property entity carries `:property/closed-values`, a set of closed-value
  entities. Each closed-value entity has `:block/closed-value-property` =
  property eid, `:logseq.property/value` (string/double), `:block/order`
  (for UI ordering), and a `:block/uuid` derived from its `:db-ident`
  (`property.cljs:305-318` builds them with
  `:uuid (common-uuid/gen-uuid :db-ident-block-uuid db-ident)`).
- A **block's** property value for such a property is a **DataScript ref to the
  closed-value entity's `:db/id`** (an int), NOT a string. Reading is
  `view.cljs:498` (`:block/closed-value-property` datoms) → ordered by
  `:block/order`. Writing a status therefore means: resolve the desired
  `:value` (e.g. `"Doing"`) to its closed-value entity id and write that id as
  the block property value; the outliner also adds `:logseq.class/Task`
  (`property.cljs:205-206`).
- For `:logseq.property/status` specifically, valid values are the six
  `:logseq.property/status.*` closed values listed at
  `property.cljs:313-318` (backlog/todo/doing/in-review/done/canceled); todo is
  the default (`:320`).

**List property value (实测).**
- A numbered/ordered list is the block property
  `:logseq.property/order-list-type` with a **string** value
  (`"number"`; other list types are represented by other string values). It is a
  `:default`-type, `:hide? true` built-in (`property.cljs:249-252`), and the UI
  checks `= own-order-list-type "number"` (`block.cljs:37`). Writing a numbered
  list means setting that block property to the string `"number"` (or the
  appropriate list-type string) via a real property write — the importer has no
  special list-type handling, it would be an ordinary (string) block property,
  but the built-in is hidden and non-`user.property`, so the current tool
  refuses it.

**What this implies (推断).** Both TASK-7 and LIST-4 are "write a particular
built-in block property" problems. The existing `:build/properties` importer can
write an *ordinary* block property value (string for list-type; closed-value ref
for status). The blocker is the resolver's gate (user-only, number-only,
no-closed-values) plus, for status, the need to encode a closed-value **ref id**
rather than a string. The validation path in (c) is what would accept a correct
status ref and reject a wrong one.

### (b) Can the `:build/properties` importer accept anything other than a plain
### number, or is it structurally number-only?

**Not structurally number-only (实测).** The importer
(`deps/db/.../sqlite/build.cljs`) accepts a wide range of value shapes:

- **Schema** `::property-values` / `::user-properties` (`:542-553`):
  ```
  ::property-value  [:or ::build-page-property-value ::block-uuid-property-value
                     ::block-property-value  :any]
  ::property-values [:or [:ref ::property-value] [:set [:ref ::property-value]]]
  ::user-properties [:map-of Property [:ref ::property-values]]
  ```
  i.e. scalar `:any`, a ref (`[:block/uuid X]`), a page ref
  (`[:build/page {:block/title ...}]`), a nested block value
  (`{:build/property-value :block ...}`), or a **set** (many values).
- **Encoding** `->block-properties` (`:78-90`) maps keys to idents and
  translates values (`translate-property-value`, `:37-56`); a **set** denotes a
  `:many` value (`:81-84`).
- **Ref value entities** `build-property-map-for-pvalue-tx` (`:106-130`) +
  `->property-value-tx-m` (`:172-189`) create the property-value block when the
  property type is a value-ref type, and resolve a closed-value id from
  `:build/closed-values` when `:property/closed-values` is present
  (`:181-185`).
- **Doc string** (`:1050-1055`): "The following property types are supported:
  :default, :url, :checkbox, :number, :node and :date. :checkbox and :number
  values are written as booleans and integers/floats. :node references are
  written as vectors e.g. `[:build/page {:block/title \"PAGE NAME\"}]`."
- Test fixtures exercise exactly this breadth (实测):
  `query_dsl_test.cljs:326-346` writes a closed-value `:status` as
  `:build/properties {:status "Doing"}` under a property whose config includes
  `:build/closed-values`, and `:tagz (set (map #(vector :build/page ...) tags))`
  (`:361`) writes a many-ref value.

**Conclusion (实测).** The `:build/properties` importer is **generic**, not
number-only. The number-only behavior is imposed entirely by
`resolve-numeric-properties` (tools.cljs:356-394), which filters and re-keys the
values *before* they reach the importer. So SCALAR/REF/TASK/LIST writes are a
**resolver** problem, not an importer problem. (Caveat: I verified this from the
source and from test fixtures, not by executing an import; 推断 that widening the
resolver to emit these shapes would work, because the same shapes are what the
importer and its tests already accept.)

### (c) What is the existing validation path for closed values, and where does it
### live?

The closed-value validation is a **tx-scoped, pre-transaction, malli
schema + membership** path (实测):

1. **Schema.** `deps/db/.../frontend/malli_schema.cljs`
   - `closed-value-block` / `closed-value-block*` (`:471-490`): a
     closed-value entity requires `:block/closed-value-property` and
     `:logseq.property/value [:or :string :double]`, plus block attrs.
   - `property-value-block` (`:428-435`): an ordinary (non-closed) property
     value entity requires `:logseq.property/value [:or :string :double
     :boolean]` and `:logseq.property/created-from-property`.
   - `entity-dispatch-key` (`:557-591`) routes an entity with
     `:block/closed-value-property` → `:closed-value-block` (`:581-582`).
2. **Membership check.** `validate-property-value` (`malli_schema.cljs:88-119`):
   when `closed-values-validate?` is true, the type is in
   `closed-value-property-types` (`property/type.cljs:30-32` =
   `#{:default :number :url}`), the value is not a *new* closed value, and the
   property has closed values, the value's id must be in the set of
   `:property/closed-values` eids (`:106-112`).
3. **Enforcement.**
   - `deps/db/.../sqlite/export.cljs:1396-1433` `validate-import-txs` →
     `validate-import-tx-data` (`:1453-1468`): dry-runs the tx
     (`d/with db tx-data`), then validates **only the touched entities** when
     `:validate-scope :tx`.
   - `deps/db/.../frontend/validate.cljs:130-160` `validate-local-db!`: builds
     entity maps, calls `update-properties-in-ents` (`:146`), and explains with
     `*closed-values-validate?* true` (`:149`).
   - Scope `:tx` is set by the MCP write path at
     `cli.cljs:176-178` (`:build-existing-tx? true` + `:validate-scope :tx`), and
     consumed in `deps/outliner/.../op.cljs:213-234`
     (`import-edn-data`: `build-import` → `validate-import-txs` → `transact!`).
   - The transaction only commits if validation passes
     (`op.cljs:227-231`), satisfying "reject before the transaction."
4. **Outliner-layer guards (separate, not in the MCP write path).**
   `deps/outliner/.../property.cljs:27-92`
   (`throw-error-if-read-only-property`, `throw-error-if-deleting-protected-property`,
   `throw-error-if-deleting-required-property`, `validate-batch-deletion-of-property`)
   protect built-in/protected properties during GUI property edits.

**Where it matters for the write tool (推断).** This tx-scope path is the
correct place that would accept a *correctly encoded* status ref and reject a
wrong/unknown/protected value. The current tool never relies on it for property
writes because `resolve-numeric-properties` throws on closed-value properties
first (`tools.cljs:385`). The gap is: (1) make the resolver emit the right
closed-value ref id, and (2) publish a support/reject table. The validation
machinery itself already exists and is wired into the import op.

---

## 3. Most important gaps (ranked)

1. **The resolver is the single bottleneck.** `resolve-numeric-properties`
   (tools.cljs:356-394) enforces user-only / number-only / single-valued /
   no-closed-values / not-hidden. Every non-number capability (SCALAR text/date/
   url, REF, TASK, LIST) is refused *here*, while the importer
   (`build.cljs`) already supports them. Fixing the resolver to emit the right
   `:build/properties` shapes is the core of the delivery.

2. **Page metadata is structurally absent from the write tool.** Page edit is
   hard-throw (tools.cljs:782-783) and page `:data` has no `:properties` key
   (add-non-block-schema, tools.cljs:628-631). PAGE-9 and the "person/paper
   metadata scenario" need a new page-property path, not just a resolver tweak.

3. **No verified receipt for property content.** The readback
   (read-upsert-blocks, tools.cljs:747-772) checks uuid/page/parent/title only;
   a property write cannot be `:verified`, and `receipt=true` + `:properties` is
   refused (tools.cljs:365-366). Until property values are read back and
   compared, RECEIPT-8 stays PARTIAL and agents must fall back to independent
   reads (plan §4B explicitly permits this for now).

4. **No public support/reject table in the shipped API.** Only a free-text
   description (mcp_server.cljs:187). CLOSED-9's "public support/reject table"
   and the plan's "first publish the exact accepted key/value/support table in
   the index" are unmet; agents cannot discover valid closed-value/reference
   identities without raw-db access.

5. **Hidden built-ins are invisible to reads.** `order-list-type` and other
   `:hide? true` properties are dropped by `remove-hidden-properties` on read
   (tools.cljs:64,150,263), so even a correctly written list property would not
   be read back through getBlock/getPage — the "readback returns a real list
   property" clause of LIST-4 needs a read-side decision, not just a write.

---

## 4. Commands run (real output)

```
$ cd D:/orca/workspaces/logseq/issue9-typed-properties && git log --oneline -5
f2958757d2 wip: support numeric MCP property writes
76c7a22327 wip: support parented MCP block writes
44ce435eab fix: keep local-only fixes on top of upstream
16c4ed1a04 refactor: share title-aware tag-ref matching between save and insert
2ef453b73a refactor: extract ref dedup fold from resolve-page-refs

$ wc -l src/electron/electron/mcp_server.cljs src/main/logseq/api/db_based/tools.cljs src/electron/electron/mcp_upsert.cljs
270 .../mcp_server.cljs
830 .../tools.cljs
8   .../mcp_upsert.cljs

# Grep for the :build/properties consumer (only tools.cljs produces it in src/main;
# the consumer is in the local deps):
$ grep -rn "build/properties" --include=*.cljs --include=*.cljc . | grep -v "/test/"
./deps/db/src/logseq/db/sqlite/build.cljs:116,138,140,157,241,258,294,301,305,312,313,386,484,492,493,513,527,573,574,589,623,634,636,640,643,691,692,703,704 ...
./src/main/logseq/api/db_based/tools.cljs:470,511   (producers only)
```

(All other file:line citations were obtained by reading the named files directly
with the read/grep tools; no output was fabricated.)
