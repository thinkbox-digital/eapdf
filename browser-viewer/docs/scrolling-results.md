# Continuous scrolling — beta 0.2.1-beta.1

The viewer now scrolls between pages while keeping Page x of x synchronized.
The page with the largest visible area is current. Page arrows, direct entry,
message selection, search and Parent navigation remain available. Only five nearby
pages retain rendered pixels/text, so a long archive does not allocate canvases
for every page. Distant pages render again when approached.

Corrected 100% sizing: PDF points are converted to CSS pixels using 96/72. A Letter
page is now 816 pixels wide (previously 612). Ctrl+O opens the extension's file
picker when the viewer has focus; this was exercised with the actual Chromium
picker. Firefox also routes actual Ctrl+O key events to the extension picker;
the test cancels the native dialog, so OS dialog selection remains a manual check.

Single-email PDFs now use one subject row, showing email metadata and all files
from that PDF. The filename remains available in breadcrumbs, document information
and the row tooltip. Multiple-email PDFs keep their individual message rows.

## Verification

Both Chromium 156.0.8078.47 and Firefox 158.0.1 pass:

- Scrolling forward/back through all five pages of `examples/pst/pst_sample_1s.pdf`,
  with the page counter following, plus arrows, page entry and message selection.
- Correct Letter-page width at 100% (816 CSS pixels) and 150% (1,224 CSS pixels).
- Jumping to page 100 and back in the real 151-page PST archive, with at most five
  rendered canvases and distant canvas pixels released.
- Existing 13 interface cases, 17 message selections, 15 attachment checks and
  the large-file, rotated text, nested-PDF and 71-member collection cases.
- Original downloaded bytes, offline local use and unchanged source PDFs.

Chromium also passes native mouse-wheel navigation and Ctrl+O picker selection.
A parallel browser run timed out waiting for one Chromium download; the timeout
did not reproduce in subsequent complete Chromium runs. Its cause is not established
and remains a reliability follow-up. No warning or failed assertion was suppressed.

The TypeScript check, canonical inspection contract and original pixel/navigation
smoke test pass. The generic PDF.js patches and frozen semantic expectations were
not changed in this update. Firefox emitted the same browser-internal shutdown
warning as earlier runs; test assertions passed before shutdown.

## Reported TRIP INFO attachments

Independent inspection of `examples/pst/pst_sample_1c.pdf` confirms that its child
`TRIP INFO_000046_1s.pdf` contains both files as page-2 FileAttachment annotations:

| File | Stored bytes | SHA-256 |
| --- | ---: | --- |
| TAHOE INFORMATOIN.doc | 21,504 | `09e08361abaf87d368f7d72038ffa73b2ccc688d9a538ef1b411ff60426e4e14` |
| tahoe.xls | 19,968 | `46a552eeac3def863cc23dce0d3bf133e4fd6123a40aa4c2016d3945b1578c19` |

The named-attachment dictionary contains the email source and transport headers;
these two Office files are discoverable through the page annotations instead.
The current viewer lists both Office files immediately after opening that child.
Their absence could not be reproduced. Added a regression that opens the actual
collection child and downloads both files in each browser, checking exact lengths
and hashes against the independent reference. No source PDF or creator code was
changed. Office files remain downloads handled by the browser/installed software.

## Install and remaining work

See [the installation guide](beta-release.md). Reload the unpacked Chromium
extension and reopen the tab; for Firefox reload the temporary add-on from
`dist/firefox/manifest.json`. The packages are unsigned and unpublished.

Before a broader beta, verify Firefox's native file-dialog selection, native browser
context-menu invocation/site-access prompts, keyboard/accessibility acceptance,
and more real collections with attention to file visibility and loading failures.
Other operating systems and older browser versions remain untested. Automatic
PDF interception, printing and an Office renderer are not part of this update.

[Verification receipt](scrolling-verification.json) records reports, source hashes
and package fingerprints. Logs and screenshots remain under ignored `test-results/`.
