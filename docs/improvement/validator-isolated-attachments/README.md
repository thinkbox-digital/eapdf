# Isolated attachment comparisons

Completed 2026-10-25. Both validators repaired; creator and PDF bytes unchanged.

## Change

The isolated archive contains empty attachment placeholders. Some portable PDF
filenames differ from their original metadata names. Global matching compared
these placeholders with attachments from every email, mixing unrelated media
types and reporting unavailable comparisons.

Both engines now restrict candidates when the existing FileAttachment annotation,
its page's DPart, the body range and a unique email record establish the declared
owner. This uses no filename cleanup, size, checksum or media-type value to
choose that owner. Each annotation context must be known. Missing identities,
conflicting page information and an additional unassigned reference retain the
original global candidate set.

Repeated equivalent candidates still establish only field agreement. The check
does not claim a unique original attachment occurrence or complete original
inventory. Actual size/checksum/type comparisons and inspection limits remain.

## Results

**All six unchanged public sample profiles now PASS both validators**, including
`examples/sample_1mi.pdf`. Fresh complete web checks pass PDF/A, EA-PDF and selected
ISO checks. The exact standalone JAR also passes the isolated sample through its
actual GUI, from a folder containing only the JAR with Python/Docker unavailable.
Bundled specification text loads and worker files are cleaned.

In the complete reference corpus, only three parents have changed rule results:
the isolated sample becomes PASS; wrong-owner and swapped-DPart-GUID controls
retain FAIL and now preserve the uncertainty of the affected comparisons.
No previous FAIL becomes PASS. All input PDF bytes are unchanged.

Focused controls prove that incorrect size/checksum values still FAIL and that
missing page ownership or an additional unassigned reference remains INCOMPLETE.

## Verification

| Check | Result |
| --- | --- |
| Complete Java validator suite, including desktop | 140 passed; zero failures/errors/skips |
| Complete Python suite | 1,175 run; 1,135 passed; 40 optional skips |
| Creator | Unchanged; preceding complete 274-pass result retained |
| Parent PDFs / embedded reports | 808 / 67 |
| Matching corpus rule outcomes / overall results | 186,375 / 875 |
| Additional real MSG reports | 32; all rule outcomes and overall results agree |
| Total matching rule outcomes / reports | 193,191 / 907 |
| Missed/extra failures; execution/inspection errors | Zero |

All current EA-PDF checks were rerun. Java corpus and MSG upstream checks are
fresh runs from the delivered JAR. Python corpus and MSG upstream reports are
reused only after exact PDF SHA-256 matching; current ISO report verification
still runs. All six public samples separately received fresh full web checks.
Cached reports are not described as fresh runs.

The isolated fix updates only `dist/eapdf-validator.jar`. Both creator JARs retain
the corrected recipient order and seven native submission dates from the two
preceding deliveries. Rulebook, samples, protected Agenda/BFO PDFs and inspection
limits remain unchanged.

## Remaining work

Full source comparisons for native-only/unsupported MSG, the three documented
specification interpretations and default very-large-archive validation limits
remain. No additional ordinary-input creator blocker is recorded by this review.
Distribution and reader development remain outside the current work.

## Evidence

- [Standalone validator](../../../dist/eapdf-validator.jar)
- [Unchanged isolated archive](../../../examples/pst/pst_sample_1mi.pdf)
- [Build, source hashes and tests](evidence.json)
- [Complete corpus comparison](comparison.json.gz)
- [Changes against the preceding delivery](effect.json)
- [Real MSG comparisons](real-comparison.json)
- [Fresh full sample checks](live-web-samples.json)
- [Single-JAR GUI proof](standalone-proof.json)
- [Desktop screenshot](window.png)
