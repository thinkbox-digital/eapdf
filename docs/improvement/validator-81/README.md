# V81: Uninspected nested originals limit source proofs

Known nested email attachments and mailbox envelopes make an outer-message-only source inventory incomplete. When a tentative outer match contradicts the represented email, its score cannot assign ownership or prove that every possible original contradicts the metadata. Those comparisons remain INCOMPLETE. A consistent outer email remains checkable; ordinary non-mail attachments and supplied schema constraints retain their independent checks.

Body-media comparison reuses the accepted source binding, including an unassigned result, so it cannot independently reselect a rejected outer source.

Eight native controls cover RFC822, message/global, mail filenames under generic media, unsupported MSG, matching outer headers with incompatible size, a mailbox envelope, a consistent outer positive and an independent wrong-recipient failure. Six unsupported FAIL results become INCOMPLETE. The matching nested RFC822 bytes are independently checked against the retained original seed.

Verification: 1117 unit tests (1077 passes, 40 skips); 750 fresh custom cases with all 742 prior hashes/statuses/failure sets unchanged; eight new and four positive collection PDF/A/ISO controls; CLI/worker agreement; twelve retained creator failures; sixteen offline browser checks. See [evidence.json](evidence.json).

No nested ownership assignment, full mailbox enumeration, MSG/PST parser, arbitrary archive discovery, creator build or sample acceptance is claimed. Full obligation coverage and cited draft-edition equivalence remain open. The separate source-recommendation binding reproducer is retained for V82.
