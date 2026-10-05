## Resolution: real MCP-authored numbered lists

Closing as completed with maintainer approval for the original core request: creating real numbered-list items through the generic properties map, rather than putting fake number prefixes in titles.

### Verified acceptance (measured)
- `List type` can be discovered via `listProperties`; writing `number` produces the real numbered-list attribute.
- Receipt and independent block/page readback confirm the attribute and unchanged textual content.
- Earlier packaged-app screenshots confirm actual static 1/2/3 rendering.
- A batch containing a valid title edit followed by an unsupported `bullet` value is rejected without applying the title edit. This is evidence for that tested rejection path, not universal atomicity.

### Build attribution
The earlier live/static-rendering evidence is recorded under [run 37134185100](https://github.com/e-zz/logseq/actions/runs/37134185100); it is not a full rerun on the newer package. Follow-up API writes/readbacks and the dynamic-numbering observation were on [run 37203268496](https://github.com/e-zz/logseq/actions/runs/37203268496), source `fb5eb4eb43cdb3be7a29d816b969d45c127d4861`. No fresh tests were run solely to close this issue.

### Limits retained after closure
- This importer-based API does not support property removal or explicit number-to-bullet conversion; these requests fail explicitly.
- Static numbering does not establish dynamic renumbering. The user-observed live refresh defect remains OPEN as #20; it is not being marked fixed here.
- Nested numbered-list rendering has not been separately validated. The newer-package static GUI fixture was not completed.
- Other write-API gaps mentioned as context are not being declared solved by this closure.

This closes the original numbered-list creation gap, not every list editing operation or all GUI behavior.
