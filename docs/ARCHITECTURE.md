# Code layout

The project has three parts: create PDFs, check PDFs, and read PDFs.
The browser reader runs without Java or a server.

Normal development continues in this GitHub-main checkout. The retained
`experiment/c2pa-pades` checkout is in `../eapdf-secure`; its C2PA/PAdES coordinator, export
verification, experimental viewer identity and other local additions are not
automatically merged here. Even More Lite and its required reader helpers were
explicitly selected on 18 November 2026; C2PA/PAdES remains separate. Selected maintenance fixes are transferred independently.

The `../eapdf-lite` checkout starts from this code with an EML-only CLI, the
same standalone Java validator, and the Even More Lite source export. Main
continues to own the shared implementation and produces the local Even More
Lite extension installation used by both checkouts.

The Python inspection oracle remains a development tool. Its frozen manifest
records exact package versions and source hashes. Dependency updates require an
explicit provenance refresh and review; tests do not rewrite expected records.

[Architecture diagram (PDF)](EA-PDF_Software_Architecture.pdf)

## Cross-edition improvements

Main owns shared dependency pins, EA-PDF rules and validator code, and creator
code used across editions. A shared improvement can start in any checkout. Land
it as a focused Main change and carry it to each affected edition. Keep Secure's
WeasyPrint and C2PA/PAdES changes in Secure when their impact is confined there.

While the editions share source snapshots, cherry-pick the focused Main commit
into affected checkouts and resolve differences with their edition behavior in
mind. Once the shared Maven modules and BOM are available, propagate changes by
updating each affected edition's pinned shared release through a dependency
update PR. Check all six profiles for shared creator and validator changes and
include signed-output checks when the change affects Secure's signing workflow.

For each improvement you work on or review, tell the user which editions it
affects, which updates were applied and verified, and which are still pending.

## Where to work

| Part | Main files |
| --- | --- |
| Local email readers | `src/main/java/eapdf/`: `EmlReader`, `MsgReader`, `PstReader`, `MboxReader` |
| Gmail reader | `GmailDownloadToPdf` |
| Conversion | `EmailToPDFA`, `PdfMailWriter` |
| HTML rendering | `MailHtmlRenderer`, `MailRenderPolicy` |
| Metadata, attachments, collections | `PdfMetadataWriter`, `PdfAttachmentSupport`, `PdfCollectionSupport` |
| Conversion workers and saved output | `ConversionSupervisor`, `OutputTransaction`, `PreservationInventory` |
| Creator window | `EapdfGui`, `GuiConversionProcess`, `GuiRunConfiguration` |
| Java validation | `validator-desktop/src/main/java/eapdf/`: `JavaValidation`, `ValidationSession`, check classes |
| Validator window and reports | `ValidatorGui`, `ValidationReportReader`, `ReportFindings` |
| Python validation | `validate_eapdf.py`, `eapdf_validation/` |
| Validation workers | `validation_worker.py`, `pdf_stream_limits.py` |
| Browser reader | `browser-viewer/src/`: `viewer.ts`, `browser-session.ts`, `domain/` |
| Reader panels | `message-tree.ts`, `files-panel.ts`, `search-controller.ts` |
| Full-reader extras | `collection-view.ts`, `portfolio.ts`, `conversations.ts`, `thread-preview.ts` |

## Ownership rules

- `PdfMailWriter` keeps control of the final PDF, document parts and logical structure.
  Rendering and metadata helpers do not publish files or own the final document.
- Collection helpers call the writer to create child PDFs. They share its rules
  for attachments and source preservation.
- `viewer.ts` owns the active document, selection, navigation and cancellation.
  Panels use that shared state; they do not create another active selection.
- `browser-session.ts` owns PDF workers, caches and extracted-file lifetimes.
  Keep parsing behind the documented PDF.js APIs.
- Python's root modules keep the command-line and public import interfaces.
  Separate modules hold report formatting, engine adapters and rule families.

## Maintained reader exports

