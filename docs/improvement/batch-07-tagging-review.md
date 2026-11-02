# Batch 07 tagging and related applicability review

Reviewed 2026-10-18 against local EA-PDF 1.0 sections 7.1.2, 9.2, 9.6 Table 8 (printed pp.70–71), and 9.7 (printed pp.74–76). This is a bounded review of recommendation rules 100/107 and one confirmed body-comparison applicability gap.

## Content Set evidence

The specification recommends logical structure/Tagged PDF for custom Content Sets. Table 8 permits both listed and extension-valued Content Set types, but these types do not provide a definitive enumeration of every custom Content Set. The validator therefore checks all declared DPart ranges as a conservative superset. It does not assert that all such ranges are custom.

A structure root alone no longer passes. The bounded positive evidence requires MarkInfo.Marked, structure correspondence, structurally marked non-artifact paint on every scoped page, no unmarked text/image/vector painting operations and structure object references for page annotations. Form invocation is assessed through its inner paint; a form can have its own valid MCIDs even when its Do operator has none. Explicit Artifact sequences remain distinct. Existing visible/text classifications and mandatory correspondence checks are preserved.

PASS describes syntactic stream/annotation connections across this superset. It does not certify semantic correctness, reading order, full accessibility or every Tagged PDF requirement. Missing evidence is recommendation WARN, without inventing a mandatory tagging obligation. Omitting optional structure cannot automatically waive the recommendation to create it.

## Header recommendation

The header bullet in section 9.7 follows the condition that custom EA-PDF tags are used. A purely standard tag tree therefore receives explained N/A for rule 107. When custom tags are used, observed fields are grouped by their immediate Mail_Field owner and names/values require actual text connections. A value alone, unrelated tags elsewhere, or an unrendered name is insufficient. An absent value is not invented as an obligation.

Even complete observed pairs do not prove that every rendered header was tagged. This requires a source/rendered-header inventory. The outcome remains partial/unavailable WARN with observed and unresolved fields, rather than claiming full recommendation PASS. Existing mandatory missing-name and sibling checks still fail independently.

## Body-media applicability exposed by corpus

The first extension-valued Content Set fixture exposed DPM_BODY_MEDIA_TYPE_MATCH_SOURCE reporting INCOMPLETE without a declared BodyRendering set. Added a separate failing regression, then a narrow N/A condition: nonempty declared ranges, actual PDF-name types, valid ranges, and no declared BodyRendering anywhere in DPM. Missing types, invalid ranges and unresolved declared bodies retain INCOMPLETE. The finalizer uses the same predicate. The initial corpus log and the failing body test are preserved.

## Evidence boundaries

Initial regressions: eight failed assertions and two missing-evidence-key errors across ten exercised tests (one generated test skipped). The later body-absence regression adds one preserved failure. Final focused tests include Form, artifact, annotation, MarkInfo/correspondence, absent-value and ambiguity guards.

Six generated PDFs: four expected EA-PDF PASS, one exact mandatory FAIL and one INCOMPLETE; all PDF/A PASS. Five fixture headers independently establish expected observed field counts. The untagged custom-only case remains incomplete because mandatory Core Field text evidence cannot be assigned through a BodyRendering owner. No recommendation repair waives that limit.

No creator change, Java rebuild or saved-sample regeneration is needed. Full header inventories, custom-set interpretation/external review, semantic source equivalence, wider source/asset ownership, remaining N/A predicates and complete standards/inclusion-rule coverage remain open.
