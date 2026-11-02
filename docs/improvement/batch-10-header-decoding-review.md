# Batch 10 expected encoded-header failures

Reviewed 2026-10-18. This is application assurance for EA-PDF 1.0 section 9.7 recommendation evidence under rules 107/113. It adds no mandatory source-encoding rule and does not complete rendered-header coverage.

## Confirmed gaps

The installed Python 3.12 decoder raises HeaderParseError for a base64 encoded word containing `a`. That expected input error was absent from three decoder exception lists. A malformed optional, unrendered X-Project header could therefore abort validation of the whole PDF while B09 enumerated source occurrences. The retained B09 baseline probe reproduces exactly VALIDATION_ERROR with Base64 decoding error, although the PDF passes independent PDF/A.

The decoder also repairs or tolerates some invalid payloads: it can discard invalid base64 alphabet characters, supply missing padding, or leave malformed Q escapes as text. Such results previously allowed a decoded attribute-fidelity PASS. Before-fix regressions preserve ten failed assertions and five errors across the initial eight-test run. The first focused assertion also included unrelated successful attributes; its correction filters the affected Mail_X-Project and keeps the no-false-PASS requirement.

## Bounded interpretation and repair

[RFC 2047 section 2](https://www.rfc-editor.org/rfc/rfc2047.html#section-2) defines compact encoded-word components and nonempty printable encoded text. Its [encoding definitions](https://www.rfc-editor.org/rfc/rfc2047.html#section-4) use B and Q. Base64 groups/padding come from [RFC 2045 section 6.8](https://www.rfc-editor.org/rfc/rfc2045.html#section-6.8). The [Python base64 API](https://docs.python.org/3.12/library/base64.html#base64.b64decode) supports strict alphabet checking, while [email.errors](https://docs.python.org/3.12/library/email.errors.html) documents parser exceptions.

For complete encoded-word-shaped components, decoded comparisons now require a nonempty payload with graphic ASCII, supported B/Q transfer encoding, complete base64 groups accepted by the strict library decoder, or valid Q hexadecimal escapes. Character decoding and replacement/surrogate checks follow. Invalid or unsupported data becomes an explicit unavailable comparison. Recognized payload repairs cannot establish fidelity or select a repeated occurrence.

The same helper now serves decoded UserProperties and observed source-header occurrences. Raw UserProperties still compare literal unfolded source values, including malformed encoded text. The older tolerant decoded_header_text helper gains only the expected HeaderParseError fallback needed to inspect such sources; its matching/normalization policy is otherwise unchanged. Genuine unexpected decoder errors still propagate to existing operational failure handling. No broad exception catch or new N/A branch is added.

This is not a complete RFC 2047 recognizer: contextual placement, header-specific character restrictions, incomplete delimiters, 75/76-character limits, charset registry conformance and other malformed representations remain outside the repair. Ordinary literal text with `=?` but no complete candidate remains ordinary text. Existing tolerant XMP/source selection is not independent proof of every source association.

## Fixtures and verification

Six generated archives use ordinary core fields and optional X-Project plus a later X-Later header. Valid Q-encoded fidelity passes; malformed alphabet, padding and Q fidelity become unavailable WARN. A malformed source-only header remains a per-occurrence limitation while ten observed core fields and later headers are assessed. Optional isolated source omission remains valid with unavailable observations and N/A source fidelity. All six must pass independent PDF/A and mandatory EA-PDF.

Original source hashes and parsed raw payloads are asserted independently of decoded findings. The exact baseline PDF/report, focused logs, frozen final inputs and packaged acceptance reports are retained. Valid B/Q words, folded adjacent words, ordinary literal text, raw attribute comparisons, repeated-candidate ambiguity and both report renderers have focused coverage. Unexpected RuntimeError and expected HeaderParseError are tested separately.

No creator, Java/CLI/debug, saved-sample or UI implementation change is needed. Accepted B08 creator hashes and saved samples match; its 138 Java passes remain historical. The full suite and exact archived corpus/profile reports supply final acceptance. Complete rendered-header enumeration, arbitrary occurrence/aggregation semantics, wider source/asset ownership, remaining N/A predicates and complete standards/ISO/TS 32005 review remain open.
