# C07: flowed plain-text display

Display of MIME `text/plain; format=flowed` now interprets soft line breaks,
DelSp, quoting and space stuffing. Fixed/unknown formats and HTML retain their
existing interpretation. Signature separators and quote-depth boundaries remain
separate. Only display text changes; original emails and MIME parameters remain exact.

RFC 3676 sections 4.1 and 4.3–4.5 describe the interpretation. Receiver reflow is
optional: this adds rendering support and does not assert that prior fixed-looking
output violated a mandatory EA-PDF rule.

Six of nine focused regressions failed before the repair. A non-suspending
logpoint captured the declared parameters and unprocessed decoded transport
lines. All nine regressions now pass, including fixed-text, signature and HTML
controls. The final build passes **194 Java tests**, with no failures/errors/skips.

Twelve ordinary normal/accessibility profiles and six native flowed-message
profiles PASS unchanged V85 EA-PDF, PDF/A and the verified ISO profile. Ten ordinary
collection children and four ordinary finding-aid PDF/XLSX pairs pass. Reopened
flowed PDFs and collection children contain the joined paragraphs, preserve body
subtypes/parameters, and retain exact source bytes where the profile retains sources.
Isolated omission is intentional. Layout-preserving extraction and PDF coordinates
confirm the signature separator; the initial default-extraction assertion is retained.

Only EmlReader.java changes in this batch. The immutable source overlay, incremental
patch, baseline failures, runtime proof and final receipts are retained. The temporary
remote configuration and agent logpoint were removed. CLI arguments, existing debug
output, validator code/rules and saved examples were not changed.

```sh
mvn -o -Deapdf.build.directory=/tmp/eapdf-creator-07-build package
python3 -B docs/improvement/creator-07/verify-profiles.py
python3 -B docs/improvement/creator-07/verify-flowed.py
```

Use the C07 overlay on C06 to reproduce this checkpoint. Arbitrary rendering,
cryptographic fidelity and accessibility remain broader open work. Continue creator
work with diagnostics for malformed text bytes; validator development stays at V85.

[Evidence](evidence.json), [ordinary profiles](profile-results.json),
[flowed profiles](flowed-results.json), [runtime proof](before-tracepoints.json),
[incremental patch](EmlReader.java.patch),
[primary RFC](https://www.rfc-editor.org/rfc/rfc3676.html).
