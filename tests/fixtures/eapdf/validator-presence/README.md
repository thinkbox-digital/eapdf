# Required metadata presence and provenance

Reproduce with `python3 scripts/build_eapdf_presence_fixtures.py`. Set
`EAPDF_GENERATOR_CLASSPATH` to regenerate the retained no-attachment source.
The other controls come from the existing isolated-email and XMP record families.

All twelve probes pass PDF/A. Empty/whitespace first creator entries, a missing
count for an exactly owned attachment and malformed supplied optional
NumberMessages fail their declared requirements. Address-like creator/producer
names, a default count omitted without attachments and legal signed/zero-padded
integers pass. Unknown creation attribution is reported as unavailable, with no
inference from spelling or sender equality.

The manifest pins source/probe hashes and exact failure codes. The recipe and
tests keep source fidelity, arbitrary name grammars and ambiguous ownership
outside these assertions.
