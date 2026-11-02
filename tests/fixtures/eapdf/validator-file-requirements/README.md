# V04 file requirement probes

Regenerate with `env -u PYTHONPATH python3 -B scripts/build_eapdf_remaining_file_fixtures.py`.

`expectations.json` records the authority, control hashes, exact expected failures and PDF hashes. All twelve PDFs pass independent PDF/A. Four pass EA-PDF (two controls, UTF-16 parameters and an allowed extension); eight fail exactly their declared custom rules.

The container probes retain required FrontMatter and add a second Content Set in the correct DParts array grouping. They use lawful optional untagged structure so a custom-tag count violation cannot mask the Table 8 result. The extension probe passes using the same construction.

Reports, pre-fix behavior and limitations: [V04 evidence](../../../../docs/improvement/validator-04/README.md).
