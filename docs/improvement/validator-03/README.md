# Validator V03: XMP extraction and exact GUID identity

Authority: [validator_rule_text.json](../../../validator_rule_text.json). This scoped batch implements reviewed semantic extraction and EA-PDF schema requirements. It does not certify complete XMP support or resolve the eight open expert questions.

## Implementation

- Normal XMP strings retain their XML-decoded Unicode value. RDF URI values remain distinguishable from normal strings, even when a normal string looks like a URI. No UUID formatting requirement is invented.
- `Mail_GUID` lookup, uniqueness and identity joins preserve case, whitespace and Unicode exactly. DPM, structure attributes, attachment ownership and source attachment matching use the same rule. The undocumented `GUID` alias and foreign namespaces cannot satisfy the required property.
- The extractor reads simple property attributes, structured attributes, nested `rdf:Description`, `rdf:parseType="Resource"`, qualified `rdf:value` forms and tested typed inner FOAF resources. Semantic field access is separate from generic XMP syntax validation.
- Only top-level RDF descriptions feed document property lookup; attachment-owned GUIDs do not leak into document/email identity sets. Qualified email/attachment arrays preserve their original member nodes for per-email source comparisons.
- Supplied email, asset and attachment arrays require unordered structured records. An empty email array cannot borrow required fields from document-level metadata. To/From/Cc/Bcc require ordered structured agents with the FOAF namespace. Comparisons preserve member order. Existing presence conditions are retained; In-Reply-To and attachment absence/empty-set interpretation remain unresolved.
- Generic syntax stays with veraPDF. Current mailbox SMTP normalization is unchanged and remains part of the explicit FOAF interpretation question.

## Verification

[Eighteen authored tests](../../../tests/test_xmp_identity.py) cover equivalent legal representations, GUID type/name/namespace checks, exact matching, owner scoping, structured record arrays, ordered address arrays and attachment source ownership. The initial eleven tests failed against V02 with 18 failures and one error. Later added cases expand this authored contract.

The [eleven frozen PDF probes](../../../tests/fixtures/eapdf/validator-xmp-identity/README.md) include four valid controls/alternatives and seven negative cases. Their expected failures are pinned independently. Before-state results expose false accepts and rejection of a legal qualified value. The runtime verifier also exercises actual CLI and local web workers. [Final evidence](../validator-03-evidence.json) records exact test counts, hashes, profile reports and runtime outcomes.

```sh
env -u PYTHONPATH python3 -B -m unittest discover -s tests -p test_xmp_identity.py -v
env -u PYTHONPATH python3 -B docs/improvement/validator-03/verify-runtime.py
env -u PYTHONPATH python3 -B scripts/build_eapdf_requirements_audit.py
```

Creator sources, saved examples, CLI argument definitions and debug print functions are preserved. Integration uses the retained B13 jar and does not claim a new Java build or hosted-service deployment.

## Remaining work

Finish the mixed-rule fragment audit and remaining date/byte metadata schema checks. Six file obligations still lack executed mappings: media-type reconstruction, associated-file ModDate and four Table 8 exclusions. The eight expert/dependency questions remain explicit. Creator repairs follow validator acceptance.
