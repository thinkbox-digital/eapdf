# Viewer 1.1.0-beta.1 — beta release (2026-11-10)

Packages accompany EA-PDF 2.1.0-beta.1. The release integrates Conversations,
chronological file groups, selectable CSV/original-XMP metadata export,
message-as-PDF downloads and initial viewing destinations. This is beta software.
See [current project status](../../docs/PROJECT_STATUS.md) for unreleased fixes
and [viewer use](../README.md) for behaviour; source changes are not automatically
present in the downloadable ZIPs.

## Install or update

Download the packages from the
[GitHub beta release](https://github.com/thinkbox-digital/eapdf/releases/tag/v2.1.0-beta.1):

- `eapdf-viewer-1.1.0-beta.1-chromium.zip`
- `eapdf-viewer-1.1.0-beta.1-firefox.zip`

Extract the new ZIP. Chromium: open `chrome://extensions`, enable Developer mode
and load the extracted directory with **Load unpacked**. Firefox: open
`about:debugging#/runtime/this-firefox`, choose **Load Temporary Add-on**, and select
its `manifest.json`. Packages are unsigned; Firefox installation ends on exit.

Remove or disable the older installation, and close old viewer tabs. The footer
must show `1.1.0-beta.1`; the extension manager shows `1.0.0.2`. Reloading an older
extracted directory does not update it. Workspace builds use
`browser-viewer/dist/chromium/` or `browser-viewer/dist/firefox/manifest.json`.

## Example emails

Download [the example bundle](https://github.com/thinkbox-digital/eapdf/releases/download/v2.1.0-beta.1/ea-pdf-examples-2.1.0-beta.1.zip),
or see the [example inventory](../../examples/README.md).

## Use and limitations

The [viewer README](../README.md#use) describes navigation, filtering, text search,
attachment viewing/downloads and CSV export. Normal Messages view keeps the source/attachment/supporting groups open.
Conversations initially collapses the other right-pane sections and keeps details
closed to leave room for its thread tree. Empty files retain Download and have no View control. Isolated profiles show a single notice about omitted source
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

The beta.2 release passed the required Java, Python, PDF.js, conversion acceptance,
Chromium and Firefox GitHub checks. Its recorded viewer unit total is 180. The
release assets were downloaded and checksum verified. These are release results,
not a fresh acceptance run for every later source edit; current local verification
is separated in [project status](../../docs/PROJECT_STATUS.md).

Tests cover six profiles, recursive navigation, whole-word search/filtering,
metadata and download bytes, initial destinations, aliases, cancellation, recovery,
index fallback and resource handling. The test guide and recorded compatibility
scope do not establish unrestricted capacity, all-platform usability, store
approval or independent conformance certification.

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

On 2026-11-09, `main` protection was enabled and read back through the GitHub API.
It requires `java-builds`, `pdfjs-pin`, `python-regressions`,
`conversion-acceptance`, `Viewer (chromium)` and `Viewer (firefox)` from the
GitHub Actions app, with strict up-to-date checks and administrator enforcement.
Force pushes and branch deletion are disabled; no reviewer-approval requirement
was added. Before each release, verify these settings still apply with
`gh api repos/thinkbox-digital/eapdf/branches/main/protection` and check the
candidate commit's results. Path-filtered documentation jobs are not required
for every change.
