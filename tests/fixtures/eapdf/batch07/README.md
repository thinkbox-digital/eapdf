# Batch 07 tagging recommendations

Six creator-derived PDFs exercise actual structure/content connections. Independent object recipes change a Content Set type, remove optional structure, append one unmarked painting operation, convert one complete header field to standard tags or leave only custom field values.

The five source headers (From, To, Date, Subject, Message-ID) provide an independent fixture oracle for observed field counts. Complete observation of these five pairs still gives partial recommendation WARN because the validator does not implement a general inventory of all rendered headers. Missing custom field names also have separate mandatory failures.

Custom Content Set coverage uses all declared DPart page ranges as a conservative superset. It checks stream paint, artifacts, annotation connections, MarkInfo and independent structure correspondence; it does not define every custom Content Set or certify semantic/accessibility equivalence. Both named standard and extension-valued types exercise this boundary.

All cases must independently pass PDF/A. Exact expected mandatory failures and recommendation outcomes are in cases.json. Run scripts/build_eapdf_tagging_fixtures.py with EAPDF_GENERATOR_CLASSPATH. Final packaged acceptance archives exact PDFs and reports.

Expected overall outcomes: four PASS, one exact mandatory FAIL and one INCOMPLETE. The untagged custom-only rendering remains INCOMPLETE because mandatory Core Field text evidence cannot be assigned through a BodyRendering owner; this is retained, not waived by the recommendation repair. The initial corpus exposed a separate body-media applicability gap: known, valid non-body ranges do not require a BodyRendering comparison. Unknown types, bad ranges and unresolved declared bodies remain INCOMPLETE.
