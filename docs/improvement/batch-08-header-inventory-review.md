# Batch 08 observed header text and ownership review

Reviewed 2026-10-18 against local EA-PDF 1.0 sections 7.3 (printed pp.36–39), 9.6 Table 8 and 9.7 (printed p.76). Reference PDF hashes remain in requirements.json. This extends rule 107 evidence for observed custom fields; it does not complete the recommendation or invent a mandatory comparison rule.

## Interpretation

The header-tagging recommendation is conditional on custom EA-PDF tags. Rendering choices are flexible: the source header list does not establish which headers were actually rendered. Finding ten tagged fields cannot establish that no eleventh header was rendered without those tags. Standard-tagged, untagged and unrecognized rendered headers remain outside this inventory. Rule 107 therefore remains partial WARN even when all observed comparisons are equal.

Source field names and folding follow [RFC 5322 section 2.2](https://www.rfc-editor.org/rfc/rfc5322.html#section-2.2); encoded display words follow [RFC 2047 section 6.2](https://www.rfc-editor.org/rfc/rfc2047.html#section-6.2). These support bounded display comparisons, not arbitrary transformations or exact raw-header fidelity. B04's UserProperties/raw-value checks remain distinct.

## Scoped decoding

Each observed Mail_FieldName/Mail_FieldValue carries actual page/Form identity and MCID references. Native pypdf text decoding projects the referenced marked content through its original font resources. Unrelated text-show and Form invocation operators are excluded; text-state operators are retained. The reference's page must contain that scope/MCID, including an explicit or uniquely resolvable page for reused Forms. Page content-stream aliases are resolved to their owning page. Strict nonnegative integer MCIDs prevent Boolean aliasing.

Empty/replacement-character extraction, unsupported references and Forms without explicit resources remain unavailable. Unexpected extraction failures retain the existing INCOMPLETE operation guard. Whole-page extraction, mandatory Core Field text connections and existing correspondence checks retain their previous purpose. This evidence does not establish visual correctness, reading order, font-map accuracy, arbitrary nested Form/resource inheritance or complete accessibility.

## Owner and comparison

Actual structure ancestry selects the enclosing Mail_Message. Effective UserProperties use B05 ClassMap/direct resolution. A GUID selects exactly one XMP record; only absent GUIDs can fall back to a unique Message-ID. Conflicting attributes and duplicate XMP identity matches are unavailable. The existing source selector then uses that record and retained source metadata; the field value cannot select a better-matching source.

One recognized source header occurrence is required. Repeated/absent headers and arbitrary labels remain unavailable. Supported comparisons decode RFC 2047, fold layout whitespace, compare parseable dates by instant and compare supported mailbox lists while retaining display names/order. Sent can map to Date only without a source Sent header. These transformations describe display equivalence, not unchanged source bytes. Known field pages outside that message's GUID-matched DPart ranges are diagnostic differences, including when text happens to be shared. Missing DPart ownership remains explicitly unavailable. Existing XMP/source matching is reused; this is not independent proof of every source association.

## Independent fixtures and verification

Two small authored messages share Message-ID and have distinct subjects. The creator independently renders five known fields per message. Six generated PDFs exercise ten matching fields, swapped Subject content references (two differences), missing GUID with duplicate IDs (five unavailable), isolated/no source (ten unavailable), incorrect enclosing GUID (five differences) and ClassMap ownership (ten matches). Swapping content references repairs ParentTree ownership to keep the comparison diagnostic isolated from mandatory structure correspondence. All six must pass independent PDF/A. Only the missing-GUID case has a declared mandatory failure, exactly MAIL_MESSAGE_GUID_ATTRIBUTE; the other five pass mandatory EA-PDF.

The initial missing-feature regression log is preserved. Focused tests additionally cover page/Form scoping, unrelated body text, resource absence, empty text, reused Form ambiguity, conflicting identities, strict MCIDs, repeated headers, Unicode and formatting. The full packaged acceptance log and frozen input hashes provide final results. The old temporary build had expired, so B08 performs a fresh offline Maven package: 138 Java tests pass. No creator/CLI change or saved-sample regeneration occurs; saved examples and sidecars match B07 hashes.

Full rendered-header enumeration, source/asset inventories, arbitrary header occurrence mapping, wider association assurance, custom-set semantics, remaining N/A predicates and complete standards/ISO/TS 32005 inclusion-rule review remain open.
