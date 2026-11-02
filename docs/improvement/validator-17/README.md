# V17: attachment owners and counts

## Changes

An attachment may identify its email by Message-ID alone. The validator now
finds one matching email XMP record before checking its body annotations or
source payload. It preserves identifier case and punctuation. A supplied GUID
still takes priority. Optional DPM Message-ID values may be absent.

An owned attachment record also proves that the email has at least one
attachment. Its NumberAttachments must be present and positive, even when the
source email is omitted. This check does not infer the exact count.

Ambiguous owners stay unavailable. The general text matcher is unchanged.
The separate metadata property-name question remains open.

## Checks

Final acceptance is recorded in [evidence.json](evidence.json). The final inputs
are frozen in `.eapdf-verification-v17-final`:

- 430 unit tests: 396 pass, 34 expected skips.
- Nine PDF cases: all pass PDF/A and upstream ISO checks; all expected EA-PDF results match.
- Twelve fresh outputs: all six profiles in normal and accessibility modes pass.
- 150 custom-only comparisons: only seven reviewed results change from V16.
- Sixteen CLI and isolated web-worker checks match.
- All 141 older PDF files and custom results are unchanged.

[Before/after results](before-after-comparison.json) use the same PDF bytes.
The six original owner cases use V16 as their baseline. Three source-omitted
cases use the initial V17 snapshot, which isolates two count errors: missing
and zero counts previously passed. V16 separately misrouted their annotations;
all seven differences from V16 are retained in the custom comparison results.

The initial snapshot and six-case checks remain as history. They predate the
count repair and do not prove the final code. The final log/report set
and final snapshot support this acceptance.

## Scope

`validator_rule_text.json` remains authoritative. The [supporting review](requirements-review.json)
covers EA-PDF Tables 7, 18 and 19 and section 9.3. RFC references provide context;
this repair adds no general RFC canonicalization or IMAP requirements.

Java sources, resources and creator JAR are unchanged from V16. The existing
161 Java passes are historical; no new Maven build or full packaged-suite run
is claimed. Eleven expert/dependency questions, global attachment occurrence
assignment and complete original-input coverage remain open. B13 stays deferred.
