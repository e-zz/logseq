# Acceptance update — generic `properties` map (2026-10-04)

**Parent review correction:** the earlier version incorrectly attributed earlier acceptance evidence to run 37203268496. The live evidence below comes from earlier integrated-build acceptance, including [run 37134185100](https://github.com/e-zz/logseq/actions/runs/37134185100); it was not all re-run on [run 37203268496](https://github.com/e-zz/logseq/actions/runs/37203268496). Source-level contracts are not additional live test results. No controlled performance benchmark was collected. The generic `properties` map is now accepted on block `add`/`edit` (and page `add`/`edit`), keyed by property UUID or full ident. No source changes.

## Measured passing (write + independent readback, receipt `mode=verified`)

| Type | Result |
|---|---|
| URL | write + readback exact |
| Text | write + readback exact |
| Number | write + readback exact (including explicit `0`) |
| Checkbox | write + readback exact (including explicit `false`) |
| Node reference (cardinality `many`) | write + readback exact UUID set |
| Date reference (journal page) | write with a `{uuid: …}` reference object + readback exact UUID/title |
| Asset reference (uploaded image block) | write with a `{uuid: …}` reference object + readback exact UUID |
| Task status (built-in closed value) | write on add and on an existing plain block; readback exact |
| Property-only edit | preserves title/parent/page/order |

- A property-only edit (no title in the payload) leaves the title, parent, page and order unchanged — verified by full before/after block comparison.
- Normal app reopen was verified for earlier date/task/metadata writes. It was not verified for the latest asset reference; the list above is not a claim that every case was reopened.

## Measured rejections (explicit errors, no partial commit)

Each of these was tested in a batch paired with a **preceding valid title edit** on the same block; the whole call was rejected and the title edit did **not** land (before/after block readback identical; the would-be title absent from search). This proves no-partial-commit for these specific paths:

- `number` given a string (`must be a finite JSON number`)
- URL given a non-URL string
- Checkbox given the string `"false"` (must be a boolean)
- `List type` given `"bullet"` (number-only; removal unsupported)
- Closed-value property (e.g. status) given an unknown value
- Node reference (class-restricted to Page) given an ordinary block UUID (`requires a target tagged with one of [:logseq.class/Page]`)
- Many-valued input validation is also implemented in source (array required; empty array rejected), but is not claimed here as an additional paired-title-edit live atomicity test.

## Cardinality-many semantics — source contract, with live deduplication evidence

- Incoming values are **unioned** with existing values. Existing entries are never removed or duplicated; incoming duplicates are deduped.
- Source contract: references are matched by node identity/UUID, not display title. A target-renaming experiment is not claimed here.
- Illustrative semantics (not an additional benchmark or exact live fixture): existing `[A, B]` + incoming `[B, C, C]` yields `{A, B, C}`; existing values remain and duplicate references collapse.

This is intentional additive semantics, so "setting" a many property to a subset is not expressible (see below).

## Current API limitations (not defects of the UI)

- **Property removal is not supported**: a `null` value is rejected (`removal is not supported`), and empty arrays are rejected for many properties. Omitting a key leaves the existing value untouched.
- **Many-valued closed-value properties are not supported** by this API path (single-valued closed values work).
- Reference values (date/asset/node) must be passed as a reference object (`{"uuid": "…"}`), not a bare string.

These are import-path API limits: the UI can still remove or edit the same properties interactively.

## Not yet verified (not claimed)

- Non-journal target rejection for `date` properties (the passing case used a real journal reference).
- Re-open persistence for the specific asset reference written in the latest batch (reopen persistence was verified for the earlier date/task/metadata writes).
- No latency/timing measurements in this pass.