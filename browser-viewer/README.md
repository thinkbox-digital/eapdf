# EA-PDF browser viewer — beta 1.0.0-beta.1

Current beta: `1.0.0-beta.1`, with creator/validator `2.0.0-beta.1`.

This demonstration focuses on viewing emails and their files. It uses PDF.js
6.5.438 and retains the existing Chromium/Firefox architecture.

Read emails, browse collection folders, search text, zoom or fit pages, and
view/download embedded files. Image and text previews have a fixed title bar with
a labeled **× Close** button at the top right; Escape also closes the preview
and returns focus to its View control. The file panel identifies the containing PDF and
links owning emails automatically. New creator output records explicit file
ownership; older files can use bounded header matching. Insufficient evidence
is a document error, not a guessed association.

CSV export reads email metadata directly and does not require an embedded finding aid.

**Export CSV** includes PDF paths, collection folder paths, sender,
recipients, subject, date, Message-ID, attachment counts and attachment names.
Multiple folder memberships and nested collections are preserved. Mail GUID is
not exported. Missing counts are blank; recorded zero counts remain zero. Names
come from email metadata, so a `ci` finding aid does not claim that original
attachment bytes are embedded. Unreadable or conflicting information prevents a
misleading complete export. Export covers the selected PDF and its children.

The redundant Email Details pane, printing and user-controlled rotation remain
removed. Intrinsic PDF page orientation is respected. Search opens from the header
or Ctrl/Cmd+F; Enter and Shift+Enter move between matches, and Escape closes the
panel. The message tree supports native Tab/Enter disclosure controls and arrow-key
movement. The viewer tagline is “Your email—complete, authentic, accessible, structured.”

