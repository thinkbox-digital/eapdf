# V79: Coverage follows the matched original

V79 binds additional source-header coverage and final source-derived applicability to the selected email original. An unresolved owner stays unavailable; optional raw-address absence remains N/A independently of unrelated originals. Match receipts identify the selected original by filename, SHA-256 and object identity.

The fourteen new PDFs include reversed catalog order, five omitted-header cases, mixed Date validity, matches to the second original, unchanged positives and definite universal contradictions. Five unsupported FAIL results become INCOMPLETE, one false FAIL passes, and three unperformed comparisons become PASS after using the correct source. All 715 prior hashes, statuses and failure sets remain unchanged.

Verification: 1096 unit tests (1056 passes, 40 skips); 729 fresh custom cases; fourteen new PDF/A/ISO controls and four positive collection controls; CLI and web worker agreement; twelve retained creator failures; sixteen offline browser checks. See [evidence.json](evidence.json).

The rejected first unit run is preserved in first-pass/. It exposed a legacy test that treated optional Raw-To/Raw-From presence as required whenever the original header existed, and did not model physical supplied empty values. Corrected tests preserve ordinary omission and require unresolved assessment for supplied values. The expanded supplied-empty control then exposed another fallback that still treated empty text as absent. That runtime branch was corrected; second-pass/ preserves the rejected cohort, and final root receipts execute the corrected frozen runtime.

Full source/attachment occurrence ownership, source candidate enumeration, cited draft-edition equivalence, obligation coverage and creator repairs remain open. The independently reproduced viewer attachment-scope defect is tracked for V80; this checkpoint does not claim it fixed.
