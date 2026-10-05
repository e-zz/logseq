# GUI search upstream compatibility: independent read-only audit

## User acceptance contract
User asks only to ensure our fork did not break upstream features. Concrete observed symptom: old merge-all-fixes Cmd-K shows containing-page title/path for ordinary block results, and Recycle as the containing page for a recycled page; build B no longer shows these. Do not conflate missing label with missing result. Do not ask user to choose implementation methods. Audit before repair.

## Exact refs
- Current private build B: fb5eb4eb43cdb3be7a29d816b969d45c127d4861 (CI37203268496).
- Same upstream anchor U: 22a29b30dee3b3930cf49bba50454650c31d2a07. Parent confirmed merge-base(U,B)=U.
- Old observed build A: bb70c73d29352c85473bdc3bba2bd37d1f2abe72.
- Later search option patch C: 7b1ae92ee1eb1679b8bb827d12d7e76e22d4d5c5. B predates C, so C cannot be the change newly introduced into B. This does NOT exclude other fork changes or upstream migration effects.
- Checkout: D:/orca/workspaces/logseq/issues-human-test-20261003. Read pinned refs via git show, not mutable HEAD. HEAD includes later patch and audit docs. Existing untracked artifacts belong to other work; do not modify.

## Independence and safety
Read-only source review. Do not read previous worker/parent search reports or transcripts: derive findings independently. Read AGENTS.md, prompts/review.md and repo review skill as needed. No subdelegation, source edits, commits, installs, package rebuilds, process termination, CLI/server launches, live graph reads/writes, MCP calls, API writes or issue closures. Only new audit artifacts under .agents/audits/gui-search-upstream-compatibility-independent/ are allowed.

## Scope and required work
1. Verify refs, ancestry and git status. Inspect full U..B search-affecting delta, including listener, API, worker index/enrichment and recyclable entity helpers; do not limit to UI files alone.
2. Trace Cmd-K input -> worker search -> returned block/page/title/breadcrumb fields -> renderer -> containing-page label -> navigation. Find render guards; name exact symbols and line numbers at pinned refs.
3. Compare U and B exact functions. Treat U source match only as source equivalence, NOT runtime PASS. Compare A to U separately for upstream changes with named commits, without declaring attribution from commit messages alone.
4. Trace app API / MCP publication and prove or disprove any actual consumer connection to Cmd-K. publish-result? is an option, not a function. Avoid attributing label disappearance to it without a consumer path.
5. Identify concrete fork regression candidates, upstream candidates, and missing runtime evidence. Report confirmed differences separately from inference. Broad absence of regressions cannot be proved by static analysis.
6. Return minimal controlled GUI test acceptance requirements for U vs B (not merely A vs B). No test execution touching existing graphs.

## Deliverables
A short Chinese review.md and raw scoped git diffs under audit directory. Explicit verdict NOT VERIFIED/PARTIAL/FAIL as warranted; no invented PASS. Evidence categories: 源码核验 (direct source), 推断 (support and limitations), 未验证 (runtime). Include exact paths/symbols/refs and changed paths; return artifact absolute paths for parent verification. Focus on evidence, not a rewrite or suggested new features.
