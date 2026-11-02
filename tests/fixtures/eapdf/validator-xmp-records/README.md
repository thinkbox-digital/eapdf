# XMP record schema probes

Regenerate with `env -u PYTHONPATH python3 -B scripts/build_eapdf_xmp_record_fixtures.py`.

The manifest pins source/PDF hashes and exact failures. All ten probes pass PDF/A; the control, absent optional checksum and signed/zero-padded size pass EA-PDF. Other probes expose canonical-property, type or supplied-checksum errors. The corpus does not require checksum presence or MIME lexical validity for preserved raw Content-Type text.

[Verification and limits](../../../../docs/improvement/validator-06/README.md).
