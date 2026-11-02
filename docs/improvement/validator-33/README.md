# V33: preserve the types of required standard metadata

EA-PDF 9.8 printed p84 requires dc:creator[0], pdf:Producer and xmp:CreateDate
as standard XMP fields. Primary XMP schema definitions give an ordered array
of names, AgentName Text and Date respectively. The former flattened presence
checks accepted unordered creators, URI/array producer or date substitutions,
later non-name creators and impossible calendar dates.

The validator now reads unflattened values, checks the required standard types
and validates dates using the existing reduced-precision XMP Date helper.
Every supplied occurrence is checked. Qualified and attribute forms remain
accepted. Names are not restricted to software products, address-like text is
permitted, and timestamps do not acquire filesystem/source equality rules.
[Authority and scope](rule-review.json).

Ten focused tests pass. The frozen suite runs 595 tests: 555 pass and 40 skip.
All 296 custom outcomes match; all 284 prior bytes/outcomes remain unchanged.
Twelve exact-byte before/after controls all pass the exercised PDF/A and ISO
engines. Seven former full-pipeline PASS results now fail exactly the declared
mandatory field rule. An actual CLI negative and isolated worker reduced-date
positive agree. Four exact V32 before-runs are reused after hash checks; the
other twenty before/after engine executions are new.

The twelve original creator inputs have their actual canonical creator Seq,
literal producer and literal parseable creation date independently inspected.
All added guards are true; their exact V29 engine diagnoses remain applicable
through V32. Their known failed conformance remains. No new Java build, creator
change, sample regeneration or creator acceptance is claimed.

Reproduce with `freeze-inputs.py`, frozen unit discovery, `verify-regressions.py`,
`verify-pdfs.py`, `verify-retained-metadata.py`, `refresh-observations.py`, then
`assemble-evidence.py`. Inputs are `.eapdf-verification-v33`,
`verification-inputs.json` and `runtime-inputs.zip`.

This checks the three required fields. It does not certify all standard XMP,
arbitrary RDF, all Date edge cases, metadata provenance or equivalence between
public XMP documentation and every clause of the cited ISO edition. Forty skips,
eleven review decisions, broader obligation/shared coverage, Info/XMP crosswalk
review and creator repair remain open. The separately saved PDF/A-3U candidate
has actual upstream failures and is not a positive acceptance control.
