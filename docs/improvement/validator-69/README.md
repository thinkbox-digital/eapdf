# V69: Raw address mailbox fidelity

Raw-To/From/Cc/Bcc source comparisons no longer erase changed quoted mailbox data. Known ASCII mailboxes are parsed from original header occurrences and compared before the existing normalized text check. Missing optional raw fields remain optional; existing whitespace policy outside the mailbox and unsupported/missing source occurrence behavior remain. FAIL templates explain source-data or text-type violations.

Verification: 996 frozen unit tests ran: 956 passed and 40 integration/schema checks were explicitly skipped. All 621 prior hashes/statuses/failure sets remain across 629 freshly checked custom cases. Eight exact-byte controls pass actual PDF/A and verified ISO checks; CLI/worker agree. Twelve retained creator outputs retain their established failures. Sixteen offline browser checks pass. Four new false passes fail; exact and qualified text, outside whitespace policy and an independent FOAF mismatch preserve their outcomes.

This is a bounded ASCII mailbox-data comparison, not complete raw-header byte fidelity, internationalized/obsolete/folded grammar or source-inventory certification. Original source bytes are retained. Creator source/JAR and CLI/debug behavior remain unchanged; the creator proposal remains deferred.
