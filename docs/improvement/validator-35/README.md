# V35: preserve raw PDF Info value types

EA-PDF's mandatory Info/XMP crosswalk inherits Table7's PDF string/date entry
types. The former read_docinfo comparison API converted every PDF value with
str(), allowing numbers or PDF names to match normal XMP text. All eight saved
before-probes passed the actual complete PDF/A-3U pipeline.

V35 checks the raw object before comparison. Non-string values fail their
existing field rule and cannot gain a later comparison PASS. Normal numeric,
name-like and empty PDF strings remain permitted. Optional null dictionary
entries are absent under ISO32000-2 7.3.9. The shared read_docinfo API, date
lexical/comparison policy and text whitespace policy remain unchanged.
[Review](rule-review.json), [Table7 source/access limits](../validator-34/crosswalk-source.json).

Eight focused tests pass. The frozen suite runs 611 tests: 571 pass / 40 skip.
All 320 custom outcomes match, including unchanged 308 prior PDF hashes/outcomes.
Twelve exact-byte before/after PDF/A-3U controls match; eight false pipeline
PASS results now fail exactly the respective Info rule. All twelve controls
pass PDF/A; ISO32005 is correctly N/A for PDF1.7. An actual negative CLI and
positive isolated worker check agree. Eight exact V34 before-runs are reused
after hash checks; sixteen before/after engine executions are new.

Twelve original creator files are directly confirmed as PDF/A-4, making the
changed branches inapplicable. Their exact V29 diagnoses remain applicable
through V34; known failed conformance remains explicit. No new creator engine
run, Java build, creator edit or sample regeneration is claimed.

Reproduce with `freeze-inputs.py`, frozen unit discovery, `verify-regressions.py`,
`verify-pdfs.py`, `verify-retained-pdfa4.py`, `refresh-observations.py`, then
`assemble-evidence.py`. Inputs: `.eapdf-verification-v35`,
`verification-inputs.json`, `runtime-inputs.zip`.

This is raw object-type preservation, not certification of every PDF string
encoding, Unicode/whitespace policy or Date conversion. Forty skips, eleven
review decisions, wider obligations/shared checks, source occurrences and
creator repairs remain open.
