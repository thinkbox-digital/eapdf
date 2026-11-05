# Patched renderer

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




## Building and replacing the renderer

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
