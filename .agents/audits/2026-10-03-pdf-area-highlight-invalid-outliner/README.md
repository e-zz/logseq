# Audit: Logseq PDF area highlight `Invalid outliner data` (2026-10-03)

**Status: complete. No fix applied. No repo / installer / graph modified.**

## Files

| File | Role |
|---|---|
| `AUDIT-RESULT.md` | **AUTHORITATIVE.** Full auditor verdict + parent verification pass + corrections. |
| `AUDIT-BRIEF.md` | Input brief sent to the auditor. ⚠️ Contains **overturned** claims (C1, C3). Provenance only. |
| `AUDIT-RAW-gpt-6.1-sol.txt` | Raw auditor transcript (44,781 B, ANSI, 24m40s, 67 msgs / 65 tool calls). `.txt` not `.log` so the repo's `*.log` ignore rule stays intact. |

Auditor: `cpa/gpt-6.1-sol` (provider `custom:cpa`). Session `20261003_004012_3bd649`
(`hermes --resume 20261003_004012_3bd649`).

## One-paragraph finding

A PDF area highlight fails with `Invalid outliner data` (worker HTTP 500) because the
transaction's emitted block has **`:block/parent` nil**. The nil is produced by
`compute-block-parent` (`deps/outliner/src/logseq/outliner/core.cljs:587–590`) when a
**parentless journal page is used as a `sibling?` insert target**, and the guard at
`core.cljs:1082` then throws. It is **not** a nil `target-page` (the log's own
`:block/page 57693` disproves that) and **not** caused by Asset being judged non-`page?`.
**Which caller submits that illegal target relation is still not closed** — that is the open
gap. Do not patch `page?` and do not force `sibling? true`; both were audited as wrong/unsafe.

## Refs fixed by the audit

- fork HEAD `44ce435eab` · upstream/master `6f2c2a0f65` · 2.0.1 tag `26f6f7880b`
- Failing tx: `~/logseq/graphs/zotan/db-worker-node-20260924.log` line 244 (log ends at 245)

## Next step (as recommended by the auditor)

Capture the segment **raw op → resolved target/sibling → parent** around the failure. The
logger's `:opts` does not retain all entry parameters, so this likely needs a debug-level
reproduction. Only then decide between (a) fixing the caller's target/insert relation, or
(b) the worker-side defensive rejection at `core.cljs:1047–1048`.
