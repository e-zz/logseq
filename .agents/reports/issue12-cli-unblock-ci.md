# #12 CLI artifact unblock CI run

Root cause confirmed in workflow: fork skipped OCaml setup/deps/cli:release and emitted not-built stub, then skipped CLI verification. Existing package was not runnable; not evidence of #12 implementation failing.

User approved enabling real CLI build and producing new non-release Windows package. Changed only .github/workflows/build-desktop-release.yml in dedicated worktree test/issues-mcp-20261003. Removed upstream-only CLI build gates and stub fallback; made bundle help gate universal. Added Windows x64 packaged CLI --help gate using packaged Electron with ELECTRON_RUN_AS_NODE=1. ARM64 bundle shares compile-stage CLI but final ARM64 binary execution not smoke-tested on x64 runner.

Local measured checks: git diff --check exit0; YAML parsed and structure assertions passed (CLI dependency/help steps ungated, real cli:release present, stub absent, packaged x64 check present). Narrow credential-pattern scan zero matches. No runtime source files modified; unrelated local audit/scratch files not staged.

Base eb69c1bd179fa4582ff1f59506d43338dfab9190. One source commit fb5eb4eb43cdb3be7a29d816b969d45c127d4861, ci: build real CLI in fork desktop artifacts. Exact remote ref refs/heads/test/issues-mcp-20261003 on https://github.com/e-zz/logseq.git read back matches local SHA. No main/tag/release/install/current-app changes.

Dispatched workflow build-desktop-release.yml with ref/git-ref test/issues-mcp-20261003, build-target non-release, windows-only true, build-android false, publish-linux-stores false, is-draft true, is-pre-release true, enable-file-sync-production true, enable-plugins true. Readback run37203268496 headSha exactly matches source, initially queued.
URL https://github.com/e-zz/logseq/actions/runs/37203268496
Background completion watcher proc_a056b10c7a0b exited0. Fresh gh run view confirms completed/success and exact headSha fb5eb4eb43cdb3be7a29d816b969d45c127d4861. Compile CLJS, Verify bundled CLI and Windows x64 Verify packaged CLI all passed. Both Windows builds succeeded; release/nightly/store jobs skipped. Fresh artifacts API confirms nonexpired x64 artifact11304481011 (348706440 bytes), arm64 artifact11303583378 (341580985 bytes), static11303902343. The missing/nonrunnable CLI artifact blocker is resolved at CI x64 smoke-test level; #12 file preservation/missing-default acceptance still pending. Local download/install and original-user graph test not performed yet. x64 URL https://github.com/e-zz/logseq/actions/runs/37203268496/artifacts/11304481011
