# V60: typed unique parent MessageID annotation binding

An attachment GUID can identify exactly one XMP email owner while a DPM body has only that email's MessageID available for matching. The child attachment identity omitted the parent MessageID, creating annotation failures despite known visible native payloads. The selector now uses that parent record for this additional path only when its MessageID is normal text and unique across email records under the current comparison policy.

Readable GUID precedence and decoder-unavailable identities remain intact. URI, missing and duplicate MessageIDs do not justify a new match. A definite GUID binding still works beside duplicate MessageIDs. Actual annotations outside the CropBox retain their geometry failure. The negative guard tests do not certify legacy unbound-owner failure severity.

An independent V59 native census proves three historical cases have matching typed parent/body IDs and visible nonempty matching annotation payloads. Their PDF bytes and overall FAIL statuses remain, with required GUID failures retained and unsupported annotation failure codes removed. Four analogous new controls receive the same correction. All other historical outcomes remain unchanged.

Verification binds 1,436 frozen files to 905 unit passes, 40 explicit integration/schema skips, nine focused tests, 548 custom cases, eight exact-byte PDF/A/verified ISO controls, CLI plus isolated worker and twelve fresh creator diagnoses. All 540 historical hashes/statuses remain; 537 failure sets are unchanged and three are corrected. No new file is certified conformant by removing its required GUID failure.

Existing no-GUID handling, lexical policy and broader unbound/ambiguous/typed-invalid owner diagnostics remain separately unverified. All eleven questions, source occurrences and XMP group binding remain open. Creator bytes and JAR are unchanged, and all twelve creator conformance results remain FAIL.
