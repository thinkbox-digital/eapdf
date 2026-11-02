# V12 — source-asset association under filename collisions

A two-source PDF passes PDF/A and all reviewed checks. Giving its source asset
records path-bearing original names with the same basename preserves validity.
Reversing that unordered Bag previously produced `ASSET_MD5_MATCH`, `ASSET_MATCH`
and `SOURCE_WITHOUT_XMP` failures. The matcher selected the first filename-tail
match before considering a unique checksum match. Before-source and actual PDF
results are retained here and in `../validator-next/asset-ownership/`.

The repair prioritizes a unique checksum match, retains unique-name comparison
when a checksum is wrong, and reports ambiguous association as unavailable.
Identical payload/media observations are interchangeable for these bounded byte
comparisons. Known checksum mismatches and source-record cardinality deficits
remain failures. No generic RDF or PDF/A predicates were added locally.

Seven focused unit tests cover both file and Bag order, renamed sources,
uppercase checksums, ambiguous evidence, identical bytes and provable failures.
Six retained PDF/A probes include valid permutations, an identifiable incorrect
checksum and an ambiguous case with a separately provable checksum violation.
Final corpus, CLI/worker and regression acceptance are recorded in `evidence.json`.

Final frozen V12 acceptance: **356 current unit passes / 28 integration/schema
skips; all 98 retained PDF/A-valid cases meet exact custom/upstream outcomes;
all six CLI/isolated-worker checks agree.** Every applicable ISO job is verified;
the five N/A cases have no applicable PDF 2.0 logical structure. Frozen/live
runtime and test hashes match. The [mixed-rule review](mixed-fragment-review.md)
records all 59 split codes and its limits.

The authoritative checkpoint was frozen while verification was in progress;
the completed outcomes and source boundaries are recorded in `evidence.json`.

V11's full packaged suite uses its own frozen source. It does not include this
subsequent repair. V12 records the current unit run and targeted complete-pipeline
acceptance separately. The creator JAR is unchanged from its 161-test Java build.
Eleven expert/dependency questions, broader ownership review and certification
limits remain open. No public CLI arguments or debug output changed.
