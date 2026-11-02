# Creator accessibility and speed repairs — 2026-10-28

Accessibility remains partial. The GUI's existing **Accessibility (partial)**
checkbox controls the new accessibility changes. Required EA-PDF structure is
still produced when the checkbox is off. No PDF/UA conformance identifier is added.

## Creator changes

- Preserve source HTML language labels, including inherited document language
  and `xml:lang`, in the rendered body when Accessibility is enabled.
- Repair empty link descriptions in imported tags using the existing visible
  link description. Preserve authored descriptions and keep the annotation's
  description consistent with its tag.
- Mark generated rules and finding-aid table backgrounds as decoration when
  Accessibility is enabled. Table headings and values remain real content.
- Look up page-parent entries without scanning all earlier pages repeatedly.
- Check a needed font's file information once per text scan instead of once per
  distinct character. Font size limits and missing-glyph warnings remain.
- Index collection filenames once instead of repeatedly scanning every embedded
  filename. Existing case, folder and collision rules remain unchanged.

## Evidence

The first four real outputs contained 26 PDF/UA machine-check failures beyond
the deliberately absent identification: ten link-description mismatches and
16 unmarked decorative paths. Those failures are repaired. Seven resulting
outputs, covering EML, MSG, MBOX and PST inputs, all pass the standalone Java
EA-PDF checks. Their explicit PDF/UA-2 machine reports now fail only the missing
identification requirement. Collection children pass EA-PDF checks; the separate
PDF/UA audit here covers the seven top-level PDFs, not every embedded child.

This does **not** establish full PDF/UA compliance. Generic image descriptions,
meaningful reading order, table/list semantics and assistive-technology use still
need human review. Files whose sources lack this information cannot be declared
fully accessible merely because automated checks pass.

Seven rendered pages from three real emails are pixel-identical before and after
the repair at the checked 1,100-pixel scale. A focused language/link regression
also verifies identical extracted text with Accessibility off and on. The actual
Swing checkbox was toggled off/on/off and its generated settings were verified.

All **308 creator tests pass**. The Java validator runs **166 tests, zero failures,
one desktop-only skip**. Earlier Python date/source repairs passed 1,191 tests
with 40 optional skips; Python was not rerun for these Java-only creator changes.

All **24 real examples** were rebuilt using four requested rendering jobs and
pass standalone Java validation. Their manifests retain source and output hashes;
logs and reports are in `output/real-examples-final-2026-10-28/`.

Isolated timing checks: allocating 5,000 distinct embedded filenames fell from
3,586 ms to 12 ms, with matching results. Running 2,000 font-coverage scans on
real email text fell from 522 ms to 106 ms. These measurements concern those
operations; they are not whole-conversion speed claims.

The three formerly incomplete PST archives were also rebuilt and now pass.
The 48,129-message large PST still has no completed parent output or full
validation result. Its running build predates the filename index and current
date/accessibility changes. Large-collection validation still has its 256-child
and 256 MiB child-data limits. Two MSG comparisons remain incomplete, and other
unrerun corpus failures remain open.

Local creator, GUI and validator JARs in `dist/` are updated. The published GitHub
release is unchanged. The requested viewer sentence is updated in both local
0.3.2 extension packages.

## Shared-memory cleanup

The active large PST had about 11 GB of temporary data in RAM-backed `/tmp`.
48,129 completed child PDFs (4,345,368,692 bytes) were copied to disk and verified
by SHA-256 before their original paths were atomically replaced with symlinks.
The running conversion retains access to identical bytes. A cleanup watcher
removes these disk copies after the owning worker exits. An additional
342,251,347 bytes of obsolete build copies were removed after preserving reports.
At that checkpoint `/tmp` usage fell from 75% to 46%; available system memory
rose from about 7 GB to 13 GB. Active PDFBox scratch storage was not removed.

Full results: [verification.json](verification.json). Detailed Java reports,
audit scripts, timing logs and GUI evidence are retained in
`output/accessibility-repair-2026-10-28/`. Memory-relocation status is in
`output/pst-large-repair-2026-10-28/shared-memory-cleanup.json`.
