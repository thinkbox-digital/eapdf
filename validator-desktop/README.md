# Standalone Java EA-PDF validator

This is a Java desktop app that checks one PDF at a time. It builds into one
runnable JAR. **Java 21 or later is the only runtime installation needed.**
No Python, Docker, browser, server, source checkout or separate rule files are
needed. The PDF/A engine, its libraries, the EA-PDF rule descriptions and the
selected ISO 32005 profile are built into the JAR.

## Current state

The earlier parity baseline matched the web validator's **213 rule outcomes across
808 saved PDFs and 67 embedded-file reports**. The complete comparison also
checks the actual PDF/A and selected ISO 32005 results, rule identifiers,
object locations and error text. [Parity evidence](../docs/improvement/validator-desktop-parity/README.md).

The October 27 reexamination updates both validators to use version-aware
collection exit modes and independently verified document-wide content ownership.
All six freshly generated profiles pass both engines with matching outcomes for
all 213 rules. [Current decisions and verification](../docs/improvement/specification-audit/specification-reexamination-2026-10-27.txt).

A file can now receive **PASS** when the required checks complete successfully.
Actual violations produce **FAIL**. Required comparisons with unavailable evidence
still produce **INCOMPLETE**. Operational errors, cancellation and timeouts also
produce **INCOMPLETE**, unless a completed check has already proved a violation.
Finding wording and occurrence counts can differ because the two engines
aggregate individual observations differently; rule decisions are compared for
every file. This is automated validation, not independent certification.

Checks include PDF/A, selected ISO 32005 structure rules, EA-PDF identification,
XMP types, catalog/profile settings, original and embedded file bytes, source
matching, MIME body and attachment metadata, document parts, structure ownership,
parent-tree correspondence, attachment annotations and collection children.
The existing limits on unsupported original-source formats, ambiguous ownership,
large structure inspections and isolated attachment evidence remain visible.

The selected upstream profile is unchanged. Fully upstream-owned checks run in
the validation pipeline and are omitted from the EA-PDF rule list. Runtime rule data
is in `validator_rule_text.json`; development review records are kept separately
in `docs/improvement/specification-audit/validator-rule-review.json` and are not
bundled in the JAR.
The creator now places archive-wide front matter at document level; its CLI is
unchanged. This validator does not display emails,
follow links or connect to email accounts.

## Build

From the repository root, using Java 21 and Maven:

```sh
mvn -f validator-desktop/pom.xml verify
```

On Linux, the JAR is written to `/tmp/eapdf-validator-build/eapdf-validator.jar`.
Override the build directory with `-Deapdf.build.directory=/tmp/your-build`.
No build files are written to the creator's `target/` directory.
`verify` checks the final shaded JAR's manifest, ZIP CRCs, bundled runtime classes,
resources and Java 21 entry point from a clean directory. Both Maven JAR and Shade
stages explicitly set `Main-Class: eapdf.ValidatorGui`.

To build, verify and atomically replace the distributable:

```sh
python3 scripts/package_validator.py
(cd dist && sha256sum -c eapdf-validator.jar.sha256)
```

Run the checksum check from `dist/` (the checksum file names `eapdf-validator.jar`).
The publisher uses a private temporary build and leaves the previous distributable
intact if building or verification fails. Publish `eapdf-validator.jar`, never
`original-eapdf-validator.jar` or a source/archive ZIP. Details and the tested
checksum are in `docs/improvement/validator-packaging-2026-10-30.md`.

## Run

```sh
java -jar /path/to/eapdf-validator.jar
```

The JAR includes its runtime libraries, rules and specification references; only
a Java 21 desktop runtime is needed. On Linux/Crostini it needs the normal graphical
desktop session; a headless session cannot show the Swing window. No Maven cache,
repository checkout, Python or separate veraPDF installation is used at runtime.

The window starts at 2,000 × 1,100 pixels with 16-point text.

Choose a PDF and press **Validate**. Select a row to read its details, including
PDF/A failures. The table includes rule numbers. **Keyword in Rule** filters rule
codes using all entered words. **Esc** clears the filter. Finding and
**Specification** appear side by side.
Findings name concrete required values and types, such as version 1 and revision
2024. The specification pane shows the cited specification text,
section, table and both printed/PDF page numbers. References are bundled; the
source PDF is not needed at runtime. Summaries are labeled as paraphrases, and
operational checks do not receive invented specification pages.
The initial list follows specification order. Identical rule/result/message rows
are grouped. Select a group to see its occurrence count and details;
different results or messages remain separate. **Save report** preserves the full,
ungrouped JSON report. **Stop** cancels a run.

The input PDF is read only. A separate Java worker lets Stop end checking without
closing the window. Private scratch prefers disk storage when the system temp
folder uses RAM. Collection children are checked recursively with default limits
of depth 8, 250,000 children and 64 GiB of decoded child bytes. The existing
`VALIDATOR_CHILD_MAX_DEPTH`, `VALIDATOR_CHILD_MAX_COUNT` and
`VALIDATOR_CHILD_MAX_BYTES` settings override those defaults.

The desktop worker uses two thirds of currently available memory, respecting container
limits. CLI users can set the JVM heap with `-Xmx`; large-file checks may need more
than the JVM's default. The measured 1.28 GB PST PDF/A check used a 16 GiB heap.

CLI reports include `.progress.json` and `.summary.json` sidecars. The complete
report is streamed to disk. The desktop keeps finding details on disk and retains
all groups/counts; large detail panes show a stated excerpt. Save writes every
finding. Cancellation produces an incomplete report which can also be saved.
Private working files are removed when checking ends or the displayed report is
closed. Large MIME sources share one disk file instead of retained body arrays.

The veraPDF rule predicates and bundled profiles are unchanged. A source-included
adaptation spills exact visited identities to disk; a bounded glyph cache preserves
PDF/A-1 inventory semantics. See THIRD_PARTY_NOTICES.md. These are local resource
changes, not an upstream release or independent certification.

## Checks for developers

Normal tests run during `package`. To also open the window, validate a real
sample, stop a second run and check temporary-file cleanup on a graphical desktop:

```sh
mvn -f validator-desktop/pom.xml -Deapdf.validator.uiTest=true test
```

The app bundles the same veraPDF component versions used by the pinned
1.31.18 CLI: core/pdf-model 1.31.1, parser 1.31.7, validation-model 1.31.17.
Versions and the unchanged rule/profile hashes are checked before use. The
PDF/A library runs directly in Java; no CLI installation is needed.
