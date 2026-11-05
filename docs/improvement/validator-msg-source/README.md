# Original MSG comparisons and recipient order

Completed 2026-10-25. Updates the creator and both validators.

## Changes

Both validators can now read stored Outlook transport headers and complete
ordinary file-attachment inventories directly from embedded MSG bytes. Existing
email metadata, date, size, attachment, body-type and source-property checks use
this evidence. The source checksum and size always describe the original MSG,
not an invented EML export.

Missing attachment MIME-type properties remain absent. The attachment's original
name, exact payload size and MD5 are still checked; the original-type comparison
is N/A when no native MIME-type value exists. The required XMP Content-Type and
its agreement with the embedded PDF attachment remain separate checks.

The new checks exposed two actual creator errors: native Outlook recipient
lists had a different order from their stored transport headers. The creator
now follows the stored header order only when both contain exactly the same
recipient occurrences. Native names, addresses and repeated occurrences remain.
Differing recipient inventories are not silently replaced.

Python also misread a valid folded quoted display name as an invalid mailbox.
Unfolding the line break before parsing fixes it while preserving the raw header
and significant spaces inside quoted mailbox names.

Java's existing URL check now recognizes the same stored redirect destinations
and wrapped URL continuations as the web engine. This fixes false warnings on
these real MSG outputs. It creates no PDF links and follows no network URLs.

The native storage layout follows Microsoft's
[MS-OXMSG structure description](https://learn.microsoft.com/en-us/openspecs/exchange_server_protocols/ms-oxmsg/621801cb-b617-474c-bce6-69037d73461a).
Apache POI is bundled inside the standalone JAR; web inspection uses `olefile`,
now declared in its requirements. Unavailable dependencies or unsupported
storage do not produce a source-comparison PASS.

## Scope and remaining work

The tested source-inspection scope covers **12 of the 31 supplied ordinary MSG
inputs**. Missing transport headers, nested MSG attachment storages,
inline-resource inventories and undecodable/incomplete storage remain
unavailable. Native Outlook properties without transport headers are the next
source-comparison task. This is not complete MSG format support.

The isolated attachment-evidence limit, three documented specification
interpretations and very large archive resource limits remain. The active
rulebook, existing samples and protected Agenda/BFO PDFs are unchanged.

## Verification

| Check | Result |
| --- | --- |
| Complete creator suite | 273 passed; zero failures/errors/skips |
| Complete Java validator suite, including desktop | 130 passed; zero failures/errors/skips |
| Complete Python suite | 1,169 run; 1,129 passed; 40 optional skips |
| Existing parent PDFs / embedded reports | 808 / 67 |
| Existing matching rule outcomes / overall results | 186,375 / 875 |
| Additional real MSG cases | 14; every rule and overall result matches |
| Additional matching rule outcomes | 2,982 |
| Total matching rule outcomes / reports | 189,357 / 889 |
| Missed/extra failures; execution/inspection errors | Zero |

The real cases cover 12 passing outputs and both previous recipient-order
outputs, which correctly FAIL. Both corrected outputs PASS. All 14 pass PDF/A.
Metadata contradictions and corrupt attachment checksums are rejected by the
focused tests. Unsupported cases retain unavailable results.

All current EA-PDF checks were rerun over the full existing corpus. Java's real
upstream corpus runs use the exact delivered JAR; these reports were retained
while the Python folded-header repair was verified. The Python corpus reuses
previously verified real upstream reports only after exact PDF SHA-256 matching;
current ISO report verification still runs. All 14 MSG cases separately received
fresh full web validation. Cached upstream reports are not described as fresh
runs.

Across all 31 real MSG inputs, original bytes, body text/HTML/RTF, subjects, sent
dates, native attachment bytes/names/types and participant names/mailboxes are
unchanged. Only the two displayed recipient orders change.

The exact standalone JAR passes through its actual GUI on the corrected MSG
with only the JAR in its working folder and Python/Docker unavailable on PATH.
Bundled specification text loads, and temporary worker files are cleaned.
No validator test entry is available through the IDE. Creator IDE compilation
is configured under `target/`, which project rules prohibit modifying; runtime
evidence therefore comes from isolated `/tmp` builds and direct JVM runs.
No debugger sessions or agent breakpoints were created.

## Delivery and evidence

- [Creator CLI](../../../dist/emailtopdfa.jar)
- [Creator GUI](../../../dist/emailtopdfa-gui.jar)
- [Standalone validator](../../../dist/eapdf-validator.jar)
- Corrected recipient order (local artifact; not distributed)
- Corrected Cc order (local artifact; not distributed)
- [Build, source hashes and test results](evidence.json)
- [Full existing corpus comparison](comparison.json.gz)
- [Real MSG comparisons and negative controls](real-comparison.json)
- [Creator preservation evidence](creator-preservation.json)
- [Supported and unsupported input inventory](supported-scope.json)
- [Single-JAR GUI proof](standalone-proof.json)
- [Desktop screenshot](window.png)
