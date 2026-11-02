# Validation requirements.docx compared with current JSON rules

Reviewed: 2026-10-26.

The Word document is a useful partial summary, but it is not equivalent to the current rulebook. Its most consequential differences are attachment-set presence, PDF/A profile interpretation, missing DPM requirements, and the required Mail_GUID attribute on Mail_Message.

## Inputs and scope

- Document: [Validation requirements.docx](../../reference/Validation%20requirements.docx), 235 paragraphs in 11 numbered sections. Paragraph references below count every body paragraph, including headings and blank paragraphs, from 1.
- The retained [reference copy](../../reference/Validation%20requirements.docx) has identical paragraph text to the reviewed root copy, although their package hashes differ. The root input was subsequently removed; the retained copy is linked here. Neither file contains tables, tracked changes, drawings, text boxes or comment markers in its main document XML; neither package contains a comments part.
- Current rules: [validator_rule_text.json](../../../validator_rule_text.json), **213 active entries**, numbered 1–213. These include recommendations and operational diagnostics, so 213 is not a count of independent mandatory requirements.
- Interpretation evidence: [validator-rule-review.json](validator-rule-review.json), [review questions](review-questions.md), and the pinned [EA-PDF 1.0 specification](../../reference/EA-PDF-v1.pdf).
- This compares authored requirements and applicability. It does not establish complete implementation or validator conformance. No rule, document or implementation changes were made; only this report was added.

## Findings

### 1. Attachment-set presence conflicts with the current rule

**Document:** §10, paragraphs 199–201, and §11, paragraph 226, treat attachment-XMP presence as recommendation-level and validate records only if the property exists.

