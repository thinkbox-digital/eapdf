# V05 — DPM field schema and bounded applicability

Authority: `validator_rule_text.json`, EA-PDF Tables 7/8, section 9’s extension allowance and the retained ISO-approved DPM name erratum 86.

Table 7 identity fields and Table 8 descriptions require PDF text strings; scalar, name, Boolean, array and dictionary coercions cannot satisfy them. Leaf DPM must be a dictionary. Table 8 name values require valid decoded UTF-8, with raw hexadecimal evidence retained when decoding fails. Unrelated extension keys cannot replace required Mail_ fields and are no longer rejected merely for using another prefix.

Annotation applicability distinguishes established absence of BodyRendering sets (N/A) from unresolved body coverage (WARN/unavailable). A malformed UTF-8 name cannot establish non-applicability. Missing annotations in identified applicable body sets retain their existing failures.

[Evidence](evidence.json): 330 unit tests completed with 308 passes and 22 expected integration/schema skips; eight focused tests pass; all twelve final-source normal/accessibility profile reports PASS; six PDF/A-valid probes and six actual CLI/web-worker checks meet exact expectations. V04’s full packaged run is historical. The initial type failures, Unicode reporting exception and the later invalid-name applicability regression are preserved separately.

The [source freeze](verification-inputs.json), [application delta](application.patch), [corpus](../../../tests/fixtures/eapdf/validator-dpm-schema/expectations.json) and [recipe](../../../scripts/build_eapdf_dpm_schema_fixtures.py) support reproduction. Nested arbitrary DPM-value conformance, complete per-email ownership, other XMP schemas and standalone ISO/TS 32005 integration are not certified by this repair. No creator/Java/CLI change or sample regeneration occurred.
