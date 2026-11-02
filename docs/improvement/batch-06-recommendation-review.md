# Batch 06 recommendation applicability review

Reviewed 2026-10-18 against local EA-PDF 1.0 section 9.3 (printed pp.64–66) and section 9.7 (printed p.74). Source PDF SHA-256 is pinned in requirements.json. This is a seven-rule subset; the complete N/A audit is unfinished.

## Decisions

- Rules 48–49: all pages are in scope for page context and its text representation. Extraction errors do not remove that scope. Only an actually empty page tree establishes no pages; a missing/short/duplicate extraction result is an execution failure. Such failures produce explicit unverified WARN and VALIDATION_INCOMPLETE.
- An absence of inferred textual context does not establish that context is inapplicable. It may exist graphically. Record unavailable/partial WARN with the unassessed page numbers. Successful extraction remains a heuristic; it does not prove human usability.
- Rule 52: extracted URL candidates are checked against Link annotations. Actual extraction failure is INCOMPLETE. A successful extraction with no candidates retains bounded N/A for this heuristic; source-intent/image-only URL completeness is still open.
- Rule 50: original color declarations and conversion policy have not been compared. Unavailable WARN does not allege a color-space violation. Independent PDF/A color requirements remain separate.
- Rules 53–54: the recommendation depends on rendered assets being preserved separately. Missing source/render/preservation-policy evidence does not prove that the premise is false. Both checks now show unavailable WARN and identify the missing inventory/object mapping. This repair does not implement asset reconstruction.
- Rule 99: equivalence to source logical semantics remains unavailable WARN, including when the PDF has no optional structure. The recommendation to create equivalent semantics cannot be waived merely by omitting structure. No new mandatory tagging rule is introduced.

SHOULD findings retain WARN severity. They do not create mandatory EA-PDF failures. A documented unavailable manual/source-policy assessment is distinct from a failed validator operation: failed extraction produces INCOMPLETE. Mandatory failures continue to take precedence. Warning text in HTML/JSON must explain the limitation rather than filling a generic mismatch template.

## Evidence and boundaries

The before-fix file records 22 failed assertions across eight exercised tests (one generated test skipped). After repair those eight pass. Generated assessment scenarios use two exact creator-derived PDFs (tagged and legally untagged), independently PDF/A-valid. Each PDF is assessed normally, with an extractor exception and with an incorrectly empty extraction result: six scenarios, not six distinct PDF defects. Sources and recipes are preserved, and final packaged acceptance archives the exact PDFs/reports.

The creator is unchanged and its B05 artifact hash is reused. No creator/sRGB/asset-preservation defect is inferred from these unavailable checks. Remaining N/A predicates, complete source inventories/associations, header tagging/custom Content Set applicability, rendering coverage and full standards coverage remain open.
