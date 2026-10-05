# Source diagnosis: GUI search scope and same-title recycled-page import

Read-only diagnosis; current HEAD fb5eb4eb43cdb3be7a29d816b969d45c127d4861. No source/app/config changes.

## GUI-affecting local changes (verified Git diff)
Relative to synchronized upstream anchor 22a29b30dee3b3930cf49bba50454650c31d2a07, local changes touch frontend/handler/search.cljs, frontend/worker/search.cljs, frontend/worker/db_listener.cljs. Search scoping/options validation lives in shared handler/worker code; listener recognizes runtime-write imports and permits incremental indexing. These are GUI-affecting execution paths, not MCP-only code. Search component UI and frontend/search.cljs have no diff in this comparison. Therefore 'GUI untouched' is too broad; 'no new global recycle filter in the local patch relative to upstream anchor' is the narrower verified statement.
Current recycled exclusion predicates already exist in upstream baseline. Source diff alone does not establish why reporter's earlier GUI showed recycle-labelled matches; prior running artifact SHA and matched search mode remain unverified.

## Same-title page-add behavior: source chain matching runtime observation
- MCP upsert in src/main/logseq/api/db_based/cli.cljs:333-349 passes operations to outliner batch-import-edn; non-receipt mode reports summarized input operations.
- deps/outliner/src/logseq/outliner/op.cljs:219-240 imports using sqlite-export/build-import.
- deps/db/src/logseq/db/sqlite/export.cljs:1132-1150 add-uuid-to-page-if-exists looks up page by exact title via ldb/get-case-page. If found it rewrites incoming page UUID to existing UUID and records import->existing mapping. It does not reject a recycled match here.
- deps/db/src/logseq/db.cljs:583-590 get-case-page resolves title using common-initial-data/get-first-page-by-title.
- deps/db/src/logseq/db/common/initial_data.cljs:23-33 selects oldest page entity with exact block/title; predicate is page?, with no deleted-at/recycled/active filter.

Runtime evidence from issue14-real-page-results.md corroborates this source path: a proposed add page with same title as the recycled page reused original UUID, preserved deleted-at, and attached the new child to the recycled page. Default reads/search still excluded it.

Precise outcome: independent active-page creation did NOT succeed; API did NOT reject/error/rollback. A block was actually created on a recycled target. This is not a generic claim that every same-name operation always errors, nor evidence that GUI page creation shares this import behavior. It is incorrect to describe it merely as a harmless fixture limitation: this write semantics can silently hide agent-created content.

Proposed required boundary (not implemented): ordinary runtime upsert should reject a recycled page target before any batch commit, or use an explicitly defined create-new-active-generation behavior. It must not implicitly restore a recycled page or silently append to it. Import/merge workflows may need their own intentional policy; do not globally change shared import semantics without scope review.

#14 active-first read behavior remains untested with real active/recycled coexistence. MCP same-title write failure is a separate write-path defect/contract question. Publication not performed in this diagnostic turn.
