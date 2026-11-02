# V85: Original attachment filename alternatives

Table 19 permits the original filename to come from either Content-Disposition filename or Content-Type name, without prescribing precedence. Source attachment summaries preserve both understood values. Matching and source-derived alias checksums accept either value without changing the one-occurrence reservation. Nonempty original whitespace names remain present instead of being trimmed into absence.

Known nested mail candidates are recognized through either original parameter. The primary filename remains available for existing diagnostics; comparison receipts also list the permitted source filenames.

Eight native controls include both choices, encoded Unicode disposition names with a different type-name choice, optional checksum omission, literal space/tab filenames and known wrong-name/size negatives. Five false failures become PASS. Eleven focused tests also protect exact whitespace/case, one occurrence under two names, differing-payload checksum ambiguity, RFC2231 values and nested-mail recognition.

Verification: 1155 unit tests (1115 passes, 40 skips); 774 fresh custom cases with all 766 prior hashes/statuses/failure sets unchanged; eight new and four positive collection PDF/A/ISO controls; CLI/worker agreement; twelve retained creator failures; sixteen offline browser checks. See [evidence.json](evidence.json).

Existing empty supplied filename failures remain. Unknown filename encodings, duplicate MIME parameter resolution, arbitrary native filename aliases, complete source enumeration and unique occurrence ownership are not certified. No creator changes or sample acceptance are claimed.

Acceptance was completed after recovering thread `01a16350-a8e5-77f0-8683-0552b5d55dee`. Its saved test receipts were verified against all 1783 frozen/archive input hashes, all 774 custom PDF hashes and all 26 pipeline reports. Runtime, rule, test and creator inputs still match the frozen versions. Five generated audit/browser artifacts were refreshed after freezing; their current hashes are recorded separately in `acceptance-recovery.json`. V86 remains deferred pending instructions.
