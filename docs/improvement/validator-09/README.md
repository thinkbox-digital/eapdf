# V09 — supplied Table 18 schema without source dependence

Supplied normal-string and nonnegative-integer types are checked for each email
even when no source email is retained. Optional values remain optional. Message-ID
spelling, FOAF and In-Reply-To interpretation questions remain unresolved.

Table 18's `xsd:dateTime` accepts legal unknown timezones, end-of-day and extended
or negative years. The schema check no longer inherits Python datetime's year
range. Source-instant comparison reports unsupported/unknown instants as
unavailable and preserves decimal fractions beyond microseconds. Integer type
and comparisons use ASCII decimal syntax and preserve legal signs, zeros and
large values without Python-only coercions or digit-limit false violations.

Authority: EA-PDF Table 18 and [XML Schema 1.0 Part 2, sections 3.2.7/3.3.20](https://www.w3.org/TR/2004/REC-xmlschema-2-20041028/).
Normal XMP values remain distinct from RDF URI, array and structure values.
Internal schema validity does not establish source-field fidelity.

Pre-fix failures are retained. Twelve independently PDF/A-valid isolated-email
probes meet their exact outcomes. The original V09 packaged run was interrupted
and has no completed PASS result. Frozen V11 provides the cumulative full gate;
its source/artifact boundary is recorded below and in `evidence.json`.

Java source and CLI arguments are unchanged by V09. Its reused build and the
packaging-resource correction are recorded in V08; application class/resource
bytes are identical across those two artifact variants.

## Cumulative acceptance and historical boundaries

The frozen V11 full packaged suite completed 376 tests: 375
passes and 1 optional PREMIS schema skip. All 92 retained cases meet exact
custom/upstream outcomes. This is the cumulative V07–V11 gate with the corrected
complete creator artifact. [Machine-readable evidence](evidence.json) retains
each earlier run's own outcome; earlier running/pending notes above describe
that historical boundary. V08's original creator/packaging failures and V10's
original URI-size failure remain recorded as failures, not passing full runs.

V12's subsequent asset-association repair has its own [targeted acceptance](../validator-12/evidence.json).
