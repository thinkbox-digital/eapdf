# Creation and validation rule alignment

Completed 2026-10-25. Implements the approved review of the current rulebook,
the `.old2` baseline and the recommendations in the supplied screenshot.

## Changes

- Require document-level `pdfmailmeta:attachments` as an unordered `rdf:Bag` in every profile. Missing containers fail both validators and the creator's final check. Empty Bags are valid. Reporting every original attachment remains SHOULD.
- Java rule 200 accepts nonempty normal XMP Content-Type text preserving original header errors. URI, array, absent and empty values still fail. Source/payload agreement remains a separate check.
- Web checksum explanations allow upper or lower case, matching both validation engines.
- Active rule 107 labels the standard-only namespace exemption as an unresolved local interpretation. Rules 156/157 state the unresolved creation-date interpretation. Rules 16/86 state the Table 5/6 size conflict. Existing policy is retained.
- Rebuild the Java specification resource from the active JSON. No new schema fields, CLI arguments or reader features were added.

Mandatory Sent checks, supplied In-Reply-To/checksum checks and generated
attachment filename behavior were already implemented and are retained.
The 213-rule inventory, ordering and compact JSON format remain unchanged.
The original `.old2` file and protected Agenda/BFO PDFs are unchanged.

## Verification

| Check | Result |
| --- | --- |
| Creator suite | 271 passed; zero failures, errors or skips |
| Java validator suite, including desktop | 117 passed; zero failures, errors or skips |
| Python suite | 1,162 run; 1,122 passed; 40 optional skips |
| Parent PDFs / matched embedded reports | 808 / 67 |
| Matching EA-PDF rule outcomes | 186,375 / 186,375 |
| Matching overall results | 875 / 875 |
| Missed or extra failures; execution/inspection errors | Zero |

All current EA-PDF checks were rerun in both engines. Java's upstream checks
were rerun. For the full web corpus comparison, previously verified real
upstream reports were reused after matching the PDF SHA-256 values; the current
ISO report verification still ran. The upstream profile and versions are
unchanged. No new upstream result was fabricated or treated as a fresh run.

All six ordinary samples also received fresh full web validation. Every sample
passes PDF/A and selected ISO checks with zero EA-PDF failures. Profiles 1m and
1mi retain their known inspection INCOMPLETE results.

Compared with the previous Java results, 33 rule/report outcomes change, all
under `ATTACHMENT_XMP`. One overall result changes from INCOMPLETE to FAIL.
Every other overall result is retained. Java's Content-Type correction is also
checked with valid and malformed original text, missing/empty values and wrong
XMP types.

Two controls made from the ordinary sample demonstrate the distinction:
a missing attachment container fails both engines while passing PDF/A; an
empty container passes both engines and PDF/A even though the original email
has an attachment. Its missing description remains a recommendation.

Tests for unrelated source behavior now supply valid empty containers.
Four historical fixture expectations now record the missing-container failure;
the saved PDF bytes were not changed. Neither assertions nor limits were relaxed.

The exact standalone JAR passed through its actual GUI from a folder containing
only the JAR, with Python and Docker unavailable on PATH. Specification text is
bundled, and temporary worker files were removed.

## Delivery and evidence

- [Creator CLI](../../../dist/emailtopdfa.jar)
- [Creator GUI](../../../dist/emailtopdfa-gui.jar)
- [Standalone validator](../../../dist/eapdf-validator.jar)
- [Build and source hashes](evidence.json)
- [Complete content-free comparison](comparison.json.gz)
- [Effect of the stricter rule](rule-effect.json)
- [Missing/empty container controls](controls-summary.json)
- [Fresh web sample checks](live-web-summary.json)
- [Standalone GUI proof](standalone-proof.json)
- [Desktop screenshot](window.png)

The automatic approval review timed out once on temporary desktop-check setup.
The permitted retry succeeded; the final check completed.
