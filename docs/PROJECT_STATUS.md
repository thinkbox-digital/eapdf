# Project status

Current release, 18 November 2026: creator/validator **2.3.0-beta.1** and
**Full/Even More Lite readers 1.3.0-beta.1**. Even More Lite replaces the discontinued
Lite packages. The creator's empty-metadata guard, worker supervision/report
synchronization fixes and dependency PRs #26–#33 are included. Full retains its
independent CSV/XMP export. Both PDF.js hook recipes include the equivalent
font-name quote character classes in patch 0012.

Even More Lite and its required reader helpers were explicitly selected for
transfer on 18 November. The C2PA/PAdES experiment, WeasyPrint integration and
other experimental changes remain separate in `../eapdf` on `experiment/c2pa-pades`.
Normal development continues in this checkout.

See [release checks](releases/2.3.0-beta.1-validation.md) for verification and limits.

## This release

- **New reader:** Even More Lite has message/folder navigation, PDF pages and embedded assets in three adjustable panes; it replaces Lite packages.
- **Bug fix:** reject empty email metadata input explicitly.
- **Code improvement:** process-exit notifications replace watchdog sleep polling; JobReport owns report-import synchronization. Intentional Gmail retry backoff remains interruptible.
- **Code improvement:** dependency PRs #26–#33, inspection provenance and PDF.js source patches are maintained in this checkout.

[Downloads](https://github.com/thinkbox-digital/eapdf/releases/tag/v2.3.0-beta.1) ·
[Release notes](releases/2.3.0-beta.1.md) · [Code layout](ARCHITECTURE.md)

## What is supported

The creator accepts EML, MSG, PST, MBOX and Gmail. It produces the six EA-PDF
formats described in the [user guide](manual/user-manual.html#basics).
Use the standalone Java validator for normal conversion checks. Python CLI validation remains for regression tests and diagnostics.

Keep the original mailboxes. Isolated output omits sources; it does not remove
private information from the visible email. The creator no longer makes PREMIS
or finding-aid files. The reader's CSV export remains available.

## Latest update

Historical checkpoint, 14 November: Full and the then-maintained Lite restricted message downloads to
proven body pages unless a complete inspection establishes standalone scope.
Message page exports omit inherited archive files, metadata, annotations and
document-part links. The explicit Portfolio whole-file download remains available. Both PDF.js workers
include Mozilla's RTL ligature correction. Chromium and Firefox checks cover
partial archives, original bytes, Arabic/Latin text, search, highlights and copying.

Both readers use the supplied PNG and SVG artwork. A short [privacy policy](site/privacy.md)
explains local files, optional connections and saved data. The release includes
1280×800 screenshots of the Creator, Validator and both readers with fictional data.

## Checks and limits

Historical dependency integration, 14 November: all 19 dependency PRs are
included together. TypeScript 7 builds both readers; the Lite exporter and type
checks use its command-line interface. Acorn selects the shared JavaScript tests.
PyMuPDF 2.1 matched every pixel on the same six baseline pages, so the reference
images and comparison threshold are unchanged. The PDF manual and website also build.

Browser checks now wait for completed indexing, current virtual rows and the
requested Portfolio view. The association check compares complete records by
SHA-256 inside the browser, avoiding a roughly 116 MB test-protocol response.
These changes preserve the existing checks and reader layout. Dependency notices,
runtime inventories and the architecture PDF are refreshed.

Historical code improvement, 14 November: the creator now uses Jakarta Mail for
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
[verification record](releases/2.3.0-beta.1-validation.md).
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
