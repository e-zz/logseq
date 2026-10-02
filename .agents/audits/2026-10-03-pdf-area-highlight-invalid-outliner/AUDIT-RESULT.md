# Audit result — Logseq PDF area highlight `Invalid outliner data`

Date: 2026-10-03
Repo under audit: `D:/Action/logseq` (fork @ `44ce435eab` "fix: keep local-only fixes on top of upstream")
Auditor: `cpa/gpt-6.1-sol` (custom:cpa, 127.0.0.1:8317), 24m40s, 67 msgs / 65 tool calls
Auditor session: `20261003_004012_3bd649`  (`hermes --resume 20261003_004012_3bd649`)
Input brief: `audit-invalid-outliner-brief.md` (parent-written, contains 2 false claims — corrected below)
Raw log: `sol-audit.log` (44,781 bytes, ANSI)
Parent: Hermes main agent (this session)

Comparands (fixed by auditor via `git show`):
- fork HEAD      `44ce435eab2d871508537abf6758c4deadf082ce`
- upstream/master `6f2c2a0f659a0b5b1155d31fa42811edb1de0588`
- 2.0.1 tag       `26f6f7880b1ec894871a9ec2c03bb97b954b4cb0`

Scope limits stated by auditor: read-only; no repo/installer/graph modification; no GUI
reproduction. E1 (failure transaction) and E9 (asar unpack) were supplied by the parent and
were NOT independently re-fetched by the auditor.

Abbreviations: `core.cljs` = `deps/outliner/src/logseq/outliner/core.cljs`;
`editor/assets.cljs` = `src/main/frontend/handler/editor/assets.cljs`;
`pdf/assets.cljs` = `src/main/frontend/extensions/pdf/assets.cljs`.

Line-number caveat: the same throw condition lives at `core.cljs:1082` (fork), `:1130`
(upstream/master), `:777` (2.0.1). The parent brief mixed these; unversioned line numbers
below mean the fork.

---

## 1. Verdict on the parent's claims

**C1 不成立 / C2 部分成立 / C3 不成立 / C4 部分成立 / C5 部分成立 / C6 不成立**

### Headline correction

> E1 already carries `:block/page 57693`. The nil is **not** `target-page`; it is
> **`:block/parent`**. The direct failure mechanism is locatable in source, but *which caller
> passes the journal page as a sibling target* is not yet closed.

### C1 — 不成立 (parent's root-cause mechanism was wrong)

Two direct counter-evidence points:

1. `get-target-block-page` does **not** merely take `(:block/page target-block)` —
   `core.cljs:709–720` ends with an explicit fallback to `(:db/id target-block)`:
   ```clojure
   (or (:db/id (:block/page target-block))
       (when sibling?
         (when-let [parent (:block/parent target-block)]
           (when (ldb/page? parent) (:db/id parent))))
       ;; target-block is a page itself
       (:db/id target-block))
   ```
   The target journal page has `:db/id 57693`, so `target-page` = 57693 — matching the
   tx's `:block/page 57693`. (Parent independently re-verified this at `core.cljs:709-720`.)

2. The throw condition checks **only parent/order**, not page — `core.cljs:1082–1087`.
   Even if some other tx had a nil page, that would not explain this exception.

**Corrected mechanism** (auditor: 推断·条件推导，未重现; parent concurs, source-verified):
- input block is top-level;
- resolved target is a journal page with **no parent**;
- resolved `sibling?` is **true**;
- `compute-block-parent` takes `(:db/id (:block/parent target-block))` → **nil**.

Evidence: `core.cljs:577–593` (esp. `:587–590`), `core.cljs:756–768` (parent written into tx):
```clojure
top-level?
(if sibling?
  (:db/id (:block/parent target-block))   ;; journal page has no parent ⇒ nil
  (:db/id target-block))
```
2.0.1 equivalent logic: `core.cljs@2.0.1:420–436`, `:555–568`.

Asset being non-`page?` is **normal behaviour of the current data model**, not the misjudgment
to fix: `deps/db/src/logseq/db/frontend/entity_util.cljs:24–56`, `editor/assets.cljs:74–82`.

Conclusion must be reworded as: **"a journal page was used as a sibling insert target, making
parent compute to nil"** — NOT "Asset wasn't recognised as page, making target-page nil".

### C2 — 部分成立 (plugin exclusion is too strong)

