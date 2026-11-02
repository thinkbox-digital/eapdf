# V32: read the recommended default title by language

EA-PDF 9.8 printed p84 recommends `dc:title["x-default"]`. The old helper
returned the first nonempty title in any language. That let localized-only,
plain-text and ambiguous duplicate-default representations report recommendation
PASS. It could also report a different language as the default value.

The recommendation now reads explicit language-qualified Text items in an Alt
array, including qualified RDF values and inherited XML language qualifiers.
Missing or ambiguous evidence produces WARN. No new mandatory failure is added.
The [Adobe XMP data types](https://developer.adobe.com/xmp/docs/xmp-namespaces/xmp-data-types/)
define Text to permit an empty value; an explicitly empty default is therefore
set. The initial V31 probe expectation that it should warn was corrected during
source review, with the original expectation retained. [Review](rule-review.json).

Eight focused tests and the frozen 585-test suite pass: 545 passes / 40 skips.
All 284 custom status/failure outcomes match; the 276 prior bytes/outcomes are
unchanged. Eight exact-byte before/after controls all pass the exercised PDF/A
and ISO engines. Three recommendation PASS results become WARN; none changes
overall conformance. One actual CLI and one isolated worker check agree.
Four exact V31 before-runs are reused after hash checks; the remaining twelve
before/after engine runs are new. Complete reports and reuse provenance remain
in `pdf-reports` and `pdf-results.json`.

Twelve original creator titles are inspected by actual XML language. Each new
predicate agrees with its old result, so the exact V29 creator engine diagnoses
remain applicable through V31. Their known failed conformance is retained.
No Java build, creator change or sample regeneration is claimed.

## Scope

This is the EA-PDF title recommendation. A correct recommendation result and
passing upstream report do not certify generic XMP ordering, unique language
qualifiers, all RDF forms or every title type. The later/duplicate-default and
plain-title controls retain that separate boundary. The mandatory PDF/A-3
Info Title/Subject crosswalk still uses the old helper and needs its own pinned
source review. Eleven decisions and broader obligation/shared coverage remain.

Reproduce with `freeze-inputs.py`, frozen unit discovery, `verify-regressions.py`,
`verify-pdfs.py`, `verify-retained-titles.py`, `refresh-observations.py`, then
`assemble-evidence.py`. Inputs are `.eapdf-verification-v32`,
`verification-inputs.json`, and `runtime-inputs.zip`.
