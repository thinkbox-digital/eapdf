# Documentation map

Start with [current project status](PROJECT_STATUS.md) for the release baseline,
unreleased work and remaining acceptance. Use [architecture and separation decisions](ARCHITECTURE.md) for an engineering handover and root-file layout.

## Maintained sources

| Question | Authoritative guide |
| --- | --- |
| What is the current scope and what still needs acceptance? | [Project status](PROJECT_STATUS.md) |
| Where does code belong and what should be separated? | [Architecture](ARCHITECTURE.md) |
| How do users install and operate the applications? | [Editable user manual](manual/user-manual.html); the website is generated from it. The [PDF manual](EA-PDF_User_Manual.pdf) is a separately built release artifact. |
| What features are implemented? | [Feature inventory](FEATURES.md) |
| How does the browser reader behave? | [Viewer guide](../browser-viewer/README.md), [beta installation](../browser-viewer/docs/beta-release.md), [browser compatibility](../browser-viewer/docs/compatibility.md) |
| What do validation results mean? | [Coverage and limitations](VALIDATOR_COVERAGE.md), [Java validator](../validator-desktop/README.md), [Arlington](ARLINGTON_VALIDATION.md) |
| How are conversion and deployment operated? | [Conversion/recovery](CONVERSION_OPERATIONS.md), [Gmail](GMAIL.md), [web deployment](DEPLOY_WEB.md), [security](security/HARDENING.md) |
| How do signing and embedded indexes interact? | [Signatures](DIGITAL_SIGNATURES.md), [embedded indexes](EMBEDDED_SEARCH_INDEX.md) |
| What must a distributor review? | [Licenses](THIRD_PARTY_LICENSES.md), [renderer provenance](RENDERER.md), [runtime inventory](security/runtime-inventory/DEPENDENCY_LICENSES.md), [packages](PACKAGES.md) |
| What do the PDF.js archive patches contribute? | [Controlled comparison](PDFJS_LARGE_ARCHIVE_COMPARISON.md) |
| How are these documents published? | [Website maintenance](WEBSITE.md) and [manual build](manual/BUILD.txt) |

## History and publication

[Release notes](releases/2.1.0-beta.1.md), the [changelog](CHANGELOG.md) and
[repository audit](REPOSITORY_AUDIT.md) are dated evidence. Their counts and
then-open findings apply to those checkpoints. [Specification review material](specification-review/README.md)
is historical discussion, not a current compliance verdict.

Update the authoritative guide first, then the feature summary or release notes
that describe the same behaviour. Keep versions in VERSION/package.json and the
release/download pages aligned. Verify examples, controls and limits against
source and tests. Changes to HTML user guidance require a separately verified
PDF rebuild before claiming that the downloadable manual is refreshed.

The website copies an explicit source allowlist and manual screenshots; it does
not copy the whole docs directory. The local development log and docs/private
are ignored, untracked work records, excluded from that publication list. Public
guides must not link to private history, local run output or correspondence.
