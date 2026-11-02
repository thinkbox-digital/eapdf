# C04: attached message preservation and parent body selection

Unnamed `message/global` parts were omitted from the attachment inventory.
Both international and ordinary forwarded messages without an explicit
attachment disposition could also add a false unsupported-body notice to their
parent. The collector now treats `message/global` like `message/rfc822`, retains
its actual media type and native bytes, and keeps nested message parts out of
parent body selection.

The new encoded-message test exposed another cause: Jakarta Mail's default
`restrictEncoding` ignores transfer encoding for message parts. The initial
repair retained 320 base64 bytes instead of the 234 original bytes. The creator
now explicitly decodes each attached message's raw MimeBodyPart/MimeMessage
stream, with the existing bounded reader. It changes no global library property.
This also repairs reconstructed base64 `message/rfc822` attachments.

[RFC 6532 sections 3.5 and 3.7](https://www.rfc-editor.org/rfc/rfc6532.html#section-3.5)
define transfer-encoded international message content. The exact Jakarta Mail
2.1.5 primary source was inspected to establish the default restriction.

Verification: **176 Java tests pass**, without failures, errors or skips.
All three original regressions failed before repair. Four final regressions
verify raw/encoded international attachments, encoded RFC 822 payloads and
unchanged parent content. The earlier partial repair failure is retained in
`first-repair/`. The debugger observation of the original missing attachment is
retained from the preceding C03 survey; that collector code was unchanged in C03.

Twelve ordinary normal/accessibility profiles and six encoded attached-message
profiles pass unchanged V85 EA-PDF, actual PDF/A and the verified ISO profile.
Ten ordinary collection children and four ordinary finding-aid PDF/XLSX pairs
pass. Native annotation streams contain exact decoded attached-message bytes in
non-isolated output, correct zero-byte placeholders in isolated output and the
correct `message/global` subtype. Reopened original source streams are exact.
Extracted PDF text retains the parent body without painting attached content or
the false notice. Complete isolated attachment preservation remains unclaimed.

The source overlay, accepted hashes and incremental patch separate this repair
from C03 and later work. Validator code/rules, CLI arguments and existing debug
statements are preserved. Existing saved examples retain their preceding bytes;
the new generated fixtures and reports are retained here. Build output remains
outside `target/` and debug directories.

```sh
mvn -o -Deapdf.build.directory=/tmp/eapdf-creator-04-build package
python3 -B docs/improvement/creator-04/verify-profiles.py
python3 -B docs/improvement/creator-04/verify-attachments.py
```

Use the frozen C04 overlay on its C03 baseline to reproduce this checkpoint.
General rendering, full accessibility, complete validator coverage and the
large-PST INCOMPLETE limits remain open. Continuous creator work proceeds next
to native MSG body selection.

[Evidence](evidence.json), [ordinary profiles](profile-results.json),
[attachment profiles](attachment-results.json), [source byte checks](native-source-checks.json),
[incremental patch](EmlReader.java.patch).
