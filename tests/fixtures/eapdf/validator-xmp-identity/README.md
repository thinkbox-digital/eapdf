# XMP and Mail_GUID probes

Authority: the reviewed `XMP-SEMANTIC-VALUES`, `XMP-ARRAY-SEMANTICS` and `GUID-IDENTITY` contracts in [validator_rule_text.json](../../../../validator_rule_text.json), including EA-PDF Tables 16, 18 and 19. Generic XMP/PDF-A conformance is checked separately with veraPDF.

The source is the frozen PDF/A-valid V01 control. The expected outcomes follow the reviewed requirements, independently of creator behavior. `expectations.json` pins source and candidate hashes.

| Fixture | Expected custom outcome |
| --- | --- |
| `valid.pdf` | PASS |
| `guid-spaces-consistent.pdf` | PASS: whitespace is retained identically in all identity locations |
| `guid-uri-looking-text.pdf` | PASS: URI-looking normal text is a legal GUID |
| `guid-qualified.pdf` | PASS: `rdf:value` carries the normal GUID string |
| `dpm-guid-whitespace-mismatch.pdf` | FAIL: DPM/XMP mismatch, structure/DPM mismatch and lost attachment/body owner linkage |
| `structure-guid-whitespace-mismatch.pdf` | FAIL: structure GUID differs from DPM/XMP |
| `guid-uri-value.pdf` | FAIL: the email GUID is a URI value instead of a normal string and cannot satisfy DPM/email identity matching |
| `email-ordered.pdf` | FAIL: email records require an unordered array |
| `assets-ordered.pdf` | FAIL: asset records require an unordered array |
| `attachments-ordered.pdf` | FAIL: a supplied attachment array must be unordered; no new absence requirement is introduced |
| `to-unordered.pdf` | FAIL: To requires an ordered agent array |

The preserved V02 engine incorrectly accepted both whitespace mismatches, ordered asset records and unordered To agents. It also rejected the valid qualified-GUID representation. See [before-state results](../../../../docs/improvement/validator-03/pre-fix-corpus.json).

Rebuild with `env -u PYTHONPATH python3 -B scripts/build_eapdf_xmp_identity_fixtures.py`. Verify with `env -u PYTHONPATH python3 -B docs/improvement/validator-03/verify-runtime.py`. The latter checks all candidates with veraPDF and the custom engine, then exercises actual CLI and local web-worker entry points. See [verification evidence](../../../../docs/improvement/validator-03-evidence.json).
