# B13 MBOX reader inventory review

**Stopped, not accepted:** The user redirected work to complete specification-to-validator-requirements review first. Java, authored-corpus and saved-sample checks completed, but the full Python acceptance suite was terminated before its summary. Retain this work and its evidence; do not infer final acceptance or resume preservation expansion ahead of the corrected priority.

## Confirmed failure

Before the edit, a recognized separator followed by EOF, a malformed first
header or a blank first header was folded into the preceding body. Three new
assertions failed, including protection of an existing collection destination.
The retained pre-fix log contains assertion failures rather than setup errors.
A second gap allowed a missing header/body separator to consume the next entry.

## Bounded repair

The parser now rejects those ambiguous recognized boundaries. Accepted results
capture an immutable ordered inventory of envelope/message offsets, raw and
exported byte sizes/SHA-256, skipped Content-Length padding, selected framing and
quoting, requested mode, and full container size/SHA-256. Logical stream offsets
include CR, CRLF and buffered lookahead; hashing happens on the underlying input
and completes at EOF. Raw/export hashes are captured before the callback.

Before returning the mailbox reader result, reconciliation checks entry count,
ordinal identities, complete contiguous input coverage and actual exported
Source streams, including disk-staged bytes. Mutable checksum declarations are
not the comparison oracle. Metadata budgets reserve room for provenance before
staging; parser inventory allocation is also bounded. Existing message/count
limits continue to apply. Source descriptions and existing global derivation
metadata retain the detailed provenance even when isolation omits source bytes.

## Policy and evidence

Recognized ambiguous envelopes fail conservatively. This is an application
acceptance policy, not a claim that every such mailbox violates a universal
standard. [RFC 4155](https://www.rfc-editor.org/rfc/rfc4155) documents historical
format/escaping variation. Existing supported Content-Length framing permits a
separator-looking line inside the declared body. CLI arguments and existing
debug output are unchanged.

Seven Java regressions exercise ambiguous boundaries, existing destination
protection, nine independent byte-span oracles, staged provenance, omitted and
reordered identical entries, and changed staged bytes with updated checksums.
Full acceptance outcomes will be recorded in `batch-13-latest-evidence.json` only
after the commands complete. The authored recipe and packaged corpus are in
`tests/fixtures/eapdf/batch13/README.md`.

## Remaining boundary

This inventory covers supported MBOX parser entries and the reader's normalized
Source records. It cannot prove universal dialect recognition, reader-to-writer
dispatch completeness, MIME/MAPI attachment enumeration, PST folder/message
selection or Gmail/API enumeration. Whole MBOX containers/envelopes remain
unembedded; hashes and offsets are provenance, not full container preservation.
Broader source/XMP associations and independent final publication/sidecar gates
remain open. PDF-only checks without the original input cannot detect reader
loss; the separate corpus compares the authored input bytes with actual outputs.
