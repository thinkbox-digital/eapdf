# V80: Viewer conditions follow the represented email

Single-message viewer-mode checks run after email source matching. The profile predicate and final applicability context share attachment-presence evidence: declared attachment inventory/nonzero counts and actual matched originals. Unrelated original emails cannot change a matched message with no attachments into the attachment branch. Unknown original ownership does not establish attachment absence.

Thirteen native PDF controls include source-order reversal, isolated and non-isolated positives, both known wrong viewer modes, ambiguous mixed counts, unsupported originals and values forbidden by every possible count. Three false failures pass, one unsupported failure becomes INCOMPLETE, and two known wrong-mode controls retain FAIL under the correct no-attachment codes. Universal mode contradictions still fail without assigning an owner.

Verification: 1107 unit tests (1067 passes, 40 skips); 742 fresh custom cases with all 729 prior hashes/statuses/failure sets unchanged; thirteen new and four positive collection PDF/A/ISO controls; CLI/worker agreement; twelve retained creator failures; sixteen offline browser checks. See [evidence.json](evidence.json).

The no-attachment seed is corrected only by the independent structure/page/MCID fixture oracle. Creator sources, original PDFs, CLI arguments and debug output are preserved. No creator build or acceptance is claimed.

Full source/attachment occurrence ownership, complete MIME parsing, candidate enumeration, cited draft-edition equivalence and obligation coverage remain open. A separate nested-source proof is retained for V81; V80 does not repair that false closed-world conclusion.
