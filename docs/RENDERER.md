# Patched renderer

## Dependency provenance

Upstream: `https://github.com/openhtmltopdf/openhtmltopdf`, commit
`ce841f1dadb45479fd5d5260375f807151eb67b8`.
Patches: `https://github.com/jialiang/HTML-to-PDF-UA`, pinned repository commit
`7fff0e4ad793a521659ad19d46f4d9ddb39941a2`.

The four rebased patches apply in numerical order with `git am`. Fixed
committer identity and `--committer-date-is-author-date` make application commit
identifiers reproducible. The rebase retains upstream transparency-group MCID handling alongside the PDF/UA-2 namespace additions.

| Patch | Original patch commit | Applied commit |
|---|---|---|
| 0001 | `225219232e0e87d89683a0ac2b31472f91dbf491` | `c91aaa8848a5097a1e5dc87212f6a610b830554a` |
| 0002 | `54b4513f20c1d55bc212b9ab11b83740239979d3` | `dbd94bfcf5070a775b3b8a5fd0480f5b44c1c9b4` |
| 0003 | `9648fff9aba215891af47fc51890e498ace91074` | `959bf8d785a2ea5754190385ddcd3b5acb379124` |
| 0004 | `98572627de49af5e978acbb50aa1ee5c8625a284` | `a0f6c64328db5424d8d973574b5e11d539258e8d` |

The patched version is **1.1.114-eapdf.2** and covers `openhtmltopdf-core` and
`openhtmltopdf-pdfbox`. Patch 0005 checks cancellation and the 2,048-page limit
before OpenHTMLtoPDF creates each layout page. The application POM resolves the modules
from the version-controlled `third_party/maven` file repository. Production
builds do not require patched artifacts preinstalled in a developer's Maven
cache. Flattened POMs, binary JARs, corresponding source JARs, SHA-1/SHA-256
sidecars and a SHA-256 lock are supplied. Other PDF libraries are not added.

The base source patches are retained as reviewed derivatives. A packaging step fixes
ZIP entry order/time/mode and volatile OSGi manifest build fields
(`Bnd-LastModified`, `Built-By`, `Build-Jdk`). The layout guard rebuild compiles
the changed `Layer` class and updates package version metadata. The upstream
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




## Building and replacing the renderer

Requirements: Git, Maven, JDK 21 and Python 3. The tested source-rebuild toolchain
uses OpenJDK 21.0.13.0.3, Maven 3.8.7 and Python 3.12. Byte-identical source rebuilds
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
upstream commit, applies the four base patches with `git am`, and builds with
`-Drevision=1.1.114-eapdf.1`. It checks the normalized base artifacts against
`third_party/openhtmltopdf/lock.json` before compiling and verifying patch 0005.
`python3 scripts/build_patched_openhtmltopdf.py --rebuild-layout-guard` reproduces
the layout guard offline using the committed base artifacts.

For explicit PDF/UA-2 machine checking using an existing standalone validator JAR:

```
javac -cp /path/to/eapdf-validator.jar -d /tmp/eapdf-ua2-probe scripts/java/eapdf/PdfUa2Probe.java
java -cp /tmp/eapdf-ua2-probe:/path/to/eapdf-validator.jar eapdf.PdfUa2Probe input.pdf report.json
```

Raw private-input conversion/validation evidence remains excluded from Git.
No validation rules are changed or suppressed by this integration.
