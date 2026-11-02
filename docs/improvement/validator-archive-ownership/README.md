# Archive ownership checks

Completed 2026-10-25. Repairs repeated validation work in the web and Java engines.

## Change

The validators check which email owns each part of the page content. The same
content reference is visited under its email, content group and content set.
Repeating its page-connection work exhausted the inspection budget on the
ordinary multi-email sample.

Both engines now reuse that reference's result within one assessment. The key
includes the structure element, child position and effective inherited page.
Different page contexts remain separate. Every structure path, cycle and depth
check still runs. Invalid or missing connections remain unavailable. The Java
work counter follows the same reuse; its actual ownership checks remain active.

Neither the 100,000-work limit nor the depth limit of 256 increased. On the
ordinary sample, measured work falls from the limit of 100,001 to 81,360.
All 214 ownership assessments complete. The isolated sample uses 78,111 units
and also completes all 214 assessments.

## Results

- `examples/sample_1m.pdf` changes from INCOMPLETE to **PASS** in both engines.
- `examples/sample_1mi.pdf` completes all five ownership rules. It remains **INCOMPLETE** solely because attachment native-byte consistency lacks sufficient evidence.
- Only those two PDFs have changed rule outcomes across the complete corpus. No previously failing file becomes PASS; all unrelated outcomes are retained.
- Creator code and JARs, the active rulebook, input PDFs and protected Agenda/BFO PDFs are unchanged.

## Verification

| Check | Result |
| --- | --- |
| Java suite, including desktop | 119 passed; zero failures, errors or skips |
| Python suite | 1,163 run; 1,123 passed; 40 optional skips |
| Parent PDFs / matched embedded reports | 808 / 67 |
| Matching EA-PDF rule outcomes | 186,375 / 186,375 |
| Matching overall results | 875 / 875 |
| Missed or extra failures; execution/inspection errors | Zero |

Existing corruption, reused-content, inherited-page and small-budget checks
continue to pass. Regression checks exercise both actual multi-email samples
with the unchanged default limits.

All current EA-PDF checks were rerun in both engines. Java's upstream checks
were rerun. The Python corpus reused previously verified real upstream reports
after matching each PDF's exact SHA-256; current ISO report verification still
ran. No cached result is described as a fresh run. Both affected samples also
received fresh full web validation: PDF/A PASS and zero EA-PDF failures.

The exact delivered JAR passed through its actual GUI with only the JAR in its
working folder and Python/Docker unavailable on PATH. The ordinary multi-email
sample passes PDF/A, selected ISO and EA-PDF checks. Specification excerpts load
from the JAR, and temporary worker files are cleaned.

## Delivery and evidence

- [Standalone validator](../../../dist/eapdf-validator.jar)
- [Build and source hashes](evidence.json)
- [Complete content-free comparison](comparison.json.gz)
- [Measured work before and after](work-measurements.json)
- [Changes against the previous delivery](effect.json)
- [Fresh web sample checks](live-web-summary.json)
- [Standalone GUI proof](standalone-proof.json)
- [Desktop screenshot](window.png)
