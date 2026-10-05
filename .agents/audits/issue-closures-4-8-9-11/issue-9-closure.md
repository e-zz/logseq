## Resolution: generic typed property writes

Closing as completed with maintainer approval for the original generic-properties capability, with explicit importer API limits.

### Verified acceptance (measured)
- Source contract: the properties map is accepted on page/block add/edit, keyed by property UUID or full ident. Measured block property-only edits preserve title, parent, page and order.
- Tested types include text, URL, number (including zero), checkbox (including false), node references, journal-date references, asset references and single-valued closed values such as Task status.
- Tested many-node writes are additive and deduplicate references.
- Tested invalid number, URL, checkbox, list-type, closed-value and class-restricted reference inputs reject batches without applying the preceding valid title edit. This applies to the tested paths, not every conceivable failure.

### Completed follow-ups (measured + user-confirmed normal restart)
- After the user-confirmed normal restart, independent reads verified the previously written asset reference still targets the exact same asset and that the asset entity still exists. This is reference/target persistence, not image rendering, file-byte integrity or crash durability.
- A non-journal target for a date property was rejected in a batch with a preceding valid title edit; complete before/after block reads were equal. The valid title edit did not land.

These two follow-ups supersede the corresponding pending items in the earlier acceptance comment.

### Build attribution
Earlier core live evidence is recorded under [run 37134185100](https://github.com/e-zz/logseq/actions/runs/37134185100). The asset/date follow-ups belong to the [run 37203268496](https://github.com/e-zz/logseq/actions/runs/37203268496) acceptance track, source `fb5eb4eb43cdb3be7a29d816b969d45c127d4861`; the application process SHA was not independently re-detected in the follow-up turn. They are not blanket reruns on the later search-only package.

### Limits retained after closure
- Property removal (`null`) and many-value clearing are unsupported; omission leaves a value unchanged.
- Many writes add to the existing set; they do not replace it with an arbitrary subset.
- Many-valued closed-value writes are unsupported by this API path.
- Node/date/asset references use reference objects, not arbitrary bare strings.
- Receipt is transaction-state readback, not a disk-durability or index-completion guarantee.

Closing this issue does not claim unrestricted parity with every interactive UI property operation.
