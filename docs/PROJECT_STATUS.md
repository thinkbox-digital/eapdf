# Project status

Updated 14 November 2026 for **creator/validator 2.2.0-beta.3** and
**full/Lite readers 1.2.0-beta.3**.

## This release

- **New reader:** Lite keeps the familiar layout, folders, attachments and CSV/XMP
  export. It leaves out Conversations, Portfolio view, embedded search indexes
  and large-archive support.
- **Bug fix:** Lite can keep scanning collection messages while the user opens one.
  Released documents no longer use up its allowance for open documents.
- **Code improvement:** updated rendering, validation, email and signing libraries.
  Our required PDF/A, PDF/UA and validator patches remain.
- **Code removal:** removed the web workbench and its server dependencies.
- **Documentation:** shorter starting pages and simpler instructions. Regeneration
  instructions are near the end of the README.

[Downloads](https://github.com/thinkbox-digital/eapdf/releases/tag/v2.2.0-beta.3) ·
[Release notes](releases/2.2.0-beta.3.md) · [Code layout](ARCHITECTURE.md)

## What is supported

The creator accepts EML, MSG, PST, MBOX and Gmail. It produces the six EA-PDF
formats described in the [user guide](manual/user-manual.html#basics).
Use the standalone Java validator for normal conversion checks. Python CLI validation remains for regression tests and diagnostics.

Keep the original mailboxes. Isolated output omits sources; it does not remove
private information from the visible email. The creator no longer makes PREMIS
or finding-aid files. The reader's CSV export remains available.

## Latest update

Bug fixes, 14 November: both readers restrict message downloads to
proven body pages unless a complete inspection establishes standalone scope.
Message page exports omit inherited archive files, metadata, annotations and
document-part links. The explicit Portfolio whole-file download remains available. Both PDF.js workers
include Mozilla's RTL ligature correction. Chromium and Firefox checks cover
partial archives, original bytes, Arabic/Latin text, search, highlights and copying.

Both readers use the supplied PNG and SVG artwork. A short [privacy policy](site/privacy.md)
explains local files, optional connections and saved data. The release includes
1280×800 screenshots of the Creator, Validator and both readers with fictional data.

## Checks and limits

Unreleased code improvement, 14 November: the creator now uses Jakarta Mail for
MIME assembly and XMPBox for metadata serialization. Outlook Message Parser is
1.16.3; its missing-date fix replaces our workaround. No dependency was added.
Production Java code fell from 15,720 to 15,539 lines, excluding comments and blank
lines. All input formats and six output profiles remain supported.

Creator checks: 434 passed and 23 optional checks were skipped. A real-input
comparison produced 48 baseline/candidate PDFs across EML, MSG, PST and MBOX.
All passed standalone Java validation; page images and preserved content matched.
The comparison allows fresh message identifiers and changed MIME framing;
the validator checks the resulting sizes and checksums. Original inputs were unchanged.
The custom archive storage remains: replacing it with stock PDFBox increased
retained heap in the storage probes. This is not a general speed or memory claim.

Release checks and library versions are recorded in the
[verification record](releases/2.2.0-beta.3-validation.md).
Earlier counts remain dated evidence in the changelog and release records.

The tested desktop environment is mainly Linux with Java 21. Reader extensions
are unsigned; Firefox installation is temporary. Browser-store distribution,
other desktop platforms, live Gmail onboarding and human accessibility review
still need separate checks. PDF/UA machine checks are not a human review.

A file opening successfully does not prove preservation or conformance.
Large-file limits are not a promise that every allowed file will fit in memory.
No speed or memory improvement is claimed for the
library update.

See [validation limits](VALIDATOR_COVERAGE.md), [security](security/HARDENING.md)
and [configuration](../config/README.md). Private development logs and email samples stay
outside GitHub and the website.
