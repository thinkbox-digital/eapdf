# Project status

Updated 13 November 2026 for **creator/validator 2.2.0-beta.1** and
**full/Lite readers 1.2.0-beta.1**.

## This release

- **New reader:** Lite keeps the familiar layout, folders, attachments and CSV/XMP
  export. It leaves out Conversations, Portfolio view, embedded search indexes
  and large-archive support.
- **Bug fix:** Lite can keep scanning collection messages while the user opens one.
  Released documents no longer use up its allowance for open documents.
- **Code improvement:** updated rendering, validation, email, signing and web libraries.
  Our required PDF/A, PDF/UA and validator patches remain.
- **Documentation:** shorter starting pages and simpler instructions. Regeneration
  instructions are near the end of the README.

[Downloads](https://github.com/thinkbox-digital/eapdf/releases/tag/v2.2.0-beta.1) ·
[Release notes](releases/2.2.0-beta.1.md) · [Code layout](ARCHITECTURE.md)

## What is supported

The creator accepts EML, MSG, PST, MBOX and Gmail. It produces the six EA-PDF
formats described in the [user guide](manual/user-manual.html#basics).
Use the standalone Java validator for normal conversion checks. The Python/web
validator remains available.

Keep the original mailboxes. Isolated output omits sources; it does not remove
private information from the visible email. The creator no longer makes PREMIS
or finding-aid files. The reader's CSV export remains available.

## Checks and limits

Release checks and library versions are recorded in the
[verification record](releases/2.2.0-beta.1-validation.md).
Earlier counts remain dated evidence in the changelog and release records.

The tested desktop environment is mainly Linux with Java 21. Reader extensions
are unsigned; Firefox installation is temporary. Browser-store distribution,
other desktop platforms, live Gmail onboarding and human accessibility review
still need separate checks. PDF/UA machine checks are not a human review.

A file opening successfully does not prove preservation or conformance.
Large-file limits are not a promise that every allowed file will fit in memory.
The hosted workbench is for a trusted team; it has no separate file ownership
for individual customers. No speed or memory improvement is claimed for the
library update.

See [validation limits](VALIDATOR_COVERAGE.md), [security](security/HARDENING.md)
and [web setup](DEPLOY_WEB.md). Private development logs and email samples stay
outside GitHub and the website.
