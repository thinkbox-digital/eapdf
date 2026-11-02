# Specification-ordered rules and grouped GUI findings

## Authoritative local rulebook

[`validator_rule_text.json`](../../../validator_rule_text.json) now contains
**213 active rules**, numbered consecutively in specification order.

- Removed the 11 fully delegated PDF/A checks from the active rulebook and output.
- Moved the ISO/TS 32005 upstream entry out of the rulebook. Its Python execution
  settings are in [`ISO-32005.json`](../../../config/verapdf/ISO-32005.json).
  The Java JAR still contains its own pinned upstream profile and libraries.
- Both upstream checks remain part of the validation pipeline. A failed or
  unavailable upstream check cannot be replaced by a local PASS.
- Retained mixed rules for their EA-PDF-specific assertions.
- Retained the EA-PDF conformance-declaration requirement under the local
  identification and namespace rules; removing generic XMP checks must not
  remove EA-PDF identification requirements.
- Removed unused profile-reference data from the runtime rulebook. Retired rules,
  previous numbers and review evidence remain in the separate review record.

Order is the first mapped specification section, page and passage. Checks sharing
one passage retain their relative order. Operational diagnostics appear last
because they are not specification requirements. Rules retain all their additional
section/page mappings; a rule is listed once even when several passages apply.
Java and Python use the same active inventory. Python rejects an empty, malformed
or incorrectly numbered rulebook instead of silently loading an empty one.

The published, pinned EA-PDF specification remains the normative authority.
Rule summaries are reviewed paraphrases, not quotations or amendments.

## Completeness audit

The [source audit](coverage.json) accounts for all **301 obligations in the
existing reviewed inventory**:

| Disposition | Obligations |
| --- | ---: |
| Mapped to active rules | 244 |
| Fully upstream-owned | 5 |
| Permissions, guidance and scope statements | 20 |
| Creation definitions and process obligations | 15 |
| Software behavior outside PDF-file validation | 17 |

Every mandatory file assertion in that inventory has an active or upstream
mapping. No mandatory file assertion was dropped by this cleanup. Reader behavior
and creation process obligations are not converted into invented PDF-file checks.
This is an inventory/traceability audit; it does not certify the checking code or
establish full Java/web parity. The Java port remains incomplete and overall PASS
remains disabled.

Recheck ordering, numbering and mappings with:

```sh
python3 -B scripts/audit_eapdf_rule_registry.py
```

## GUI grouping

Rows with the same rule, result and message appear once, with a **Count** column.
Different severities and different assertions stay separate. Selecting a group
shows every occurrence's details, including filenames. Repeated identical detail
blocks carry their own counts. The saved JSON report retains the individual
findings.

On the exact PDF shown in the user's screenshot:

- **1,679 findings become 230 groups.**
- **73 embedded-stream results become one counted row.**
- All 1,679 occurrences remain accounted for.
- The PDF's existing FAIL result is retained.

![Grouped findings and individual file details](grouped-results.png)

## Verification

- Final Java build: **79 checks pass**, zero failures/errors/skips, including the
  real desktop, grouping, details, filtering and cancellation.
- Python creation/validation suite: **1,161 tests**, zero failures/errors;
  **40 optional skips**.
- Final JAR compared with the previous delivery on **808 PDFs and 67 embedded
  reports**: no unexpected changes. Removed delegated rows, numbering, totals,
  finding/reference order and random child paths are accounted for explicitly.
  All other findings, details, statuses and upstream results agree.
- The copied JAR runs from an otherwise empty directory with PATH unavailable.
  The ordinary sample has zero EA-PDF failures and passes PDF/A and selected ISO.
- The source ISO profile, creator code and protected Agenda/BFO PDFs are unchanged.

See [hashes and evidence](evidence.json). Current delivery:
[`dist/eapdf-validator.jar`](../../../dist/eapdf-validator.jar).
