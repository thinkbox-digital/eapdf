# Patched OpenHTMLtoPDF integration

Branch: `integration/openhtmltopdf-1.1.105-patched`. Base: published creator
commit `dd5af6947bd7efe3c4df0c7cc4c3bdeb1f5390c5` (1.6.0-beta.3).
Merged into `main` at `c2d6065` after the recorded quality checks. Performance
changes were kept outside the integration branch and are checked separately in
the [profiling report](../profiling-2026-10-30/REPORT.md).

## Existing implementation inspected

- Maven previously used `io.github.openhtmltopdf:openhtmltopdf-pdfbox:1.1.97`.
  PDFBox and XMPBox are both 3.0.8 and remain at those versions.
- `PdfMailWriter.renderHtmlPdf` / `renderXhtmlPdf` performs XHTML rendering,
  including wide-table fitting, asset restrictions and font fallback.
- PDFBox subsequently adds email sources, MIME attachments, AF relationships,
  document parts/DPart metadata, EA-PDF XMP, output intents and final PDF/A-4f
  identification. Base renderer conformance cannot establish final conformance.
- `FontSupport` supplies embedded primary/fallback fonts; `SemanticStructure`
  rewrites header tags, preserves marked content and imports renderer structures.
  Normal PDFBox font subsetting/CIDSet handling is preserved; no forced CIDSet
  removal is introduced. Existing FEFF-to-WORD-JOINER display handling remains.
- The code already modifies structure trees: `addLogicalStructure` and
  `addArchiveLogicalStructure` rebuild EA-PDF wrappers, while
  `SemanticStructure.attach` imports body children, remaps ParentTree keys and
  StructParents/StructParent values, and retains content relationships. Final
  namespace/role mappings are rebuilt by `addLogicalStructureNamespaces`.
  Therefore renderer namespace declarations alone are not treated as proof of
  final PDF/UA-2 correctness.

## Dependency provenance

Upstream: `https://github.com/openhtmltopdf/openhtmltopdf`, tag `1.1.105`,
commit `54b31598f14ea77139ec435106810ba67bd68656`.
Patches: `https://github.com/jialiang/HTML-to-PDF-UA`, pinned repository commit
`7fff0e4ad793a521659ad19d46f4d9ddb39941a2`.

All four patches apply unchanged in numerical order with `git am`. Fixed
committer identity and `--committer-date-is-author-date` make application commit
identifiers reproducible. No compatibility edits to the patches were needed.

| Patch | Original patch commit | Applied commit |
|---|---|---|
| 0001 | `225219232e0e87d89683a0ac2b31472f91dbf491` | `80db81df12a07f3d682147287279ed7b430e8442` |
| 0002 | `54b4513f20c1d55bc212b9ab11b83740239979d3` | `c96b09db7b5696b6873583d7c6630cd3336636bd` |
| 0003 | `9648fff9aba215891af47fc51890e498ace91074` | `0799dfa86362b46a6b4e39645c66596d09ef8674` |
| 0004 | `98572627de49af5e978acbb50aa1ee5c8625a284` | `391485cd9dcd4b53ed9dc57e87b4b2d27329ccb1` |

The patched version is **1.1.105-eapdf.1**, covering both active modules:
`openhtmltopdf-core` and `openhtmltopdf-pdfbox`. The application POM resolves them
from the version-controlled `third_party/maven` file repository. Production
builds do not require patched artifacts preinstalled in a developer's Maven
cache. Flattened POMs, binary JARs, corresponding source JARs, SHA-1/SHA-256
sidecars and a SHA-256 lock are supplied. Other PDF libraries are not added.

The four source patches are unmodified. A documented packaging-only step fixes
ZIP entry order/time/mode and volatile OSGi manifest build fields
(`Bnd-LastModified`, `Built-By`, `Build-Jdk`). Every class/resource/source byte
except those manifest fields is retained. This is necessary because the upstream
Bnd plugin otherwise puts build time/user information in binary JARs even with
`project.build.outputTimestamp` fixed. Clean-source rebuild hashes now match.

## Renderer configuration and assembly

The renderer selects patched `PdfAConformance.PDFA_4_A` and an sRGB output profile.
Despite that API enum's name, the patch emits **base PDF/A-4**, without an `A`
conformance value, and writes a PDF 2.0 header. It does not supply 4f attachments.

The patched `usePdfUaAccessibility(tagged, 2)` supplies PDF/UA-2 structures for
existing accessibility/header tagging paths. The public configuration switch
`accessibility.addPdfUaFeatures` continues to control final PDF/UA identification;
no CLI arguments are added. Existing renderer tagging for EA-PDF headers remains
available when the optional accessibility setting is disabled.

The final PDFBox assembly remains responsible for EA-PDF and PDF/A-4f metadata,
associated files, DParts, outlines, logical wrappers, namespace mappings and
ParentTree relationships. It replaces the renderer's output intent instead of
appending an independent ICC object. This prevents duplicate incompatible output
intents without removing any source or attachment content.

The negative-spacing and GSUB patches operate inside the renderer. GSUB ligature
substitution is disabled on font loading by the supplied patch, rather than by
adding application-side font rewriting. Text extraction, supported non-ASCII
characters and embedded font/ToUnicode presence have explicit regression coverage.

## Verification

- An application build from an initially empty Maven repository succeeded.
  The final full creator suite ran **350 tests, zero failures/errors, four missing-input skips**.
