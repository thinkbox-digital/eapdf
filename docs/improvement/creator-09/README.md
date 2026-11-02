# C09: international original-message media labels

Original EML messages with direct high-bit UTF-8 top-level headers now identify
the retained source as `message/global`, including its associated-file stream.
The detector reuses the bounded header scan and validated UTF-8 session decision.
ASCII encoded-word headers, body-only UTF-8 and the existing invalid-legacy-header
fallback retain their previous `message/rfc822` labels. Original bytes and filenames
remain exact; the international source description reflects its type.

RFC 6532 section 3.7 defines message/global for internationalized headers. This
checkpoint covers direct top-level headers. Classification based only on non-ASCII
MIME body-part headers and reconstructed PST sources remains open; complete RFC
message validation is not claimed.

Two of five baseline tests failed; the debugger independently captured the old
`message/rfc822` label on an actual direct UTF-8 original. All **204 Java tests
pass**, without failures/errors/skips. Twelve ordinary normal/accessibility and
six paired international/ASCII profile checks PASS unchanged V85 EA-PDF, PDF/A
and the verified ISO profile. Ten ordinary collection children and four ordinary
finding-aid PDF/XLSX pairs pass. Reopened actual PDFs/children show the correct
original AF media labels, readable Subject/body text and exact retained source
bytes. Isolated omission is intentional.

Only EmlReader.java changes. The temporary remote config and agent logpoint were
removed. Validator code/rules, CLI arguments, existing debug output and saved
examples remain unchanged. Immutable overlay and local patch preserve this checkpoint.

```sh
mvn -o -Deapdf.build.directory=/tmp/eapdf-creator-09-build package
python3 -B docs/improvement/creator-09/verify-profiles.py
python3 -B docs/improvement/creator-09/verify-international.py
```

Use the frozen C09 overlay on C08 for reproduction. Continue creator work with
escaped CID image references and literal Content-ID preservation.

[Evidence](evidence.json), [ordinary profiles](profile-results.json),
[international profiles](international-results.json), [runtime proof](before-tracepoints.json),
[incremental patch](EmlReader.java.patch),
[primary RFC](https://www.rfc-editor.org/rfc/rfc6532.html#section-3.7).
