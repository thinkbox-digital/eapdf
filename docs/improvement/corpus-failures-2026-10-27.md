# Recorded corpus failures — 2026-10-27

**Current rerun — 2026-10-29:** The entries below are historical. Current builds,
per-input results and qualifications are in [Development Status](../DEVELOPMENT_STATUS.md).
The final 91-input ledger is still in progress under
local verification records; no complete new pass count is claimed yet.

Large-file checking now finishes. The full preceding PST 06 output passes PDF/A
and ISO 32005 with zero EA-PDF failures, but remains INCOMPLETE because some
source checks are unavailable. Four native attachment entries cannot be recovered.
PST 23 preserves all 8,205 messages and 1,316 readable attachment payloads; it
fails three mandatory Sent checks because the original notifications have no
submission or delivery dates. Both archives are being regenerated on the final
creator. Conversion success is kept separate from compliance success.

Creator repairs include mailbox URI encoding, repeated recipient headers, UTF-7
source recovery, native display-address parsing, hidden form widgets, heading/list
structure and a reproduced parallel HTML-rendering race. All 342 creator tests
pass. The 282-email collection now passes all embedded checks on the final
creator after the race repair. Java and web validation retain finite
limits and explicit incomplete results.

## Historical results

**Update — 2026-10-28, incomplete-source recovery:** The three previously rejected
MBOX inputs now produce PDFs with missing-date warnings. They still fail the
mandatory Sent rule; conversion success must not be counted as validation PASS.
The actual missing-attachment message in PST 06 also produces marked output with
its readable content and remaining attachment. Its standalone Java result is
INCOMPLETE. A full repaired PST 06 run is still needed. Evidence for 18 profile
outputs and three normal CLI runs is in local verification records.
All output PDFs pass PDF/A; original source hashes are unchanged.

Older strict-policy PST reruns found a missing date in PST 23 and Java heap
exhaustion in PST 20 and PST 22. PST 24's corrected metadata-budget retry also exhausted
its Java heap. These remain unresolved full-file results, not passes. See the
[current status](../DEVELOPMENT_STATUS.md) and local verification records.
The original audit below is retained unchanged.

This is the last recorded full corpus result, not a new test run. Of 95 retained input files, 58 passed, 23 produced PDFs with incomplete validation, 10 failed conversion, and four were rejected as non-email Outlook items. Reader-only tests and PDF/A-only passes are not complete conversion-and-validation passes.

The 24 examples regenerated on 2026-10-28 use a smaller selected set. Separate
corpus repair runs below resolve 21 of the original 23 incomplete validations;
the historical tables retain the original findings. Six large-PST results,
four missing-data conversion failures and two MSG source-comparison limits remain
open. The four non-email rejections were expected, not creator defects.

Source: [full corpus audit](real-world-corpus-2026-10-27.json).

## Repair results — 2026-10-28

The original results below remain the historical record. The following are new checks, not a replacement full-corpus run.

