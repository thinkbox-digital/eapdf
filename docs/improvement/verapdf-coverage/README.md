# veraPDF coverage and custom-rule ownership

Authority: [validator_rule_text.json](../../../validator_rule_text.json). These are generated supporting views.

All 229 current rule codes have ownership dispositions against the five pinned veraPDF 1.31.18 PDF/A profiles. Generic PDF/A prerequisites stay upstream; custom or mixed rules retain only uncovered assertions. A profile's presence is not evidence that it ran. The local ISO 32005 selection retains all 1636 unchanged upstream ISO predicates and excludes all 91 PDF/UA predicates. Its PDFUA_2 flavour selects the verifier model; it does not impose PDF/UA conformance. This selection is not an officially published standalone profile.

- `operational_only`: 11
- `retain_custom_eapdf_requirement`: 150
- `split_custom_and_verapdf`: 67
- `split_custom_declaration_and_external_structure`: 1

[Ownership details](rule-ownership.json), [profile predicates](profile-rule-index.json), [profile hashes](bundled-profiles.json), [expert review](../specification-audit/expert-review.html).

Regenerate with `python3 -B scripts/build_eapdf_requirements_audit.py`. The generator verifies every claimed upstream rule reference against the retained XML and index. The runtime delegates the 11 fully owned rules as N/A entries with separate upstream run evidence; see [routing evidence](../validator-02-evidence.json). [ISO selection provenance](iso32005-profile.json) is verified against the pinned composite and authoritative execution rule; unavailable or unverified required execution prevents PASS. Mixed-rule fragment routing remains unfinished. Ownership dispositions do not certify complete normative coverage or replace unresolved expert questions.
