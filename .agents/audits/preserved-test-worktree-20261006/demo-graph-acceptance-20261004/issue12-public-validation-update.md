## Windows CI artifact validation update — partial acceptance

Build: [CI run 37203268496](https://github.com/e-zz/logseq/actions/runs/37203268496), commit `fb5eb4eb43cdb3be7a29d816b969d45c127d4861` (non-release test build).

### Measured results

The previous fork artifact shipped a `not built` CLI placeholder. The workflow now builds the real CLI and checks both its bundle and its final Windows x64 packaged entry point. Those CI checks passed.

The downloaded Windows x64 package was then exercised on an isolated disposable DB graph, using its own `Logseq.exe` with `ELECTRON_RUN_AS_NODE=1` and `resources/app.asar/js/logseq-cli.js`; no source recompilation or legacy npm CLI was substituted.

Test sequence:
1. Read the five database file entities: `logseq/config.edn`, `logseq/custom.css`, `logseq/custom.js`, `logseq/publish.css`, and `logseq/publish.js`.
2. Append distinct, harmless, syntactically valid comments using the packaged worker's `thread-api/transact` interface, not direct SQLite edits.
3. Independently read each saved value through `thread-api/get-file-content` before exercising CLI reopen.
4. Run packaged CLI `server stop --graph <test-graph>`, then `graph info --graph <test-graph>` to reopen it. Rediscover and verify the isolated CLI-owned worker and build revision before reading back.
5. Repeat the stop/open cycle a second time.

**Result: all five complete file contents and their SHA-256 hashes remained identical after both reopen cycles.** The probe exited successfully. A freshly created graph also contained all five default file contents, with a non-empty config template and empty initial CSS/JS values.

### Scope and outstanding acceptance

This establishes that the tested ordinary packaged CLI worker stop/open sequence did not overwrite agent-written file content. It does **not** establish the historical cause independently, nor prove that this newer worker lifecycle exercises the original `logseq.outliner.cli/init-conn` path reported above. The existing historical reproduction/patch evidence and this artifact test are separate evidence tracks.

Still not verified by this artifact test:
- Missing-file repair in an existing graph (fresh creation alone does not prove repair).
- Explicit Windows classpath lookup with multiple directories separated by `;`.
- GUI config reload/effective behavior, duplicate file-entity counts, unrelated block preservation, or crash durability.

**Leaving this issue open for the remaining targeted acceptance checks; not claiming complete resolution from the packaged CLI smoke/preservation test alone.**
