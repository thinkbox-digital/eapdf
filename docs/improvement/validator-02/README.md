# Validator V02: apply reviewed veraPDF ownership

Authority: [validator_rule_text.json](../../../validator_rule_text.json). This batch implements the eleven `delegate_to_verapdf` dispositions; it does not close the remaining mixed-rule audit or specification questions.

## Changes

- Normal CLI and web-worker validation skip the duplicate OutputIntent, UTF-8 and PDF/A-3 extension-schema conformance checks. Font discovery remains an operational observation; embedding conformance is delegated. Generic embedded-subtype assertions are skipped while EA-PDF subtype/XMP comparisons stay active.
- Document XMP is still parsed to obtain EA-PDF evidence. Missing/unreadable XMP continues to fail the dependent EA-PDF checks. Generic XMP presence/parsing conformance is delegated.
- Each delegated rule produces one N/A row with `state: delegated`, the separate upstream status, reported version, applicable reviewed references and a coverage-verification flag. It does not produce an inferred individual PASS. Combined PASS still requires a successful, compliant, allowed-profile veraPDF run.
- Web visibility uses these reviewed ownership decisions. EA-PDF version restrictions, Info/XMP crosswalks, Keywords, PieceInfo and mixed rules remain visible. Delegated rows can be hidden only when every compared first pass passed under the reviewed version and applicable profile. Missing/failed local results remain visible.
- JSON-only edits invalidate the web module cache as well as the rule-text cache. The first N/A result replaces the initial display placeholder, so delegation is not mislabeled as non-applicability.

Existing check helpers remain callable as diagnostics. The normal `validate()` / `validate_one()` / `validate_pdf()` pipeline applies delegation. `validate()` is the custom portion; use `validate_one()` or the CLI for the combined verdict. An ownership mapping is not proof that upstream validation ran.

## Evidence and reproduction

The authored tests in [test_validator_ownership.py](../../../tests/test_validator_ownership.py) first failed against the preserved V01 source. The [pre-fix log](pre-fix-tests.log) records those failures. See [batch evidence](../validator-02-evidence.json) for final test counts, source hashes, integration results and limitations.

```sh
env -u PYTHONPATH python3 -B -m unittest discover -s tests -p test_validator_ownership.py -v
env -u PYTHONPATH python3 -B docs/improvement/validator-02/verify-runtime.py
env -u PYTHONPATH python3 -B scripts/build_eapdf_requirements_audit.py
```

The runtime verification uses the existing independent Table 11 expectations: a valid control must PASS, and a prohibited child must fail exactly `MAIL_CONTENTSET_CHILDREN`. Both must PASS veraPDF. It exercises the actual CLI subprocess and local web validation worker and checks the rendered comparison attributes. The first-pass engine requires Docker and the configured veraPDF image.

## Next work

Review each mixed rule's remaining local assertion against its retained profile predicates, then repair metadata representation and identity checks using the reviewed contracts. Six file requirements still lack executed-rule mappings: media-type reconstruction, associated-file ModDate and four Table 8 Content Set exclusions. Eight interpretation/dependency questions remain open. Creator repairs follow validator acceptance.

No remote deployment is claimed. Local code and worker verification do not update a separately hosted service.
