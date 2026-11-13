# Code layout

The project has three parts: create PDFs, check PDFs, and read PDFs.
The browser reader runs without Java or a server.

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

## Maintained Lite viewer export

The full reader is the source for shared fixes. `npm run build:lite` selects its
shared modules and substitutes small adapters for features Lite omits. Do not edit
generated exports by hand.

Lite keeps the reading layout, message/folder navigation, attachments, CSV/XMP
export and normal PDF text search. It omits Conversations, Portfolio presentation,
embedded search indexes and large-archive optimizations. Its collection metadata
scan still supports filtering.

The generic PDF.js hooks expose document parts, metadata, associated files and
collection membership. They do not depend on the EA-PDF user interface. Lite keeps
upstream cross-reference storage and parsed-object caching. Its limits are in the
[reader guide](../browser-viewer/README.md).

Each export records source and file hashes. Changed hook inputs require reviewed
adaptations and a reproduced bundle. This is a local extension, not PDF.js approval.
The [archive comparison](PDFJS_LARGE_ARCHIVE_COMPARISON.md) records earlier tests;
it does not establish normal-viewer speed gains or upstream acceptance.

## Library patches

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
