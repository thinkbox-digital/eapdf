# C05: MSG native body selection

The MSG reader always preferred HTML converted from RTF before native HTML.
It ignored the declared native-body format, so a message declaring HTML or
plain text could render an older RTF alternative and describe that alternative
as its body subtype.

The reader now honors available plain-text, RTF and HTML native-body declarations.
When no usable declaration exists, it considers the stored synchronization flag
and available representations. A declared but unavailable representation produces
an explicit fallback warning. The selected DPart subtype follows the actual
source representation used for rendering. Original MSG bytes remain unchanged.

The [native-body property definition](https://learn.microsoft.com/en-us/openspecs/exchange_server_protocols/ms-oxcmsg/71428f1c-a004-4c05-bc8e-6a687de06a2e)
and [RTF synchronization definition](https://learn.microsoft.com/en-us/office/client-developer/outlook/mapi/pidtagrtfinsync-canonical-property)
establish the selection inputs. The parser exposed the native format as an
Integer but returned no synchronization value for the actual stored Boolean.
The creator therefore reads that typed fixed property directly from the MSG
table, with the existing metadata budget. It neither modifies nor synchronizes
the source message.

IntelliJ captured declarations 1, 2 and 3 with distinct native/converted HTML,
while the old reader selected converted RTF for all three. Six final logpoint
events have no evaluation errors. The first attempt's expression errors are
retained separately. Three of five initial regressions failed. Final positive
fixtures keep RTF and plain text consistent whenever their flag declares them
in sync; the earlier diagnostic fixtures remain retained.

Verification: **181 Java tests pass**, without failures, errors or skips.
All twelve ordinary normal/accessibility profiles pass unchanged V85 EA-PDF,
actual PDF/A and the verified ISO profile, including the established child and
sidecar checks. Six additional native MSG profiles pass PDF/A and ISO with zero
mandatory failures. Extracted PDF text and native DPart subtypes prove plain,
RTF and HTML selection; reopened source streams match the exact MSG inputs.

The isolated MSG profiles PASS EA-PDF. Non-isolated s/m/c results remain
**INCOMPLETE** because V85 cannot inspect MSG originals and contained comparisons
inherit that availability limit. These are not presented as complete acceptance.
An initial harness mixed the ordinary EML fixture directory into its MSG input;
the final recipe uses a separate `msg-inputs/` directory and retains that earlier
receipt. The final runtime and positive fixture inputs were checked fresh.

```sh
mvn -o -Deapdf.build.directory=/tmp/eapdf-creator-05-build package
python3 -B docs/improvement/creator-05/verify-profiles.py
python3 -B docs/improvement/creator-05/verify-msg.py
```

Use the frozen source overlay on C04 to reproduce this checkpoint. Clear-signed
or encrypted body retrieval, malformed missing-body recovery, full Microsoft
Best Body interoperability and arbitrary rendering remain unverified. Validator
code/rules, CLI arguments and existing debug statements are unchanged. The
temporary debugger configuration and logpoints were removed. Saved examples
retain their preceding bytes. Continuous work proceeds to MSG raw-address and
visible mailbox preservation in C06.

[Evidence](evidence.json), [ordinary profiles](profile-results.json),
[native MSG profiles](msg-results.json), [runtime observations](before-tracepoints-final.json),
[incremental patch](MsgReader.java.patch).
