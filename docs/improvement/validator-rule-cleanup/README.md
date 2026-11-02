# Runtime rule JSON cleanup

The latest GUI was built and passed all 76 checks, including the actual desktop,
before the runtime JSON was reduced. The final JAR was then rebuilt and checked.

## Runtime data

`validator_rule_text.json` retains all 225 rule codes and their existing order.
It shrinks from 1,524,044 bytes to 181,823 bytes, an 88% reduction.

| Retained field | Use |
| --- | --- |
| Rule keys | Validation rule inventory and report rows |
| `rule_number` | Java GUI and report numbers |
| `spec_section`, `spec_excerpt` | Specification explanations and bundled Java references |
| `requirements_review.requirement_ids` | Build the Java specification section/page references |
| `validation_scope.decision` | Identify checks delegated to veraPDF |
| `validation_scope.version` on delegated rules | Report the reviewed upstream version |
| `validation_scope.profile_rule_references`, where supplied, and their `version` | Verify and report reviewed PDF/A coverage |
| Runtime fields of `validation_scope.external_profile` | Select and verify Python's ISO/TS 32005 profile |

The creator does not read this registry. Java bundles the reduced registry;
neither validator loads the separate review file during validation.

## Review data

Removed fields are preserved in
[`validator-rule-review.json`](../specification-audit/validator-rule-review.json).
This includes review notes, templates, duplicate rule names, inventories,
ownership explanations and profile provenance not read during validation.
The JAR does not contain this file.

Development tools reconstruct the reviewed document through
[`load_review_rules`](../../../scripts/eapdf_rule_review.py). Runtime and review
fields cannot overwrite one another. A semantic hash verifies that combining
the files reconstructs the exact pre-cleanup JSON data, including existing
user edits. Audit generators and affected review tests now use this loader.
Historical evidence files have not been regenerated or relabeled.

## Verification and limits

The [evidence](evidence.json) records the exact final JAR, build checks,
before/after comparisons and standalone run. Java report comparison ignores
finding order and random temporary child directory names; every other value
is compared, including upstream findings and embedded reports.

Java results agree for all 808 PDFs and 67 embedded reports. Python custom
checks also agree for all 808 PDFs, including recursive child findings; ten
profile/version delegation scenarios agree. Python upstream execution settings
are unchanged. The final build passes all 76 Java checks without skips.

Two web ownership tests already fail with the pre-cleanup JSON: they expect
verified profile references that are absent from that input file. The cleanup
preserves that input and its behavior; it does not invent coverage evidence.
Three optional Python checks are skipped by the existing environment gates.

This cleanup does not establish Java/web parity. The Java port remains
incomplete and overall PASS remains disabled. The creator, Python checking
logic, selected ISO profile and protected Agenda/BFO PDFs are unchanged.
