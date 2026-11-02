# Exact attachment filename and role evidence

Regenerate with `python3 -B scripts/build_eapdf_attachment_evidence_fixtures.py`.
The recipe uses deterministic PDF serialization IDs and preserves its two source
PDFs. Table 19 defines the original case-sensitive filename as text and permits
a different stored PDF filename. Without independent payload evidence, a
cleaned or sanitized filename is not proof that these names identify the same
attachment. Parsed MIME parameter and PDF filename text retain significant
spaces. UF is preferred over the compatibility F name.

Three controls PASS: a normal name, a matching repeated-space name, and a native
annotation with a separately associated Alternative representation. Three
unresolved associations are INCOMPLETE: an original path, original outer spaces,
and a PDF-only repeated-space difference. A filename contradicted by the unique
retained source fails ATTACHMENT_XMP_MATCH. A body annotation retargeted solely
to an Alternative fails ATTACHMENT_ANNOTATIONS_PRESENT; the native bytes remain
preserved but no native attachment annotation remains in that body Content Set.
The Alternate's identical payload deliberately isolates the role distinction.

All eight probe PDFs pass independent PDF/A and upstream ISO under the baseline.
Before/after results, expected codes and file hashes are retained under
`docs/improvement/validator-16`. This is bounded association evidence, not an
original attachment inventory or global occurrence/owner assignment proof.
