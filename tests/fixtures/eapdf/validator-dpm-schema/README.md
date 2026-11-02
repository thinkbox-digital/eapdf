# DPM schema probes

Regenerate using `env -u PYTHONPATH python3 -B scripts/build_eapdf_dpm_schema_fixtures.py`.

The manifest pins the valid source, six PDFs and exact expected failures. All six pass independent PDF/A. Controls, UTF-16 descriptions and unrelated extension keys pass EA-PDF. Number/name descriptions and invalid UTF-8 Content Set names fail their declared DPM rules. The invalid-name report remains valid UTF-8 HTML/JSON.

[Verification and limits](../../../../docs/improvement/validator-05/README.md).
