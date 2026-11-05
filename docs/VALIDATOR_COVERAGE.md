# Validation coverage and limitations

The standalone Java validator checks EA-PDF identification, profile constraints,
metadata, document parts, collections, logical structure, embedded source emails,
and attachment relationships. The rule descriptions are in
[validator_rule_text.json](../validator_rule_text.json).

PDF/A and selected ISO structural checks use veraPDF. Collection members are
validated recursively. Arlington is an optional additional PDF object-model check;
see [Arlington validation](ARLINGTON_VALIDATION.md).

## Interpreting results

- **PASS** means the applicable checks completed without a reported violation.
- **FAIL** identifies a definite violation supported by the available evidence.
- **INCOMPLETE** means a required assessment could not finish or evidence was
  unavailable. Resource exhaustion, unsupported decoding and ambiguous source
  relationships must not become a pass.
- A warning may concern a recommendation rather than a mandatory requirement.

A successful outer collection check does not establish that every embedded member
passed. Review the complete report, including child results and incomplete checks.

## Limits

Source comparisons require an inspectable original and an unambiguous link to its
message. Missing source dates cannot be reconstructed reliably. Native MSG and
other unsupported source representations can leave comparisons incomplete.
Isolated profiles deliberately omit original source assets, limiting comparisons.

Resource budgets protect the validator from malformed or unusually large files.
An interrupted or resource-limited assessment remains incomplete even when some
independent checks succeeded. See the [Java validator guide](../validator-desktop/README.md)
for execution settings.

PDF/UA identification and tagging do not certify accessibility. Meaningful image
alternatives, reading order and usability require human review in addition to
machine checks. Validation also cannot establish provenance or authenticity
without independent evidence.
