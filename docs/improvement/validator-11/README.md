# V11 — remaining metadata constraints and comparison prerequisites

Independent PDF probes reproduced three false passes: empty first creator
entries concealed by later names, and an omitted NumberAttachments in an
isolated email with an exactly owned attachment record. They also reproduced
false attribution failures for mailbox-like creator/producer names. The repairs
follow existing authoritative requirements; no arbitrary name grammar or
ambiguous Message-ID ownership inference was added.

Supplied optional asset NumberMessages now receives its required nonnegative
integer type check, including empty/URI/array values. Absence remains permitted.
An invalid semantic size still fails its type requirement; byte comparison is
reported unavailable rather than inventing a numeric mismatch.

The broader retained-corpus run exposed four legitimate inherited ISO inclusion
findings in the existing malformed Table 11 probes. Their PDF bytes and custom
expectations are unchanged. Separate upstream expectations record the actual
Art–TD, Document–TD and TR–Art findings. The upstream predicates are unchanged.

All twelve new PDF/A-valid probes meet the reviewed outcomes. Current unit
regressions pass; actual CLI/isolated-worker checks also meet expectations.
Final cumulative packaged acceptance completed 376 tests: 375 passes and one
optional PREMIS schema skip. All 92 retained outcomes match against
`.eapdf-verification-v11`. `verification-inputs.json`, `runtime-inputs.zip` and
`application.patch` identify the exact version. The original V10 broad/corpus
failures are retained and are not claimed as final PASS results.

The repaired creator artifact is unchanged from its 161-test Java build. Earlier
V07–V10 work, image diagnosis, sample regeneration, snapshot omissions, host and
usage interruptions retain separate evidence. Eleven expert/dependency questions
and full coverage limits remain explicit. No CLI arguments, external publication,
deployment or commit changed.

## Cumulative acceptance and historical boundaries

The frozen V11 full packaged suite completed 376 tests: 375
passes and 1 optional PREMIS schema skip. All 92 retained cases meet exact
custom/upstream outcomes. This is the cumulative V07–V11 gate with the corrected
complete creator artifact. [Machine-readable evidence](evidence.json) retains
each earlier run's own outcome; earlier running/pending notes above describe
that historical boundary. V08's original creator/packaging failures and V10's
original URI-size failure remain recorded as failures, not passing full runs.

V12's subsequent asset-association repair has its own [targeted acceptance](../validator-12/evidence.json).