Native area-highlight chain (实测·源码):
`pdf/assets.cljs:140–148` → `editor/assets.cljs:84–149` →
`src/main/frontend/modules/outliner/op.cljs:42–46` →
`deps/outliner/src/logseq/outliner/op.cljs:236–240` → `core.cljs insert-blocks`.

That direct call does not pass through `logseq-pdf-extract`, and the plugin is not the place to
fix a `core.cljs` parent-computation defect. **But** "改插件无效" overstates it: E1's `:method` is
only the generic `apply-outliner-ops`, not a full call stack, so it cannot by itself exclude the
plugin having earlier constructed data or submitted a separate transaction. More importantly,
E1 is a **PDF-typed asset with a PDF external-url**, whereas the native area-highlight entry
constructs `"pdf area highlight.png"` — E1 being *that* PNG-creation tx is **not proven**. The
plugin-exclusion conclusion must be limited to the confirmed native direct chain.

### C3 — 不成立 (the MCP attribution was wrong; parent had already half-retracted it)

All three supposed "MCP fingerprints" are explicable natively:

- **`size 0`** — `editor/assets.cljs:43–51`: a string `src` becomes `external-url`, `file` is
  nil, size defaults to 0. The native asset function produces this on its own.
- **no `:block/name`** — `editor/assets.cljs:74–82`: native `new-asset-block` simply does not
  return `:block/name`.
- **tags keyword-set → full entities** — `core.cljs:383–397`
  `remove-disallowed-inline-classes` expands qualified-keyword tags via `d/entity`. E1 has
  `blocks` = `#{:logseq.class/Asset}` and `tx` = the full Asset entity; exactly this native
  conversion.

Also: the checksum does not prove MCP either — for a string it is SHA-256 over UTF-8 encoded
text, no PDF read required (`src/main/frontend/handler/assets.cljs:217–220`,
`deps/db/src/logseq/db/frontend/asset.cljs:12–19`). So E1's checksum must not be treated as a
PDF-content digest.

MCP write is **one candidate source, not a proven one**; it would need an MCP write record
matching that UUID and payload.

### C4 — 部分成立 (the git diff is real; the history reading is backwards)

The code difference exists, but "this is the fork's Asset→today fix" is **false**. Actual
direction:

- **2.0.1 already used the today fallback**: `src/main/frontend/handler/editor.cljs@2.0.1:1488–1521`
- **the fork still uses the today fallback**: `editor/assets.cljs:105–109`, `:121–132`
- **upstream commit `e9fd02c8be` (2026-10-01) changed today→Asset class page**:
  `editor/assets.cljs@upstream/master:105–106`, `:128–129`

That commit is in upstream/master, **not** in fork HEAD, **not** in 2.0.1. So
`git diff upstream/master HEAD` showing "+today" must NOT be read as the fork having performed
a fix.

Neither fallback fails merely for lacking `:block/page`: a valid journal page or an Asset class
page, once resolved to an entity in the worker, both fall back to their own id in
`get-target-block-page` (`core.cljs:709–720`). Must also distinguish "Asset **class** entity"
from "asset instance carrying the Asset tag" (`entity_util.cljs:28–30`, `:45–56`).

[推断·边界] The fork does fail to prevent "parentless page + sibling true" producing a nil
parent — but that gap is **not** caused by `page?` not counting Asset. And "edit-block without
`:block/page` will still throw" is also false: throwing depends on parent/order; a missing page
may cause wrong page ownership but is not sufficient for this exception.

### C5 — 部分成立

2.0.1 keeps the same parent computation and validation (`core.cljs@2.0.1:420–436`, `:777–783`).
Fork and upstream/master retain the same parentless-sibling handling. One **cannot** say
"2.0.1 unfixed, fork fixed".

[推断·部署] Editing the plugin or `D:/Action/logseq` sources does not replace the running
installer — that part holds. But "the installed app will still error" must be limited to: it
still **contains a code path that can trigger** this error. Not every area highlight necessarily
fails; a legal child insert, or a sibling insert with a parent, will not trip this condition.

Also: E9's "`existing-page` 0 hits" is only auxiliary evidence — minified JS may drop local
variable names, so a text count cannot prove a code path absent. The explicit 2.0.1-tag source
comparison is the stronger evidence.

### C6 — 不成立 (both proposed fixes are wrong or unsafe)

