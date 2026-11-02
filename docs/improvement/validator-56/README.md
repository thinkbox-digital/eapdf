# V56: guarded DPM description decoding

A PDF String object can reject text decoding. The old `norm_text(Mail_Desc)` conversion aborted validation; Table 8 still requires a human-readable description. Rejected decoding now gives DPM_DESC_REQUIRED WARN/unavailable and VALIDATION_INCOMPLETE, then continues the remaining checks. No decoded label or encoding grammar is invented. Known nonempty text, empty text and wrong object classes retain their existing outcomes.

Verification binds 1,333 frozen files to 853 unit passes, 40 explicit integration/schema skips, ten focused tests, 514 custom cases, eight exact-byte PDF/A/verified ISO controls, CLI plus isolated worker, and twelve fresh original creator diagnoses. All 506 historical hashes/statuses/failure sets remain. Three operational exceptions become two INCOMPLETE results and one independently retained EAPDF_REV failure.

Native receipts preserve actual String/Name classes, encoded bytes, target DPart identity and decoding results. Valid UTF8/UTF16 descriptions pass; known empty, decoded-empty UTF16 and Name values fail the existing description rule. Rejected UTF8 and surrogate encodings remain unavailable. This scope does not resolve other DPM decoding paths, namespace questions, source occurrences or XMP group binding. All eleven questions and creator gates remain open; original creator PDFs and JAR are unchanged.
