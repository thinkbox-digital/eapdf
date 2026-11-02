# Standalone Java validator: web parity

Verified on 2026-10-24. Delivery: [eapdf-validator.jar](../../../dist/eapdf-validator.jar).

The full comparison below records JAR `c2204a…`. The current JAR has a
[subsequent finding-text update](../validator-finding-reasons/README.md), with
unchanged validation conditions and a focused result comparison.

| Check | Result |
| --- | ---: |
| Main PDFs | 808 |
| Embedded reports | 67 |
| Active EA-PDF rules | 213 |
| Matching rule/file outcomes | 186,375 / 186,375 |
| Matching overall results | 875 / 875 |
| Matching PDF/A and selected ISO results | 875 / 875 each |
| Matching upstream object locations and error text | 875 / 875 each |
| Missed or extra failure results | 0 |
| Execution errors, inspection errors or different child inventories | 0 |
| Java checks, including the actual GUI | 105 passes; no failures, errors or skips |

Overall **PASS** is now enabled when required comparisons complete. An actual
violation produces **FAIL**; unavailable required evidence still produces
**INCOMPLETE**, matching the web validator.

## Repairs

- Match each email and attachment to its correct original source, preserving message-ID case for ownership.
- Check original headers, dates, MIME bodies and media parameters without treating repaired or ambiguous data as proof.
- Read owning DPart page ranges and real rendered header text. Follow MIME related roots and encrypted body types.
- Check associated-file calendar dates, annotation Contents types, embedded sizes/checksums and canonical XMP fields.
- Check actual structure connections, indirect elements, parent-tree entries, reused keys, ordering, orphan entries and missing content.
- Match conditional applicability, incomplete-evidence diagnostics and the web engine's inspection limits.

## Single JAR and GUI

The copied JAR passed through its real GUI and worker from a folder containing
only the JAR, with `PATH=/unavailable`. Its worker classpath was only
`eapdf-validator.jar`. PDF/A, selected ISO and the ordinary EA-PDF sample all
passed. Bundled specification excerpts were displayed, and temporary worker
files were removed. Java 21 or later is the only runtime installation needed.

The GUI retains 16-point text, a 2,000 × 1,100 starting window, four columns,
rule numbers, grouped findings, the keyword filter, Esc clearing and side-by-side
Finding/Specification panes. [Actual JAR screenshot](window.png).

## Scope and evidence

Every saved fixture PDF, eight example PDFs and both protected comparison PDFs
were checked. Embedded reports were matched by SHA-256. No rule/file outcome,
upstream discrepancy or skipped file was waived. Finding wording and occurrence
counts can differ because observations are aggregated differently; the full
comparison retains those counts.

This establishes parity with the existing web engine, not independent standards
certification. Its limits on unsupported original formats, ambiguous ownership,
isolated attachment evidence and large inspections remain. The creator, web
checking code, 213-rule JSON and protected comparison PDFs are unchanged.

- [Full results and all rule comparisons](RESULTS.md)
- [Build hashes and acceptance gates](evidence.json)
- [Rule matrix](rules.json)
- [Complete comparison, compressed JSON](comparison.json.gz)
- [Fresh baseline comparison, compressed JSON](baseline-comparison.json.gz)
- [Standalone GUI proof](standalone-proof.json)
- [Standalone proof harness](StandaloneGuiProof.java)

JAR SHA-256: `c2204a667fbb424e3716f094230d53d86bed9cea49d4f1a766e72b6d9dd767ec`.
