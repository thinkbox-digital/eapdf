# EA-PDF review questions

11 questions have user-authorized project interpretations; 0 await a project decision. These are local working decisions, not official errata or external approval. Implementation evidence is recorded separately.

Open the [two-pane rule page](rule-browser.html) to compare rules with the original specification.

Profiles: s = one email; m = multiple emails; c = collection. Add i when the original source link is missing or unreliable.

XMP is the PDF's metadata. FOAF describes people and their email addresses. Bag means an unordered list; Seq means an ordered list.

The [2026-10-27 reexamination](specification-reexamination-2026-10-27.txt) records 52 findings and working assumptions. Its implementation section and the current resolutions below supersede conflicting older answers; historical resolutions remain in the review JSON.

## 1. Parents of document-wide content sets

Can document-wide front matter, conversion reports and policy pages sit outside individual emails in every profile?

**Why:** These two instructions conflict for c/ci collection PDFs.

**Project decision:** Email-owned Mail_ContentSet elements must descend from Mail_Message. Local interpretation: document-wide FrontMatter, ConversionReport and PolicySettings may descend from the Document root in any profile when actual content and DPart ancestry establish document-wide ownership. A type label alone does not establish the exception. Collections retain their document-level exception and prohibition on Mail_Message.

**Basis:** Document-wide material belongs to the document in both Table 3 and DPart ownership; assigning it to the first email invents ownership.

**Confidence:** medium. **Verification:** Reexamination implementation; see new regression evidence in the report.

**Sources:**

