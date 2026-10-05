# #12 current packaged worker: missing file follow-up

## 实测结果
Package track: non-release CI 37203268496; exact CLI-owned worker metadata revision fb5eb4e and isolated root verified before writes and after reopen. No current GUI graph accessed.

1. Before removal, all five file contents/hash values matched historical saved data; actual packaged CLI Datascript query returned one file entity per expected path.
2. User explicitly authorized removal of ONLY the isolated graph's `logseq/custom.css` file entity and normal isolated worker stop/open. Full before contents were saved before removal.
3. Supported runtime `thread-api/transact` retracted that exact file/path entity; immediate independent reads/count query confirmed its absence and the other four files unchanged.
4. Official packaged `server stop --graph issue12-preservation`, then `graph info --graph issue12-preservation` opened a new isolated worker successfully.
5. After reopen, `get-file-content(custom.css)` is null/nil and the file/path entity is absent from count query. Remaining four full contents and existing UUID/title query rows match the pre-removal values.

**FAIL against the missing-default recreation acceptance criterion on this packaged worker path.** Other-four preservation and UUID/title preservation are PASS. Do not collapse these into an overall PASS.
Fixture intentionally remains partial; no automatic restoration. No disk file deleted, no GUI restart/kill, no direct SQLite modification.

## 源码核验 / 推断
`src/main/frontend/worker/db_core.cljs:774-791` uses an existing-initial-data check / supplied datoms / sync-download condition to gate initial-data construction. Existing graph opening can skip `build-db-initial-data` even when one default file is absent.
推断：this explains the observed failure of worker reopen to recreate the missing file. The source branch was not instrumented in the packaged runtime; it is not a measured branch trace.
`deps/outliner/src/logseq/outliner/cli.cljs:38-74` is a DIFFERENT legacy init-conn path that gathers existing file paths and calls initial-data creation on opening. This runtime test does not execute or falsify that legacy initializer's repair logic.

## Remaining boundaries
- Legacy init-conn and Windows semicolon-separated classpath remain NOT EXERCISED through current package. Known source callers/tests are not packaged-runtime acceptance substitutes.
- No new dedicated unrelated canary block was created; preservation check is actual existing UUID/title rows, not invented canary coverage.
- Probe originally emitted exit 0 despite result FAIL because it logged acceptance outcomes without final nonzero exit. The saved JSON was independently inspected and failure was reported immediately. Probe now exits 1 on non-PASS; it was not rerun against the already-partial fixture merely to obtain an exit code.
- No implementation repair or public issue update performed in this follow-up.

Evidence: `issue12-readonly-followup.json`, `issue12-missing-file-probe.py`, `issue12-missing-file-results.json`. These are local evidence; private paths/graph names must be redacted from public comments.