- The added renderer regression checks PDF 2.0 header, base PDF/A-4 identification,
  PDF/UA-2 identification, registered PDF 2.0 namespace, negative letter spacing,
  Unicode extraction, embedded fonts and ToUnicode entries.
- A separate pinned-source rebuild used an empty Maven repository, applied the
  four patches, ran upstream tests and reproduced all vendored artifact hashes.
- The final matrix has **34 conversions**, all passing standalone Java EA-PDF
  validation and PDF/A-4f: **22 existing-corpus conversions** and **12 table-edge
  fixture conversions**, each covering accessibility disabled/enabled. The corpus
  includes plain text, verified HTML bodies, verified inline images, Unicode,
  nested-message MIME/attachments and all six EA-PDF profiles. Original hashes
  remain unchanged. The synthetic table fixture isolates caption/spacing/Unicode
  behavior beyond the available real-input HTML body cases.
- All **17 accessibility-enabled outputs** pass explicit veraPDF PDF/UA-2 machine
  validation with zero failed rules. Extracted EA-PDF collection members, including
  the table-edge members, also pass PDF/UA-2 machine validation independently.
- No unresolved PDF/A-4f or PDF/UA-2 machine-check failure was found in this corpus.
  This is evidence for the tested cases, not a guarantee for arbitrary HTML/email.
  Generic image alternatives and complex reading order still require human review.

Results are recorded in `verification.json`. Local private logs, source hashes,
full Java reports and PDF/UA-2 reports are in local records.
The earlier raw dependency rebuild failed hash comparison because of volatile
Bnd manifest fields. Packaging normalization corrected that reproducibility
failure; a second independent source/cache rebuild reproduced all final hashes.

Selecting renderer conformance flags is not a compliance result. Explicit
PDF/UA-2 probing uses the same pinned veraPDF engine as the standalone Java
validator, with its PDF/UA-2 profile. Human accessibility checks (meaningful
alternative text, reading order and usability) remain separate from machine checks.

## Standalone validation

Build the existing validator (no rule changes) and validate a converted file:

```
mvn -f validator-desktop/pom.xml -Deapdf.build.directory=/tmp/eapdf-validator-build package
java -cp /tmp/eapdf-validator-build/eapdf-validator.jar eapdf.JavaValidation input.pdf eapdf-report.json
```

The machine PDF/UA-2 probe below checks the same file independently with the
PDF/UA-2 profile. Final PDF/A-4f results are not inferred from renderer settings.

## Clean-machine build

Requirements: Git, Maven, JDK 21 and Python 3. The tested source-rebuild toolchain
uses OpenJDK 21.0.12.1, Maven 3.8.7 and Python 3.12. Byte-identical source rebuilds
also depend on compiler/ZIP implementation versions; lock mismatch is an error,
not silently accepted.

```
python3 scripts/build_patched_openhtmltopdf.py
mvn -Dmaven.repo.local=/tmp/eapdf-clean-m2 -Deapdf.build.directory=/tmp/eapdf-build package
```

The first command verifies committed patches/artifacts. The second proves no
preexisting developer cache is necessary; ordinary pinned dependencies and Maven
plugins are fetched from their normal repositories, and patched renderer modules
come from `third_party/maven`.

To rebuild and verify the patched libraries themselves:

```
python3 scripts/build_patched_openhtmltopdf.py --rebuild
```

This command creates temporary source/cache directories, checks out the pinned
upstream commit, verifies patch hashes, applies all four with `git am`, builds with
`-Drevision=1.1.105-eapdf.1`, runs upstream tests, normalizes packaging and compares
SHA-256 hashes against `third_party/openhtmltopdf/lock.json`.

For explicit PDF/UA-2 machine checking using an existing standalone validator JAR:

```
javac -cp /path/to/eapdf-validator.jar -d /tmp/eapdf-ua2-probe scripts/java/eapdf/PdfUa2Probe.java
java -cp /tmp/eapdf-ua2-probe:/path/to/eapdf-validator.jar eapdf.PdfUa2Probe input.pdf report.json
```

Raw private-input conversion/validation evidence remains excluded from Git.
No validation rules are changed or suppressed by this integration.

## Confirmed table-caption assembly defect

The additional table fixture failed PDF/UA-2 clause 8.2.5.27. The renderer creates
an anonymous Div containing Table followed by Caption. Because Div is transparent
to the structure inclusion rules, the caption becomes a middle child of several
higher-level containers when more body content follows. The saved PDF and the
actual veraPDF findings confirmed this shape. The adapter must associate this
caption with its table while retaining its order and marked-content references;
the four upstream patches remain unchanged. No validation rule is relaxed.
This was corrected in `SemanticStructure.normalizeBlocks` for the exact wrapper
shape. A regression verifies that the final Caption belongs to Table and the
rendered text remains present. All six table-edge EA-PDF profiles now pass
PDF/A-4f and, when enabled, explicit PDF/UA-2 machine checking.

The resolved runtime dependency audit (`dependency-changes.json`) contains only
OpenHTMLtoPDF core/pdfbox 1.1.97 → 1.1.105-eapdf.1. PDFBox, FontBox, pdfbox-io,
XMPBox and unrelated dependency versions are unchanged. Machine checks used the
existing standalone validator with veraPDF core 1.31.1 / validation-model 1.31.17;
tool and candidate hashes are in `verification.json`.
