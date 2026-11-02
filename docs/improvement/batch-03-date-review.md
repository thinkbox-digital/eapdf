# Batch 03 date review

Reviewed EA-PDF 1.0 section 9.7, Table 12 and ISO 32000-1:2008 / ISO 32000-2:2020 section 7.9.4 (printed pages 87–88 / 118–119). Mail_Sent is a PDF date string. Mail_Raw_Sent remains ordinary text; XMP Sent has its own xsd:dateTime checks.

The mandatory prefix is D: followed by a four-digit year. Remaining local date/time fields occur in order, in pairs; omitted month/day default to one and omitted time fields default to zero. Calendar dates must exist; hours are 00–23 and minutes/seconds 00–59. Preserve reduced precision, including the standard's minute-precision example with an offset. Optional timezone information uses Z or a signed offset. Offset hours are 00–23; offset minutes 00–59, introduced by an ASCII apostrophe. Omitted numeric offset fields default to zero. Z offsets must be zero. PDF 2.0 note 2 recommends accepting the legacy final apostrophe; note 3 permits zero offsets after Z. All four year digits are accepted, including 0000; the PDF clause gives no smaller year range. Calendar checking uses the proleptic Gregorian calendar without conversion to a Python datetime.

PDF 2.0 prohibits whitespace in the decoded date. Reject missing prefixes, non-ASCII digits, fractional seconds, colon offsets, lowercase z, extra characters and impossible calendar/time values. Decoded UTF-16 text strings are allowed by PDF 2.0 note 1. Do not use the tolerant parse_pdf_datetime comparison helper as a conformance check; it currently accepts syntax that should fail this check. This batch does not change its comparison behavior or add source-value equivalence.

Sources reviewed:

- Local `docs/reference/EA-PDF-v1.pdf` and `docs/reference/ISO_32000-2_sponsored-ec2.pdf` (hashes recorded in evidence).
- [Adobe's official PDF 1.7 archive](https://raw.githubusercontent.com/adobe/dc-acrobat-sdk-docs/master/docs/standards/pdfstandards/pdf/PDF32000_2008.pdf); downloaded to `/tmp/eapdf-b03-iso32000-1.pdf`. The earlier developer.adobe.com URL returned 404.
- [PDF Association date errata](https://pdf-issues.pdfa.org/32000-2-2020/clause07.html#7.9.4) checked 2026-10-18. Issue 251 clarifies the missing timezone defaults to UTC offset zero. This batch checks representation and ranges without timezone conversion.

Full standards/errata and attribute-resolution review remain open. Fixtures establish this bounded check; they do not certify all dates throughout a PDF or equality with the original email date.
