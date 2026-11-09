# Viewer 1.0.0-beta.1 — beta release (2026-11-08)

Packages accompany EA-PDF 2.0.0-beta.1. This beta fixes Messages-pane arrow
navigation and stale filtering status, adds compact filtering indexes, and moves
large metadata parsing off the UI thread. CSV export remains available.

## Install or update

Download the packages from the
[GitHub beta release](https://github.com/thinkbox-digital/eapdf/releases/tag/v2.0.0-beta.1):

- `eapdf-viewer-1.0.0-beta.1-chromium.zip`
- `eapdf-viewer-1.0.0-beta.1-firefox.zip`

Extract the new ZIP. Chromium: open `chrome://extensions`, enable Developer mode
and load the extracted directory with **Load unpacked**. Firefox: open
`about:debugging#/runtime/this-firefox`, choose **Load Temporary Add-on**, and select
its `manifest.json`. Packages are unsigned; Firefox installation ends on exit.

Remove or disable the older installation, and close old viewer tabs. The footer
must show `1.0.0-beta.1`; the extension manager shows `0.5.0.20`. Reloading an older
extracted directory does not update it. Workspace builds use
`browser-viewer/dist/chromium/` or `browser-viewer/dist/firefox/manifest.json`.

## Example emails

Download [the example bundle](https://github.com/thinkbox-digital/eapdf/releases/download/v2.0.0-beta.1/ea-pdf-examples-2.0.0-beta.1.zip),
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
comparisons can remain unresolved. Large-archive capacity and source-validation limits remain documented; the
verified larger-file tests do not establish unrestricted capacity.

Full-text indexes created with older extraction engines fall back to PDF.js
scanning. Regenerate an archive with the current creator for the corrected fast
index; existing PDFs remain viewable.

Current processing limits are in the [README](../README.md#limits) and
[security boundaries](security.md). A file below a size ceiling is not guaranteed
to load successfully. See [compatibility](compatibility.md) for platform coverage.

## Verified scope

Local release checks passed: 131 viewer unit tests and all Chromium integration
stages, Firefox UI acceptance with a fresh temporary installation, and 72
unsigned/signed Chromium indexed/scanned comparisons with highlighting. The
independent inspection oracle and the 44 MB repeated-open/search/extraction
probe also passed. Both viewer packages reproduce byte for byte after a clean
rebuild.

Regenerated examples now contain indexes and omit optional PREMIS. Scan-specific
tests explicitly exercise fallback, while a frozen public legacy fixture retains
PREMIS grouping coverage. Navigation checks use the refreshed PDF page counts. Association and search-result
navigation checks also passed against the regenerated 1,433-message Enron archive;
its separate validation limitation remains documented in the example inventory.

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
adds the public 44 MB repeated-open/search/extraction probe. These requirements apply to the current release; historical verification
records remain in the repository audit.

`package:beta` rejects version/hash mismatches and unexpected files even under
optimized Python, then verifies the ZIP entries against the validated bytes.
Repository branch protection must require the named checks; editing workflow
files alone does not enable branch protection or demonstrate a remote CI pass.
