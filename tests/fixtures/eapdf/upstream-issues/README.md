# Upstream EA-PDF issue probes

The frozen V01 control supplies ordinary email content; it is not the specification publication PDF. `expectations.json` pins all input hashes and requirement-derived outcomes.

| Probe | Expected automated custom result |
| --- | --- |
| `valid.pdf` | PASS, with creation provenance unavailable |
| `createdate-equal.pdf` | PASS with provenance WARN; equality with Sent is insufficient evidence of misuse |
| `createdate-earlier.pdf` | PASS with provenance WARN; an untrusted source Sent header does not prove an impossible creation event |
| `createdate-later.pdf` | PASS with provenance WARN; different dates do not establish creation provenance |
| `mcaf-listed.pdf` | PASS; corrected MCAF association refers to an already listed file |
| `mcaf-unlisted.pdf` | FAIL `AF_IN_NAME_TREE`; EA-PDF requires the associated file in the EmbeddedFiles name tree |

The timestamp controls do not certify actual creation provenance. Required field presence and upstream PDF/A checks remain separate. The negative MCAF probe is valid generic association syntax under [ISO-approved erratum 374](https://pdf-issues.pdfa.org/32000-2-2020/clause14.html#H14.13.5); its intended failure is the stronger EA-PDF name-tree requirement.

Rebuild with `env -u PYTHONPATH python3 -B scripts/build_eapdf_upstream_issue_fixtures.py`. Verify with `env -u PYTHONPATH python3 -B docs/improvement/upstream-issues/verify-runtime.py`.

[Upstream dispositions](../../../../docs/improvement/specification-audit/expert-review.html#upstream), [before-state behavior](../../../../docs/improvement/upstream-issues/pre-fix-corpus.json), [final evidence](../../../../docs/improvement/upstream-issues/evidence.json).
