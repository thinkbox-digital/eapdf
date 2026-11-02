# D02 — full corpus comparison and specification references

The Java app is **not a replacement for V85 yet**. The comparison found missed
EA-PDF failures and Java-only failures. Overall PASS stays disabled. The window
states this limitation. The existing creator and frozen Python validator are
unchanged.

## Comparison method

Run both engines on every saved PDF under `tests/fixtures/eapdf`, all eight
`examples/sample_*.pdf` files, and the two protected Agenda/BFO reference PDFs.
This is 808 parent files: 798 fixtures, eight examples and two references.
The corpus intentionally contains malformed files. Do not use its overall
failure rate as a measure of normal creator output.

The V85 reference uses its normal 100,000-item tree limit. The sample-refresh
script's 10,000,000-item override for the larger PST samples is not used here.
Actual unavailable/error results are retained; this comparison does not silently
change either engine's inspection limits.

Use real PDF/A validation and the real selected ISO 32005 profile when applicable.
Do not mock either upstream engine. Match recursively checked embedded files by
SHA-256 and compare their full reports too. The frozen validator's code, rule
text and selected profile are pinned by hash.

For each of the 225 rule codes compare:

- Maximum severity, including an explicit MISSING state.
- Counts of individual PASS/WARN/FAIL/N/A findings. These counts may differ because
  one implementation aggregates findings; equality of maximum severity does not
  prove equality of every assertion.
- Every transition, including Python FAIL → Java PASS/WARN/N/A/MISSING and the
  reverse. An overall PDF/A FAIL must not hide an EA-PDF miss.

Also compare upstream profile, status, run/completion/compliance flags, failing
rule identifiers, object locations and error text. Compare child inventories and
record execution errors. Preserve the deliberately incomplete Java result rather
than removing its guard to improve agreement.

The existing validator is the comparison reference. A disagreement does not by
itself prove which implementation correctly applies the specification. The
reviewed clause references remain the authority for investigating each difference.
This corpus comparison does not prove untested operational/resource behavior,
all possible inputs, or independent conformance certification.

Development commands:

```sh
python3 validator-desktop/tools/compare_existing.py --output /tmp/eapdf-validator-compare
python3 validator-desktop/tools/comparison_report.py /tmp/eapdf-validator-compare
```

The comparison runner is development code, excluded from the runnable JAR.
Raw reports stay in `/tmp`, compressed. The checked-in report excludes email
content and report details; it contains rule/status comparisons and input hashes.

## Repairs from the comparison

1. Compare a marked-content operand when either its tag **or its structure
   owner's tag** is custom. A standard `Span` owned by `Mail_FieldName` previously
   escaped this check. Missing or ambiguous owners remain unavailable rather than
   silently passing.
2. Run the selected ISO check before returning for an invalid single-Document
   structure root. A malformed root must not prevent the independent upstream
   check or declaration check from running.
3. Read veraPDF's object location and enable its detailed error messages. The
   initial Java adapter discarded the location and used the API default with
   error messages disabled.

Existing malformed PDFs reproduce these defects; regression checks use those
actual files. The final Java revision is compared across the full corpus again.
These repairs do not resolve the other port gaps documented in the comparison.

## Specification tab

Select a finding, then open **Specification**. The app includes:

- A reviewed plain-language summary, clearly labeled as a paraphrase.
- Relevant section, table, printed page and 1-based PDF page numbers.
- Actual text extracted from each cited specification page, with surrounding
  context. Text layout is simplified; running headers/footers are omitted.
- The SHA-256 of the source specification PDF.

All 225 frozen rule codes have reviewed references. `PDFA_FIRST_PASS` uses the
EA-PDF PDF/A baseline reference. Operational checks such as opening the file
correctly state that they have no direct specification section/page. References
into an underlying ISO standard are not given invented page numbers.

The text and references are built into the JAR. Neither the PDF nor Python is
needed at runtime. `build_spec_references.py` rebuilds the resource from the
pinned local PDF and verifies every printed/PDF page pairing. Plain text Swing
controls do not interpret document content as HTML or fetch external links.

The graphical check selects a rule after sorting, verifies its actual quotation
and page numbers, and checks that stale text is cleared when the file changes.