New creator collections can carry an optional [embedded metadata search index](../docs/EMBEDDED_SEARCH_INDEX.md). The viewer verifies it once and builds the message list without opening each child PDF; missing or invalid indexes retain the existing scan fallback. New `1m`, `1mi`, `1c`, and `1ci` archives can also carry a positional full-text index. It locates matching pages without opening every child; selection confirms and highlights the actual PDF.js text. Corrupt, stale, unsupported or oversized indexes fall back to ordinary search. See the same [index reference](../docs/EMBEDDED_SEARCH_INDEX.md#full-text-index-version-2) for exact phrase semantics, resource limits and built-in creator text extraction.

The persistent **Filter by subject, sender or date** field filters the message list
using subject, sender, recipients and date across unopened
collection members. It caches bounded metadata, shows incomplete coverage, and
opens the exact matching email. No page rendering is needed while indexing.
Download requests are spaced to avoid Chromium blocking rapid download bursts.
**All files** text search also inspects unopened collection PDFs, with
cancellation and explicit coverage limits. **Reopen PDF** is a recovery action
shown after an operation error; it clears cached documents and reopens the root.
Passwords are not persisted. External PDF links and executable actions remain
inactive, and HTML/email previews are inert text.

A self-contained Firefox/Chromium extension for local PDFs. Open a file, select
messages and collection children, read document information and download embedded
files. The same TypeScript interface uses bundled PDF.js in both browsers.
No Java, server, native host or network connection is needed at runtime.

Folders now start expanded and can be collapsed with their chevrons. File groups
show real counts; email sources, attachments, other files, and technical/supporting files start expanded.
Email attachments show the owning subject and clickable source filenames. A filename opens the mapped message and clears a conflicting email filter. Source-omitted profiles show the containing PDF filename; links with no unambiguous page mapping are disabled. Empty files have no View or Download controls. Isolated profiles show one source-omission notice above the attachment groups. File actions have consistent labelled icons. Inspection problems use short warnings beside the affected content; detailed diagnostics are available in the browser debug console.

[Install the local beta and view attachments](docs/beta-release.md). Packages are
in `releases/`; the [current checkpoint](docs/beta-release.md#verified-scope) records tests and limits.

## Use

- Open or drop a PDF anywhere inside the viewer, including the left Messages/Portfolio pane, or use the Open PDF control. Drops on the browser tab strip or address bar are handled by the browser.
- Scroll continuously between pages; **Page x of x** follows the visible page.
  Page arrows and page-number entry remain available.
- In **c/ci collections**, scroll past the beginning or end of a message PDF to
  open the previous or next message PDF in the **same immediate folder**, following
  sidebar order. Forward starts on the first page; backward opens the last page.
  Wheel/trackpad, touch edge swipes, and page-pane arrow/Page Up/Page Down/Space
  keys are supported. Folder ends, nested collections, and ambiguous membership
  stop automatic navigation. PDFs with no message metadata are skipped; unreadable
  PDFs report an error. Other folders still require an explicit selection.
- The left panel lists documents, messages and collection folders. A PDF containing
  one email uses one subject row and shows all its embedded files. Folders start
  expanded; only folders with children have expand chevrons.
- Filtering retains the result list when you select an email. Scrolling past its
  end or beginning follows the filtered results and stops at the list boundaries.
  Clear the filter to restore normal archive scrolling.
- Select a message to see its uniquely owned body page and files. If no
  page mapping is known, the document remains visible with an explicit explanation.
- Select a document name to see all its files, including files with unknown owners.
- The file-row **View** icon opens an embedded PDF in the main viewer, with its
  own files and data. The **Return to parent PDF** icon returns to its containing
  PDF; the tree also provides document navigation.
- **View** opens image or text attachments. Office files use normal browser download
  handling. Downloads save the exact stored bytes; files never open automatically.
- Files appear in collapsible groups with real counts. Email sources, attachments, other files
  and technical/supporting files start expanded. View and Download use
  labelled icon buttons. Empty files have no View or Download controls; the source-omission notice appears only for isolated profiles.
- The always-visible message filter shows matching subjects with sender and date.
  It does not change the PDF text search. The Messages and Files panes have hide/show
  controls. Wide windows retain 27% / 46% / 27% proportions; medium windows show one
  270-pixel sidebar; compact windows use full-width panes over the reader.
- PDFs and Portfolio members initially open at **Fit page** and refit when the window or panes change. Manual zoom
  remains fixed when resizing. Rendered PDF content is unchanged.
- **Search** in the header, or Ctrl/Cmd+F, opens a search panel. Choose **This PDF**
  or **All files**. Enter moves to the next match; Shift+Enter moves to the previous
  match. Escape closes the panel without discarding results. Clearing the input
  clears results and highlights. Collection results show the matching filename
  once, with highlighted excerpts, inside the panel.
- **Export metadata CSV** downloads one row per message from the currently displayed
  PDF and recursively from its embedded collection PDFs. Columns: PDF, Folder paths, From, To, Cc, Bcc, Subject, Sent, Message-ID, Attachment count, Attachment names. Mail GUID is not exported. Values come from
  EA-PDF metadata; missing values stay blank, and dates retain their stored format.
  Raw address headers take precedence when available. CSV uses UTF-8 with a BOM;
  formula-like cells receive a leading apostrophe for spreadsheet safety.
  Export runs locally and can be cancelled with **Close / cancel**. Unreadable
  collection children or inspection limits stop the export without a partial file.
  Resource budgets apply; CSV export stops explicitly at its 64 MiB byte budget.
  PDF, record and row counts do not truncate the export.
  Arbitrary PDF attachments outside collections are not recursively exported.
- **Close / cancel** stops processing and releases the document workers and data.
- Right-click a web PDF link and choose **Open in EA-PDF viewer**, then explicitly
  open the linked file in the new tab. Site access may require browser approval.

## Portfolio viewing (local development)

On opening, document identification selects the interface. A recognized EA-PDF
version/profile keeps the existing email interface, including Messages, filtering,
indexed search and CSV export. Otherwise a valid catalog Collection dictionary
selects Portfolio view; ordinary PDFs retain document viewing. Identification is
not a conformance verdict. Unsupported/incomplete EA-PDF declarations are reported.

**Portfolio view** is also available explicitly for EA-PDF collections. Switching
back restores the selected document, email and page unless another member was explicitly
opened; then it keeps that newly selected member. A Portfolio lists only members
of its EmbeddedFiles name tree, including non-PDF files; other associated attachments
remain accessible through PDF view. It supports folders, Details/Tiles layouts,
custom schema columns and CI values, typed/multiple-field sorting, metadata filtering,
opening/previewing and downloading members. Collection-subitem prefixes display but
do not participate in sorting. Column visibility can be changed without modifying
the PDF. Initial file, hidden view and split direction are read from the collection;
vertical sidebars default to about 425 pixels, with user-resized width saved locally.
Changing the layout keeps navigation in the left sidebar, including Details and
Filmstrip, and retains the chosen sidebar width. Filmstrip shows compact thumbnail/name
rows. Preview and details (Linear) places one selected-file metadata panel and a compact
file list beside the large reader preview. Metadata follows file selection, keyboard
navigation and document links; showing the cover clears the member details. Freeform
places clickable thumbnails at randomized, non-overlapping positions. Positions remain
stable during selection and adapt to pane resizing; keyboard/source order is unchanged.
Details initially shows
only Name and Size, with optional columns under **View options → Details columns**.
Long filenames stay on one line, preserving the ending; hover or focus reveals the full
name. **Selected file details** below the list starts open and can be collapsed. It shows all available metadata.
Hidden fields remain searchable. Sizes use B/KiB/MiB/GiB; dates use YYYY-MM-DD, with
exact byte counts and full timestamps available in tooltips and selected-file details.
Sorting uses original values. Optional wide tables scroll horizontally. Drag the divider
between Portfolio and the document to resize the panes, or focus it and use arrow
keys (Shift for larger steps; Home/End for the limits). Horizontal initial splits
can also be resized. The divider is hidden in the single-pane narrow-window layout.
PDF 2.0 named navigator preferences select Details, Tiles, Hidden, Tree, Filmstrip,
Linear or Freeform presentations; unknown names fall back to a recognized preference
or Details. Layouts use viewer code, never embedded executable content. The Portfolio uses the
email reader's folder/document icons, eye/download actions and selected-row styling.
Folder breadcrumbs, Back/Forward/Up and Previous/Next file controls support navigation;
Up/Down opens the next row, Home/End goes to the first/last row, and Left/Right collapses
or expands a folder in Tree view. **View options** contains column controls.
PDF bookmarks and the document path remain available when browsing nested collections.
On narrow windows the Portfolio pane fills the workspace; opening a PDF switches to
the reader, and **Show portfolio** restores the chosen layout.

Thumbnails are lazy, with one rendering worker and at most 64 cached images. Automatic
thumbnails are limited to supported PDF/image members with a recorded size at most
4 MiB; other files retain type icons and can still be opened or downloaded under the
normal extraction limits. Stored folder thumbnails use PDF.js image decoding on demand,
with a total image/mask budget of 1,048,576 pixels, at most four image streams and a
4 MiB encoded-stream limit per image. Missing, unsupported or oversized previews retain
their type icon. The metadata pass does not decode embedded payloads.
Its bounds are 200,000 traversal/value operations, 8 Mi characters and 256 schema/CI
fields, with explicit partial-listing notices. Displayed metadata cells show at most
1,024 characters; filtering uses at most 65,536 characters per item. Lists mount 100
rows at a time. Files and source hashes are not changed.

Embedded Go-To links and bookmarks resolve named destinations and explicit page
numbers through parent/child chains, including nested Portfolios. Child targets use
exact name-tree byte keys or attachment annotations identified by page/index or name.
Target chains are capped at 32 steps; cycles, missing/ambiguous targets and non-PDF
content fail with a message. Encrypted members use the existing password dialog and
support retry/cancellation. Cross-file links open in the current reader; an external
root file specification requires opening that PDF separately. Destination page selection
is supported; target zoom/coordinate settings are not applied.

This is collection viewing, not a claim of complete ISO 32000 conformance. Editing
collection fields/folders and executing custom/legacy navigator programs are not implemented.
Named navigators use native viewer layouts, with responsive adaptations for narrow screens.
Unsupported native file formats remain downloadable. Existing PDF reading and resource
limits continue to apply.

`npm run test:portfolio` covers classification, all six EA-PDF profiles, membership,
sorting, initial targets, layouts, keyboard/folder navigation, encrypted members, inter-file
links, stored thumbnails, a 1,200-member listing and view switching. Private real samples under
`in/portfolio` are checked when available and explicitly skipped otherwise. Additional
samples are discovered automatically and compared with an independent read-only inventory,
including exact native downloads and mixed EA-PDF profile navigation. Firefox's
full UI acceptance also exercises Portfolio viewing.

## Build

Use Node **24.19.0**, npm **11.17.0**, TypeScript **5.9.3** and PDF.js **6.5.438**.
PDF.js upstream commit: `0a4153349dc473ba950fc17941c5ca7eda43c1de`.
`.nvmrc`, exact package versions, package-lock.json integrity fields and
`vendor/pdfjs-pin.json` record the upstream inputs. The narrow source patch,
build recipe and generated-bundle hashes are in `vendor/pdfjs-patches/`.
Normal builds use verified generated bundles; no global TypeScript or bundler
is needed. Source reproduction is a separate development command.

From this directory:

```sh
npm ci
npm run check
npm run build
npm run verify:licenses
```

The first install needs npm registry access. Build and use are local after
dependencies are installed. Compilation stages in a temporary directory and
cleans it afterward. Final unpacked builds are `dist/chromium/` and `dist/firefox/`.
No Java, Python, native helper, server, CDN or remote service is used by either
extension. Node and browser automation tools are development dependencies only.
Never open viewer.html directly using file://; load the extension as below.

## Chromium

Tested with locally installed Chromium **156.0.8078.47**. The manifest requires
Chromium 156 or newer; earlier versions have not been evaluated.

1. Open `chrome://extensions` and enable **Developer mode**.
2. Select **Load unpacked**, then choose `browser-viewer/dist/chromium/`.
3. Pin the extension if needed and click **EA-PDF viewer beta**. It opens a tab.
4. Use **Open PDF**. Try `tests/fixtures/ordinary.pdf`, then the repository's
   `../examples/eml/eml_Agenda_and_timeline_slides_1s.pdf`. Use Previous/Next, Page and Zoom.

This unpacked extension does not update automatically. After changing source,
run `npm run build`, click **Reload** in `chrome://extensions`, then close and
reopen the viewer tab. This also restarts its PDF.js worker. There is no watcher.

The extension requests contextMenus and activeTab permissions for explicit opening.
HTTP(S) site permissions are optional and requested only after a click to open a
linked PDF. Newly granted access is removed afterward. The local file picker
requires no site access. There is no automatic PDF interception or native helper.

## Firefox

Tested with locally installed Firefox **158.0.1**. The manifest requires 158
or newer. Use the separate Firefox build, not the Chromium manifest.

1. Open `about:debugging#/runtime/this-firefox`.
2. Select **Load Temporary Add-on**, then `dist/firefox/manifest.json`.
3. Click the extension action from the extensions menu to open the viewer tab.
4. Open the same local files and exercise page navigation and zoom.

Temporary add-ons disappear at browser restart. Permanent installation requires
signed Firefox packaging. Signing, AMO submission, browser UI/store review,
older versions and Android are not completed in M0. See
[compatibility.md](docs/compatibility.md).

## Tests

Install the browser used by the pinned Playwright dependency once:

```sh
npm exec -- playwright install chromium
npm test
```

Alternatively, use a locally installed Chromium executable. This was the exact
browser selection used in this workspace:

```sh
CHROMIUM_PATH=/snap/chromium/current/usr/lib/chromium-browser/chrome npm test
```

`npm test` builds both packages, runs twelve Node tests, then loads the actual
Chromium extension in a fresh temporary browser profile. It checks the ordinary
PDF's colored pixels, a real PDF.js worker, page navigation, zoom, invalid-file
recovery and offline behavior. It also opens the repository EA-PDF sample when
present. For another checkout, specify `EAPDF_SAMPLE=/absolute/path/sample.pdf`;
an explicitly requested missing sample fails. Without any EA-PDF sample, the
test report says NOT TESTED for that case and the M0 acceptance gate is unmet.
The M5 checks also exercise all six profiles, nested collections, unknown owners,
missing streams, exact saved downloads, page search and cancellation. Screenshots
and machine-readable results go to ignored `test-results/`.
No real email PDF is copied into the public fixture set or extension package.
The DPart worker test also compares six cases against frozen M1 expectations: real
1s/1m, invalid page reference, reversed range, DPart cycle and ordinary PDF.
It checks object/generation identities, parent/child order, raw scalar bytes and
zero-based page mappings through the public API. Run just this slice with
`CHROMIUM_PATH=/path/to/chromium npm run test:m2`.

Optional installed-Firefox check (geckodriver **0.37.1**, Firefox **158.0.1**):

```sh
M5_UI=1 GECKODRIVER_PATH=/path/to/geckodriver FIREFOX_PATH=/usr/lib/firefox/firefox npm run test:firefox
```

This development harness starts a temporary loopback-only WebDriver process and
fresh browser profile, installs the temporary add-on, switches Firefox offline,
and checks rendering, navigation and the same M5 interactions. Downloads go to a
temporary directory that is removed after the check. Its privileged driver flag is used only for setting
that test profile offline and locating the installed add-on; it is not an
extension permission or runtime requirement.

The ordinary two-page fixture is original Apache-2.0 content. Recreate it with
`node scripts/make-fixture.mjs`; its hash and license are in
`tests/fixtures/manifest.json`. Run `npm audit` to recheck advisories; audit results
are time-dependent. [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) covers separate
font/codec licenses, including the bundled Liberation font sources.

## M1 contract and reference checks

The [readonly TypeScript model](src/domain/inspection.d.ts),
[JSON schema](schema/inspection-v1.schema.json) and
[fixture manifest](tests/inspection/manifest.json) define inspection version 1.
The [schema guide](docs/inspection-schema.md) explains observed, derived, missing,
invalid, ambiguous and unavailable values. The [test matrix](docs/test-matrix.md)
documents all six profiles, negative cases and provenance.

The independent Python/qpdf extractor is development-only. Building and running
the extension still need no Python. For the additional reference checks, use
CPython 3.12.3 and the pinned development environment (first install needs package
registry access):

```sh
python3 -m venv /tmp/eapdf-m1-reference
/tmp/eapdf-m1-reference/bin/python -m pip install -r reference/requirements.txt
PATH=/tmp/eapdf-m1-reference/bin:$PATH npm run test:m1
PATH=/tmp/eapdf-m1-reference/bin:$PATH npm run fixtures:check
```

These commands check 27 frozen records, including the six existing EA-PDF
examples referenced in place. They fail on missing/changed evidence; they never
refresh expected results automatically. See the test matrix for the explicit
review-and-refresh procedure. Remove the temporary virtual environment after use.
The checked-in Python, schema and fixture data are excluded from both packages.

## Limits

The interactive viewer caps root PDFs at 4 GiB, individual extracted streams at
32 MiB and concurrent extraction reservations at 512 MiB. Child count and depth do not impose
content cutoffs. Successful extraction uses a 32 MiB cache. Each reading session retains
at most three active PDF workers; isolated CSV/search work can use three more.
These are processing ceilings, not guarantees that every file within them opens.

The eager InspectionSession remains for independent semantic comparisons, with
its separate 32/16/64 MiB limits. Reference expectations were updated explicitly
for document-scope and source ownership; fixture PDF bytes were preserved.
Byte integrity observations remain unavailable until the bytes are read.

The viewer scrolls continuously, retaining at most five rendered pages near the
current position, with selectable text and 50–200% zoom. At 100%, a Letter page
is 816 CSS pixels wide.
It has no printing or automatic URL interception. Password-protected files use
a local prompt. Internal PDF destinations work; external/executable actions remain
inactive. File names and metadata are text, never HTML. Office files are downloadable;
no Office rendering engine is included. Store signing/publication, other operating
systems, older browsers and full accessibility acceptance remain untested. See
[compatibility](docs/compatibility.md) and [security](docs/security.md).

Supplement ownership in collections can also be established by exact byte
identity: a single-email child must contain both the same source email and the
same supplement in its catalog associations, with consistent document-part
ownership. No filename, sequence-number or Message-ID guess is used. The scan
uses two workers, rotated after 16 children, and a 120-second processing budget checked
between children (individual PDF operations retain their 60-second timeout), within the session's 512 MiB concurrent extraction budget. Incomplete
scans and conflicting identities leave ownership unresolved. Verified links are reused for
the session and survive inspection refreshes. Closing the document cancels the
scan and disposes its workers.
