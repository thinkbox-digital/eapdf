# V72: Direct UTF-8 source headers and message/global

**V72 accepted — 2026-10-21:** Valid direct UTF-8 source headers are restored losslessly before parsing and comparison. `message/global` sources and nested messages are recognized by media type, including non-`.eml` filenames. **985 unit passes / 40 skips; 657 freshly checked custom cases; ten PDF/A/ISO controls, CLI/worker and 16 browser checks match.** All 647 prior hashes/statuses/failure sets remain. Seven false failures now pass and one missed mismatch fails. Creator repair remains deferred. [Evidence](improvement/validator-72/evidence.json).

Valid UTF-8 header octets are restored losslessly from BytesParser surrogate escapes before structural address parsing. Subject/identifier text, quoted mailbox data and decomposed Unicode retain their actual characters; no normalization or legacy charset guessing is applied. message/global sources and nested attachments are identified by media type. The obsolete occurrence test now distinguishes valid UTF-8 from genuinely invalid octets.

This is bounded source parsing/comparison evidence, not complete SMTPUTF8, IDNA, malformed-source handling or obligation certification. All creator source remains unchanged.
