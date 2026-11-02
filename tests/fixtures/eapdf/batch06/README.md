# Batch 06 recommendation assessments

Six assessment scenarios use two creator-derived PDFs: tagged and legally untagged. Both PDFs independently pass PDF/A and mandatory EA-PDF checks. Source/policy recommendations remain visible as unavailable WARN.

Each PDF is checked normally, with a text-extractor exception, and with an incorrectly empty page-analysis result. Faults affect the validator environment; they do not mutate PDF bytes. Expected outcomes are explicit in cases.json. Fault cases must stay INCOMPLETE with explained unverified page recommendations, despite independent PDF/A PASS. The exact same PDF hashes are used across normal/fault runs.

Run scripts/build_eapdf_recommendation_fixtures.py with EAPDF_GENERATOR_CLASSPATH and optional EAPDF_RECOMMENDATION_FIXTURE_DIR. The packaged test archives both PDFs and all six reports before cleanup. Separate focused tests cover zero real pages, partial page context and duplicate/missing analysis entries.