**(a) treat Asset / all class-tagged objects as `page?`**
[实测·源码] This only changes whether `:block/page` is retained — `core.cljs:724`, `:739–741`.
`parent` has already been computed and written earlier (`core.cljs:757`, `:765–768`). So it
cannot fix E1's nil parent, and is more likely to strip page ownership from otherwise-fine asset
blocks.

**(b) force `sibling? true` for `:pdf-area?`**
[推断·源码支撑] If the resolved target is still a parentless journal page, this lands squarely
on the nil-parent path rather than fixing it (`core.cljs:587–590`). Explicitly choosing an
ordinary block with a parent can avoid the immediate error when conditions hold, but it changes
insert location — and "not a page" does not guarantee a parent, a valid order, or a correct page.
Not an unconditional safe fix.

---

## 2. Paths and causes the parent missed

### 2.1 E1 is not aligned with the native PNG-creation path

[实测·源码] The native area-screenshot entry constructs the File at `pdf/assets.cljs:140–143`.
`new-asset-block` for that File should yield **PNG type, File size, and no external-url**
(`editor/assets.cljs:43–51`, `:61`, `:74–79`). **E1 instead is PDF type, PDF external-url,
size 0.** [推断] At least one more explanation is missing: either the log belongs to a
**different** external-PDF create/reinsert transaction, or some same-UUID entity merge retained
old fields. The two transactions must not be assumed identical just because the user saw the
error when clicking an area highlight.

### 2.2 `sibling?` is recomputed — the boolean alone is not enough

[实测·源码] `get-target-block` recomputes target and `sibling?`: `core.cljs:846–893`.
Bottom insert: with a last child → `[last-child true]`; without children → `[page false]`
(`:880–883`). The exception's `target-block` is the **re-resolved** target
(`core.cljs:1047`, `:1083–1087`). So "caller said sibling false, worker flipped it to true"
does not adequately explain E1: on a normal bottom-recompute to true, the exceptional target
should be that child block, not a parentless journal page. To fully explain E1 one needs the
**original** outliner op including `bottom?`/`top?`, plus target before/after `get-target-block`;
the exception's `insert-opts` does not retain all entry parameters.

### 2.3 Can `existing-page` run outside paste?

**[排除·源码]** No — the branch requires `(= :paste outliner-op)` **and** `(:block/name block)`
(`core.cljs:727–729`). E1 is `:insert-blocks` with no name; 2.0.1 has no such branch at all.
Even in other paste transactions it uses the already-computed `parent` (`core.cljs:731–734`) —
it cannot backstop a nil parent.

### 2.4 Does `merge entity block` revive title / clear page?

[实测·源码] Merge order: existing entity → normalized raw-title/title → input `b`
(`core.cljs:1024–1041`). So: absent title in input can bring the old title back; a provided
title overwrites; an explicit `nil` field in input can also overwrite. Merge itself does not
unconditionally clear page — page is then reassigned or dissoc'd in `build-insert-block-tx`
(`core.cljs:739–741`), and parent/order are subsequently overwritten by computed results
(`core.cljs:765–771`). [推断] Same-UUID reuse is worth auditing but is **not** a proven cause
of E1. In particular, check whether an empty edit block exists during area screenshot, since
the asset UUID-reuse condition does not exclude `pdf-area` (`editor/assets.cljs:99–103`, `:117–118`).

### 2.5 Does `assign-temp-id` overwrite parent?

**[排除·源码]** No. It modifies `db/id`; when replacing an empty target it additionally modifies
uuid/order (`core.cljs:548–563`). And it runs **after** the `Invalid outliner data` validation
passes (`core.cljs:1082–1088`), so it cannot cause this already-thrown exception.

### 2.6 `order a0` does not imply an undo restore

[实测·日志] E1 has `keep-block-order? nil`. [实测·源码] A normal delete-restore constructs an
insert op that explicitly carries `keep-block-order? true`
(`deps/outliner/src/logseq/outliner/op/construct.cljc:767–775`). So "a0 must mean preserved old
order, hence undo" does not hold; and when the target page has no order, `start-order` cannot be
assumed to be `a0`. [推测] Other callers directly submitting, history/sync replay, or the target
being deleted/changed during the async save are all entry candidates — current evidence cannot
pick one.

---

## 3. Fix evaluation

**Recommendation: correct the target/insert relation actually submitted by the tx; do NOT change
Asset's `page?` semantics.**

