# Navigation and larger files — beta 0.2.0-beta.1

Completed the user-requested viewer changes on 2026-10-27. The creator,
validators, original PDFs and frozen semantic expectations were not changed.
[Install/update instructions](beta-release.md) describe the current builds.

## Changes

- Replaced the crowded interface with a document/message list, page controls,
  breadcrumb path, message details and a clearly labelled Embedded files panel.
- Embedded PDFs open recursively in the main viewer, with their own embedded files
  and metadata. Parent returns to the containing PDF. Ordinary annotation-only PDF
  attachments work as well as collection members.
- Corrected the screenshot's collection navigation: its root metadata rows were
  not the embedded email documents. The viewer now opens the actual PDF member.
- Removed the interactive 32 MiB input cutoff, hashes/reads local Blobs in chunks,
  loads children only when selected, and retains at most three active PDF workers.
  Returning to an evicted document reopens it through its parent.
- Added selectable page text and search highlighting using the public PDF.js
  TextLayer. Correct scale and rotation align the selection with rendered text.
- Files show type, size and available actions. Empty files are identified;
  missing/broken files explain the problem. Office files use normal downloads and
  browser/installed-application handling, with no Office conversion or renderer.
- Added explicit toolbar/context-menu link opening. It never fetches on tab load;
  optional site access is requested on the opening action and newly granted access
  is removed afterward. Local file links still use the picker or drop.

The interactive BrowserSession is separate from the legacy eager semantic-test
session. Only requested payloads are extracted; byte-dependent observations remain
unavailable until read. Generic extraction's explicit request cap rose to 512 MiB;
its default inspection limits did not change. All discovery and ownership rules
remain shared with the independent semantic checks.

## Verification

| Check | Result |
| --- | --- |
| TypeScript and canonical contract | Passed |
| Node tests, including independent SHA-256/MD5 comparisons | 12 passed |
| Pinned PDF.js source tests | 14 focused tests and 73 selected upstream specs passed |
| Clean source reproduction | Second build byte-identical for both bundles |
| Chromium 156.0.8078.47 and Firefox 158.0.1 | Both installed and rendered using bundled resources |
| Shared UI checks | 13 cases and 17 message selections in each browser |
| Attachment checks | 15 cases per browser, original download bytes verified |
| Larger local PDF | 40 MiB opens, renders and matches independent document SHA-256 |
| Larger embedded PDF | 20 MiB opens in the main viewer and returns to parent |
| Recursive PDF navigation | Seven annotation-only levels and return through all parents |
| Worker retention | Chromium observed at most three during recursive navigation and reopening |
| Real PST collection | 71 unopened members initially; the screenshot's RR expansion message opens correctly |
| Text layer | Selection/search checks and 90/180/270-degree alignment passed in both browsers |
| Frozen semantic comparisons | All 27 unchanged records passed |
| BFO interoperability | Supplied three-page file and its email metadata remain available |
| Cancellation and limits | Worker cancellation, input-hash cancellation in both session paths, timeouts and legacy budgets passed |
| Link startup | Web/local banners work; no fetch without an explicit opening action |
| Offline local use | No HTTP(S) attempts or extension page errors |
| Licenses and inputs | 16 locked packages, 203 pinned PDF.js files and source-patch hashes verified |
| Final ZIP reproduction | Two complete builds produced identical ZIP hashes |

Firefox emitted a browser-internal shutdown warning after successful tests; it
was not counted as an extension failure. Native browser menu clicks and acceptance
of the host-permission prompt have not been automated or manually accepted in this
checkpoint. These remain explicit acceptance checks, not claimed successes.

Machine-readable results and package/source fingerprints are in
[navigation-verification.json](navigation-verification.json). Full local logs are
retained in ignored `test-results/navigation-logs/`. Real email originals were
read in place, not copied into shipped fixtures or packages. The PST source SHA-256
and third-party source SHA-256 are recorded in the verification receipt.

## Limits

The root-file cutoff is removed, but browser capacity is finite. Embedded extraction
allows 128–512 MiB per request based on approximate device memory, defaulting to
256 MiB when unavailable. Active-worker retention is not a process-wide memory
limit; inspected metadata is retained for navigation. Existing generic graph
bounds remain; nesting is limited to 64 levels. The release guide records all
limits. No Office renderer, conformance validator, automatic PDF interception,
store publication, printing or cross-document text index was added.
