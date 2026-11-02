# V43 connected subtype MIME values

Subtype comparison now uses the existing reviewed media signature on actual decoded
values. General whitespace cleanup no longer changes parameter data before parsing.
The same normalization is applied before intersecting connected candidate sets.
MIME-equivalent shared candidates can therefore match without assigning an owner.
Distinct compatible candidates stay unverified; universal contradictions still fail.
Signatures use a local cache capped at 4096 entries.

## Authority

EA-PDF9.6 Table8 defines subtype media types and parameters by RFC2046;9.7 Tables12/13
represent DPM names as strings and require matching. The MIME interpretation follows
[RFC2045 sections2/5/5.1](https://www.rfc-editor.org/rfc/rfc2045) and
[RFC2046](https://www.rfc-editor.org/rfc/rfc2046.html): type/subtype and parameter names
ignore case, parameter order and delimiting quotes do not change values, and generic
parameter values preserve case and meaningful quoted spaces. [Charset names](https://www.iana.org/assignments/character-sets) ignore
case. This is the reviewed matching policy; it is not a verbatim additional EA clause.
[Review](rule-review.json), [media sources](media-source-review.json).

## Verification

- 721 unit tests: 681 passes and 40 integration/schema skips; fourteen focused tests pass.
- 411 custom fixtures; all 403 prior hashes/statuses/failure sets remain unchanged.
- Eight exact-byte before/after PDF controls pass PDF/A and verified ISO checks.
- One former false comparison pass fails; five false comparison failures pass.
- One CLI rejection and one worker acceptance match the fixture expectations.
- Twelve fresh creator profile runs preserve known operand/child conformance failures
  while passing the exercised PDF/A/ISO gates. Creator acceptance remains false.

Eight exact V42 engine before-runs are reused by hash. Eight after control engine
runs, two entry points and twelve creator engine runs are fresh. Skipped integrations
are not passed gates. Java, CLI and original creator inputs remain unchanged.

## Limits

Optional FrontMatter fixtures establish internal declared correspondence, not original
source/vendor format fidelity. The reused MIME signature covers reviewed common
semantics, not all media-type-specific parameter equivalences. Malformed or duplicate
parameters retain raw fallback rather than parser-repaired equality; separate DPM
syntax checks still apply. GUIDs, property N names and opaque Content Set type names
retain their own comparison policies. Other scalar consumers, logical coverage,
source occurrence assignment, XMP group binding and eleven listed questions remain.

[Evidence](evidence.json) · [Frozen inputs](verification-inputs.json) ·
[Custom results](custom-regressions.json) · [Pipeline results](pdf-results.json) ·
[Observation review](observation-review.md)
