# V06 — canonical XMP record fields and semantic shapes

Authority: `validator_rule_text.json`, EA-PDF Tables 16/19 and the reviewed XMP semantic-value contract.

Required source-asset fields use canonical `Checksum` and `ContentType`; attachment records use `CheckSum`, `Content-Type` and `Message-ID`. Convenience aliases cannot supply required properties or waive Mail_GUID. Normal text remains distinct from RDF URI, array and structure values. Signed/zero-padded decimal integers remain legal; negative sizes, URI/array counts, non-ASCII digits and Python underscore syntax cannot satisfy nonnegative count types.

Attachment CheckSum remains optional. A supplied empty, malformed, URI or array value fails its declared schema, and malformed shapes cannot abort source comparison. Content-Type remains free text, including original source errors; a malformed bare value cannot compare equal to text/plain through the MIME parser’s fallback.

[Authored regressions](../../../tests/test_xmp_record_schema.py), [source freeze](verification-inputs.json), [corpus expectations](../../../tests/fixtures/eapdf/validator-xmp-records/expectations.json), [recipe](../../../scripts/build_eapdf_xmp_record_fixtures.py), and retained pre-fix logs establish the boundary. The earlier validator accepted both canonical-field substitutions, URI sizes and malformed optional checksum probes. Negative source sizes previously failed only their byte-size comparison, without failing the required type.

Ten independently PDF/A-valid probes match exact custom expectations. Final packaged acceptance and entry-point evidence are recorded in `evidence.json`. One initial isolated-worker report correctly returned PDF/A ERROR after Docker’s Go client failed to allocate a thread. That HTML is retained in `worker-initial-reports/`; the repeat succeeds without changing the validation code.

This repair does not resolve FOAF representation, In-Reply-To, the cross-table Message-ID spelling question, complete ownership or standalone ISO/TS 32005 execution. Creator/Java/CLI behavior and saved samples are unchanged. The declaration namespace and upstream-only ISO feasibility review are subsequent work, outside V06’s frozen-source acceptance.
