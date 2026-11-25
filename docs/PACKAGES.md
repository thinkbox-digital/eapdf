# Library packages

EA-PDF ships patched libraries for email rendering, PDF reading, and validation.
The OpenHTMLtoPDF builds are also published to GitHub Packages. Download the
applications from [Releases](https://github.com/thinkbox-digital/eapdf/releases).

## OpenHTMLtoPDF

| Library | Published version | Current source version | Why it is patched |
| --- | --- | --- | --- |
| `io.github.openhtmltopdf:openhtmltopdf-core` | `1.1.113-eapdf.1` | `1.1.114-eapdf.2` | The current core checks for cancellation and enforces the 2,048-page limit before allocating each layout page. [Patch 0005](../third_party/openhtmltopdf/patches/0005-Bound-layout-page-allocation.patch) |
| `io.github.openhtmltopdf:openhtmltopdf-pdfbox` | `1.1.113-eapdf.1` | `1.1.114-eapdf.2` | Adds PDF/A-4 and PDF/UA-2 support to the renderer, preserves extractable word spaces under negative letter spacing, and disables GSUB ligatures to keep text mapped to Unicode. |

The two OpenHTMLtoPDF modules are built and versioned together. Each published
artifact has a matching source JAR and license information. See [renderer details](RENDERER.md).

## Java library patches bundled with EA-PDF

EA-PDF resolves these builds from the checked-in Maven repository.

| Library | Version | Why it is patched |
| --- | --- | --- |
| Apache FontBox | `3.0.8-eapdf.1` | Handles malformed or overlapping character-map ranges and limits nested Type 1 and Type 2 glyph subroutines when reading embedded fonts. [Recipe and hashes](../third_party/fontbox/lock.json) |
| Apache PDFBox | `3.0.8-eapdf.1` | Lets the Desktop Viewer reuse the document’s cross-reference index when reading compressed objects, avoiding repeated index construction. [PDFBOX-6268](https://issues.apache.org/jira/browse/PDFBOX-6268) · [Recipe and hashes](../third_party/pdfbox/lock.json) |
| java-libpst | `0.9.3-eapdf.1` | Bounds PST decoding, checks for cancellation, and reports a limit failure so the Creator can stop the conversion and flag the incomplete source. [Recipe and hashes](../third_party/java-libpst/lock.json) |

The Validator also bundles local veraPDF source overrides for `core`, `parser`,
and `validation-model`. They bound validation storage and decoding, reuse
structure and cross-reference data, and defer resource checks until needed.
[Architecture notes](ARCHITECTURE.md#library-patches) · [Validator packaging](../validator-desktop/pom.xml)

## Browser patch

The browser bundles patch PDF.js 6.5.438 to inspect document parts, associated
files and metadata, navigate Portfolios, and extract pages without copying the
archive’s files or metadata. They also carry compatibility fixes for text
extraction, cross-reference streams and embedded fonts. [Patch maintenance](../browser-viewer/docs/pdfjs-patch-maintenance.md)

## Using the packages

EA-PDF source builds use the checked-in local Maven repository and do not need a
GitHub token. Other projects that retrieve the published Maven artifacts from
`https://maven.pkg.github.com/thinkbox-digital/eapdf` need a token with `read:packages`,
even for public packages.

Follow [GitHub's setup guide](https://docs.github.com/en/packages/working-with-a-github-packages-registry/working-with-the-apache-maven-registry).
Keep tokens in private Maven settings, never in this repository.

Maintainers publish through the **Publish patched libraries** workflow. It checks
the uploads against `third_party/openhtmltopdf/lock.json`. Use a new package version
when library contents change; `verify_only` checks an existing publication.
