# Viewer 0.5.0-beta.17 — GitHub prerelease (2026-11-07)

Packages accompany EA-PDF 1.6.0-beta.18. This beta adds embedded archive search,
corrects text-fragment matching and highlights, improves CSV export and keyboard
navigation, and returns filename selections to the first page at Fit Width.

## Install or update

Download the packages from the
[GitHub beta release](https://github.com/thinkbox-digital/eapdf/releases/tag/v1.6.0-beta.18):

- `eapdf-viewer-0.5.0-beta.17-chromium.zip`
- `eapdf-viewer-0.5.0-beta.17-firefox.zip`

Extract the appropriate ZIP into a new directory. Chromium: open
`chrome://extensions`, enable Developer mode, choose **Load unpacked**, then select
that directory. Firefox: open `about:debugging#/runtime/this-firefox`, choose
**Load Temporary Add-on**, then select the extracted `manifest.json`.
The packages are unsigned. Firefox's temporary installation ends when the browser
closes. No Java, Python, Node or server is needed to use either extension.

Remove or disable the older installation. Reloading an old extracted directory
does not download an update. Close old viewer tabs and open a new one. The footer
shows `0.5.0-beta.17`; the browser's extension manager shows `0.5.0.17`.
For workspace builds, reload `browser-viewer/dist/chromium/` or reinstall
`browser-viewer/dist/firefox/manifest.json`.

## Example emails

Download [the example bundle](https://github.com/thinkbox-digital/eapdf/releases/download/v1.6.0-beta.18/ea-pdf-examples-1.6.0-beta.18.zip),
or see the [example inventory](../../examples/README.md).

## Use and limitations

The [viewer README](../README.md#use) describes navigation, filtering, text search,
attachment viewing/downloads and CSV export. Email sources, attachments, other files and
technical/supporting file groups start expanded. Empty files have no View or
Download controls. Isolated profiles show a single notice about omitted source
messages; an empty attachment alone does not imply an isolated profile.

The viewer displays the declared EA-PDF profile; it does not validate conformance.
Use the standalone validator and read its [coverage and limitations](../../docs/VALIDATOR_COVERAGE.md).
An INCOMPLETE result means required evidence or an assessment is unavailable,
not a pass. Missing original dates and unsupported or ambiguous native MSG source
comparisons can remain unresolved. Large-archive loading, performance and known
validation problems remain deferred; this beta does not claim they are fixed.

Current processing limits are in the [README](../README.md#limits) and
[security boundaries](security.md). A file below a size ceiling is not guaranteed
to load successfully. See [compatibility](compatibility.md) for platform coverage.

## Verified scope

Local beta.18 checks passed: 118 unit tests, all standard Chromium test commands,
fresh Firefox installation/UI checks, inspection-oracle tests and the bounded
44 MB archive probe. One keyboard test expectation was updated from two to three
phrase matches after correcting whitespace normalization; it then passed.

Release checks and remaining limitations are recorded in the
[embedded-index design](../../docs/EMBEDDED_SEARCH_INDEX.md) and
[repository audit](../../docs/REPOSITORY_AUDIT.md). Performance measurements for
older index engines remain historical; they are not claims for this build.

## Rebuild and release checks

Run `npm ci`, `npm run check`, `npm run build`, and `npm run package:beta` from
`browser-viewer/`. Python 3 is a development-only packaging dependency. Preserve
the licenses, notices, `license-sources/`, `SBOM.cdx.json` and `build-info.json`.
See [patch maintenance](pdfjs-patch-maintenance.md).

Before publishing, verify the explicit prerelease tag and every documented asset
against GitHub. Do not replace prerelease links with `/latest`, which excludes
prereleases. Verify both browser manifest versions, ZIP checksums and `npm run verify:pdfjs`.


For subsequent releases, require the quality workflow's separate Chromium,
Firefox, Python regression and conversion-acceptance jobs, as well as Java and
PDF.js pin checks. Firefox runs `M5_UI=1 npm run test:firefox` after a build and
performs a fresh temporary add-on installation. It does not establish signed
installation, store approval or cross-platform compatibility. `npm test` includes
the upstream/patched PDF.js differential checks; `npm run test:archive-memory`
adds the public 44 MB repeated-open/search/extraction probe. These requirements
do not retroactively change the beta.17 verification record above.

`package:beta` rejects version/hash mismatches and unexpected files even under
optimized Python, then verifies the ZIP entries against the validated bytes.
Repository branch protection must require the named checks; editing workflow
files alone does not enable branch protection or demonstrate a remote CI pass.
