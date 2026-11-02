# Native Outlook dates and definite property checks

Completed 2026-10-25. Creator and both validators updated.

## Creator repair

When transport headers were absent, the Outlook library's generic Date could
contain a modification or creation date. The creator used that value as Sent.
Seven supplied messages therefore showed incorrect sent times, including dates
years after their actual submission.

The creator now reads the stored submission FILETIME directly when there is no
transport Date header. The visible header uses that instant, and XMP retains its
full stored fractional precision. A supplied transport Date keeps precedence.
No date is inferred from an absent submission field by this repair; existing
fallback behavior remains for those unsupported cases.

Independent binary-property checks verify all seven corrected instants. Across
31 inputs, original bytes, text/HTML/RTF bodies, attachment bytes/names/types,
subjects, participant names/mailboxes and recipient order are unchanged.

## Validator repair

For a single represented email with exactly one MSG original and no attachment
storages, both engines check available native Subject, Message-ID, SizeInBytes,
NumberAttachments and submission-time facts. Eight ordinary inputs gain partial
checks. Missing fields are not reconstructed as transport headers; full header,
address and body comparisons remain INCOMPLETE. Nested candidates and ambiguous
owners do not qualify for this direct comparison.

Property readers ignore only verified trailing zero padding. Nonzero truncated
records and unsupported encodings remain unavailable.

Four existing PDF controls embed an MSG whose native fields contradict their
XMP. These now fail the concrete Subject/date/count/size checks. Three overall
results change from INCOMPLETE to FAIL; the fourth already failed. PDF bytes
remain unchanged. Fixture expectations and their builders record the stronger
checks, while the original isolation/applicability assertions remain.

## Verification

| Check | Result |
| --- | --- |
| Complete creator suite | 274 passed; zero failures/errors/skips |
| Complete Java validator suite, including desktop | 136 passed; zero failures/errors/skips |
| Complete Python suite | 1,171 run; 1,131 passed; 40 optional skips |
| Existing parent PDFs / embedded reports | 808 / 67 |
| Existing matching rule outcomes / overall results | 186,375 / 875 |
| Additional real cases | 31 current MSG outputs and one previous bad-date PDF |
| Total matching rule outcomes / reports | 193,191 / 907 |
| Missed/extra failures; execution/inspection errors | Zero |

All 31 current outputs pass PDF/A and have zero EA-PDF failures. Twelve fully
PASS; nineteen retain source-comparison INCOMPLETE results. The previous
bad-date output passes PDF/A but correctly FAILs the Sent comparison in both
engines. Tests also reject truncated fractional times and incorrect native
subject, identifier, byte count and attachment count.

All current EA-PDF checks were rerun across the existing corpus. Java upstream
checks are fresh runs from the delivered JAR. The Python corpus uses verified
real upstream reports only after matching exact PDF hashes; current ISO report
verification still runs. All 32 real cases separately received fresh full web
validation. Cached upstream reports are not described as fresh runs.

The exact standalone JAR passes through its actual GUI with only the JAR in its
working folder and Python/Docker unavailable on PATH. Specification excerpts
load from the JAR and worker files are cleaned.

## Remaining scope

Full source comparisons for headerless MSG, nested/inline MSG inventories,
isolated placeholder association, the three documented specification questions
and very large archive limits remain. The rulebook, existing samples and
protected Agenda/BFO PDFs are unchanged. Next: the isolated sample's ambiguous
empty attachment placeholders.

## Delivery and evidence

- [Creator CLI](../../../dist/emailtopdfa.jar)
- [Creator GUI](../../../dist/emailtopdfa-gui.jar)
- [Standalone validator](../../../dist/eapdf-validator.jar)
- [Corrected real Outlook example](../../../output/creator-review/msg-native/Correct%20Outlook%20sent%20date.pdf)
- [Build, hashes and tests](evidence.json)
- [Corpus comparison](comparison.json.gz)
- [Real MSG comparisons](real-comparison.json)
- [Preservation and exact native timestamps](creator-preservation.json)
- [Changed historical outcomes](effect.json)
- [Native property scope](native-inventory.json)
- [Standalone GUI proof](standalone-proof.json)
- [Desktop screenshot](window.png)
