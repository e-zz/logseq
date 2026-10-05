# Finish full gate: exact measured failure, no external lint reads

Continue inside D:/orca/workspaces/logseq/issue9-typed-properties only. Preserve all edits. No external directories, commits/push/checkouts/reset/stash/clean/deletes/user graphs/delegation/self-event-log inspection. Last run aborted by reading ~/.gitlibs lint source. DO NOT do that again; lint output already identifies actual failures. Continue without asking authorization.

Parent verified B independently: LOGSEQ_STABLE_IDENTS=1 node static/tests.js -n frontend.worker.recycle-persistence-test =>4 tests/162 assertions,0 failures/0 errors. Parent ran bb dev:lint-and-test itself; exit1. Full log .agents/audits/2026-10-03-mcp/scratch/10-parent-full-gate.txt lines200-207 identifies FOUR >100-line vars:
- src/dev-cljs/electron/mcp_verify.cljs run-scenario! 184
- same file user-property-assertions 128
- src/test/frontend/worker/recycle_persistence_test.cljs user-property-persists-across-worker-restart 148
- src/main/logseq/api/db_based/cli.cljs upsert-nodes 128
Kondo errors0/warnings0; test groups shown green. Do NOT declare full gate green just because suites are green.

Minimal correction: extract cohesive helpers (setup/requests/assertion groups/receipt assembly) to bring EACH function below100 lines. Keep every existing assertion and ordering/error/finally cleanup behavior; no skipping test namespaces, no lint exemptions/config threshold changes, no dropping blank lines solely to evade lint. upsert-nodes was119 lines baseline but now128 due OUR changes, so refactor coherently rather than saying preexisting. Preserve default legacy return and receipt modes.

Run bb lint:large-vars and kondo targeted first. Compile fresh tests and HTTP harness. Re-run strict HTTP generic properties + negative control, real worker4/162 or greater with all tests still present, CLI/property/tools/issue6 namespaces separately. Then FULL bb dev:lint-and-test > scratch/10-full-gate-final.txt 2>&1 with actual exit captured (no tail pipelines masking failures). If failure exists diagnose it, don't stop by reading external task internals. No file deletions.

Update 10-generic-http-persistence-verification.md to FINAL exact measured results (its header says full gate running while §Full gate says never run; many table says B pending despite pass). Correct 09 original status-only acceptance claims. Record function refactor, full gate command/exit, support/reject and unverified Electron IPC/legacy getPage blocks UUID projection. Incrementally write report before last tool so another denial can't erase progress. No new features here. Finish real full gate and report now.
