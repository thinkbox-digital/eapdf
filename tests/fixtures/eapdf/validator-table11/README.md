# EA-PDF Table 11 validator probes

Authority: EA-PDF 9.7 Tables 11/13 and the user's 2026-10-19 correction that printed `Mail_ContentField` means `Mail_Field`. This is an explicitly adopted local interpretation, not a claim of official errata.

`expectations.json` pins the source and candidate hashes and exact expected failures. The control is copied from the saved single-message example. The four mutations change custom containment while retaining actual marked text, original MCIDs and ParentTree owners:

| Candidate | Required result |
| --- | --- |
| `valid.pdf` | PDF/A and EA-PDF PASS |
| `content-set-field-name.pdf` | Only `MAIL_CONTENTSET_CHILDREN` fails |
| `message-field-name.pdf` | Only `MAIL_MESSAGE_CHILDREN` fails |
| `field-content-set.pdf` | Only `MAIL_FIELD_CHILDREN` fails |
| `misparented-field-value.pdf` | `MAIL_CONTENTSET_CHILDREN` and `MAIL_FIELDVALUE_WITH_NAME` fail |

All five candidates pass independent veraPDF validation. The before-state validator accepted all five as EA-PDF PASS; the updated validator retains the valid control and rejects all four mutations with exactly the declared failures. These scoped probes do not establish complete tagging or ISO/TS 32005 conformance.

Rebuild and independently validate from the repository root:

```sh
python3 -B scripts/build_eapdf_table11_fixtures.py
```

The recipe updates these generated fixtures/expectations and writes reports into a fresh `/tmp/eapdf-table11-*` directory. The authored unit regressions additionally cover all disallowed known child types, required immediate children, empty/standard/extension children, direct-dictionary identity, optional custom tagging and authoritative report attribution.
# Custom and inherited findings

`expected_failures` retains the custom EA-PDF expectations. The separate
`expected_upstream_failures` and `expected_iso_clauses` fields record the four
negative probes' additional inherited ISO findings after upstream integration:
Art–TD, Document–TD and TR–Art inclusion violations. PDF bytes are unchanged.
Complete-pipeline comparison uses the union; custom-only checks keep their
original expectations. These inherited findings are executed by veraPDF.
