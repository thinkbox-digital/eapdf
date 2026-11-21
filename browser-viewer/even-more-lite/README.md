# EA-PDF Even More Lite

A reading-only copy of Viewer Lite with a smaller interface and application.
Open a local PDF or drop it into the window. Use Parent, previous/next page,
page number and zoom. Close releases the document; it also cancels an opening.
The left pane shows messages, folders and collection PDFs; the centre displays
pages and the right pane lists embedded assets. Drag either divider or focus it
and use the arrow keys to resize. The pane-heading chevrons hide Messages or
Files. Show messages and Show files restore them above the document. Page, zoom
and Parent controls sit above the PDF. The top bar shows the open filename or
the path to the active embedded PDF.
Click a message to read its body and known assets; All files restores the complete
document inventory. Parent returns to the previous page in the containing PDF.
Scroll normally; text selection and accessible PDF structure remain available.

The download-arrow icon saves exact embedded bytes. The eye icon opens a PDF or
previews supported images/text using the Full viewer's shared preview component.
Hover titles and accessible labels identify both actions. Attachment links open
or preview supported files; other types download. Files are grouped as collection
PDFs, email sources, attachments and supporting files. The total includes all
embedded files, not just email messages. Asset rows show known owners and actual
attachment pages. Go to page
jumps to that attachment's page. Document attachments have no invented page.
The known owning subject appears directly under a filename, as in the Full reader.
Go to page remains available for a recorded attachment-page association. Broken
links are reported explicitly. Scrolling follows the active message in the left
pane without changing the reading position. At an email PDF boundary, another
scroll gesture opens the next/previous email in that same folder and selects it
in the retained folder tree. Folder ends and nested collections stop automatic
continuation.

A collection's cover can have one page while its emails live in separate child
PDFs. The tree lists those PDFs under their folders. Cover-level message metadata
without a body page is not repeated as a misleading unavailable-page row. When a
non-collection PDF genuinely lacks a body-page link, selecting the metadata row
explains that the body page is not identified in that PDF.

Search, filtering controls, CSV/XMP exports, metadata dialogs,
Conversations, Portfolio view and embedded search indexes are omitted.
Collection members use their stored filenames; there is no background scan of
child message subjects. The Full reader retains its independent CSV/XMP export. Viewer Lite is no longer built or published.

Limits: 32 MiB / 2,048 pages per PDF, 16 MiB per decoded asset, 2 MiB metadata
per document and four embedded levels. Only the current document and its parents
are retained; returning to a parent releases the child. Incomplete inventories
show a warning. Application limits do not bound total browser memory.
Unreadable assets and exceeded limits are reported. Downloads retain the original
bytes and warn about conflicting declared sizes/checksums. Embedded PDF opening
requires matching declarations. The source PDF remains unchanged.
No conformance validation, external URL opening, uploads or analytics.

See INSTALL.txt for local installation. Packages are unsigned beta builds.

## Build

From this repository's browser-viewer directory:

```sh
npm run build:even-more-lite
```

This builds only Even More Lite using the retained Lite exporter, generic PDF.js
hooks and maintained overrides in `even-more-lite/overrides`. Shared fixes stay in
this repository. Browser/web packages, a standalone source ZIP, checksums and
provenance appear in `releases/`. Nothing is published by this command.

In a standalone source export, with the Node/npm versions in package.json:

```sh
npm ci --ignore-scripts
npm run check
npm run build
npm test
npm run test:browser
npm run test:firefox
npm run package
```

Builds need no Java or backend. Packaging uses Python's standard library.
Use the shared PDF.js patch guide for reproducing the generic hook bundles.
All fonts, character maps and image/color decoders remain packaged for offline
rendering. JavaScript and CSS are minified; source maps and the welcome image
are omitted. The size report compares application bytes, source lines and full
ZIP sizes separately; PDF.js and its rendering assets dominate the download.
