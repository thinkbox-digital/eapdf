# Concrete finding messages and 16-point text

The GUI now uses 16-point text. Starting size stays 2,000 × 1,100 pixels,
Esc clears the filter, and the Count column remains removed.

Findings state the required value or type directly. Examples:

- `pdfmailid:version must be the XMP integer 1.`
- `pdfmailid:rev must be the XMP integer 2024.`
- `pdf:Producer must be a non-empty XMP text string.`
- CreateDate identifies the XMP date format, with an explicitly labeled example
  and the permitted reduced precision.
- Namespace rows name the exact prefix and URI.
- Navigation rows name the required `/UseOutlines` or `/UseAttachments` value.
- Collection rows name the field and its required `/Subtype` value.
- Role mappings, DPart Type names and user-property types/tags name their
  expected values instead of saying only “required” or “prescribed”.

The existing predicates still decide PASS/FAIL. Scalar details retain the
observed `value` and now also include the expected requirement. More specific
messages form separate groups where the underlying requirements differ.

All 80 Java checks pass, with no failures/errors/skips. This includes the actual
desktop, exact 16-point fonts, filter clearing, grouped details, and explicit
version/revision messages for invalid metadata controls. The rulebook remains
unchanged. Full Java/web parity remains unfinished.

![Updated findings](window.png)

[Build evidence](evidence.json)
