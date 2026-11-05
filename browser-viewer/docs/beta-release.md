# Viewer 0.5.0-beta.8 — GitHub prerelease (2026-11-05)

Packages accompany EA-PDF 1.6.0-beta.9.

- Clearer file groups, equal-width sidebars, and grouped document controls.
- Faster collection search, navigation, and finding-aid exports.
- Search matches keep the document and file panes synchronized.
- Root PDF input ceiling increased to 4 GiB.

Unsigned browser packages; manual installation is required.

## Install or update

Download the packages from the
[GitHub beta release](https://github.com/thinkbox-digital/eapdf/releases/tag/v1.6.0-beta.9).
Local packages are also in `browser-viewer/releases/`.

- `eapdf-viewer-0.5.0-beta.8-chromium.zip`
- `eapdf-viewer-0.5.0-beta.8-firefox.zip`

Extract the appropriate ZIP. Chromium: open `chrome://extensions`, enable
Developer mode, choose **Load unpacked**, then select the extracted directory.
Firefox: open `about:debugging#/runtime/this-firefox`, choose **Load Temporary
Add-on**, then select the extracted `manifest.json`. The Firefox installation
ends when the browser closes. The browser packages remain unsigned; Firefox uses temporary installation.

For the workspace builds, reload `browser-viewer/dist/chromium/` or reinstall
`browser-viewer/dist/firefox/manifest.json`. Close the old viewer tab and open a
new one. No Java, Python, Node or server is needed to use either extension.

## Example emails

Download [the beta.9 example bundle](https://github.com/thinkbox-digital/eapdf/releases/download/v1.6.0-beta.9/ea-pdf-examples-1.6.0-beta.9.zip), or see the [example inventory](../../examples/README.md).

## Release checklist

Before publishing installation guidance, query the GitHub release API for each
explicit prerelease tag and verify that every documented download filename exists
in its assets. Check both viewer ZIPs and the examples ZIP; do not replace these
links with `/latest`, which excludes prereleases. Run `npm run verify:pdfjs` too.

## Historical release notes

The [current README](../README.md) describes viewing, file ownership, search,
finding-aid exports and limits. The material below is historical; where
it differs, the current README and security boundaries take precedence.

> Unreleased security checkpoint (2026-10-31): production sessions now use fixed
> ceilings: 512 MiB root input, 32 MiB embedded stream, 512 MiB concurrently extracted
> bytes. Child count and depth do not impose content cutoffs. Linked downloads stop at 64 MiB/30 s.
> PDF.js decoding is bounded in normal rendering as well as attachment extraction.
> Older resource figures below describe the earlier beta. See
> [current security boundaries](security.md) and the repository
> [hardening record](../../docs/security/HARDENING.md). Published packages have not
> been replaced by this local checkpoint.

### Viewer beta 0.4.0-beta.1

The beta displays EA-PDF pages, document information and embedded files. It does not
validate conformance. The local 0.3.1 update improves folder navigation and reduces
the height of the header and toolbar. The opened-PDF count and Document
Information conformance row have been removed. The duplicate Message section
has also been removed. Download-only files retain their Download button without
the extra explanatory line. Reading Notes was removed in the current local build; diagnostic details go to the browser debug console.

The earlier 0.3.2 update made zoom buttons move in 10% steps while preserving the horizontal viewing position. Scrolling an m/mi file now follows the current message in the left pane. Both browser interaction suites pass.

## Open files and attachments

Click the extension button, then **Open PDF**, or drop a local PDF into the tab.
**Ctrl+O** also opens the picker while the viewer tab has focus (verified in
Chromium; Firefox key routing also passes, with native dialog selection still
requiring a manual check).
The left panel lists documents and messages. Folders start collapsed. A small
chevron and folder icon indicate a folder with children; the chevron points down
when open. Empty folders and file/message rows have no expand marker. Opening
a file from the Embedded files panel reveals its folder in the tree. The right panel shows **Embedded files**, with file types, sizes and available
actions, followed by Document information. Short warnings appear beside content that could not be fully read.

**Open PDF** beside an embedded file opens it in the main viewer. Its own
embedded files are available in the same right panel. Repeat to open deeper
PDFs. **Parent PDF** returns to the containing PDF; the breadcrumb path can jump to
an earlier document. Collection children load when selected. Selecting a document
name shows all its files, including files whose email owner cannot be determined.
A PDF with just one email uses one subject row, showing all embedded files. The PDF filename remains in the breadcrumb, document
information and row tooltip. PDFs with several emails retain separate message
rows; those rows show only files explicitly associated with the selected message.

The **Page** field accepts typed page numbers. Press **Enter** (or leave the
field) to jump. Invalid or out-of-range values show a message without navigating.
**Esc** restores the current page. Scrolling preserves a number while you are
still typing it.

Scroll through the PDF continuously. **Page x of x** follows the page with the
largest visible area. The arrows and page-number entry still jump to a page;
message selection, search and Parent navigation also move the scroll position.
The current page appears first; nearby pages render in the background. Up to two
neighbors on each side retain rendered pixels and text, within a combined
32-million-pixel budget. Other pages keep lightweight placeholders and render when needed.

**100%** now uses the normal 96-screen-pixels-per-inch conversion: a Letter page
is 816 CSS pixels wide, instead of the previous 612. Browser/desktop scaling can
still affect its physical size on your screen.

Text on PDF pages can be selected and copied. **Find next** finds a matching page
and highlights matches. Esc clears the search field. Search is limited to the
current PDF.

| File | Available action |
| --- | --- |
| PDF | Open in the main viewer, including its own embedded files. |
| PNG, JPEG, GIF | View and zoom; first frame only. |
| Plain text, CSV, JSON, XML, HTML, SVG, EML | View as text; markup is not executed and EML is shown as source. |
| Word, Excel, PowerPoint and other binary files | Download; the browser or installed application handles opening. No Office renderer is included. |
| Empty embedded file | Download the original empty bytes; an explanation replaces View. |
| Missing or broken content | An explicit message; other files remain usable. |

Image/text views use a separate dialog; **Back to email** or Esc closes it.
Downloads preserve original bytes. Attachments never open automatically.

Right-click a PDF link and choose **Open in EA-PDF viewer**, or use the extension
button on a web PDF. The new tab asks you to click **Open linked PDF**, and the
browser may ask for access to that site. Merely opening the tab does not fetch
the link. Newly granted site access is removed after the operation. Requests omit
cookies and reject redirects; authenticated or redirected URLs may require a
normal browser download followed by **Open PDF**. Local file links also use the
picker or drag and drop. No automatic PDF interception is installed.

## Large files and limits

The old **32 MiB input limit is removed**. Local files are hashed and read in
chunks. Only three PDF workers remain active; older documents reopen from their
parent when needed. There is no 32-child browsing limit.

Browsers do not report reliable free memory. Embedded-file extraction therefore
uses a per-request allowance of **128–512 MiB**, based on the browser's approximate
device-memory class; it defaults to **256 MiB** when that value is unavailable.
This is a practical safeguard, not a guarantee that every file below it will fit
in memory. Large compressed streams still need decoded memory. Resource failures
are reported; there is no claim of unlimited file size.

File, object, edge and nesting counts do not cut off graph inspection.
Loading/inspection/extraction operations time
out after 60 seconds; page operations after 30 seconds. Page and image rendering
use a 16-million-pixel limit. Text attachment display is capped at 2 MiB. Search
checks all pages within a 120-second deadline; collection search also bounds retained results to ten million characters. Exhaustion is reported as incomplete.

## Verified scope

For 0.4.0-beta.1, TypeScript checks and all 20 unit tests pass. Chromium HTTP
smoke tests cover metadata CSV downloads, unreadable-child handling, and
forward/backward scrolling with folder boundaries. Extension installation and
Firefox interaction were not rerun for this version; the older browser results
below are historical evidence, not a fresh cross-browser acceptance claim.

The 0.3.1 interface update passes the shared Chromium and Firefox checks: 13
interface cases, 17 message selections, attachment downloads, nested PDFs and
scrolling. TypeScript checks and all eight beta hardening checks pass. Visual
inspection confirms closed initial folders, visible selection when opening from
Embedded files, removed duplicate panels and two 41-pixel top rows at 1600 × 1000.
One Chromium run alongside Firefox missed a zero-byte download event; the
isolated full rerun passed. This intermittent harness result is not claimed fixed.


Chromium 156.0.8078.47 and Firefox 158.0.1 on Linux passed the same 13 interface
cases, 17 message selections, 15 attachment cases, and new large-file/nested-PDF
checks. These include a 40 MiB local PDF, a 20 MiB embedded PDF, seven nested
levels with return navigation, and the real 71-message PST collection. Text
selection, search highlighting and rotated text alignment are covered. The
27 independent semantic expectations remain unchanged and pass.

See [the current release evidence](release-0.3-results.md) and
[the earlier navigation evidence](navigation-results.md). The supplied third-party BFO
PDF also remains readable. Native browser menu clicks and the site-permission
prompt require a manual acceptance check; automated tests verify the link banner
and that it does not fetch automatically. Store signing, other operating systems,
older browsers, accessibility acceptance and printing remain outside this
beta checkpoint. This viewer does not certify EA-PDF or PDF/A compliance.

## Rebuild

Use `npm ci`, `npm run check`, `npm test`, and `npm run package:beta` from
`browser-viewer/`. Python 3 is a development-only packaging dependency. Keep the
notices, licenses, `license-sources/`, `SBOM.cdx.json` and `build-info.json` in
redistributed packages. See [patch maintenance](pdfjs-patch-maintenance.md) and
[security boundaries](security.md).
