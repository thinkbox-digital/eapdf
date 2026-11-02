# Declaration and upstream structure probes

Reproduce with `python3 scripts/build_eapdf_declaration_fixtures.py` after the
upstream-issues fixture family. `expectations.json` pins source/probe hashes and
complete-pipeline failure codes.

All seven probes pass PDF/A. The canonical control, qualified URI identifier and
reduced-precision claim date pass EA-PDF and the selected upstream ISO rules.
Legacy HTTPS namespace, ordered declarations and an impossible supplied claim
date fail the declaration requirement. A paragraph nested in a paragraph passes
the custom checks but fails the actual upstream ISO inclusion predicate.

The declaration namespace is `http://pdfa.org/declarations/`; the registered
identifier is `https://pdfa.org/declarations#iso32005`. Optional claim fields are
not mandatory. Declaration presence is independent of structural conformance.

Authority: EA-PDF 9.7, PDF Declarations 2019 sections 7/8 and the pinned unchanged
upstream ISO profile selection. These probes demonstrate scoped behavior and
are not a standards certification suite.