Full retains the message-download scope decision. Original-byte message
downloads require a declared standalone profile, complete relevant inspection,
one recognized message and ownership of every page. Otherwise only proven body
pages can be exported. An opt-in page-only PDF.js extraction mode drops inherited
files, metadata, annotations, structure and DPart back-references. Its distinct
worker action fails closed against older workers. Default upstream extraction and
the explicit Portfolio whole-file action retain their behavior.
Both patch recipes include upstream PDF.js PR #22521 as patch 0010 for RTL glyph
ordering; this correction does not introduce the Full reader's archive optimizations.
Shared patch 0012 uses equivalent quote character classes in font-name validation.

Full and Even More Lite are the published editions. Viewer Lite is discontinued;
its exporter and generic hooks remain shared build infrastructure.
The full reader is the source for shared fixes. `npm run build:even-more-lite` selects its
shared modules and adds the overrides in `even-more-lite/overrides`. Do not edit
generated exports by hand.

The exporter asks the TypeScript compiler's command-line interface for the module
graph after applying Lite overrides, including type-only imports. Acorn selects
the shared JavaScript tests. Type-contract tests also use the compiler interface.
These tools are used during development and are not bundled into either reader.

Even More Lite keeps message/folder navigation, PDF pages and embedded asset
access in three adjustable panes. It omits search, filtering, CSV/XMP export,
Conversations, Portfolio presentation, embedded indexes and large-archive
optimizations. Full retains those features, including independent CSV export.
Full exposes Portfolio view only for documents classified as PDF Portfolios;
EA-PDF collections retain email navigation even though they have a Collection dictionary.
Five reader-specific helper overrides supply navigation, page scrolling, file
categories, message labels and pane resizing without changing Full's shared code.

The generic PDF.js hooks expose document parts, metadata, associated files and
collection membership. They do not depend on the EA-PDF user interface. Even More Lite keeps
upstream cross-reference storage and parsed-object caching. Its limits are in the
[reader guide](../browser-viewer/README.md).

Both worker recipes include Mozilla's tricky TrueType font-identification and
checksum fix in patch 0013. Identification uses original hint tables before
sanitization and restores a recognized family name without changing text mapping.

Each export records source and file hashes. Changed hook inputs require reviewed
adaptations and a reproduced bundle. This is a local extension, not PDF.js approval.
The [archive comparison](PDFJS_LARGE_ARCHIVE_COMPARISON.md) records earlier tests;
it does not establish normal-viewer speed gains or upstream acceptance.

## Library patches

The creator delegates MIME assembly to Jakarta Mail / Angus Mail and XMP
serialization to XMPBox. Our code still maps email fields, EA-PDF profiles,
source files and document parts. Reconstructed messages keep stable boundaries,
original attachment bytes and supplied identifiers; missing dates and identifiers
are not invented. Opaque message and multipart attachments bypass automatic
parsing. XMP serialization retains the required namespaces and FOAF layout.

Outlook Message Parser 1.16.3 supplies the missing-date fix. Native timestamp
precision and other preservation repairs remain local until equivalent upstream
behavior is verified. Keep the custom archive storage: stock PDFBox was more
memory-intensive in the measured stream-cache and large-archive loading probes.

The renderer keeps four patches, recorded in `third_party/openhtmltopdf/lock.json`.
A clean rebuild must reproduce their commit and artifact hashes.

The Java validator keeps local storage, decoding, cross-reference and deferred
resource changes. New upstream interruption, progress and dictionary fixes are
included. Recheck each override whenever veraPDF changes.

Python accepts only the existing allowed PDF/A profiles, allowing the new
capitalization of their built-in names. Custom profiles still require an exact
name, hash, rule count and matching engine versions.

## Keep changes focused

Move code when it gives a responsibility a clear owner. A shorter central file
does not necessarily mean less code, faster execution or lower memory use.
Keep public interfaces, original inputs and useful debug output intact.

See [project status](PROJECT_STATUS.md), [contribution rules](../CONTRIBUTING.md)
and the [documentation map](README.md).

The former web workbench and its deployment files are removed. The Java validator
is the normal checking tool; Python CLI code remains for regressions and diagnostics.

Rebuild the diagram with `scripts/build_architecture_diagram.py`, passing the
current creator and validator JARs. It reads versions and rule counts from source
and draws arrows downward from each tool to its libraries. Regenerate it before
every push.
