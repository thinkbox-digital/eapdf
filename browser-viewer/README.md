# EA-PDF readers

**Full and Lite readers 1.2.0-beta.2**, with creator/validator 2.2.0-beta.2.
Read local PDFs, browse email folders and save attachments. Files stay on your
computer. No Java, server or internet connection is needed by the extensions.

[Download](https://github.com/thinkbox-digital/eapdf/releases/tag/v2.2.0-beta.2) ·
[User guide](../docs/manual/user-manual.html#viewer) · [Privacy](../docs/site/privacy.md)

## Choose a reader

| | Full | Lite |
| --- | --- | --- |
| Message/folder navigation, attachments, CSV and XMP export | Yes | Yes |
| PDF text search and message filtering | Yes | Yes |
| Conversations and Portfolio view | Yes | No |
| Embedded search indexes and large-archive optimizations | Yes | No |
| PDF size/page limit | See [full-reader limits](docs/security.md) | 32 MiB / 2,048 pages per PDF |

Lite uses the same reading layout. It still scans message metadata for filtering;
this is separate from the embedded search indexes it leaves out.
The two extensions can be installed together.

## Install

**Chromium:** extract the Chromium ZIP. Open `chrome://extensions`, enable
**Developer mode**, choose **Load unpacked**, and select the folder containing
`manifest.json`. Click the extension icon to open the reader.

**Firefox:** extract the Firefox ZIP. Open `about:debugging#/runtime/this-firefox`,
choose **Load Temporary Add-on**, and select `manifest.json`. Repeat after restarting
Firefox. These beta packages are unsigned.

**Lite web page:** extract the web ZIP, then run:

```sh
python3 -m http.server 8765 --bind 127.0.0.1 --directory /tmp/eapdf-viewer-lite-web
```

Open `http://127.0.0.1:8765/viewer.html`. Use the directory where you extracted the
ZIP. Double-clicking `viewer.html` is not supported.

## Use

- Click **Open PDF** or drop a file into the reader.
- Select a message or folder on the left. Scroll or use page controls to read.
- Filter by subject, sender, recipients or date. PDF text search is separate.
- Use **View** to open an embedded PDF, image or text file. Use **Parent** to return.
- Download attachments, message PDFs, selected metadata as CSV, or original XMP.
  Other attachment types use normal browser downloads.
- In the full reader, enable **Conversations** to group related messages. Only
  links found in email metadata are confirmed; optional subject grouping is inferred.
- The full reader also offers Portfolio layouts for ordinary PDF collections.

Incomplete scans and unsupported files show a warning. A readable PDF has not
necessarily passed validation. Original files are unchanged. External actions and
embedded scripts do not run.

## Limits

Lite limits decoded attachments to 16 MiB, metadata to 2 MiB, and open child PDFs
to 32 at once and four levels deep. Closing a child returns its allowance, so a
background scan can read more than 32 messages. Use the full reader for larger archives.

See [security and limits](docs/security.md), [browser support](docs/compatibility.md),
and the [PDF.js patch guide](docs/pdfjs-patch-maintenance.md) for technical details.

## Build

Use the Node.js/npm versions listed in `package.json`, then run from this folder:

```sh
npm ci
npm run check
npm test
npm run package:beta
npm run build:lite
```

Packages appear in `releases/`. The Lite command also makes a standalone source ZIP.
Shared fixes belong in this repository; regenerate Lite rather than editing an
exported copy. See [Lite maintenance](lite/README.md) and [code layout](../docs/ARCHITECTURE.md#maintained-lite-viewer-export).

## Tests

`npm test` runs the full unit and Chromium suites. `npm run test:firefox` covers
Firefox. The independent PDF inspection checks use Python and
`reference/requirements.txt`; run them with `npm run test:m1`.
Browser tests need the corresponding browser and driver installed. Use
`CHROMIUM_PATH`, `FIREFOX_PATH` and `GECKODRIVER_PATH` when they are outside the default locations.

For PDF.js API and fixture details, see the [inspection contract](docs/inspection-schema.md).

Build uses TypeScript and PDF.js **6.5.438**.
PDF.js upstream commit: `0a4153349dc473ba950fc17941c5ca7eda43c1de`.
