# V24: custom content operands and actual structure ownership

EA-PDF 9.7, printed p75, requires marked-content operands to match their
corresponding custom EA-PDF structure element names. The former check compared
a global set of names. It accepted swapped owners, standard operands owned by
custom elements, and ambiguous ownership. Seven pre-fix regressions reproduce
those defects in `operand-before.log`.

The repaired check resolves page or Form scope, MCID, inherited pages,
MCR stream/page references and named resource properties. It compares the
actual owner of each sequence. Definite mismatches FAIL. Missing/ambiguous
ownership remains WARN with VALIDATION_INCOMPLETE; another matched sequence
cannot establish aggregate PASS. Standard-only content retains N/A for this
custom assertion. Generic structure conformance remains separately upstream.

Authority remains [validator_rule_text.json](../../../validator_rule_text.json),
rule 106 / `CUSTOM_TAG_OPERAND_MATCH` / `TAGGING-OPERAND`.

## Creator conformance finding

Independent object inspection confirms that **194 of the 202 retained V23
PDFs** use `/Span` content operands owned by `Mail_FieldName` or
`Mail_FieldValue`. **The other eight PDFs contain affected collection members.**
Their exact historical bytes are preserved. The former validator missed the
defect; earlier PASS reports retain their historical verification boundary.

`review_eapdf_operand_corpus.py` does not import the validator. It reads the
actual structure K/Pg and content MCID objects, including collection children.
[Independent review](retained-operand-review.json) records each mismatch and
embedded-child hash. Previous expectations are retained in
`before-expectations/`; current manifests require the independently proved
operand/child failures alongside the previously expected failures.

The creator conformance gate is open. No creator source, JAR, saved example,
CLI argument or debug behavior is changed by V24. Creator repairs remain in
the plan's subsequent phase. Do not interpret a successful diagnosis script
as passing creator conformance.

## Verification boundaries

[Final evidence](evidence.json) accepts the scoped validator repair: **473 unit
passes / 40 skips, 207 custom comparisons, six PDF probes and six actual
CLI/worker checks**. All 26 focused tests pass. All twelve original creator
conformance gates fail on the diagnosed defect, with PDF/A and ISO PASS.
The asserted gates are:

- Frozen Python units, including the 26 focused metadata/operand tests.
- All 207 custom comparisons: 202 historical PDFs plus five new controls.
  Preserve every historical hash and add only independently proved operand
  or contained-child failures. Custom-only execution omits the explicitly
  upstream ISO failure in the existing inclusion-rule counterexample.
- Six exact-byte before/after PDF probes with real PDF/A and verified ISO
  execution, followed by six actual CLI/isolated-worker checks.
- All twelve original normal/accessibility creator profile conformance gates.
  Retain their original assertions and generated PDFs; diagnose their known
  failures instead of weakening acceptance.
- A focused production-emission census establishes observed results for all
  nine checks absent from V23's census. The seven metadata checks exercise
  positive/negative/missing-counterpart conditions; generic source media is
  a recommendation WARN. This does not certify all obligation coverage.

The five new fixtures include corrected custom operands, swapped operands,
standard operands with custom owners, named properties and standard-only
structure. The standard-only control remains INCOMPLETE under the separately
open namespace/role-map assessment; its operand result is correctly N/A.

The before-state engine must be imported from `.eapdf-verification-v23`,
with its complete original rules/profile context. An initial harness imported
the archived standalone Python file from this directory and lacked that
context. Its failed logs are preserved. A separate initial regression harness
also compared a full-pipeline ISO expectation with custom-only execution;
that failed assertion is retained and the scopes are now explicit.

## Reproduction

`freeze-inputs.py` records runtime, rules, tests, fixtures and supporting inputs
under `.eapdf-verification-v24` and `runtime-inputs.zip`. The unchanged accepted
creator JAR is separately hash-verified by `diagnose-profiles.py`.

Run `verify-regressions.py`, `verify-new-coverage.py`, `verify-pdfs.py` and
`diagnose-profiles.py`. The last script runs the existing acceptance tests and
requires their twelve known conformance failures to be exactly the independently
diagnosed defect. Required normal test assertions remain intact. The scripts
retain original failures and generated files for inspection.

The original [whole-validator exit checklist](../coverage-exit-review/README.md)
remains a V23 evidence snapshot. [Five-profile upstream controls](../upstream-profile-execution/README.md)
are a separate PDF/A-only gate. Eleven interpretation/dependency questions,
source/attachment occurrence review, shared predicate coverage and release
acceptance remain open. Full coverage is not claimed.