[推断·工程建议] Capture the original op first. The native PDF save code already sends
`bottom true` and normally `sibling false` (`editor/assets.cljs:141–144`). If the real error
comes from another create / reinsert / replay path, continuing to edit this call — which already
correctly expresses "insert as a child of the page" — would fix the wrong place.

Once the caller is identified:
- for a root page, express a **child** insert;
- for an ordinary block, allow sibling insert **only after confirming a valid parent**;
- do not substitute "has a db/id" for validating the parent/page relation.

[推断·防御建议] The worker could, **after `get-target-block` resolution and before generating
orders/parent**, explicitly detect "root page + sibling true", refuse, and report both raw and
resolved parameters. Location: `core.cljs:1047–1048`, `:743–747`. If product decides to
normalize such requests into child inserts, it must normalize target/sibling **together** before
computing orders — never arbitrarily fall back on parent inside `compute-block-parent`, or parent
and order would be generated from two different insert relations. **This is a semantic-changing
fix, unverified; it must not be called a safe patch yet.**

On `today-page`: [实测·源码] it is not "pub-event! only fires and does not wait" — the current
implementation returns a deferred and the event result goes into a Promise
(`src/main/frontend/state.cljs:1108–1111`, `src/main/frontend/rfx.cljs:170–177`,
`src/main/frontend/handler/events.cljs:67–74`, `:168–171`). But the return value is not
guaranteed to always be a page: `create-today-journal!` skips in
loading/importing/publishing states, and when its re-query finds an existing page it has no
branch returning that page (`src/main/frontend/handler/page.cljs:334–351`). [推断·工程建议] A
safer caller-side handling: await creation if needed, then re-read by journal-day and validate
an effective target — rather than treating the create event's return value as necessarily a
valid entity. This gap is **separate** from E1: a nil today would first trip
`editor/assets.cljs:133–136` "invalid target", whereas E1 already has a resolved journal page.

Regression scope: [推断·风险] C6(a) affects all ordinary assets / custom-tagged objects passing
that builder — do not adopt. C6(b) may change PDF image hierarchy or insert order. Changing
global `core` affects direct insert, copy/paste, templates, and op replay. Touching only the DB
asset call site is narrower in scope, but that does not license claiming file-graph regression
freedom.

Independent issue — **orphan files**: [实测·源码] file is written before the tx commits
(`editor/assets.cljs:70–73`, `:112–119`, `:139–144`). [推断] A failed tx can indeed leave a new
file behind, but proving a given PNG belongs to this failure requires matching the filename UUID
to the failed tx. A failure-file retention/recovery mechanism should be designed; do **not**
bulk-delete without verification.

---

## 4. Not proven / what GUI reproduction must capture

Not proven:
- [未证明] E1 **is** the native area-PNG creation tx.
- [未证明] that PDF asset was created by MCP.
- [未证明] whether that asset already existed before the failure or was created in it.
- [未证明] **which** caller produced "resolved page + sibling true".
- [未证明] whether the production installer matches any local fork build.
- [未证明] that any historical fork PDF fix caused this production failure.
- [未验证] the suggested fixes are regression-free in GUI, replay, and file graphs.

GUI/repro must capture:
1. executable, `app.asar` path, and version of the running process.
2. edit-block UUID before the click, its content, whether empty block, whether unsaved changes.
3. current PDF asset UUID and the new PNG UUID.
4. `db-based-save-assets!` inputs: file/map, `pdf-area?`, explicit target/save-to-page.
5. all opts of the original insert op.
6. target UUID/parent/page/order/`sibling?` before and after `get-target-block`.
7. asset query results before/after failure, and the new file path.

[实测·时间换算] E1's `1790957758186` =
- UTC: 2026-10-02 16:15:58.186
- Beijing: 2026-10-03 00:15:58.186

The target is **Sep 18th, 2026**. Normal `today-journal-day` reads the current Date
(`src/main/frontend/date.cljs:65–67`). [推断] This target must not be called a "same-day
fallback" without explanation; a timezone shift does not account for the nine-day gap. Needs the
explicit target, the raw op record, the running build, and the state at that time.

---

## 5. Final evaluation of the fork's historical fixes

[实测·源码/Git] What can now be **excluded**:
- "Asset→today was a fork fix for nil page" (this historical explanation);
- "Asset's `page?` false is the E1 root cause" (this code explanation);
- "`existing-page` or `assign-temp-id` caused E1" (both specific explanations).