- All 32 real MSG emails were converted again. **30 pass standalone Java validation; two remain INCOMPLETE. No conversion failed.** Source SHA-256 hashes are unchanged. Logs, output hashes and individual Java reports are retained in local verification records and its case directories.
- `2019.10.08 DEP response.msg`: the stored author uses an Exchange address without a recoverable SMTP mailbox. Other readable fields are checked; author comparison remains incomplete.
- `test_Blammo.msg`: ANSI string properties lack the message code-page declaration. The validator still cannot independently decode and verify those fields. A readable PDF is produced; that does not make source validation complete.
- `sediment.msg`: new source checks exposed a real creator error in CC order and spelling. The creator now preserves the original header order and case when native Outlook mailboxes differ only in case. The new output passes. The old output is retained and correctly fails.
- Native MSG checks now use stored properties when transport headers are absent, distinguish actual inline resources from ordinary attachments, and compare nested MSG contents. Unknown data remains explicitly unavailable.
- The real `in/test.pst` produces 71 single-email PDFs which all pass. The new collection also passes, including its 71 embedded emails. Evidence: local verification records.
- Large-PST conversion now stages each email in one working file, streams single-email exports through bounded workers, and gives large individual PST inputs finite budgets based on their size. Explicit resource settings remain effective. Folder collections now honor bounded parallel rendering.
- The three incomplete PST archives (`pst_02_07+July+2003+Public+2.pst`, `pst_14_05+May+2003+Public+2.pst`, and `pst_21_04+April+2003+Public+2.pst`) were rebuilt with four requested rendering jobs and now pass standalone Java validation. The creator retains unusual source date strings, interprets two-digit years correctly, and matches raw address metadata to the embedded reconstructed email. Original transport headers remain preserved. Source hashes, logs and reports: local verification records.
- The 1.26 GB `pst_16_2005b_May-Jul_GovernF.pst` run has rendered all 48,129 child PDFs and is still assembling the parent. **It has not completed or passed validation.** This attempt includes parallel rendering and working-file cleanup, but predates filename indexing and the latest source-date/accessibility fixes. Run evidence is in local verification records. Verified finished child PDFs were moved off RAM-backed storage to release about 4 GB; their original paths remain available to the worker.
- The other five large-PST failures and four missing-data conversion failures below have not been resolved by these checks.

Large-collection validation also needs further work: embedded checksums are now streamed instead of retaining every payload, and PDFBox uses disk scratch storage. Recursive validation still stops after 256 children or 256 MiB of child data. These limits must not be mistaken for a complete large-archive validation pass.

Latest regression checks: 308 creator tests passed; the Java validator ran 166 tests with one desktop-only skip and no failures; Python ran 1,191 tests with 40 optional skips and no failures. Two unchanged negative fixtures now correctly report additional mismatched raw addresses/message ID; their expected findings were updated after direct source comparison, without changing the PDFs. All 24 real examples were rebuilt again and pass Java validation; see local verification records. [Accessibility and speed evidence](accessibility-2026-10-28/README.md).

## Processing limit (6)

| Input in `in/` | Recorded result |
| --- | --- |
| `pst_16_2005b_May-Jul_GovernF.pst` | Export metadata exceeds the processing limit of 33554432 bytes Higher-budget retry: error: Conversion exceeded the temporary-file count limit of 100000 |
| `pst_20_200501-06JanJun.pst` | Export metadata exceeds the processing limit of 33554432 bytes Higher-budget retry: error: Conversion exceeded the temporary-file count limit of 100000 |
| `pst_22_200401-06JanJun.pst` | Export metadata exceeds the processing limit of 33554432 bytes Higher-budget retry: Exception in thread "main" java.lang.RuntimeException: java.lang.OutOfMemoryError: Java heap space; Caused by: java.lang.OutOfMemoryError: Java heap space |
| `pst_23_2005d_Nov-Dec_GovernF.pst` | Export metadata exceeds the processing limit of 33554432 bytes Higher-budget retry: error: Conversion exceeded the whole-job time limit |
| `pst_24_GovernFJan-MAr2003.pst` | Export metadata exceeds the processing limit of 33554432 bytes Higher-budget retry: error: Conversion exceeded the temporary-file count limit of 100000 |
| `pst_25_2005b_Mar-April_GovernFShiavo.pst` | Export metadata exceeds the processing limit of 33554432 bytes Higher-budget retry: error: Conversion exceeded the temporary-file count limit of 100000 |

## Missing source data (4)

| Input in `in/` | Recorded result |
| --- | --- |
| `Drafts.mbox` | A message has no verifiable sent date; no date was invented. |
| `mbox_01_fmt_720_MBOX_quoted.mbox` | A message has no verifiable sent date; no date was invented. |
| `msg.mbox` | A message has no verifiable sent date; no date was invented. |
| `pst_06_1999.pst` | Native attachment payload missing at message 5068004, attachment 1; no empty replacement was invented. |

## Rejected input (4)

| Input in `in/` | Recorded result |
| --- | --- |
| `Swetlana Novikova.msg` | Outlook contact or note, not an email. |
| `contact-plain.msg` | Outlook contact or note, not an email. |
| `contact-unicode.msg` | Outlook contact or note, not an email. |
| `note.msg` | Outlook contact or note, not an email. |

