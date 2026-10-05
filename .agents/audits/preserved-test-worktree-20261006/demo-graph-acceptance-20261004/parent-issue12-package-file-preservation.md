# #12 packaged CLI file preservation

## Measured scope
- Package: C:/Users/zhang/Downloads/logseq-ci-37203268496-x64/Logseq-win-x64-2.0.2/Logseq.exe, using resources/app.asar/js/logseq-cli.js under ELECTRON_RUN_AS_NODE=1.
- Isolated root: C:/Users/zhang/AppData/Local/hermes/cache/scratch/logseq-issue12-ci37203268496; graph issue12-preservation. No user graph or current application changed.
- Packaged worker /health/server metadata verified owner-source cli, revision fb5eb4e and exact root before each probe.
- Agent wrote database file/content through actual packaged worker thread-api/transact. File identities resolved using db/id lookup refs to file/path. This is a runtime transaction, not direct SQLite editing, MCP upsert, source compilation, or legacy npm CLI.
- Wrote valid comments appended to existing config.edn/custom.css/custom.js/publish.css/publish.js contents. All five saved values independently read back through thread-api/get-file-content before reopening.
- Two cycles: packaged CLI server stop --graph, then graph info --graph to start/open graph again; rediscover correct runtime; reread all five file entities. Normal shutdown only of the isolated CLI-owned worker.
- Full content equality and SHA256 equality passed for all five files after both cycles. Script exit 0; evidence JSON independently parsed and equality asserted by parent.

## Interpretation and limits
- Measured: these five agent-written contents survive two ordinary new-package CLI worker stop/open cycles. No overwrite reproduced on this tested sequence.
- First fresh CLI-created graph had all five default file contents present, config from a full template and the other four empty; proves fresh initialization only.
- Does NOT establish historical root cause or coverage of logseq.outliner.cli/init-conn. That is a separate legacy/direct initialization path; ordinary new CLI uses db-worker lifecycle. Passing this sequence cannot prove the historical agent workflow also safe.
- Not tested: current GUI graph effective UI config reload, missing-file repair on existing graph, explicit two-directory Windows classpath, duplicate file entity counts, unrelated block preservation, crash durability.
- Initial probe halted before writes because Transit top-level scalar quote wrapper was not decoded. Harness fixed to unwrap it, then rerun succeeded. That initial harness failure was not a Logseq failure.

Evidence: parent-issue12-package-file-probe.json; reproducible probe issue12-package-file-probe.py (appends harmless additional comments on rerun; isolated graph only).
