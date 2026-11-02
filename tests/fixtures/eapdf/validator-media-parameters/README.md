# Original media parameters

EA-PDF §9.4 requires consistent embedded media parameters. Table 19 requires
attachment Content-Type text to reflect its original source, including errors.
Parser recovery cannot establish equality after discarding source text.

- `control`: existing isolated, nonbody native attachment; PASS.
- `extra-empty-parameter`, `nameless-parameter`, `unquoted-space`: XMP differs
  from native parameters but the old parser normalized the difference away.
  Both embedded media/parameter checks now fail.
- `malformed-preserved`: identical malformed XMP/native parameter text; PASS.
- `legal-extended-parameter`: RFC 2231 and Unicode quoted forms agree; PASS.
- `quoted-source`: unchanged creator output with two spaces inside a quoted
  parameter; PASS. V19 wrongly collapsed its source spaces and failed it.
- `quoted-source-cleaned`: XMP/native values collapse those spaces while the
  source retains them; source Content-Type comparison fails.
- `malformed-source-preserved`: a source parameter is replaced with malformed
  text of the same byte length. Its source checksum is updated; XMP/native
  parameter text preserves it exactly. PASS.
- `malformed-source-repaired`: the same source is retained but its XMP/native
  parameters discard the malformed part and collapse quoted spaces; source
  Content-Type comparison fails.

The source mutations preserve message length, core headers, body text and
attachment payload bytes. `_quoted-source.pdf` and `source.eml` retain the
creator control. The other control is the existing native-stream fixture.
Original malformed strings are permitted; syntax errors alone do not fail
Table 19's free-text field.

Repeat with `python3 -B scripts/build_eapdf_media_parameter_fixtures.py`.
Expectations pin the PDF hashes. Before/after independent reports are in
`docs/improvement/validator-20`.
