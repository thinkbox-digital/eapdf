# Remaining work estimate after B13

**Withdrawn as the active forecast.** The user stopped B13 before final acceptance and requires specification-to-validator-requirements completeness first, then validator improvements, then creator improvements. Re-estimate from the reviewed requirement inventory and gap list. The conditional figures below preserve the earlier planning history; they are not the current execution plan.

Planning estimate, 2026-10-18. A unit means one repair batch comparable to
B11–B13: a bounded requirement review, reproduced failures, targeted changes,
authored fixtures, independent acceptance and retained evidence. It is not a
fixed number of code edits, days or standards clauses.

**After B13 acceptance: approximately 4–7 further preservation batches;
17–29 further batches for the full eight-step improvement plan.** The broader
total includes the preservation work. These are estimates, not a certified
complete issue count. Review discoveries can change the scope and split batches.
Thirteen completed batches do not imply a percentage of full standards coverage.

## Preservation work next: 4–7 batches

| Work | Estimated batches | Completion evidence |
| --- | ---: | --- |
| PST eligible folder/message enumeration and native versus synthesized export provenance | 1–2 | Authored entry identities, explicit exclusions/failures and complete reconciliation of reader output |
| MIME/MAPI attachment enumeration and occurrence ownership before normalization | 1–2 | Missing, duplicated or reordered attachment occurrences cannot silently become completed inputs |
| Reader-to-writer dispatch and Gmail download/resume reconciliation | 1–2 | Selected, skipped, failed and written occurrences reconcile; account-dependent acceptance remains visible |
| Independent final staged PDF/A/EA-PDF and configured-sidecar acceptance before package publication | 1 | Exact parent/child/sidecar bytes checked; required unavailable checks prevent success; failed acceptance preserves destinations |

The final gate may need another batch if configured sidecar semantics or
multi-file publication faults expose substantial independent defects. That would
require revising this estimate, not declaring those checks complete by inference.
Other groups below include broader source/XMP ownership and legacy helper review.

## Full plan: 17–29 batches including preservation

| Planning group | Estimated batches |
| --- | ---: |
| Preservation work above | 4–7 |
| Complete applicability/requirements register and remaining standards review | 3–5 |
| Wider XMP/DPart/structure/source ownership, RDF forms and cardinality | 3–5 |
| Difficult rendering and accessibility review | 3–5 |
| Robustness, resource limits and viewer/platform interoperability | 2–4 |
| Required schemas, reproducible release acceptance and external review | 2–3 |
| **Total** | **17–29** |

These groups allocate work for planning; the implementation plan's steps overlap.
The 72-site N/A inventory and scoped requirements register are partial. Full
ISO/TS 32005 review needs the full standard. Live Gmail, human accessibility,
GUI/viewer/platform checks and external review require their respective access
or reviewers. Batch estimates do not predict those external waiting times.

## B13 changes the boundary

Supported MBOX framing now has ordered accepted byte spans, raw/export/container
fingerprints, reader Source reconciliation and visible extraction provenance.
This completes one bounded reader unit after B12's writer-entry inventory. It
does not establish universal MBOX dialect recognition, whole mailbox storage
preservation, or completeness of other readers. Continue with PST enumeration
and provenance, then MIME/MAPI occurrence reconciliation.