- [EA-PDF §9.7, Table 13, printed p.79](../../reference/EA-PDF-v1.pdf#page=86)

**Related rules:** [T13-SET-PARENT](rule-browser.html#T13-SET-PARENT), [T13-MESSAGE-C](rule-browser.html#T13-MESSAGE-C), [T13-SET-COUNT](rule-browser.html#T13-SET-COUNT)

Review ID: `SPEC-T13-CONTAINER-PARENT`

## 2. Two spellings of Message-ID

Do messageid in Table 7 and Message-ID in Table 18 mean the same XMP field?

**Why:** XMP field names are case-sensitive. Treating the names as separate fields could wrongly require both.

**Project decision:** Message-ID is the canonical Table 18 field; the exact legacy spelling messageid is an alias for the same identifier. Neither spelling is independently required; supplied values keep their text type and conflicting aliases must not silently choose an identity. Other names remain case-sensitive. Mail_GUID retains identification precedence.

**Basis:** Table 7 and Table 18 describe the same optional source header, with an editorial name discrepancy.

**Confidence:** high. **Verification:** Bounded implementation and regression review in V61; not complete standard/representation coverage.

**Sources:**

- [EA-PDF §9.6, Table 7, printed p.70](../../reference/EA-PDF-v1.pdf#page=77)
- [EA-PDF §9.8.3, Table 18, printed p.87](../../reference/EA-PDF-v1.pdf#page=94)

**Related rules:** [T7-Mail_MessageID](rule-browser.html#T7-Mail_MessageID), [T18-Message-ID](rule-browser.html#T18-Message-ID)

Review ID: `SPEC-MESSAGEID-CASE`

## 3. Namespaces with standard tags

Do standard-only tags need the EA-PDF namespace and role mappings, or are these needed only for custom EA-PDF tags?

**Why:** The paragraph refers to all PDF/A-4 files with structure, but describes custom semantics. Table 9 also gives compatibility mappings.

**Project decision:** EA-PDF namespace bindings and RoleMapNS are required only when EA-PDF custom semantics are used in PDF 2.0. Standard-only structure needs no unused EA-PDF namespace. Table 9 compatibility mappings apply to the PDF 2.0 tags actually used.

**Basis:** Section 9.7 explicitly makes the custom vocabulary optional; an unused namespace cannot carry used custom semantics.

**Confidence:** medium. **Verification:** Bounded implementation and regression review in V61; not complete standard/representation coverage.

**Sources:**

- [EA-PDF §9.7 and Table 9, printed pp.74–75](../../reference/EA-PDF-v1.pdf#page=81)
- [EA-PDF Table 9 continued, printed p.75](../../reference/EA-PDF-v1.pdf#page=82)
- [Reported ambiguity, issue #21](https://github.com/pdf-association/report-an-issue/issues/21)

**Related rules:** [TAGGING-NAMESPACE](rule-browser.html#TAGGING-NAMESPACE), [T9-Artifact](rule-browser.html#T9-Artifact)

Review ID: `SPEC-ROLEMAPNS-SCOPE`

## 4. When the attachment list is required

Must every PDF include an attachments set, even if empty, or only PDFs with attachments or attachment metadata?

**Why:** Section 9.8.4 recommends a complete attachment inventory, then requires an unordered set with zero or more records.

**Project decision:** Every EA-PDF file must include pdfmailmeta:attachments as an unordered XMP set, even when it has no attachment descriptions. Recording information for all original email attachments, including those removed for policy reasons, is SHOULD rather than SHALL; full source completeness is not a mandatory PDF-only test.

**Basis:** The required property permits zero records. Recommended inventory completeness is separate from mandatory property presence.

**Confidence:** high. **Verification:** Reexamination implementation; see new regression evidence in the report.

**Sources:**

- [EA-PDF §9.8.4 and Table 19, printed p.89](../../reference/EA-PDF-v1.pdf#page=96)

**Related rules:** [XMP-ATTACHMENT-INVENTORY](rule-browser.html#XMP-ATTACHMENT-INVENTORY), [XMP-ATTACHMENT-RECORDS](rule-browser.html#XMP-ATTACHMENT-RECORDS)

Review ID: `SPEC-ATTACHMENT-SET-PRESENCE`

## 5. Attachment description text

Is Contents text mandatory on every attachment annotation, even though this rule appears in explanatory section 7.5 rather than section 9?

**Why:** Section 7.5 uses mandatory wording for Contents and weaker wording for the file description Desc.

**Project decision:** Every FileAttachment annotation must contain a PDF text-string Contents entry. Empty text is allowed because no nonempty requirement is stated. Filespec Desc remains a separate recommendation. Missing or wrong-class Contents is a failure; undecodable string text is unavailable.

**Basis:** Section 7.5 explicitly says must; section 7.1 limits its own overview only and does not demote section 7.5 obligations.

**Confidence:** high. **Verification:** Bounded implementation and regression review in V61; not complete standard/representation coverage.

**Sources:**

- [EA-PDF §7.5, printed p.44](../../reference/EA-PDF-v1.pdf#page=51)
- [EA-PDF §7.1 introduction, printed p.23](../../reference/EA-PDF-v1.pdf#page=30)

**Related rules:** [ANNOTATION-CONTENTS](rule-browser.html#ANNOTATION-CONTENTS), [FILESPEC-DESCRIPTION](rule-browser.html#FILESPEC-DESCRIPTION)

Review ID: `REQ-ANNOTATION-CONTENTS`

## 6. Order of reply identifiers

Should In-Reply-To use an unordered XMP Bag, or may it use an ordered Seq?

**Why:** Table 18 says “unordered sequence.” XMP uses different containers for unordered and ordered lists.

**Project decision:** If supplied, In-Reply-To is rdf:Bag of normal free-text strings. Ordered rdf:Seq and rdf:Alt are not the prescribed unordered form. Member order is ignored in source comparison.

**Basis:** The explicit word unordered selects XMP Bag; sequence is descriptive rather than the XMP ordered-array type.

**Confidence:** high. **Verification:** Bounded implementation and regression review in V61; not complete standard/representation coverage.

**Sources:**

- [EA-PDF §9.8.3, Table 18, printed p.88](../../reference/EA-PDF-v1.pdf#page=95)
- [Adobe XMP Part 1, §7.7 array values](https://raw.githubusercontent.com/adobe/XMP-Toolkit-SDK/main/docs/XMPSpecificationPart1.pdf)

**Related rules:** [T18-In-Reply-To](rule-browser.html#T18-In-Reply-To)

Review ID: `SPEC-INREPLYTO-ARRAY`

## 7. How to store email addresses

Which XMP forms are allowed for FOAF address records, and which mailbox formats are valid?

**Why:** EA-PDF requires FOAF Agent records. It does not fully explain when to use typed structures, URI links or text, or which address syntax to reject.

**Project decision:** Address fields use ordered arrays of structured FOAF Agents with required mbox and optional name/other FOAF fields. Preserve supported equivalent XMP struct forms and both XMP URI text and rdf:resource mailto encodings. Validate mailbox syntax against RFC addr-spec/mailto rules without fabricating addresses; non-SMTP tokens use exact raw fields. Unhandled legal encodings remain unavailable rather than proved mismatches.

**Basis:** EA-PDF fixes Agent fields and array order; FOAF identifies mailbox resources, while XMP permits URI values serialized as text. Parser convenience is not normative authority.

**Confidence:** medium. **Verification:** Bounded implementation and regression review in V61; not complete standard/representation coverage.

**Sources:**

- [EA-PDF §9.8.3, Table 18, printed p.86](../../reference/EA-PDF-v1.pdf#page=93)
- [EA-PDF Table 18 continued, printed p.88](../../reference/EA-PDF-v1.pdf#page=95)
- [FOAF Agent definition](https://xmlns.com/foaf/spec/#term_Agent)
- [FOAF mbox definition](https://xmlns.com/foaf/spec/#term_mbox)
- [Adobe XMP Part 1, §§7.4–7.9](https://raw.githubusercontent.com/adobe/XMP-Toolkit-SDK/main/docs/XMPSpecificationPart1.pdf)

**Related rules:** [T18-To](rule-browser.html#T18-To), [T18-From](rule-browser.html#T18-From), [T18-Cc](rule-browser.html#T18-Cc), [T18-Bcc](rule-browser.html#T18-Bcc)

Review ID: `REQ-FOAF-REPRESENTATION`

## 8. Which PDF/A-4 edition applies

Which PDF/A-4 edition should we validate against: EA-PDF’s cited 2024 draft or the 2020 edition used by our profiles?

**Why:** The cited edition and installed profile edition differ. Passing those profiles does not prove they cover every draft requirement.

**Project decision:** Use the pinned veraPDF 1.31.18 PDF/A-3 and PDF/A-4 profiles for operational baseline checks and report their actual edition/hash. Do not require a duplicate custom PDF/A implementation. Equivalence to the cited ISO/DIS 19005-4:2024 remains an external verification limit, not a claimed fact.

**Basis:** An unavailable draft comparison cannot be guessed into evidence. The usable installed profile is an explicit project baseline.

**Confidence:** high. **Verification:** Bounded implementation and regression review in V61; not complete standard/representation coverage.

**Sources:**

- [EA-PDF §4 references, printed p.15](../../reference/EA-PDF-v1.pdf#page=22)
- [EA-PDF §9.1, printed p.61](../../reference/EA-PDF-v1.pdf#page=68)
- [Pinned veraPDF profiles and editions](../verapdf-coverage/bundled-profiles.json)

**Related rules:** [PDFA-BASELINE](rule-browser.html#PDFA-BASELINE)

Review ID: `DEP-PDFA-EDITION`

## 9. Number of original source files

May the catalog contain more than one original Source file, or must there be exactly one?

**Why:** Section 9.4 combines “only” with “at least one.” Issue #14 asks which limit was intended.

**Project decision:** Nonisolated s/m require at least one catalog Source file, with no universal exactly-one restriction. Independent source assets may each be Source. Companions in a declared file set use Supplement or RF as prescribed.

**Basis:** The explicit at least one lower bound and support for multi-message sources outweigh the stray only; file-set companions remain separately constrained.

**Confidence:** medium. **Verification:** Bounded implementation and regression review in V61; not complete standard/representation coverage.

**Sources:**

- [EA-PDF §9.4, printed p.67](../../reference/EA-PDF-v1.pdf#page=74)
- [Reported ambiguity, issue #14](https://github.com/pdf-association/report-an-issue/issues/14)

**Related rules:** [SOURCE-SM](rule-browser.html#SOURCE-SM), [CATALOG-SOURCE](rule-browser.html#CATALOG-SOURCE), [SOURCE-FILESET](rule-browser.html#SOURCE-FILESET)

Review ID: `SPEC-AF-SOURCE-CARDINALITY`

## 10. What collection Size measures

Should collection Size show the original email’s bytes or the embedded PDF’s bytes?

**Why:** EA-PDF describes original message size, but its prescribed PDF Size subtype measures the embedded file.

**Project decision:** Require the Table 6 Size subtype and interpret its displayed value as embedded-file size under ISO 32000. Original message size stays in email XMP SizeInBytes or a separately named additional numeric collection field. Do not replace required Size with N or claim it proves original bytes.

**Basis:** The explicit prescribed PDF subtype determines reader behavior; preserving original-size semantics separately avoids contradicting that object definition.

**Confidence:** high. **Verification:** Bounded implementation and regression review in V61; not complete standard/representation coverage.

**Sources:**

- [EA-PDF §7.6, Table 5, printed p.53](../../reference/EA-PDF-v1.pdf#page=60)
- [EA-PDF §9.5, Table 6, printed p.68](../../reference/EA-PDF-v1.pdf#page=75)
- [Reported conflict, issue #17](https://github.com/pdf-association/report-an-issue/issues/17)

**Related rules:** [T5-Size](rule-browser.html#T5-Size), [T6-Size](rule-browser.html#T6-Size)

Review ID: `SPEC-COLLECTION-SIZE-SEMANTICS`

## 11. Message-ID missing from Table 5

Is Message-ID missing from Table 5 by mistake, since Table 6 requires it?

**Why:** The two minimum-field lists differ. Both can be satisfied, but their intended relationship needs confirmation.

**Project decision:** Require Message-ID in the collection schema with subtype S as explicitly stated by Table 6. Table 5 is a minimum subset, not a prohibition on Table 6 additions. A source email with no Message-ID need not acquire a fabricated value.

**Basis:** Both minimum lists can be satisfied together without changing the published schema.

**Confidence:** high. **Verification:** Bounded implementation and regression review in V61; not complete standard/representation coverage.

**Sources:**

- [EA-PDF §7.6, Table 5, printed p.53](../../reference/EA-PDF-v1.pdf#page=60)
- [EA-PDF §9.5, Table 6, printed p.68](../../reference/EA-PDF-v1.pdf#page=75)
- [Related report, issue #17](https://github.com/pdf-association/report-an-issue/issues/17)

**Related rules:** [T5-To](rule-browser.html#T5-To), [T6-Message-ID](rule-browser.html#T6-Message-ID)

Review ID: `SPEC-COLLECTION-MESSAGEID-LISTS`

## Source limits

The pinned EA-PDF 1.0 specification is the source for these questions. Issue reports explain concerns; they are not approved changes.

The public Adobe XMP text is from April 2012. It helps explain XMP but does not prove equivalence to the cited 2019 ISO edition.

[Prior source review](external-sources-reviewed.json) and [pinned veraPDF coverage](../verapdf-coverage/README.md) retain the supporting evidence.