[推断] The current fork, given a valid target and a child-relation insert, will not necessarily
fail merely because Asset is not a page; but it still lacks explicit handling for illegal
root-page sibling requests, and carries independent gaps in `today` creation return value and in
file/tx non-atomicity.

[未证明] None of these facts suffice to blame the production failure on the fork's historical
PDF fixes. What is located is the **direct failure condition inside the shared outliner logic**;
what is still missing is **the real entry point that produces that illegal target relation**.

**Next step**: capture, around E1, the segment "raw op → resolved target/sibling → parent".
**Do not** first change Asset's type judgment, and **do not** force `sibling true`.

---

## 6. Parent's own corrections (post-audit, source-verified by parent)

### 6.0 Parent's verification pass — all audited citations independently re-checked

Parent re-read every load-bearing anchor in the fork (`D:/Action/logseq`) after the audit:

| Audit citation | Parent re-check | Verdict |
|---|---|---|
| `core.cljs:709–720` fallback `(:db/id target-block)` | present verbatim | ✅ confirmed |
| `core.cljs:1082` throw checks parent/order only, not page | `(some (fn [b] (or (nil? (:block/parent b)) (nil? (:block/order b)))) blocks-tx)` | ✅ confirmed |
| `core.cljs:577–593` `compute-block-parent`, nil at `:587–590` | present verbatim | ✅ confirmed |
| `core.cljs:756–771` computed parent written into tx | `m {:db/id … :block/parent parent :block/order order}` | ✅ confirmed |
| `entity_util.cljs:24–56` `page?` excludes Asset | `page? = (or internal-page? journal? class? property?)`; Asset only has `:logseq.class/Asset` ⇒ false | ✅ confirmed |
| E1 op is `:insert-blocks` | log grep: only one `:outliner-op :insert-blocks` | ✅ confirmed |

### 6.0.1 NEW finding — the `assert` at `core.cljs:758` is dead in the shipped build

`core.cljs:758` carries a **live guard that should have fired first**:
```clojure
_ (assert (and parent order) (str "Parent or order is nil: " {:parent parent :order order}))
```
It exists identically in upstream/master (present) and 2.0.1 (`core.cljs@2.0.1:559`); the fork
`git diff upstream/master HEAD` for this file shows **no** assert-related hunk — it is **not** a
fork addition.

**But the installed 2.0.1 bundle does not contain it.**
[实测·bundle] `db-worker-node.js` (extracted from the installed
`…/Logseq-win-x64-2.0.1/resources/app.asar`):
- `"Parent or order is nil"` → **0 occurrences**
- `"Invalid outliner data"` → **1 occurrence**

So in the shipped build the `assert` was compiled out (standard ClojureScript
`*assert* false` release compilation), and the **`ex-info` throw at `core.cljs:1082` is the
first and only observable failure**. This removes the apparent contradiction between "an
assertion on the same nil should fire first" and the log message actually observed — the
`assert` is not there to fire.

⚠️ This does **not** reopen the parent's C1: `assert`/throw both key on `:block/parent`, still
consistent with the auditor's corrected mechanism (nil parent, not nil target-page).

Note on evidence strength: the 0-hit grep for `"Parent or order is nil"` is weaker than a source
comparison would be (minifiers can drop strings only when unused — here the string would survive
if the assert survived, so 0 hits is reasonably strong, but the auditor's caveat about
minified-JS text counting as auxiliary evidence still applies). The one unambiguous fact is that
the observed failure is the `Invalid outliner data` throw, which the auditor's mechanism explains.

### 6.1 Overturned parent claims

The parent's brief carried claims the auditor overturned; the parent independently re-read the
source and confirms the auditor:

1. `core.cljs:709–720` — third fallback `(:db/id target-block)` exists ⇒ `target-page` = 57693,
   not nil. The log's own `:block/page 57693` is the counter-evidence. **Parent's C1 was wrong.**
2. `core.cljs:577–593` — nil originates in `compute-block-parent` under `top-level? + sibling?`.
3. MCP attribution (parent's C3) — retracted; the three fingerprints are natively producible.
4. Parent also retracts the earlier session claim that "all stack frames are the installed 2.0.1
   ⇒ fork not running" as *sufficient* on its own — the auditor's C5 limits it to "the installer
   contains a triggering path", and notes minified-JS text counting is weak evidence.

Status of the audit: **complete**. No fix applied. No repo/installer/graph modified.
