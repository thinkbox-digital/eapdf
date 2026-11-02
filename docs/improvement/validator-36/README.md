# V36: supplied optional DPart entries and unavailable name evidence

ISO32000-2 Tables408/409 define optional Type, RecordLevel and NodeNameList
entries. If supplied, Type is the prescribed PDF name, RecordLevel is an integer,
and NodeNameList has a name for each actual DPart hierarchy level. Omissions
remain permitted; sibling nodes do not add hierarchy levels. The custom
DPart structure owner had not inspected these entries. [Authority review](rule-review.json).

V36 checks the types, exact Type keywords and actual hierarchy length. XML Names
are unambiguously accepted; characters invalid under both Name and NMTOKEN fail.
NMTOKEN-only labels and undecodable names remain explicitly INCOMPLETE pending
the narrower wording/encoding review. Recommended UTF8 interpretation is not
turned into an invented mandatory encoding restriction. Unicode Names remain
permitted, and PDF/VT API-specific ASCII/presence constraints are not imported.

Ten focused tests pass. The frozen suite runs 621 tests: 581 pass / 40 skip.
All 334 custom outcomes match; all 320 prior PDF hashes/outcomes remain unchanged.
Fourteen exact-byte before/after controls pass the exercised PDF/A/verified ISO
engines: eight missed mandatory violations now fail and two unsupported complete
PASS results become INCOMPLETE. Four valid controls retain PASS, including absent
entries and Unicode labels. A real CLI FAIL and isolated-worker INCOMPLETE agree;
malformed-name diagnostics remain encodable/reportable.

Eight exact V35 before-runs are reused after hash checks; twenty control engine
executions are new. The initial probe's optional-list assumption and focused
repair's missing incomplete marker are preserved separately.

All six original creator profiles in both normal/accessibility modes also have
fresh complete-pipeline runs. All twelve still pass the exercised PDF/A and ISO
checks and fail the same known operand/child conformance rules. These are failed
creator acceptance diagnoses, not twelve creator accepts. The JAR and input
PDFs are unchanged; no Java build, creator edit or sample regeneration occurred.

Reproduce with `freeze-inputs.py`, frozen unit discovery, `verify-regressions.py`,
`verify-pdfs.py`, `refresh-observations.py`, then `assemble-evidence.py`. Inputs:
`.eapdf-verification-v36`, `verification-inputs.json`, `runtime-inputs.zip`.

RecordLevel meaning/range, the complete DPart metadata errata, unresolved label
interpretations, forty skips, eleven listed expert questions and wider coverage
remain open. This scoped family review does not close the validator phase.
