# V34: PDF/A-3 Info/XMP crosswalk and optional-field applicability

EA-PDF 9.1 printed p61 requires Info/XMP consistency for PDF/A-3 based files.
ISO19005-3:2012 Table7 printed p22 maps Title and Subject to the x-default
language, Keywords to normal pdf:Keywords Text, and the remaining fields to
their listed standard properties. [Source review and access limits](crosswalk-source.json).
The source table was inspected as primary ISO document content in the browser;
a direct binary download returned HTTP403. No local source hash or complete
standards-source audit is claimed. The mappings corroborate the retained
ISO19005-2 Table7. EA-PDF supplies mandatory strength; PDF/A-3 alone supplies
weaker writer guidance.

V34 compares Title/Subject with explicit x-default Text, including empty values,
and checks Keywords synchronization in addition to the existing EA-PDF marker.
The authoritative crosswalk inventory now links Keywords too, retaining all
301 records / 224 codes. Known absent optional Info keys receive established
N/A results. A malformed Info object or present unchecked field stays unavailable.

## Verified evidence

Eight focused tests pass. The frozen suite runs 603 tests: 563 pass and 40 skip.
All 308 custom outcomes match; the 296 prior bytes/outcomes are unchanged.
Twelve identical-byte before/after controls pass their actual PDF/A-3A/3U
profiles. The two full-field controls exercise every mapped Info/XMP comparison.

- Three missed mandatory crosswalk violations now fail.
- Three incorrect language/empty-value comparison failures now pass.
- Two false INCOMPLETE optional-field controls now pass.
- Missing XMP Keywords gains its additional required synchronization failure.
- Missing required Info/Keywords retains both mandatory failures.

An actual positive CLI and negative isolated worker check agree. All 24
before/after engine runs are fresh. ISO32005 is correctly N/A for these PDF1.7
controls; a passing ISO engine run is not claimed. The fixture removes the
source PDF2.0 inclusion declaration.

The initial PDF/A-3 conversion failed actual associated-file/schema predicates;
two subsequent attempts exposed undeclared GUID types and an overly strict
MIMEType declaration for Table19's free-text Content-Type. All initial reports
remain under V33's `next-pdfa3-crosswalk-probes`. The final fixture associates
native files with their message and EmbeddedFiles entries, declares underlying
GUID Text, preserves raw attachment media Text and has real positive PDF/A-3
reports. [Construction review](rule-review.json).

Twelve historical creator files are directly confirmed as PDF/A-4, making V34's
changed branches inapplicable. Their exact V29 engine diagnoses remain applicable
through V33; known failed conformance stays explicit. No new Java build, creator
change, sample regeneration or creator acceptance is claimed.

Reproduce: `freeze-inputs.py`, frozen unit discovery, `verify-regressions.py`,
`verify-pdfs.py`, `verify-retained-pdfa4.py`, `refresh-observations.py`, then
`assemble-evidence.py`. Inputs: `.eapdf-verification-v34`, `verification-inputs.json`
and `runtime-inputs.zip`. The initial verifier's null-ISO-details error is retained.

Later-default controls assess target language selection, not complete generic
XMP ordering. Existing text whitespace/date policy remains bounded. Forty skips,
eleven decisions, wider obligation/shared coverage, source occurrences and
creator repairs remain open.
