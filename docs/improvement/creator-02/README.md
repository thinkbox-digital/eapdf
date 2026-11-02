# C02: PST recipient display terminators — 2026-10-22

The PST reader used MAPI display strings as fallback To/Cc/Bcc headers when
transport headers and recipient agents were unavailable. These strings can
contain their trailing storage NUL. A missing group represented as `"\0"`
therefore became a fictitious `Cc: \0` or `Bcc: \0` in the reconstructed EML,
while XMP's XML sanitation removed the NUL. The exported email and metadata
then disagreed about a recipient value that had never existed in transport.

`PstReader.pstDisplayHeader` removes trailing NULs from these three display
fallbacks. It preserves the other characters, including whitespace and
non-SMTP values. Supplied transport headers and known recipient agents retain
their existing path. No common EML sanitation or validator change is involved.

IntelliJ logpoints established the transition on the actual 71-message PST:
after transport parsing the first message's Cc/Bcc were empty, while the MAPI
fallback changed both to one NUL. All 213 events are retained with zero
evaluation errors. A real-PST regression failed with `expected <> but was
<NUL>` before repair; it passes afterward. A second regression keeps the
original quoted name, double spaces and mailbox case exact through export.

Verification:

- Isolated Maven package: **167 Java tests pass**, no failures, errors or skips.
- All six profiles in normal and accessibility fixture modes: **twelve PASS**
  results for unchanged V85 EA-PDF, actual PDF/A and the verified ISO profile.
  All ten contained email PDFs pass. Four collection finding-aid PDFs pass
  PDF/A, and four XLSX sidecars contain workbooks; m/mi retain embedded indexes.
- Both actual PST archives pass PDF/A and the verified ISO profile with no
  mandatory failures. **59 fictitious Cc and 71 fictitious Bcc headers are
  removed.** All 71 source message inventories retain their other compared
  headers and every decoded MIME body/attachment payload and media parameter.
  Generated multipart boundaries may change as the header inputs change.
- The checked `sample_1m.pdf` and `sample_1mi.pdf` replace their saved examples
  with their existing sample basenames and embedded index names. Other saved
  examples and sidecars retain their preceding hashes.

The actual 71-message archives remain **INCOMPLETE** under V85. Both exceed
its structure ownership traversal budget, leaving five dependent assessments
unavailable. The isolated archive additionally retains unavailable native
attachment consistency. The 130 address-related incomplete findings are gone.
This repair does not establish full source preservation, arbitrary PST parsing,
PDF/UA conformance, complete rendering or complete validator coverage.

The unchanged V85 validator/rules and web wrapper retain their pre-batch hashes.
The temporary remote debugger configuration and three agent logpoints were
removed; the two disabled user exception breakpoints remain. No CLI arguments
or existing debug statements were changed. Build output is outside `target/`
and debug directories.

Reproduce the build and acceptance with:

```sh
mvn -o -Deapdf.build.directory=/tmp/eapdf-creator-02-build package
python3 -B docs/improvement/creator-02/verify-profiles.py
python3 -B docs/improvement/creator-02/verify-pst.py
```

The PST recipe uses the exact C01 retained sample as its comparison baseline;
its bytes were checked identical to the saved pre-publication sample. An initial
successful archive probe used generic output names; the final checked candidates
use `sample_1m.pdf`/`sample_1mi.pdf` to retain embedded index naming. Both sets of
receipts are retained. Neither recipe publishes files. `assemble-evidence.py`
checks the receipts, publishes only the two affected saved samples, and freezes
their hashes. Source overlay and the incremental patch retain this repair
separately from the pre-existing working-tree edits.

[Evidence](evidence.json), [profile results](profile-results.json),
[PST results](pst-results.json), [all 71 source comparisons](pst/source-comparison.json),
[debugger observations](before-tracepoints.json), [incremental patch](PstReader.java.patch).
