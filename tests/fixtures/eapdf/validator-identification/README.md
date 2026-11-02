# Table 15 identification values

Reproduce with `python3 scripts/build_eapdf_identification_fixtures.py` after the
upstream-issues fixture family. The manifest pins the source/probe hashes and
exact expected failure codes.

All eleven PDFs pass PDF/A. The control, signed/zero-padded integer values and
qualified simple values pass EA-PDF. URI/array substitutions, padded conformance
text and floating-point version text fail the corresponding identification
requirement. Integer value equality is distinct from canonical lexical spelling;
conformance Text retains exact spelling and semantic type.

Authority: EA-PDF 9.8.1 Table 15 and the reviewed XMP simple-value contract.
Generic RDF syntax and PDF/A requirements remain upstream.
