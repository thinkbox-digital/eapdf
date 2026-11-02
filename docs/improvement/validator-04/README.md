# V04 — previously unmapped file requirements

Authority: `validator_rule_text.json`. The six previously unmapped file records now link to three executed custom checks (221–223). The inventory still has 301 records and 11 open expert/dependency questions. Mapping every record does not certify complete implementation.

- `DPM_CONTAINER_CONTENTSET_TYPES`: Table 8 prohibits AttachmentRendering, BodyRendering, EmailHeaderRendering and RawEmailRendering in c/ci. Other extension types remain allowed; the existing AttachmentList check is retained.
- `ASSOCIATED_FILE_MODDATE`: section 7.5 requires Params ModDate on associated embedded streams. Catalog, reachable object dictionaries/streams (including direct dictionaries), page/Form marked-content associations and all EF stream variants are inspected with traversal bounds. Unassociated embedded files are exempt.
- `EMBEDDED_MEDIATYPE_RECONSTRUCTION`: section 9.4 requires UTF-8 name decoding, a semicolon and optional stream-level PDF text parameters. UTF-16 parameters decode correctly; their case and internal whitespace survive. Misplaced extension keys do not supply parameters and are not independently forbidden.

Existing XMP consistency checks use reconstructed media types. Attachment matching cannot use the media type being checked to choose a record. Ambiguous associations remain unavailable. Bare MIME syntax/presence stays with veraPDF; no embedded-file ModDate predicate was found in any of the five pinned profiles (their CosInfo ModDate checks concern document information).

## Verification

See [evidence.json](evidence.json), [frozen inputs](verification-inputs.json), [authored tests](../../../tests/test_remaining_file_requirements.py), [corpus manifest](../../../tests/fixtures/eapdf/validator-file-requirements/expectations.json), [fixture recipe](../../../scripts/build_eapdf_remaining_file_fixtures.py), and the retained JSON/HTML reports in `corpus/`.

The original partial repair was recovered from thread `01a1522c-7e25-7911-b234-262f2d4423d7`. Its baseline source is retained in `/tmp/eapdf-validator04/before`; `before-corpus.json` records its behavior on the final corpus. Two added alternate-stream assertions failed before the additional repair (`alternate-streams-before.log`).

The initial container recipe independently violated DPart grouping and custom-tag cardinality. `initial-corpus/` preserves that report. The corrected recipe follows the existing untagged-fixture method: removes optional structure connections and marked-content wrappers while preserving painted content, retains FrontMatter, and adds correctly grouped leaf references. The legal extension probe verifies this setup. Runtime Python, authoritative JSON, tests and creator sources remained frozen while the fixture recipe was corrected; the full suite exercises the corrected final corpus.

Original-email fidelity, complete source/asset ownership, broader schema/application checks, mixed-rule fragment routing and standalone ISO/TS 32005 integration remain separate work. Creator/CLI/debug changes and sample regeneration are not part of V04.
