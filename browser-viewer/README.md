# EA-PDF browser viewer — beta 0.5.0-beta.6

Current release: `0.5.0-beta.6` (EA-PDF `1.6.0-beta.8`).

This demonstration focuses on viewing emails and their files. It uses PDF.js
6.5.438 and retains the existing Chromium/Firefox architecture.

Read emails, browse collection folders, search text, zoom or fit pages, and
view/download embedded files. The file panel identifies the containing PDF and
links owning emails automatically. New creator output records explicit file
ownership; older files can use bounded header matching. Insufficient evidence
is a document error, not a guessed association.

**Export finding aid (CSV)** includes PDF paths, collection folder paths, sender,
recipients, subject, date, Message-ID, attachment counts and attachment names.
Multiple folder memberships and nested collections are preserved. Mail GUID is
not exported. Missing counts are blank; recorded zero counts remain zero. Names
come from email metadata, so a `ci` finding aid does not claim that original
attachment bytes are embedded. Unreadable or conflicting information prevents a
misleading complete export. Export covers the selected PDF and its children.

The redundant Email Details pane, printing, user-controlled rotation and custom
keyboard navigation/shortcuts have been removed. Standard browser controls and
native Tab/Enter behavior remain. Intrinsic PDF page orientation is respected.

**Find emails** searches subject, sender, recipients and date across unopened
collection members. It caches bounded metadata, shows incomplete coverage, and
opens the exact matching email. No page rendering is needed while indexing.
Download requests are spaced to avoid Chromium blocking rapid download bursts.
**Whole collection** text search also inspects unopened collection PDFs, with
cancellation and explicit coverage limits. **Reopen PDF** is a recovery action
shown after an operation error; it clears cached documents and reopens the root.
Passwords are not persisted. External PDF links and executable actions remain
inactive, and HTML/email previews are inert text.

Packages are **0.5.0-beta.6**, distributed with EA-PDF 1.6.0-beta.8 on GitHub.
They remain unsigned and are not extension-store releases. See
[implementation and verification](docs/reading-improvements.txt).

A self-contained Firefox/Chromium extension for local PDFs. Open a file, select
messages and collection children, read document information and download embedded
files. The same TypeScript interface uses bundled PDF.js in both browsers.
No Java, server, native host or network connection is needed at runtime.

The earlier 0.3.1 update started folders collapsed, uses folder chevrons, removes
the opened-PDF count and duplicate Message panel, and reduces header/toolbar
spacing. Inspection problems use short warnings beside the affected content; detailed diagnostics are available in the browser debug console.

[Install the local beta and view attachments](docs/beta-release.md). Packages are
in `releases/`; the [current checkpoint](docs/beta-release.md#verified-scope) records tests and limits.

**Conformance is not evaluated.** A declared profile is metadata, not a validation
result. Missing or ambiguous page/file associations are shown without guessing.
The [M5 checkpoint](docs/m5-results.md) is retained as earlier evidence.

The earlier 0.3.2 update made zoom buttons move in 10% steps while preserving the horizontal viewing position. Scrolling an m/mi file now follows the current message in the left pane. Both browser interaction suites pass.

The 0.4.0 beta adds metadata CSV export, folder-contained file scrolling, and
expanded Document Information by default. See the beta guide for current checks.

## Use

- Open or drop a PDF, or use the Open PDF control while the viewer has focus.
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
  collapsed; only folders with children have expand chevrons.
- Select a message to see its uniquely owned body page and files. If no
  page mapping is known, the document remains visible with an explicit explanation.
- Select a document name to see all its files, including files with unknown owners.
- **Open PDF** opens an embedded PDF in the main viewer, with its own files and data.
  **Parent PDF** returns to its containing PDF; breadcrumbs allow earlier destinations.
- **View** opens image or text attachments. Office files use normal browser download
  handling. Downloads save the exact stored bytes; files never open automatically.
- Select and copy page text. **Find next** searches the current PDF and highlights
  matches. Esc clears the search. It does not search all embedded children.
- **Export metadata CSV** downloads one row per message from the currently displayed
  PDF and recursively from its embedded collection PDFs. Columns: containing PDF
  path, From, To, Cc, Bcc, Subject, Sent, Message-ID and Mail GUID. Values come from
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

The interactive viewer caps root PDFs at 512 MiB, individual extracted streams at
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

See [current results](docs/reading-improvements.txt). Earlier milestones below are
historical evidence and describe the limits at those checkpoints.

See [beta results](docs/m6-results.md), [M5 results](docs/m5-results.md), [M4 results](docs/m4-results.md),
[M3 results](docs/m3-results.md), [M2 results](docs/m2-results.md),
[M1 results](docs/m1-results.md) and the historical [M0 results](docs/m0-results.md).
This directory contains the browser viewer; creator and validator modules live elsewhere in the repository.


Supplement ownership in collections can also be established by exact byte
identity: a single-email child must contain both the same source email and the
same supplement in its catalog associations, with consistent document-part
ownership. No filename, sequence-number or Message-ID guess is used. The scan
uses two workers, rotated after 16 children, and a 120-second processing budget checked
between children (individual PDF operations retain their 60-second timeout), within the session's 512 MiB concurrent extraction budget. Incomplete
scans and conflicting identities leave ownership unresolved. Verified links are reused for
the session and survive inspection refreshes. Closing the document cancels the
scan and disposes its workers.
