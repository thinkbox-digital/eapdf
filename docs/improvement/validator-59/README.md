# V59: exact DPart identity keys

Table 7 identifies emails using Mail_GUID and optional Mail_MessageID. Unrelated DPM Message-ID / MessageID / messageid extensions were incorrectly treated as aliases by the DPart source selector. A leaf extension could stop ancestry lookup before the valid canonical parent identity, causing false body-source and attachment-annotation findings.

One runtime line now restricts this selector to Mail_MessageID, retaining canonical Mail_GUID precedence, decoder guards and ancestor lookup. The shared XMP/source identity helper keeps its alias support. No extension data is removed or restricted, including Name, Array and nested dictionary values.

Verification binds 1,411 frozen files to 896 unit passes, 40 explicit integration/schema skips, ten focused tests, 540 custom cases, eight exact-byte PDF/A/verified ISO controls, CLI plus isolated worker, and twelve fresh creator diagnoses. All 532 historical hashes/statuses/failure sets remain; an independent census finds no such DPM aliases in that cohort. Five ordinary extension controls change FAIL to PASS with verified source-body and annotation checks. Two extension controls remain PASS and a canonical GUID contradiction retains its known failures. All source PDF bytes remain identical before/after.

Current lexical policy and invalid canonical-owner annotation behavior remain separately unverified. All eleven listed questions, broader source occurrence/XMP binding and creator work remain open. Creator PDFs and JAR are unchanged; all twelve creator conformance results remain FAIL.