**Current JSON:** [ATTACHMENT_XMP](../../../validator_rule_text.json#L2702) requires document-level `pdfmailmeta:attachments` as an unordered set for every EA-PDF, including an empty set. Complete metadata for all original attachments remains SHOULD rather than SHALL.

**Effect:** Following the document could incorrectly accept a missing attachments property. An empty `rdf:Bag` satisfies set presence; omitting the property does not satisfy the current rule.

**Correction:** State mandatory set presence separately from recommended original-attachment inventory completeness. This is the explicit current project decision in [review question 4](review-questions.md#4-when-the-attachment-list-is-required), supported by [§9.8.4, PDF page 96](../../reference/EA-PDF-v1.pdf#page=96).

### 2. The profile-specific PDF/A matrix is not the current validation policy

**Document:** §2, paragraphs 27–36, gives a restricted matrix for each EA-PDF profile. It interprets the printed `PDF/A-4fe` wording as `4f / 4e`. Section 11, paragraph 216, makes profile-to-PDF/A pairing a hard failure.

**Current JSON:** [PDFA_PART](../../../validator_rule_text.json#L33), [PDFA_CONFORMANCE](../../../validator_rule_text.json#L52) applies the normative §9.1 list to all profiles: PDF/A-3a, PDF/A-3u, PDF/A-4, PDF/A-4f or PDF/A-4e. Actual embedded files and other features must still meet the selected PDF/A profile's requirements.

**Effect:** The document adds blanket restrictions that are not expressed by the current JSON. For example, it excludes PDF/A-4e from c/ci by matrix alone. That exclusion must not be inferred to be a current custom validation rule; actual file-feature compatibility remains a separate upstream check.

**Correction:** Explain that Table 2 is descriptive, retain the printed `4fe` discrepancy as a source note, and use §9.1 plus actual feature constraints. The review record `SPEC-PROFILE-MATRIX` already makes this distinction; this is a deliberate interpretation difference, not evidence that JSON rules are missing. See [§9.1, PDF page 68](../../reference/EA-PDF-v1.pdf#page=68).

### 3. The leaf DPM requirements are incomplete

**Document:** §5, paragraphs 88–97, lists required `Mail_ContentSetType`, gives only AttachmentList and AttachmentRendering, and acknowledges a truncated source extraction. It explicitly prohibits only AttachmentList in c/ci.

**Current JSON also requires:**

- `Mail_Desc` as a human-readable PDF text string on every leaf: [DPM_DESC_REQUIRED](../../../validator_rule_text.json#L1415).
- `Mail_Subtype` as a PDF name for BodyRendering and AttachmentRendering, with separate media-type syntax and matching checks: [DPM_SUBTYPE_REQUIRED](../../../validator_rule_text.json#L1376), [DPM_NAME_TYPES](../../../validator_rule_text.json#L1210), [DPM_SUBTYPE_MEDIA_TYPE_NAME](../../../validator_rule_text.json#L1389), [DPM_BODY_MEDIA_TYPE_MATCH_SOURCE](../../../validator_rule_text.json#L1402).
- FrontMatter in every c/ci: [DPM_FRONTMATTER_REQUIRED_CONTAINER](../../../validator_rule_text.json#L1363).
- No AttachmentRendering, BodyRendering, EmailHeaderRendering or RawEmailRendering in c/ci, in addition to AttachmentList: [DPM_CONTAINER_CONTENTSET_TYPES](../../../validator_rule_text.json#L1334), [DPM_ATTACHMENTLIST_FORBIDDEN](../../../validator_rule_text.json#L1321).
- An actual DPM dictionary on every leaf: [DPM_LEAF_REQUIRED](../../../validator_rule_text.json#L1308).

**Correction:** Replace the truncated summary with the complete Table 8 requirements. Defined Content Set values are AttachmentList, AttachmentRendering, BodyRendering, ConversionReport, EmailHeaderRendering, FrontMatter, PolicySettings, Provenance, RawEmailRendering and Other. The set remains open to extension names; do not make this list exhaustive. See [Table 8, PDF pages 77–78](../../reference/EA-PDF-v1.pdf#page=77).

### 4. UserProperties are not all optional in every context

**Document:** §7, paragraphs 131–152, presents the listed attributes, including Mail_GUID, as optional. It lists their value types but omits the allowed owning tags. Section 6 also omits the mandatory Mail_Message GUID attribute.

**Current JSON:** [MAIL_MESSAGE_GUID_ATTRIBUTE](../../../validator_rule_text.json#L1892), [MAIL_MESSAGE_GUID_MATCH_DPM_XMP](../../../validator_rule_text.json#L1905) requires a Mail_GUID UserProperty on each Mail_Message and checks its identity against connected DPM/XMP. [USERPROPS_TAG_SCOPE](../../../validator_rule_text.json#L1782) preserves Table 12's restrictions on which custom tags may carry each attribute.

**Effect:** The document could permit a Mail_Message without its required GUID, or allow an otherwise correctly typed attribute on the wrong custom tag.

**Correction:** Keep optionality conditional on context and add Table 12 tag scopes. In particular, Mail_GUID and Mail_MessageID belong to Mail_Message; Mail_ContentSetType belongs to Mail_ContentGroup or Mail_ContentSet; Mail_Subtype belongs to Mail_ContentSet. Generic additional UserProperties remain distinct from EA-specific fields. See [Table 12](../../reference/EA-PDF-v1.pdf#page=84) and [Table 13](../../reference/EA-PDF-v1.pdf#page=86).

### 5. The DPM prefix statement is broader than the current rule

**Document:** §5, paragraph 74, says all custom DPM keys must use `Mail_`.

**Current JSON:** [DPM_KEY_PREFIX](../../../validator_rule_text.json#L271) applies the prefix to custom **EA-PDF** DPM keys and explicitly permits unrelated valid extension keys.

**Correction:** Add “EA-PDF” to the scope. A valid unrelated extension key must not fail merely because it does not start with `Mail_`.

### 6. Nonblank Content Set wording needs a scope clarification

**Document:** §3, paragraphs 38–41, combines “at least one Content Set,” “each Content Set,” and “the Content Set must not be blank.” It can be read as requiring every Content Set to be nonblank.

**Current JSON:** [CONTENT_SET_COUNT](../../../validator_rule_text.json#L71), [CONTENT_SET_NONBLANK](../../../validator_rule_text.json#L710) states a minimum of one nonblank Content Set. [PROFILE_EMAIL_COUNT_MULTI](../../../validator_rule_text.json#L775) additionally requires a nonblank Content Set for each email in m/mi, producing at least two nonblank pages.

**Correction:** State these two quantifiers explicitly. The document's ambiguous wording should not create a new blanket failure for every additional blank Content Set. See [§9.2, PDF page 70](../../reference/EA-PDF-v1.pdf#page=70).

### 7. Required logical-structure conditions are missing from the summary

**Document:** §6 covers custom role maps, children and cardinalities, but does not include several current conditional obligations.

**Current JSON also includes:**

- One Document root element when logical structure exists: [STRUCTTREE_ROOT_DOCUMENT](../../../validator_rule_text.json#L1441).
- ISO/TS 32005 conformance and its document-level declaration for PDF 2.0/PDF/A-4 with logical structure: [LOGICAL_TS32005_DECLARATION](../../../validator_rule_text.json#L1476). The declaration does not itself prove conformance; the external structure checks remain separate.
- Table 9 PDF 2.0-to-1.7 backward role maps, distinct from the custom Mail_* mappings: [ROLEMAP_MAIL_TAGS](../../../validator_rule_text.json#L1494).
- Namespace/RoleMapNS rules for used custom semantics under the recorded project interpretation: [ROLEMAPNS_PRESENT](../../../validator_rule_text.json#L1454). The standard-only namespace question remains a specification ambiguity.
- Custom marked-content sequence and operand constraints: [CUSTOM_TAG_MARKED_SEQUENCE_ONLY](../../../validator_rule_text.json#L1521), [CUSTOM_TAG_OPERAND_MATCH](../../../validator_rule_text.json#L1534).
- Mail_ContentSet ancestry in s/si/m/mi, with the recorded collection-front-matter exception: [MAIL_CONTENTSET_NESTED_UNDER_MESSAGE](../../../validator_rule_text.json#L1970).

**Correction:** Add these conditional checks without implying that all EA-PDF files must use custom tagging. Preserve the conditional introduction when restating the document's Mail_Message and Mail_ContentSet cardinalities. See [§9.7, PDF page 81](../../reference/EA-PDF-v1.pdf#page=81) and [the project interpretations](review-questions.md).

## Coverage by document section

| Document section | Comparison with current JSON |
| --- | --- |
| 1. Identifiers and controlled values | Namespace URIs/prefixes, version 1, revision 2024, six lowercase conformance values, required creator/Producer/CreateDate and recommended x-default title agree. JSON further distinguishes RDF value types, integer value versus spelling, and uncertain creation provenance. |
| 2. PDF/A compatibility | Deliberate policy difference described in finding 2. Excluding PDF/A-3b agrees. |
| 3. Content Sets and structure | Outlines, DPartRoot, page DPart, first-page outline references and single/multiple email counts agree. Clarify nonblank scope; JSON adds exact reachable leaf ranges, ownership and page-order checks. |
| 4. Collection schema | Listed schema keys/subtypes, ADBE extension requirement, prohibited D, non-H View, Navigator exclusions and Boolean E=false agree. JSON additionally addresses Collection presence/prohibition, member EA-PDF validation, filenames and case-normalized uniqueness. The Table 5/6 original-email-size versus embedded-PDF-size conflict remains explicit in JSON. |
| 5. DPM | Intermediate GUID requirement, optional Message-ID, GUID precedence and basic profile counts agree. Correct findings 3 and 5. JSON's c/ci identifier prohibition applies to every DPart node, not just intermediate nodes. |
| 6. Logical structure | The listed custom role mappings, child requirements and Mail_FieldValue companion-name rule broadly agree. Add finding 7 and the mandatory Mail_Message GUID from finding 4; keep optional custom-tag applicability explicit. |
| 7. UserProperties | Listed names, ASCII N and value types broadly agree. Add required-in-context attributes, allowed tag scopes, matching and source-preservation recommendation. PDF Name values must not substitute for prescribed PDF strings. |
| 8. Source asset XMP | Required fields, optional NumberMessages, asset checksum and generic-media recommendation agree. JSON clarifies NumberMessages counts successfully rendered messages, not every message stored in the source, and distinguishes canonical field spellings and byte correspondence. |
| 9. Email header XMP | Ordered FOAF agents, Sent, conditional Raw-Sent, required GUID, attachment-count conditions and source-dependent completeness broadly agree. JSON specifies In-Reply-To as an unordered rdf:Bag, unique/exact GUID identity, normal strings and supplied-value type validation independent of original-source availability. |
| 10. Attachment XMP | Required record fields, conditional GUID and optional CheckSum broadly agree. Set presence conflicts (finding 1). JSON additionally distinguishes stored-byte size, zero-length placeholders, original filename/media text, exact property spellings and owner-scoped associations. |
| 11. Practical rule split | Mandatory/recommended/source-comparison distinctions are useful, but the attachment-set and profile-matrix classifications need correction. The current result model also distinguishes N/A from unavailable applicable checks and incomplete validation. |

## Rule families outside the document's summary

The document is not a full checklist for the 213-entry rulebook. It also omits or only indirectly mentions:

- File version restrictions, PDF/A-3 Info/XMP synchronization, catalog PieceInfo prohibition, EA-PDF keywords, viewer preferences and profile-dependent PageMode/NonFullScreenPageMode.
- Mandatory original source AF associations for non-isolated profiles, source companion file sets, source coverage and source-availability limits.
- FileAttachment annotations in applicable BodyRendering sets and required annotation Contents text; associated-stream ModDate, embedded-file name-tree membership, filename restrictions and reconstructed media types/parameters.
- Rendering text as PDF text objects, the conditional actionable-link requirement, unspecified-color sRGB policy and preserved rendering-asset associations.
- Recommended embedded Size/CheckSum fields and their supplied-value constraints; source/attachment byte comparisons and uncertain association handling.
- Operational dependency/result rules that prevent an unavailable applicable check from being reported as conformance or legitimate N/A.

Generic requirements wholly delegated to veraPDF are intentionally absent from the active custom registry. The review archive records 12 retired/delegated entries, including the external ISO/TS 32005 execution entry; declaration and applicability checks remain active. Their absence is not evidence of a lost overall validation responsibility.

## Verification and limits

The existing `scripts.audit_eapdf_rule_registry.audit()` passed: **213 active entries**, **301 reviewed inventory records**, specification ordering verified, and all mandatory file obligations accounted for by active or upstream mappings. That is a traceability check, not proof that every rule is completely implemented or that the document is complete.

The pinned source SHA-256 is `b89e7471afd3aabaa4e58bd6ca1f3241378c1596cf528023e740d9e79183dbae`. Existing project interpretations were used to distinguish deliberate policy from omissions; no new normative interpretation was adopted.

Input hashes:

- Root Word document: `10a6e82a485f05dfd97e1c7618385a2ab6e497d88a4d2165c694d98f9523dad4`.
- Reference Word document: `a6c95c9b47f7bb9ef59c89191350ec6b5c5662e7da5175366d0bc4e6456d1cc2`.
- Current JSON rules: `36f7731d64f56e902d729ea8481a60413af609bc624c7e232f8b20f7e5db47d7`.

Recommended next edit: update the Word document to the current JSON and recorded interpretations, concentrating on findings 1–5 and 7, and clarify finding 6. The document alone does not justify reverting the current attachment-set or PDF/A interpretation rules.
