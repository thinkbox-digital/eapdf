# V57: guarded optional DPM MessageID decoding

Table 7 permits Mail_MessageID absence and requires supplied values to match XMP. Decoder-rejected supplied text now retains DPM_MAIL_MESSAGEID_MATCH WARN/unavailable plus VALIDATION_INCOMPLETE. Readable matches, mismatches, wrong object classes and optional absence retain their outcomes. The current whitespace/global-candidate policy is preserved pending separate lexical and source ownership review.

The complete repair also handles DPart body-source identity selection. A readable GUID retains Table 7 precedence despite unreadable MessageID. Without a readable GUID, an unreadable local identity cannot select an enclosing email. Actual body-media comparisons still run, and independent body mismatches remain failures.

The first candidate passed twelve focused tests but failed a real pipeline check in a second consumer. Python exception tracing identified identity_fields through dpart_message_identity and collect_body_content_sets. That immutable snapshot and its failed receipts remain under `initial-candidate/`. The final `.eapdf-verification-v57-r2` runtime receives fresh complete gates; the earlier results do not certify it.

Accepted final verification: 868 unit passes, 40 explicit integration/schema skips, fifteen focused tests, 522 custom cases, eight exact-byte PDF/A/verified ISO controls, CLI plus isolated worker, and twelve fresh original creator diagnoses. All 514 historical hashes/statuses/failure sets remain. Three operational exceptions become two INCOMPLETE results and one retained revision FAIL. The native receipts retain actual classes, encoded bytes, target identity and optional presence. A source-body check passes on all eight controls through their readable GUIDs.

DPM GUID decoding, lexical/source ownership policy, namespace/Table9 scope, source occurrences and XMP group binding remain open with all eleven listed questions. Original creator bytes and JAR remain unchanged, and their twelve conformance gates retain FAIL.
