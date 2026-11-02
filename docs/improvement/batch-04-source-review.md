# Batch 04 source-value review

## Requirement and outcome

EA-PDF 1.0 section 9.7, printed page 77, recommends retaining complete UserProperties values from the original email. This is a SHOULD, represented by rule 113, USERPROPS_VALUES_UNMODIFIED. A differing value warns; it does not create a mandatory EA-PDF failure. Wrong PDF object types still fail their separate mandatory rules.

The old validator unconditionally marked this recommendation N/A. The new check compares declared source-derived mail attributes when the retained RFC 822 source can be inspected.

## Ownership

Use the actual enclosing Mail_Message from the walked structure tree, including intervening standard tags. Match its GUID to exactly one email XMP record. Use Message-ID only if the structure owner supplies no GUID; do not fall back from a conflicting GUID. Then use the existing select_source_email matcher, based on that XMP record and retained source summaries. The property under test does not choose its own source.

This reuses the existing source matcher; it does not independently prove that all XMP/source associations are truthful. Duplicate owners or tied distinct source candidates produce an explicit unavailable-comparison warning. Two byte-identical candidates are equivalent under the existing matcher. Broader source inventory/ownership assurance remains in the plan.

## Compared values

- Preserve original parsed header values in source summaries, alongside existing normalized fields.
- Unfold header lines and strip outer separator padding; preserve internal spaces, character case and punctuation.
- Compare known raw fields as unfolded text. Decode RFC 2047 encoded words for ordinary header fields. Do not collapse internal spaces or HTML-unescape values.
- Compare Mail_Sent using valid PDF-date defaults and timezone semantics against the normalized source instant. Equivalent instants and reduced forms with the same defaults pass. Dates that denote another instant warn. Unrepresentable or unnormalizable dates warn as unavailable.
- Compare Mail_SizeInBytes against retained source bytes and Mail_NumberAttachments against the existing MIME attachment count.
- Map extra Mail_ header names to the literal source header suffix. String values compare as text; number/Boolean values use decimal or true/false scalar comparisons, as Table 12 permits these types. This does not establish a schema for arbitrary custom header semantics.
- Generated GUIDs, content-set kinds and media subtypes are excluded from header comparisons. Their separate checks remain active.

## Explicit limits

No declared source-derived attributes, absent optional structure or no inspectable retained source gives an explained N/A. This includes source omission in isolated files and retained formats that this validator cannot inspect. Repeated original headers, undecodable text or ambiguous ownership gives WARN with comparison=unavailable. Mixed findings remain visible; a successful comparison cannot hide another warning. Mandatory incomplete-rule handling is unchanged.

Current creator GUID/Message-ID/content-property writers were inspected; unchanged creator output passes the new recommendation. This repair does not add optional fields to the creator, change CLI arguments, weaken mandatory validation or change profile sidecar policy. Class-map/revision/duplicate-owner attribute resolution is still pending, so this is a bounded improvement rather than a full source-fidelity claim.

## Sources and independent fixtures

- Local docs/reference/EA-PDF-v1.pdf, section 9.7 / Table 12 (hash recorded in evidence); official publication: https://pdfa.org/resource/ea-pdf/.
- RFC 5322 section 2.2.3: https://www.rfc-editor.org/rfc/rfc5322.html#section-2.2.3.
- RFC 2047 section 6.2: https://www.rfc-editor.org/rfc/rfc2047.html#section-6.2.
- PDF date interpretation and applicable references: batch-03-date-review.md.

Two synthetic EML sources share Message-ID but have different subjects and sent dates. The creator makes ordinary and isolated multi-message seeds. Independent recipes change one declared attribute on the first source's structure owner. Seventeen cases must all pass PDF/A and mandatory EA-PDF checks: eight recommendation PASS, eight expected WARN and one isolated N/A. The GUID oracle is read directly from seed metadata, independently of validator findings. Unit cases also cover nearest ancestors, unresolved ownership, repeated/folded headers, preserved spaces and reduced date defaults.
