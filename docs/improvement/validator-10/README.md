# V10 — semantic Table 15 identification values

Version/revision checks compare the required integer values, accepting legal
signs and leading zeros. Conformance remains exact normal text. RDF URI and
array/structure values cannot satisfy these simple-value requirements; qualified
simple values remain supported. Reading the document properties preserves their
semantic shape instead of flattening arrays or trimming Text into validity.

Eleven independently PDF/A-valid probes meet exact expectations. Focused
regressions and the broad unit suite pass. Diagnostics distinguish URI/container
values while preserving established scalar report details.

The V10 packaged suite completed 368 tests with one URI-size comparison failure
and one optional schema skip. The comparison prerequisite was corrected in V11.
Its frozen manifest also covers V07's declaration schema, V08's
verified upstream ISO execution and V09's source-independent Table 18 types.
Earlier runs and failures remain separate: V08 completed with one creator
failure and one packaging error; V09 did not complete during the usage-limit
interruption. Neither is claimed as a full passing run. Both original failures
pass targeted verification with the corrected artifact.

See [the creator diagnosis and repair](../creator-iso-structure/README.md), source
freeze and logs. Completed cumulative acceptance is recorded below. Eleven expert/dependency
questions and broader mixed-rule review remain open; no certification claim is
made. No public CLI arguments were changed.

## Cumulative acceptance and historical boundaries

The frozen V11 full packaged suite completed 376 tests: 375
passes and 1 optional PREMIS schema skip. All 92 retained cases meet exact
custom/upstream outcomes. This is the cumulative V07–V11 gate with the corrected
complete creator artifact. [Machine-readable evidence](evidence.json) retains
each earlier run's own outcome; earlier running/pending notes above describe
that historical boundary. V08's original creator/packaging failures and V10's
original URI-size failure remain recorded as failures, not passing full runs.

V12's subsequent asset-association repair has its own [targeted acceptance](../validator-12/evidence.json).
