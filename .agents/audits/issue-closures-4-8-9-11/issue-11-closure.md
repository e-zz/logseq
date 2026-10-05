## Resolution: newly MCP-written content is searchable

Closing as completed with maintainer approval for the original defect: fresh active content written through MCP was absent from title search and autocomplete unless an unrelated action rebuilt/refreshed the index.

### Earlier measured evidence
The parent-reviewed earlier packaged acceptance under [run 37134185100](https://github.com/e-zz/logseq/actions/runs/37134185100) covered same-session add/search, title-edit/search freshness and search results after normal reopen. These are not being relabelled as reruns on a newer package.

### Current-package follow-ups (measured + user-observed GUI)
On the [run 37203268496](https://github.com/e-zz/logseq/actions/runs/37203268496) acceptance track, source `fb5eb4eb43cdb3be7a29d816b969d45c127d4861`:
- After a user-confirmed normal application restart, a distinct fresh page was added through native MCP. The user confirmed it appeared as an existing-page candidate in `[[...]]` autocomplete.
- A fresh ordinary block was independently absent from search before creation, then added through native MCP, read back by UUID and found through page-scoped search.
- The user separately confirmed GUI search found that block, `[[...]]` title autocomplete offered it, and selecting/clicking the resulting reference navigated normally.
- No agent restart, manual index rebuild or unrelated title edit occurred between those writes and the confirmations.

The application process SHA was not independently re-detected in the fresh-block follow-up turn; attribution follows the established current-package acceptance track. The earlier `((...))` test expectation was inappropriate for this DB-graph node-reference path and is not a remaining acceptance requirement.

### Limits retained after closure
- The fresh-page candidate's separate selection/navigation case is not confirmed. That is not the original search-index visibility failure; the tested ordinary-block reference navigation passed.
- No indexing latency/SLA, crash durability or exhaustive application-regression claim is made.
- #21 writes into a recycled target; its ordinary-search invisibility must not be misclassified as recurrence of this active-content indexing defect.
- Later synthetic U/B/C GUI comparison is limited compatibility evidence, not a rerun of every original issue case.

No additional graph mutation, application restart or source change was performed solely for closure.
