# Attachment file roles

Regenerate with `python3 -B scripts/build_eapdf_attachment_scope_fixtures.py`.
Expected outcomes come from Table 19's corresponding native payload semantics,
section 7.5's explicit Alternative distinction, and collection child scope.

The c/ci probes change only the collection member's file-specification names to
collide with an attachment's original name. The member's stored PDF bytes,
collection name-tree key, collection metadata and its child's attachment bytes
remain unchanged. The Alternative probe adds a converted representation with a
colliding filename and different bytes/type. All three must PASS complete
validation, including independent PDF/A and recursive child checks.

The manifest records source/output hashes. The before/after complete reports
are retained in `docs/improvement/validator-14`.
