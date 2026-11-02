# Batch 05 effective UserProperties review

Reviewed 2026-10-18: local `docs/reference/ISO_32000-2_sponsored-ec2.pdf`, Table 355, Tables 360–362 and 14.7.6.1–.4 (printed pp.738–741), plus [PDF Association clause 14 errata](https://pdf-issues.pdfa.org/32000-2-2020/clause14.html#14.7.6.1).

- Resolve each C name from the structure root ClassMap. Undefined names contribute empty classes. Classes have no parent inheritance.
- Apply class objects in their stored order, then direct A objects. A overrides C for the same attribute owner.
- A later UserProperties P replaces the whole earlier P array. Individual N entries from earlier arrays do not survive that replacement, including when the final P is empty.
- Revisions describe update currency, independently of value precedence. Creator updates use the element's current R and leave R unchanged.
- Creator updates copy the effective dictionary, P array, property dictionaries and direct A array before editing. Shared peers, classes and unrelated attributes are preserved.

The validator retains malformed resolution as VALIDATION_INCOMPLETE, while retaining mandatory failures already observed. No new EA-PDF rule number or normative failure is invented for generic malformed PDF structures. Source recommendations and context use the same resolved view.

Before-fix evidence survives in batch-05-python-before.log (seven assertion failures, one error) and batch-05-java-before.log (three failures). The first resumed corpus run had an extra nonexistent Message-ID failure expectation for empty P; corrected to GUID absence and preserved as batch-05-corpus-initial-failure.log. Resumed stream coverage passes the existing resolver; it is additional coverage, not a newly repaired defect.

This is bounded UserProperties resolution, not an implementation of every standard attribute owner, namespace inheritance, revision consumer or complete PDF structure validation. Full standards/register and applicability review remain open.
