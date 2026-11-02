# V41: connected Content Group types

Supplied Mail_ContentSetType values must correspond to the DPart Content Sets
represented by that group. Global presence elsewhere does not establish that
relationship. Values retain exact characters, and optional absence remains distinct
from a supplied empty string. [Rule review](rule-review.json).

## Verification

- 683 unit tests: 643 pass, forty integration/schema skips. Seventeen new focused
  tests pass, alongside retained message GUID ownership tests using the shared helper.
- 387 custom fixture outcomes match; all 377 prior hashes/outcomes remain unchanged.
- Ten exact-byte before/after controls: three false passes now FAIL, two unsupported
  complete passes become INCOMPLETE, one false comparison failure becomes PASS,
  and another becomes INCOMPLETE. Four legal/internal-correspondence controls PASS.
- All ten controls pass the exercised PDF/A and verified ISO32005 profiles. Two
  exact V40 before-runs are reused; eighteen new control engine runs are recorded.
- Actual CLI rejection and isolated-worker INCOMPLETE transition match the library.
- Twelve fresh retained creator profiles cover all six modes in normal/accessibility
  variants. Their PDF/A/ISO checks pass and known operand/child failures persist.
  Creator acceptance remains failed; Java, original files and JAR are unchanged.

The shared bounded helper resolves actual MCID/OBJR/Form pages and DPart candidates
without consulting tested values. GUID and type uncertainty are independent;
compatible ambiguity remains unverified and known contradictions survive other
unavailable data. Missing recommended attributes WARN; conditional matching is N/A
only for established absence. Non-string values are not compared through coercion.

Table13 mentions XMP matching, but Table18 does not define the group field/binding.
Legacy candidate properties or unreadable XMP therefore remain explicitly unverified.
No new metadata-presence rule or external answer is inferred. The opaque extension
control proves internal name correspondence, not its source/vendor semantics.
Complete logical coverage, source occurrences, forty skips and eleven listed
questions retain their wider limits.

The first probe wrapped an existing group and correctly failed Table11; it is kept
separate from the corrected exact-byte probes. See [design review](design-review.md).

## Reproduction

The archive/manifest freeze the runtime, rule text, tests and exact fixtures.
Run tests in `.eapdf-verification-v41`; use `verify-regressions.py` and
`verify-pdfs.py` (resumable). `refresh-observations.py` reads the bounded cohort;
`assemble-evidence.py` checks hashes, findings, transitions and retained failures.
See [evidence.json](evidence.json).
