# #9 remaining gaps: native current-runtime acceptance

## Scope
Current desktop native MCP only; existing disposable fixtures independently read before use. No compilation, restart, GUI automation, CLI mirror or direct database edits. Build attribution follows the current non-release CI 37203268496 acceptance track; package process SHA was not independently re-detected in this turn.

## Asset reference after user-confirmed restart — PASS (实测 + 用户实测)
Restart itself was previously confirmed by the user. This turn independently called getBlock for Task `2834bd67-ba5d-4737-acf1-5ddb4913cee7` and asset `6ac2457e-d17f-4148-b1ec-69d957a588c1`.
Task asset property `CI-ISSUE9-Asset-Reference-FWh0Sz8T` still points to exact UUID `6ac2457e-d17f-4148-b1ec-69d957a588c1`, title `2026-10-04-20-24-31`.
Preserved Task title `CI-ISSUE7-TASK-STATUS-ON-ADD`, Done status identity `logseq.property/status.done`, journal date UUID `00000001-2026-1004-0000-000000000000`, deadline `1791091897093`, page/parent `63bab91a-013d-44df-afb7-4c83026b03ef`, order `b1z`.
Asset independently exists with Asset tag, type png, size 44733, width 1453, height 436; page/parent unchanged, order b20.
This is reference/target persistence readback, NOT image rendering, file-byte reading or crash durability.

## Non-journal date + valid title edit — PASS (实测)
Preflight:
- getPage `ISSUE11-FRESH-PAGE-A` confirms ordinary page UUID `a7c5ae06-585c-4729-ac52-5f1fe6d5cb88`, not a journal.
- getPage `CI-ISSUE9-Date-Reference` confirms property UUID `00000002-5930-4674-3000-000000000000`, type date, cardinality one.
- getBlock confirms isolated ordinary fixture block UUID `5267678c-ff56-4c0d-af51-247e51676a51`.

Exactly ONE upsertNodes request, receipt=true:
1. edit this block title to `ISSUE9-NONJOURNAL-ATOMIC-REJECTION-CANARY`;
2. edit the same block date property to `{uuid: "a7c5ae06-585c-4729-ac52-5f1fe6d5cb88"}`.

Actual error: `API Error: Date property :00000002-5930-4674-3000-000000000000 must reference a journal page uuid`.
Independent getBlock readback equals the complete before object, including updated-at; valid first title edit did not land. No restoration write was necessary or performed.
Before AND after exact object:
{"created-at":1791130462930,"order":"a4","title":"ISSUE11-FRESH-BLOCK-A","updated-at":1791130462930,"uuid":"5267678c-ff56-4c0d-af51-247e51676a51","parent":"a7c5ae06-585c-4729-ac52-5f1fe6d5cb88","page":"a7c5ae06-585c-4729-ac52-5f1fe6d5cb88"}

Conclusion: tested native batch rejects non-journal date references without applying its preceding valid title edit. This does not broaden API semantics: property removal remains unsupported, many writes remain additive, many closed-value writes remain unsupported. No issue closure/publication performed.

Private fixture identifiers in this local evidence must be removed from public comments.
