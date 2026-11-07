# Public conversion acceptance corpus

These cases use already tracked real inputs under `in/`. Source expectations were retained from the independently reviewed November 2,
2026 corpus. The gate never updates source expectations or visual baselines.

On November 7, all seven raster pages were compared against the prior baselines.
Two attachment pages were byte-identical. Five pages changed with accessibility
enabled (including bold Folder labels and small header-layout changes) and the
beta.17 version banner. Source headers, complete MBOX bodies, attachment bytes,
page counts and Java validation passed unchanged. The reviewed current images
are the public baseline; the prior suite used accessibility disabled. This is
visual regression evidence, not an accessibility certification.

For beta.18, the two attachment-page baselines were reviewed again: the
user-requested removal of the "Original email attachments are available from"
sentence moves the retained source notice and attachment links upward by one
line. Attachment names/bytes and source expectations are unchanged. This gate
explicitly enables finding-aid pages to retain that existing regression coverage;
normal conversion defaults remain disabled. No visual threshold was relaxed.

- EML: Python email parsing checks headers, original bytes, attachment hashes and body text.
- MSG: independent olefile parsing checks transport/native fields and attachment bytes.
- MBOX: Python mailbox/email parsing checks the two Enron messages and their derivatives.
- Java validation must establish both EA-PDF and PDF/A PASS; source hashes and publication receipts must agree.
- Existing raster baselines use the pinned PyMuPDF version. A difference requires review.

This subset does not restore private corpus or PST visual acceptance. Synthetic
security regressions live separately in `regression/`. Conversion uses accessibility
features, disables optional PREMIS, and bounds processing to two jobs.