## Validation incomplete (23)

| Input in `in/` | Recorded result |
| --- | --- |
| `2019.10.07 Weary comments.msg` | PDF/A passed; EA-PDF checks remain incomplete: native attachment inventory not independently inspectable; source email cannot be matched unambiguously. |
| `2019.10.08 DEP response.msg` | PDF/A passed; EA-PDF checks remain incomplete: source email cannot be matched unambiguously; viewer attachment conditions unavailable. |
| `EmailWithAttachments.msg` | PDF/A passed; EA-PDF checks remain incomplete: native attachment inventory not independently inspectable; source email cannot be matched unambiguously. |
| `EmailWithInnerMailAndAttachments.MSG` | PDF/A passed; EA-PDF checks remain incomplete: native attachment inventory not independently inspectable; source email cannot be matched unambiguously. |
| `EmailWithMsgAttachment.msg` | PDF/A passed; EA-PDF checks remain incomplete: native attachment inventory not independently inspectable; source email cannot be matched unambiguously. |
| `RtfWithShortRussianString.msg` | PDF/A passed; EA-PDF checks remain incomplete: source email cannot be matched unambiguously; viewer attachment conditions unavailable. |
| `message.msg` | PDF/A passed; EA-PDF checks remain incomplete: source email cannot be matched unambiguously. |
| `msgKitOut_attach.msg` | PDF/A passed; EA-PDF checks remain incomplete: source email cannot be matched unambiguously. |
| `msgKitOut_noattach.msg` | PDF/A passed; EA-PDF checks remain incomplete: source email cannot be matched unambiguously; viewer attachment conditions unavailable. |
| `msg_13_msgKitOut_attach.msg` | PDF/A passed; EA-PDF checks remain incomplete: source email cannot be matched unambiguously. |
| `no-attachments.msg` | PDF/A passed; EA-PDF checks remain incomplete: source email cannot be matched unambiguously; viewer attachment conditions unavailable. |
| `pst_02_07+July+2003+Public+2.pst` | PDF/A passed; EA-PDF checks remain incomplete: source email cannot be matched unambiguously; original body media comparison unavailable. |
| `pst_14_05+May+2003+Public+2.pst` | PDF/A passed; EA-PDF checks remain incomplete: source email cannot be matched unambiguously; original body media comparison unavailable. |
| `pst_21_04+April+2003+Public+2.pst` | PDF/A passed; EA-PDF checks remain incomplete: source email cannot be matched unambiguously; original body media comparison unavailable. |
| `qwerty_1-orig.msg` | PDF/A passed; EA-PDF checks remain incomplete: source email cannot be matched unambiguously; viewer attachment conditions unavailable. |
| `qwerty_2-with_custom_properties.msg` | PDF/A passed; EA-PDF checks remain incomplete: source email cannot be matched unambiguously; viewer attachment conditions unavailable. |
| `qwerty_3.msg` | PDF/A passed; EA-PDF checks remain incomplete: source email cannot be matched unambiguously; viewer attachment conditions unavailable. |
| `sediment.msg` | PDF/A passed; EA-PDF checks remain incomplete: native attachment inventory not independently inspectable; source email cannot be matched unambiguously. |
| `testOut.msg` | PDF/A passed; EA-PDF checks remain incomplete: source email cannot be matched unambiguously. |
| `testOut_attach.msg` | PDF/A passed; EA-PDF checks remain incomplete: source email cannot be matched unambiguously. |
| `testOut_noattach.msg` | PDF/A passed; EA-PDF checks remain incomplete: source email cannot be matched unambiguously; viewer attachment conditions unavailable. |
| `test_Blammo.msg` | PDF/A passed; EA-PDF checks remain incomplete: source email cannot be matched unambiguously; viewer attachment conditions unavailable. |
| `test_internal.msg` | PDF/A passed; EA-PDF checks remain incomplete: source email cannot be matched unambiguously. |
