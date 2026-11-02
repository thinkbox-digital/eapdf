# V25: contradictions across ambiguous source assets

EA-PDF 9.8.2 Table 16, printed p85, requires source-asset byte size and media
type metadata. Section 9.4, printed pp66–67, describes equivalent media types
and reconstruction. Existing checks compared these values only after selecting
a Source association; an ambiguous association could hide a value contradicted
by every possible Source.

V25 adds those independently provable size/media failures. It uses the existing
association candidate set. Any compatible or unreadable observation blocks the
universal failure for that field. Invalid XMP semantic shapes remain schema
findings. No owner, occurrence or alias is selected, and no candidate is consumed
using the tested size or media type. Association/coverage uncertainty stays
visible beside a proved failure. Existing checksum behavior is unchanged.

Authority remains [validator_rule_text.json](../../../validator_rule_text.json),
`ASSET_SIZE_MATCH` and `ASSET_CONTENTTYPE_MATCH`. No new requirement or code is
introduced.

## Independent controls

Eight new PDFs start from V24's corrected operand seed. Each has two different
Source filenames with identical retained bytes/MD5 but different media types.
The metadata names cannot select a native association. The recipe computes
sizes and checksums directly from the retained bytes.

Four negative PDFs have an impossible size, impossible media type, both, or
altered malformed parameter text. V24 reported INCOMPLETE without those
failures. Compatible values, a zero-padded size and equivalent RFC 2231
parameters retain INCOMPLETE; explicit unique filenames retain PASS.

Focused tests also cover source/record permutations, large integer strings,
unknown observations, empty candidate inventories and invalid XMP shapes.
The runtime does not use size/media to select an owner.

## Verification

[Final evidence](evidence.json) accepts **485 unit passes / 40 skips, 215 custom
outcomes, eight PDF probes and eight actual CLI/worker checks**. All 207 prior
PDF hashes/outcomes and all twelve retained creator conformance failures are
unchanged. The asserted frozen gates are:

- `.eapdf-verification-v25` unit suite and twelve focused source tests.
- All 215 custom outcomes, with the 207 prior PDF hashes/outcomes unchanged.
- Eight identical-byte before/after real PDF/A and verified ISO probes.
- Eight real CLI/isolated-worker outcomes covering PASS, FAIL and INCOMPLETE.
- Revalidation of all twelve exact V24 creator profile PDFs with the final
  validator. Their independently diagnosed operand/child failures must remain
  unchanged; these remain failed creator acceptance gates.

`verify-regressions.py` reuses V24's accepted before outcomes only for its exact
207 previously hashed PDFs. New probes execute both frozen engines. The full
pipeline script executes real engines on the new inputs and retained profile
outputs. It supports resuming completed checks only after checking PDF hashes.

`source-before-corrected-controls.log` retains the regressions against the
complete V24 runtime. Initial focused control mistakes (a second record with
unknown-size text and stale media metadata) and initial PDF recipe mistakes
(a duplicated AF entry and wrong unused namespace declaration) are retained
separately. They were recipe/expectation errors, not conformance repairs.

Run `freeze-inputs.py`, the frozen unit suite, `verify-regressions.py` and
`verify-pdfs.py`, then `assemble-evidence.py`. The accepted creator is unchanged;
no Java build or regeneration of saved examples is required or claimed.

## Remaining limits

Unique occurrence/source-asset assignment is still unavailable in the ambiguous
controls. This does not establish original-inventory completeness or all media
parameter reporting. V24's confirmed creator operand defect remains unrepaired
under the plan's validator-first sequence. Eleven interpretation/dependency
questions, the broader shared-predicate review and release gates remain open.
