# V62: attachment GUID owner correspondence

Table19 Mail_GUID describes the containing email's generated GUID, including when Message-ID makes the GUID optional. A complete typed nonempty email GUID inventory can prove that a supplied attachment GUID has no counterpart. Invalid/incomplete email GUID metadata leaves this comparison unavailable. Type/presence checks remain separate.

Without an established XMP owner and matching body, annotation applicability is unavailable. A known body with missing/out-of-bounds annotations still fails its annotation check. Header-only content cannot hide a proved GUID owner mismatch.

Verification: 961 tests ran, 921 passed and 40 explicit integration/schema tests were skipped. All 566 prior hashes/statuses/failure sets remain across 574 custom cases. Eight new exact-byte controls pass actual PDF/A and verified ISO execution. One header-only false pass fails; four unsupported annotation failure codes are replaced by proved GUID owner failures. CLI and isolated worker agree; twelve retained creator outputs retain exactly their known failure sets. No creator source/JAR or CLI/debug behavior changed. The eleven V61 local interpretations and unverified draft-edition equivalence remain unchanged.

The first pipeline run completed all eight controls and both entry points before its creator-baseline lookup used a file path in place of the retained key. The repaired runner resumed only missing creator checks. Both logs and all exact-byte reports are retained. Next review: FOAF URI percent encoding and case-sensitive mailbox local parts.
