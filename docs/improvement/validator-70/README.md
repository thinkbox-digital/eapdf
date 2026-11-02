# V70: Empty raw text, presence and optional omission

Supplied empty Raw-To/From/Cc/Bcc/Raw-Sent text no longer bypasses known nonempty source value comparisons. Empty originals remain compatible with empty normal text. Unreadable original text remains WARN/unavailable with mandatory incompleteness. Physical property presence counts empty element, qualified and attribute forms; Raw-Sent presence and its SHOULD-not-presence recommendation are separate from value fidelity.

Table18 makes ordinary raw address properties optional, without a SHOULD inclusion requirement. Missing properties now have established N/A. Required Core Field/FOAF representation remains separately enforced. V65’s missing-raw-recipient expectation levels and recipe are corrected; old expectations are retained here and dated frozen receipts remain unchanged.

Verification: 1006 frozen tests ran: 966 passed, 40 integration/schema checks were explicitly skipped. All 629 prior PDF hashes/statuses/failure sets remain across 639 freshly checked custom cases. Three prior raw-code warning rows become N/A. Ten exact-byte controls pass real PDF/A and verified ISO checks; CLI/worker agree. Twelve retained creator outputs retain known failures. Sixteen offline browser checks pass. Seven new false passes fail; matching empty originals/optional omission pass, and the invalid-date empty Raw-Sent case retains its failure with the correct physical presence assessment.

Complete source decoding, raw-header byte fidelity and all normalization forms are not certified. The unreadable-text guard is a focused helper assessment, not a new general malformed-source engine gate. No creator source/JAR or CLI/debug behavior changed; the creator proposal remains deferred.
