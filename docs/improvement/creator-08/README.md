# C08: text-decoding recovery diagnostics

MIME text decoding now detects malformed/unmappable bytes with the selected
charset decoder before applying the existing replacement recovery. A conversion
warning and stderr diagnostic identify that recovery. Valid text, including a
literal Unicode replacement character, produces no warning. Original source
bytes and recovered display content remain unchanged.

Two of five corrected baseline tests fail before repair. Three controls cover
valid UTF-8 replacement characters, legacy charsets and Jakarta Mail's existing
undeclared ASCII-to-Latin-1 compatibility mapping. The initial control incorrectly
expected strict ASCII replacement; that failed assumption and first package receipt
are retained. Corrected baseline tests ran against an isolated copy of C07.
A non-suspending logpoint proved `encoding=UTF8 decoded=a�(b`; the first IDE
expression error and final successful runtime evidence are both retained.

**199 Java tests pass**, no failures/errors/skips. Twelve ordinary normal/accessibility
profiles and six native malformed-text profiles PASS unchanged V85 EA-PDF, PDF/A
and the verified ISO profile. Ten ordinary collection children and four ordinary
PDF/XLSX finding-aid pairs pass. Actual generation logs contain the recovery warning;
reopened PDFs/children display the same recovered text and retain exact original
source bytes where policy permits. Isolated omission is intentional.

Only EmlReader.java changes in this batch. CLI arguments, existing debug output,
validator code/rules and saved examples are unchanged. The temporary remote config
and agent logpoint were removed. The immutable overlay and incremental patch
separate this checkpoint from later source edits.

```sh
mvn -o -Deapdf.build.directory=/tmp/eapdf-creator-08-build package
python3 -B docs/improvement/creator-08/verify-profiles.py
python3 -B docs/improvement/creator-08/verify-decoding.py
```

Use the C08 overlay on C07 to reproduce this checkpoint. This detects invalid
byte sequences; it cannot identify a wrong charset that successfully decodes all
bytes. It does not infer corruption from a supplied replacement character, claim
original retention for isolated output, or provide cryptographic verification.
Continue creator work on international original-message media labels.

[Evidence](evidence.json), [ordinary profiles](profile-results.json),
[decoding profiles](decode-results.json), [runtime proof](before-tracepoints.json),
[incremental patch](EmlReader.java.patch).
