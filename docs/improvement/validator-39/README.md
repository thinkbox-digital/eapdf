# V39: message GUIDs follow actual content owners

A GUID present on another email cannot establish consistency of the connected
Mail_Message content. This owner-correspondence interpretation is reviewed from
the combined EA-PDF9.6/9.7 representation and identity obligations, with its
inference stated explicitly in [rule-review.json](rule-review.json).

## Verification

- 658 unit tests: 618 pass, forty integration/schema skips. Seventeen focused tests
  exercise known, ambiguous and absent ownership, shared Forms, annotation references,
  actual page scope, traversal limits and order independence.
- 367 custom fixture outcomes match; all 358 prior hashes/outcomes remain unchanged.
- Nine exact-byte before/after PDF controls: three global-membership false passes
  now FAIL; an already rejected DPM swap gains its proved logical-owner mismatch;
  five legal controls PASS, including reordered structure/XMP and standard header tags.
- All nine controls pass the exercised PDF/A and verified ISO32005 profiles. Two
  exact V38 before-runs are reused; sixteen new control engine runs are recorded.
- Actual CLI rejection and worker acceptance of optional standard header tags match.
- Twelve fresh original creator runs cover all six profiles in both modes. Their
  PDF/A/ISO checks pass and their known operand/child conformance failures persist.
  Creator acceptance remains failed; original files, Java and JAR are unchanged.

Candidate owners are resolved without the GUID being tested. Definite universal
contradictions fail; ambiguous/missing content ownership and resource limits remain
explicitly unverified. Uniform Form candidates can match without choosing an
invocation. The focused Form/annotation tests are not certified whole PDF fixtures.
Original-source rendering fidelity and occurrence assignments remain separate.
Forty skips, eleven listed questions and wider coverage gates remain open.

Initial budget-test scope and missing-owner expectation mistakes are retained.
The initial XMP reorder recipe dropped an unused required namespace declaration;
the corrected fixture preserves it. These preliminary results do not establish
accepted positive controls.

## Reproduction

The archive/manifest freeze runtime, rules, independent seeds and all fixtures.
Run unit tests in `.eapdf-verification-v39`; `verify-regressions.py` maintains a
checkpoint tied to the exact frozen inputs. `verify-pdfs.py` supports `--resume`;
`refresh-observations.py` reads the bounded execution cohort, and
`assemble-evidence.py` checks hashes, exact findings and retained creator failures.
See [evidence.json](evidence.json).
