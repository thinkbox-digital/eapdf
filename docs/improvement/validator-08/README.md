# V08 — verified upstream ISO 32005 execution

Rule 224, `ISO32005_UPSTREAM`, performs the required external structure pass for
PDF 2.0 files with logical structure after a successful PDF/A first pass. It uses
the same path for CLI, isolated web workers and contained PDFs. Declaration
schema remains a separate custom EA-PDF check.

The profile is a local selection of all 1,636 unchanged upstream ISO predicates
from veraPDF 1.31.18's composite, excluding every one of its 91 PDF/UA predicates.
It is not an officially published standalone profile. The PDFUA_2 flavour selects
the verifier model. [Provenance](../verapdf-coverage/iso32005-profile.json), retained
source, derived XML and canonical rule-by-rule comparison pin that boundary.

Runtime acceptance requires exact profile bytes, profile label, reported
apps/core/model versions, a single normally completed validation and all 1,636
reported rules. Actual ISO failures fail the PDF. Missing, changed, unsupported
or incomplete execution produces unavailable evidence and overall INCOMPLETE.
The custom engine contains no implementation of the upstream inclusion rules.

All 57 retained PDF probes pass PDF/A and meet their expected custom/ISO results.
A paragraph-in-paragraph probe passes custom checks but fails upstream ISO.
Six real CLI/isolated-worker checks agree. The original broad run completed with
one creator failure, one packaging error and one optional schema skip. Both
defects were corrected; cumulative passing acceptance is recorded below.

A host restart interrupted initial runs and erased temporary build/report data.
Partial logs and reports are retained. Recovery uses persistent workspace paths.
The recovered Java build passed 161 tests. The snapshot initially omitted Maven
notice resources; a separate complete artifact restored them and passed the
packaging gate. The original and corrected packaging boundaries are recorded
separately. The corrected creator JAR is used by final V11/V12 acceptance.

The authoritative inventory has 301 records and 224 rule codes. Eleven open
expert/dependency questions, remaining mixed-rule review and dependency edition
boundaries prevent a complete standards-coverage claim. No deployment, commit,
external publication or CLI change occurred.

## Cumulative acceptance and historical boundaries

The frozen V11 full packaged suite completed 376 tests: 375
passes and 1 optional PREMIS schema skip. All 92 retained cases meet exact
custom/upstream outcomes. This is the cumulative V07–V11 gate with the corrected
complete creator artifact. [Machine-readable evidence](evidence.json) retains
each earlier run's own outcome; earlier running/pending notes above describe
that historical boundary. V08's original creator/packaging failures and V10's
original URI-size failure remain recorded as failures, not passing full runs.

V12's subsequent asset-association repair has its own [targeted acceptance](../validator-12/evidence.json).
