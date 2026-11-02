# Mixed-rule routing review

All 59 mixed ownership codes have separate covered and uncovered fragments in
the authoritative JSON. They concern four families:

| Family | Custom assertion | Upstream boundary |
| --- | --- | --- |
| Document parts | Email GUID presence/identity and source Message-ID metadata | Generic PDF/XMP serialization |
| Document identification | EA-PDF namespace/prefix, required creation properties, prescribed version/revision/conformance values and unavailable provenance | Standard XMP property types and serialization |
| Source assets | Required asset schema, counts, source associations and byte/media comparisons | Generic RDF and declared PDF/A extension types |
| Email/attachment metadata | Prescribed Table 18/19 fields, record/array structure, exact GUIDs, ownership and source comparisons | Generic RDF and declared PDF/A extension types |

The V07–V12 repairs address uncovered EA-PDF assertions. The inherited ISO
inclusion rules run through the separately verified unchanged upstream profile.
They are not implemented in custom Python checks. Eleven fully delegated codes
retain their previously accepted routing; this review adds no generic predicate.

`mixed-fragment-review.json` links every mixed code to its exact authoritative
fragments, per-profile conditions, functions containing its literal code and
observed levels in the 98 retained complete-pipeline reports. Reports include
applicability/finalization rows. These observations are not branch coverage,
proof that a checking branch executed or a function emits the code, or
certification of the whole specification. Recorded failures are counted separately.

Source completeness, arbitrary record/occurrence assignments, unavailable
provenance, optional or unobserved branches and the eleven expert/dependency
questions keep their explicit limits. The source-asset repair does not establish
original filename identity or treat MD5 as a secure identity proof. No new answer
to an open interpretation has been inferred from passing PDFs.
