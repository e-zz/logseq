# Topic migration acceptance

Pinned upstream main: `b517311813`. Source: `merge-all-fixes@548ee5a3fe`. Original private tip: `e9045eac8b`.

All 28 source commits accounted for exactly once in CLASSIFICATION.json; 27 source effects covered by 10 canonical topic commits, 1 replaced by upstream MCP `:validate-scope :tx` validation. PDF topics form a three-commit dependency chain; seven non-PDF topics branch directly from main. No failed retry commits or unrelated brief directories integrated. Private history retained, upstream main merged, then topic branches merged without force/reset.

## Parent execution verification (实测)
- Frontend selected PDF/assets/editor suites: 147 tests / 385 assertions / 0 failures / 0 errors; test compilation completed successfully.
- Import exporter suite using explicit LOCAL db/common/outliner source classpath: 73 tests / 1289 assertions / 0 failures / 0 errors. Initial NBB packaged dependency resolution failed on missing new order helper; corrected execution classpath rather than code. Child 71-test reports do not describe this integrated run.
- External protocol allowlist: 9 Node assert checks passed.
- `git diff --check` passed. Independent source review found no blocking defects (review finding, not runtime proof).

## Preserved limitations
- Area-image persistence restores state and notifies on failure but rethrows; some no-color callers can emit unhandled rejection. Not newly corrected here.
- Active editor block can override PDF hls save-to-page target; existing semantics preserved.
- Fork CI builds unsigned, skips Sentry, and uses an explicit failing CLI stub. Desktop artifact does NOT include functional standalone CLI.
- No GUI retest or installer replacement performed. Selected suites are not the entire repository test suite.
- Historical guides describe old merge-all-fixes and are not the new migration ledger.

## CI
Parent will dispatch Build-Desktop-Release on priv with non-release, Android and store publishing disabled. No official release or upstream PR requested. Run URL and outcome will be recorded after actual execution.
