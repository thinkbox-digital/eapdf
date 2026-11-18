# EA-PDF readers

**Full and Even More Lite readers 1.3.0-beta.1**, with creator/validator 2.3.0-beta.1.
Read local PDFs, browse email folders and save attachments. Files stay on your
computer. No Java, server or internet connection is needed by the extensions.

[Download](https://github.com/thinkbox-digital/eapdf/releases/tag/v2.3.0-beta.1) ·
[User guide](../docs/manual/user-manual.html#viewer) · [Privacy](../docs/site/privacy.md)

## Choose a reader

| | Full | Even More Lite |
| --- | --- | --- |
| Message/folder navigation, PDF pages and embedded assets | Yes | Yes |
| PDF text search and message filtering | Yes | No |
| Independent CSV/XMP metadata export | Yes | No |
| Conversations and Portfolio view | Yes | No |
| Embedded search indexes and large-archive optimizations | Yes | No |
| PDF size/page limit | See [full-reader limits](docs/security.md) | 32 MiB / 2,048 pages per PDF |

Even More Lite uses three adjustable panes for messages, pages and assets.
The two extensions can be installed together. Viewer Lite is discontinued;
its last packages remain in the previous release. See the
[Even More Lite guide](even-more-lite/README.md) for controls and limits.

## Install

**Chromium:** extract the Chromium ZIP. Open `chrome://extensions`, enable
**Developer mode**, choose **Load unpacked**, and select the folder containing
`manifest.json`. Click the extension icon to open the reader.

**Firefox:** extract the Firefox ZIP. Open `about:debugging#/runtime/this-firefox`,
choose **Load Temporary Add-on**, and select `manifest.json`. Repeat after restarting
Firefox. These beta packages are unsigned.

**Even More Lite web page:** extract the web ZIP to a temporary directory, then run:

```sh
python3 -m http.server 8765 --bind 127.0.0.1 --directory /tmp/eapdf-viewer-even-more-lite-web
```

Open `http://127.0.0.1:8765/viewer.html`. Use the directory where you extracted the
ZIP. Double-clicking `viewer.html` is not supported.

## Use

- Click **Open PDF** or drop a file into the reader.
- Select a message or folder on the left. Scroll or use page controls to read.
- Open embedded PDFs, preview supported images/text, or download their original bytes.
- Use **Parent** to return to the containing PDF.
- In Full, use search/filtering, independent CSV/XMP exports, Conversations and Portfolio layouts.

Incomplete scans and unsupported files show a warning. A readable PDF has not
necessarily passed validation. Original files are unchanged. Embedded scripts do not run.

## Build

Use the Node.js/npm versions listed in `package.json`, then run from this folder:

```sh
npm ci
npm run check
npm test
npm run package:beta
npm run build:even-more-lite
```

Packages appear in `releases/`. Even More Lite also includes a standalone source
ZIP. Shared fixes belong in this repository; regenerate exported source.
The retained Lite exporter supplies generic hooks and build infrastructure;
release builds produce Full and Even More Lite packages.

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
