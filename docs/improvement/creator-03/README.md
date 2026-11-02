# C03: international email header decoding

Direct UTF-8 From, To and Subject values were interpreted as individual legacy
bytes: `Résumé` became `RÃ©sumÃ©`, and `José` became `JosÃ©`. The shared reader
propagated this into rendered headers and XMP; Gmail also used the damaged
Subject when deriving its source filename. PST transport headers were decoded
through the same default mail-library behavior.

`EmlReader.mimeSession` now checks only the original header block with a strict
UTF-8 decoder and enables the mail library's per-session UTF-8 option when that
check succeeds. A non-UTF-8 body does not affect header decoding. Invalid legacy
header blocks retain their previous byte mapping without replacement characters.
EML/MBOX reading, Gmail source naming and PST transport parsing use this helper.
No global system property is changed.

[RFC 6532 sections 3.1–3.2](https://www.rfc-editor.org/rfc/rfc6532.html#section-3.2)
permits direct UTF-8 header values. This batch preserves their text and source
bytes; it does not guess an encoding for an invalid or mixed legacy header block.

Verification: **172 Java tests pass**, no failures, errors or skips. Three new
regressions failed before repair; all five focused tests pass afterward. They
exercise direct EML/MBOX headers, participant names, a non-UTF-8 body, legacy
header octets and RFC 2047 encoded words. IntelliJ observations captured the
damaged values with `allowutf8=null` before editing, without evaluation errors.

All twelve ordinary normal/accessibility profiles pass unchanged V85 EA-PDF,
actual PDF/A and the verified ISO profile, including ten collection children
and four configured finding-aid PDF/XLSX sidecar pairs. Six additional actual
international-message profiles pass those checks. Their XMP and extracted PDF
text contain the correct names/Subject, and retained original source bytes are
exact. Isolated source omission remains intentional.

The first focused harness attempt correctly hit the two-message archive gate;
the second correctly hit the single positional input gate. The final recipe
supplies a folder containing two messages for m/mi. Those earlier receipts are
retained; creator CLI arguments and validation gates were not changed.

The C03 source is frozen in `creator-overlay.zip` with its accepted manifest
and incremental patches. Later C04 working-copy changes are separate. Existing
saved examples retain their C02/C01 hashes. Validator code/rules are unchanged.
The temporary remote configuration and agent logpoints were removed. Build:

```sh
mvn -o -Deapdf.build.directory=/tmp/eapdf-creator-03-build package
python3 -B docs/improvement/creator-03/verify-profiles.py
python3 -B docs/improvement/creator-03/verify-international.py
```

Use the frozen C03 overlay on its preceding source baseline to reproduce that
specific checkpoint. Complete internationalized mailbox support, difficult
script rendering, live Gmail authorization and full accessibility remain open.
The independently reproduced `message/global` attachment omission continues in C04.

[Evidence](evidence.json), [ordinary profile results](profile-results.json),
[international results](international-results.json), [runtime observations](before-tracepoints.json).
