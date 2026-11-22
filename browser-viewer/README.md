# EA-PDF readers

The Full browser reader **1.8.0** ships with creator and
validator **2.4.0-beta.3**. It restores recognized family names for affected embedded CJK fonts.
Read local PDFs, browse email folders and save attachments. Files stay on your
computer. No Java, server or internet connection is needed by the extensions.

[Download](https://github.com/thinkbox-digital/eapdf/releases/tag/v2.4.0-beta.3) ·
[User guide](../docs/manual/user-manual.html#viewer) · [Privacy](../docs/site/privacy.md)

Full offers search, filtering, CSV/XMP export, Conversations, Portfolio
view and large-archive support. EA-PDF collections use email navigation.
See [reader limits](docs/security.md).

## Install

**Chromium:** extract the Chromium ZIP. Open `chrome://extensions`, enable
**Developer mode**, choose **Load unpacked**, and select the folder containing
`manifest.json`. Click the extension icon to open the reader.

**Firefox:** extract the Firefox ZIP. Open `about:debugging#/runtime/this-firefox`,
choose **Load Temporary Add-on**, and select `manifest.json`. Repeat after restarting
Firefox. These beta packages are unsigned.

## Use

- Click **Open PDF** or drop a file into the reader.
- Select a message or folder on the left. Scroll or use page controls to read.
- Open embedded PDFs, preview supported images/text, or download their original bytes.
- Use **Parent** to return to the containing PDF.
- Use search/filtering, independent CSV/XMP exports, Conversations and Portfolio layouts.

Incomplete scans and unsupported files show a warning. A readable PDF has not
necessarily passed validation. Original files are unchanged. Embedded scripts do not run.

## Build

Before regenerating extension distributables, bump the reader minor version in
`package.json` and `package-lock.json` using the three-part format `1.x.0`. Rebuild
Full for Chromium and Firefox using one version per regeneration batch. Verification
rebuilds in that batch retain the chosen version. Keep local installation paths
stable; reload the extensions and reopen viewer tabs after updating their files.

Use the Node.js/npm versions listed in `package.json`, then run from this folder:

```sh
npm ci
npm run check
npm test
npm run package:beta
```

Full Chromium and Firefox packages appear in `releases/`. The shared
exporter also supports the separate [EA-PDF Lite repository](https://github.com/thinkbox-digital/eapdf-lite).

## Tests

`npm test` runs the full unit and Chromium suites. `npm run test:firefox` covers
Firefox. The independent PDF inspection checks use Python and
`reference/requirements.txt`; run them with `npm run test:m1`.
The inspection provenance records exact package versions and source hashes. After
changing those requirements, install the complete pins in an isolated CPython 3.12
environment and run `python -B reference/fixtures.py --write`. Review the manifest
and extracted-record differences, then run `npm run test:m1` and
`npm run fixtures:check` with that environment on `PATH`. Tests never refresh
their expected records automatically.
Browser tests need the corresponding browser and driver installed. Use
`CHROMIUM_PATH`, `FIREFOX_PATH` and `GECKODRIVER_PATH` when they are outside the default locations.

For PDF.js API and fixture details, see the [inspection contract](docs/inspection-schema.md).

Build uses TypeScript and PDF.js **6.5.438**.
PDF.js upstream commit: `0a4153349dc473ba950fc17941c5ca7eda43c1de`.
