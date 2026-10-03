# CI verification (实测)

Build: https://github.com/e-zz/logseq/actions/runs/37096602606
Built source revision: `7c5864f944a90eb4983c7445c9d2246b3f1935a0`.
GitHub API conclusion read back: `success`.
Compile CLJS and all six desktop packaging jobs (Windows/Linux/macOS, x64/arm64) succeeded. Android, release/nightly release and store publishing were skipped as requested.

API verified seven non-expired artifacts, including the intermediate static artifact:

| Artifact | ID | Bytes |
|---|---|---|
| logseq-win-x64-builds | 11263914834 | 347251702 |
| logseq-win-arm64-builds | 11263929772 | 340125088 |
| logseq-linux-x64-builds | 11264548676 | 352515844 |
| logseq-linux-arm64-builds | 11264014411 | 352787026 |
| logseq-darwin-x64-builds | 11264672155 | 330480837 |
| logseq-darwin-arm64-builds | 11263969476 | 346712575 |
| static | 11264417251 | 64174453 |

Initial run 37096493140 failed before compilation: actions/checkout treated the supplied short SHA as a branch/tag name. Dispatching the full 40-character SHA resolved checkout; no source change was needed.

This verifies CI compilation, packaging jobs and uploaded artifact existence. It does not verify GUI operation or the contents of downloaded archives; no installed application was replaced. Fork artifacts are unsigned and contain the documented nonfunctional CLI stub.
