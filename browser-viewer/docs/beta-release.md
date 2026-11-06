# Viewer 0.5.0-beta.11 — GitHub prerelease (2026-11-06)

Packages accompany EA-PDF 1.6.0-beta.12. This beta adds filtered-result scrolling:
selecting an email preserves the results, and forward/backward scrolling follows
that list. Clearing the filter restores normal archive scrolling.

## Install or update

Download the packages from the
[GitHub beta release](https://github.com/thinkbox-digital/eapdf/releases/tag/v1.6.0-beta.12):

- `eapdf-viewer-0.5.0-beta.11-chromium.zip`
- `eapdf-viewer-0.5.0-beta.11-firefox.zip`

Extract the appropriate ZIP into a new directory. Chromium: open
`chrome://extensions`, enable Developer mode, choose **Load unpacked**, then select
that directory. Firefox: open `about:debugging#/runtime/this-firefox`, choose
**Load Temporary Add-on**, then select the extracted `manifest.json`.
The packages are unsigned. Firefox's temporary installation ends when the browser
closes. No Java, Python, Node or server is needed to use either extension.

Remove or disable the older installation. Reloading an old extracted directory
does not download an update. Close old viewer tabs and open a new one. The footer
shows `0.5.0-beta.11`; the browser's extension manager shows `0.5.0.11`.
For workspace builds, reload `browser-viewer/dist/chromium/` or reinstall
`browser-viewer/dist/firefox/manifest.json`.

## Example emails

Download [the example bundle](https://github.com/thinkbox-digital/eapdf/releases/download/v1.6.0-beta.12/ea-pdf-examples-1.6.0-beta.12.zip),
or see the [example inventory](../../examples/README.md).

## Use and limitations

The [viewer README](../README.md#use) describes navigation, filtering, text search,
attachment viewing/downloads and CSV export. Email sources, attachments and
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

The filtered-scrolling change passed TypeScript/contract checks, 58 unit tests,
and Chromium interaction checks for 1c, 1ci, 1m and 1mi. Checks cover retained
results, forward/backward scrolling, a single matching result, list boundaries,
clearing filters, ordinary folder boundaries and nested collections.
Both browser distributions were rebuilt. Fresh-install acceptance testing and
Firefox interaction were not rerun for this release. Large-file regressions
were deliberately deferred.

## Rebuild and release checks

Run `npm ci`, `npm run check`, `npm run build`, and `npm run package:beta` from
`browser-viewer/`. Python 3 is a development-only packaging dependency. Preserve
the licenses, notices, `license-sources/`, `SBOM.cdx.json` and `build-info.json`.
See [patch maintenance](pdfjs-patch-maintenance.md).

Before publishing, verify the explicit prerelease tag and every documented asset
against GitHub. Do not replace prerelease links with `/latest`, which excludes
prereleases. Verify both browser manifest versions, ZIP checksums and `npm run verify:pdfjs`.
