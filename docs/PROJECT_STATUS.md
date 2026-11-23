# Project status

**Release verification.** The [package acceptance check](../TECHNICAL_GUIDE.md#release-package-acceptance)
exercises the creator, standalone validator, and packaged readers on the same
six generated profiles. It checks explicit collection metadata against validated
children, runs the packaged Creator and Validator GUIs, and launches the Desktop
Viewer JAR. GitHub publication through the acceptance workflow uses the packages
and checksums from the successful job.

Main release **2.4.0-beta.6** includes the Full browser reader **1.11.0** and
Desktop Viewer **0.9.1**.
[Downloads](https://github.com/thinkbox-digital/eapdf/releases/tag/v2.4.0-beta.6).

C2PA/PAdES, WeasyPrint and other experimental changes remain separate in
`../eapdf-secure` on `experiment/c2pa-pades`. Normal development continues in this
checkout. `../eapdf-lite` is the first EML-only CLI edition, using the standalone
Java validator and the Even More Lite reader.

[Downloads](https://github.com/thinkbox-digital/eapdf/releases/tag/v2.4.0-beta.2) ·
[Release notes](releases/2.4.0-beta.2.md) ·
[Release checks](releases/2.4.0-beta.2-validation.md) · [Code layout](ARCHITECTURE.md)

## This release

- **New code.** Desktop Viewer 0.8.0 is available as a standalone Java JAR in Main and Secure.
- **Packaging.** Main ships the Full browser reader for Chromium and Firefox.

## Previous release, 21 November 2026

- **Bug fix.** Even More Lite shows the open filename or embedded PDF path once
  in its header, with styling aligned to Full.
- **Packaging.** The Full and Even More Lite browser packages use version 1.7.0.
- **Code improvement.** The Main creator uses bundled DejaVu Sans and sRGB.
  Local, Gmail and monthly conversion no longer accept font or ICC profile
  paths. Propagation to the Lite and Secure creator checkouts is pending.

## Previous release, 19 November 2026

- **Bug fixes.** The paired veraPDF parser 1.31.72 and validation-model
  1.31.225 update corrects false PDF/A-4f passes with trailing data. The validator
  also recognizes core-field text nested under verified spans and links. The
  desktop report shows child summaries and relevant failures or incomplete
  checks, and saves the complete JSON.
- **Bug fixes.** The creator recovers mislabeled legacy Windows-1252 text when
  all bytes decode, warns about the recovery and preserves the original source.
  It skips malformed inline images without discarding HTML formatting, uses
  Gregorian dates across locales, keeps derived filenames valid, omits local
  mailbox paths from provenance and preserves output permissions on replacement.
  The OpenHTMLtoPDF 1.1.114-eapdf.1 update fixes rowspans at page breaks. The
  creator and validator use the FontBox 3.0.8-eapdf.1 parsing backport.
- **Speed improvement.** Gmail downloads stay sequential while bounded workers
  convert individual PDFs. Archives and collections use source sizes to bound
  rendering. With four real EML inputs served by a mock Gmail API, the warmed
  individual-PDF run took 0.56 seconds with one worker and 0.25–0.26 seconds in
  the two four-worker runs. A collection took 0.48 seconds with one worker and
  0.34–0.39 seconds with four. These local OpenHTMLtoPDF timings cover a small
  sample with no Gmail network costs. The creator shows completed and total
  message counts during and after conversion.
- **Checks.** The Java creator and validator suites passed with optional checks
  skipped. The standalone validator passed all 27 Gmail PDFs across the six
  profiles. The retained source files match their original SHA-256 hashes.
  An OpenHTMLtoPDF linked-image regression preserves the link and Figure tags.
  Its generated PDF passed EA-PDF, PDF/A and applicable ISO 32005 checks.
- **Reader improvements.** Both readers backport Mozilla's embedded TrueType
  family-name identification fix. Full reserves Portfolio view for PDF Portfolios
  and preserves email navigation in EA-PDF collections. Even More Lite puts pane
  controls in the headers and page controls above the document.

## Unreleased

- **Speed improvement, 23 November 2026.** Local archive rendering uses the
  largest extracted message, including decoded bodies and staged attachments,
  to bound workers for EML, MSG, PST, MBOX, and mixed inputs. In paired CLI runs
  with four jobs requested and a 2 GiB heap, a whole 71-message PST took 30.55
  to 17.03 seconds for 1m and 17.53 to 14.54 seconds for 1c. The 24-message MBOX
  took 12.02 to 8.02 seconds for 1m and 7.76 to 5.26 seconds for 1c. The PST 1m
  peak process RSS increased from 472 to 1,083 MiB. Each timing is a single run.
  A scale run on 2,200 distinct real EMLs up to 64 KiB each used a 4 GiB heap.
  The merged archive took 212 seconds and the collection took 96 seconds.
  Both preserved all embedded originals byte-for-byte. The prior and rebuilt
  JARs had the same merged-archive validation failures and the same per-child
  validation outcomes for all 2,200 collection members. Both large PDFs are
  diagnostic artifacts.
- **Speed improvement, 23 November 2026.** Local EML archives and collections now use
  the largest message to size their worker pools. With 24 real EMLs, four requested
  jobs, a 2 GiB heap, and the default message limit, 1m took 6.28 seconds (previously
  9.49) and 1c took 4.42 seconds (previously 5.63). Peak process RSS for 1m rose from
  576 to 1,151 MiB. Each timing is one end-to-end CLI run.
  All four EML archive profiles passed independent EA-PDF and PDF/A-4f validation. All
  four profiles also passed for Outlook MSG, whole MBOX and PST files, and a mixed
  EML/MSG/MBOX batch. Additional whole-file checks preserved 71 messages in a PST
  merged archive and 24 in an MBOX collection.
- **Speed improvement, 23 November 2026.** Separate-PDF EML batches size their
  worker pools from the largest input, leaving half of the worker JVM heap for
  shared and transient work. On 480 local EMLs with all optional features off and
  a 2 GiB heap, the GUI took 37.9 seconds with four workers. The old GUI used one
  worker and took 107.4 seconds in the paired run and 78.4 seconds in an earlier
  run. Sampled peak worker RSS in the paired run rose from 624 to 1,249 MiB.
- **Bug fix.** Converter jobs have no default whole-job deadline. A positive
  configured timeout still limits the job. Verified PDFs appear as they finish
  and stay available if a later message fails or the user stops the batch.
- **Bug fix.** Malformed MIME transfer encoding no longer stops an entire
  folder conversion. The creator keeps the original message and available parts
  and reports the incomplete content. The creator uses plain text when an
  upstream HTML form-field error prevents rendering.
- **Code improvement.** Optional Creator features default to off. The status
  shows completed message counts without elapsed timers and skips transient
  phases. Per-PDF output logs are
  written when the conversion records a warning, error or blocked asset.
- **Checks and profile, 23 November 2026.** The Creator GUI produced 7,446
  local 1s PDFs with optional features off. Two GUI sessions took 493.5 seconds
  of combined runtime, with the first PDF visible after 6.9 seconds. The first
  session retained its PDFs when the HTML form-field error stopped it. The
  second session converted the remaining messages after the fix. HTML
  preparation and rendering accounted for 41.3% of 16,500 sampled Java
  execution stacks across the two workers. All embedded original EMLs matched
  their source SHA-256, and all PDF hashes matched their creator receipts.
  Standalone Java validation passed four of eight sampled PDFs and reported
  sent-date or source-metadata findings on the other four.
- **Bug fix.** Even More Lite shows the open filename or embedded PDF path once
  in its header, with styling aligned to Full.
- **Packaging.** The Full and Even More Lite local builds use version 1.7.0.
- **Code improvement.** The Main creator uses bundled DejaVu Sans and sRGB.
  Local, Gmail and monthly conversion no longer accept font or ICC profile
  paths. Propagation to the Lite and Secure creator checkouts is pending.

## What is supported

The creator accepts EML, MSG, PST, MBOX and Gmail. It produces the six EA-PDF
formats described in the [user guide](manual/user-manual.html#basics).
Use the standalone Java validator for normal conversion checks. Python CLI validation remains for regression tests and diagnostics.

Keep the original mailboxes. Isolated output omits sources; it does not remove
private information from the visible email. The creator no longer makes PREMIS
or finding-aid files. The reader's CSV export remains available.

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

Earlier counts remain dated evidence in the changelog and release records.

The desktop viewer ships as one versioned Java JAR per checkout. It runs with
Java 21 or later and bundles JavaFX for supported host platforms.

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
