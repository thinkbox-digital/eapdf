# Docs

[User manual (PDF/A)](EA-PDF_User_Manual.pdf) — beginner instructions for the creator, both validators and viewer, with screenshots.

This directory holds lightweight documentation artifacts for the project.

Main docs:
- `CHANGELOG.md`
- `FEATURES.md`
- `GMAIL.md`
- `DEPLOY_WEB.md`
- `PRIVATE_GITHUB.md`
- `THIRD_PARTY_LICENSES.md`
- `VALIDATOR_COVERAGE.md`

Top-level release/legal files:
- `../LICENSE`
- `../NOTICE`
- `../THIRD_PARTY_NOTICES.md`
- `../third_party_licenses/`

Generated workflow output:
- `eapdf_decision_tree.pdf`

To regenerate the decision tree:

```bash
python scripts/generate_decision_tree.py
```

**2026-10-31 security checkpoint:** creator, both validators and viewer hardening
is implemented and locally verified. Creator: 358 tests (4 skips); Java validator:
214 tests (9 skips) plus 2 packaging checks; Python: 1,217 tests (43 skips), all
without failures/errors in the final runs. All 24 standard PDFs and four fresh
EML/MSG/small-PST/MBOX collections pass standalone Java checks. Builds reproduce;
public downloads/root dist JARs remain beta.4. Store/manual-platform acceptance
and other limits remain explicit. [Hardening and limits](security/HARDENING.md) ·
[Verification](security/verification.json). Large PST files remain excluded.
