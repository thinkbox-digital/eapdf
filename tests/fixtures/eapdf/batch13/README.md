# B13: accepted MBOX entries and extraction provenance

`generate_inputs.py` authors nine mailbox inputs, separate exact exported EML
oracles, and byte-span/fingerprint expectations in `inputs.json`. It does not
import the Java parser or Python PDF validator. Cases cover LF, CRLF and CR;
mboxrd, mboxo, mboxcl, mboxcl2 and auto Content-Length; a protected separator in
a Content-Length body; identical occurrences; and binary body bytes (parser only).
Four negative inputs cover recognized envelope candidates with absent/malformed
first headers and a missing header/body separator. The first three existed
before the application edit: their three regression assertions failed.

The Java tests retain accepted parser reports with:

```
mvn -o -Deapdf.build.directory=/tmp/eapdf-improvement-batch13/build -Deapdf.mboxReaderReports=/media/lepore/Work/thinkbox/convert/docs/improvement/batch-13-reader-reports package
```

`cases.json` declares eight accepted CLI scenarios and four rejected ones before
observing their validation results. All six profiles use the authored mboxrd
input; mboxo and mboxcl2 additionally exercise an archive. Twelve accepted top-level
PDFs and six actual collection children must pass PDF/A and EA-PDF. The verifier
independently checks raw input slices, exported bytes, container hashes, exact
provenance strings, subjects and source-byte multisets. Isolated profiles must
omit sources while retaining the derivation notice. Rejected collection inputs
must fail, count zero completions/one failed input, preserve the existing
destination bytes and produce no sidecars.

```
env -u PYTHONPATH EAPDF_GENERATOR_CLASSPATH=/tmp/eapdf-improvement-batch13/build/emailtopdfa-1.4.0.jar python3 -B scripts/verify_eapdf_mbox_inventory_fixtures.py
```

Artifacts default to `docs/improvement/batch-13-corpus/`; use
`EAPDF_MBOX_CORPUS_DIR` for another directory visible to the configured veraPDF
mount. Independent validation must be available. Four `.pdf` files under
`reject-*` are intentionally non-PDF destination sentinels, not corpus PDFs.
Configured sidecars are disabled for this corpus; the separate saved-sample run
exercises the project's normal sidecar settings.

This is application policy for the parser's supported framing and recognized
envelope syntax. [RFC 4155](https://www.rfc-editor.org/rfc/rfc4155) describes MBOX
variation and separator ambiguity. The fixtures do not establish universal MBOX
validity or prove all historical dialects. A recognized but ambiguous envelope
cannot silently become successful extraction. Content-Length explicitly protects
matching body text. PST, MIME/MAPI attachment enumeration, Gmail and dispatch
completeness remain separate work. Provenance identifies omitted mailbox bytes;
it does not embed or reconstruct the original container.
