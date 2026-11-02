# Specification-driven repair follow-up

Date: 2026-10-17. This updates the original review snapshot. The durable completion record is [fixed.md](../../fixed.md); remaining work and restart instructions are in [unfixed.md](../../unfixed.md). Passing the local validator is not an independent specification oracle.

## Contracts for a replacement

### Rendering and image descriptions

Do not equate an empty text extraction with an empty HTML body. Replaced/image/media elements and background styling can be the entire message. Keep that HTML through sanitization and layout; two distinct image-only inputs must yield both rendered images. Do not recover a rendering failure by painting an arbitrary inline attachment at a guessed position: it might be hidden or unreferenced, and its source layout is unknown. The existing explicitly warned plain-text rendering fallback remains a fidelity exception requiring review.

Preserve the difference between an absent `alt` attribute and explicit `alt=""`. In enhanced accessibility mode, an absent description receives a generic description and a conversion warning, never a claim of meaningful source-specific alternative text. Intentional decorative empty alternatives are not overwritten.

### Source body media types

DPM `Mail_Subtype` is a PDF **name** containing the selected source body's full media type and parameters; it is not the bare MIME subtype required in an embedded-file stream dictionary. Record the type of the actually selected MIME part, including encrypted/unsupported representations. In concatenated mixed bodies, retain the selected HTML representation's type when HTML is present. PST conversion sources are exported as UTF-8 RFC 822; their DPM type must reflect that encoding, including plain-text and original-RTF representations.

Validate syntax as a media type with optional token/quoted parameters. Compare source types semantically: media type and parameter names are case-insensitive; parameter order and quotation differences are immaterial; charset labels are case-insensitive, while other parameter values retain their case. Missing or changed parameters must not pass merely because the base types agree. Equivalent legal folding can be unfolded for comparison.

### Collection names and schema

All collection files, including raw conversion sources, PREMIS and finding aids, use valid bounded names, not just the generated child PDFs. Strip path semantics and forbidden characters, avoid terminal periods and reserve length for collision suffixes. Preserve original source names in descriptive metadata. Generate names uniquely within each collection folder using locale-independent Unicode case normalization; include multi-character case mappings and capital/lowercase sharp-S collisions in acceptance tests. File specification names and name-tree keys must agree on the stored filename.

Treat a `<numeric-id>` prefix as a collection folder encoding only when the creator added it, not when an original source filename happens to resemble it. Root auxiliary/source files cannot choose arbitrary collection folder IDs. The saved-PDF gate rejects invalid names, normalized collisions, cyclic/invalid folder names and editable custom fields. The independent validator applies the noneditable-field rule to **every** custom schema field, not just the required set.

### Mandatory checks and auxiliary files

Incorrect single-message non-fullscreen navigation is a mandatory failure. Determine email-attachment navigation from actual email source/attachment metadata, not the existence of an unrelated embedded PREMIS or index file. A populated source Core Field cannot disappear from structured email metadata and still pass; empty optional source fields remain optional. Do not incorrectly require every optional raw representation in addition to its structured representation.

Creator and validator need different negative tests: alter a valid saved PDF's navigation or remove its populated Subject without damaging PDF/A. Those PDFs still pass independent PDF/A but must fail the EA-PDF checks. A positive creator fixture alone cannot establish validator correctness.

### PREMIS

Use the official PREMIS 3 schema, not XML well-formedness alone. Version-3 file objects do not contain the old `objectCategory` child; event details use `eventDetailInformation`. Do not emit unwrapped object identifier fields in the wrong location. At the root, serialize objects before events and events before agents. When merging child metadata, preserve identifiers and links while placing child objects/events in their respective schema groups, not after the aggregate agent.

Describe the preparation/conversion event truthfully. Metadata assembled before serialization cannot attest final publication or independent conformance validation. Source-format descriptions must reflect the actual preserved/exported asset rather than labeling every source RFC 822 indiscriminately. Check single, multi-message, collection, isolated sidecars, embedded XML, and embedded child PDFs against the official schema.

### Mailbox preprocessing

EA-PDF §7.2 expressly permits exporting a mailbox message to a singular format before EA-PDF conversion as a writer policy. This implementation retains that policy: non-isolated PST/MBOX output preserves exported RFC 822 messages, **not** the unchanged original mailbox. Record the derivation and original path/message mapping in PDF/source descriptions and enabled PREMIS. Retain the original container separately when envelopes, unselected messages, deleted records or storage properties matter. Do not infer original-container fixity from an exported message checksum.

### Operational bounds and configuration

MIME preflight bounds nesting, part count and cumulative decoded leaf bytes; selected body/attachment reads are bounded. Read raster dimensions from image headers before allocating pixels. Local, Gmail and monthly CLI entry points now delegate to `ConversionSupervisor`; GUI-launched conversions use that boundary too. A fresh worker JVM has bounded heap/direct/class/code memory, a whole-conversion deadline, monitored private storage, and Linux address-space/CPU/file-size limits. Aggregate rendered image pixels, pages (including generated front matter), output bytes, font bytes and temporary-file counts are checked. Cancellation terminates and reaps the worker process tree before storage cleanup. Abandoned-workspace reclamation checks process start identities to avoid PID-reuse mistakes.

`OutputTransaction` stages PDFs and sidecars privately, verifies manifest sizes/hashes, budgets publication preparation, and publishes with rollback backups and crash journals. Collection child PDFs inside the worker workspace remain internal files; they must not be redirected as final outputs. Recovery restores interrupted publications and retains committed outputs. Gmail resume hashes read staged bytes. See [conversion operations](../CONVERSION_OPERATIONS.md) for settings and the exact recovery contract.

These are resource controls, not a filesystem/network sandbox or an instantaneous transaction across filesystems. Temporary aggregate bytes are sampled; strict disk reservation requires a filesystem quota. Native vector/codec/font costs are bounded by address space and time rather than exact pixel measurement. Publication on blocked OS storage and independent jobs targeting the same paths require an external watchdog and job serialization. Non-Linux native enforcement and interactive platform behavior remain acceptance requirements.

An explicitly selected missing configuration file is an error. Built-in and checked-in XLSX sidecar defaults now both disable sidecar creation; embedded XLSX remains separately controlled. Preserve CLI arguments and existing ordinary debug behavior.

### Non-SMTP addresses and release operations

EA-PDF §9.8.3 permits a raw representation when a structured type cannot represent the source value. Preserve Exchange/X.500 tokens in the matching raw Core Field; do not invent an SMTP address or insert that token in `foaf:mbox`. Structured entries still retain valid SMTP agents in mixed lists. Acceptance requires exact source/raw agreement; missing or changed evidence remains `INCOMPLETE`, and invalid FOAF mailboxes fail. The real attached-message PST and its seven child PDFs now pass the exercised checks with this policy.

Shade builds GUI before CLI from a regenerated thin JAR, merges provider services and distinct notices/licenses, and retains exact bundled-font notices in both artifacts. Repeat packaging preserves all implementation, notice and service contents; only Maven's generated `pom.properties` build-date comment differs. Remaining license collisions contain identical upstream text. These checks establish packaging behavior, not redistribution/relinking or proprietary-branding authorization.

The private-mail deployment template requires password-hash authentication, an allowed-host list, HTTPS, request rate limits and retention cleanup. Missing authentication configuration fails closed. Application regressions cover route authentication, host checks and cross-origin submissions. This is shared trusted-team access, not per-user file ownership. Installing certificates/services and verifying a real deployment remain external acceptance work.

## Executable acceptance

### Additional validation-rule follow-up

The proposed file-set, collection-content and UTF-8 gaps were confirmed against the local specification. `SOURCE_FILE_SET`, `COLLECTION_CONTENT_TYPES` and `XMP_UTF8` are atomic registered rules. Rule 96 already enforced exactly one array element; its rule text and diagnostics now state this explicitly, with matching creator/validator regressions. All XMP streams must use UTF-8, including PDF/A-3; successful XML parsing alone does not establish encoding conformance.

File sets are conditional. RF is a dictionary whose keys must also occur in EF, and whose values are arrays of filename/embedded-stream pairs. Alternatively, required companion files use catalog AFRelationship Supplement. Source asset XMP size/type/checksum evidence distinguishes those companions from ordinary supplements. A supplied original file-set inventory detects wholly missing companions; independent Source assets do not imply a set. Current readers ingest monolithic EML/MSG and exported PST/MBOX/Gmail messages. The normalized creator model now supports explicitly marked `AssociatedFile.sourceSetMember` companions and refuses to silently omit them from non-isolated output; this does not add a new public input format or CLI switch.

EA-PDF §7.6 provides no exception for auxiliary files in collections. Collection front matter remains paginated content. Container-level PREMIS remains a sidecar. The later requested nesting change embeds an actual finding-aid EA-PDF at the collection root, with the XLSX as an attachment inside it. This is a PDF/mail-1s rendering of an explicitly labelled, software-generated RFC 822 index message, not original archived correspondence; its source is preserved for truthful standalone EA-PDF validation. `embedExcel` and `excelEnabled` both imply the containing PDF and place the XLSX inside it for collections, while external XLSX is available only for flat archives, as documented in [configuration](../../config/README.md). Ordinary archive behavior and embedded PREMIS in non-isolated email children are preserved. Sample regeneration publishes only the six primary examples and keeps collection finding-aid PDFs/XLSX embedded; the earlier extracted-copy policy is superseded.

- Java: `SpecRepairTests`, `ProcessingLimitRepairTests`, `SupervisedConversionTests`, and the updated creator/content regressions.
- Python: `test_spec_repairs.py`, `test_spec_repair_integration.py`, `test_supervised_conversion.py` and `test_followup_security.py`, plus the existing suites.
- Set `EAPDF_GENERATOR_CLASSPATH` to the packaged JAR to enable generator checks.
- Set `EAPDF_PREMIS_SCHEMA` to the official PREMIS 3 XSD and install `lxml` in the test environment to enable schema checks. This is an external acceptance dependency, not a new runtime dependency.
- Set `EAPDF_INTEGRATION_REPORT_DIR` to retain reports; unavailable independent checks must not be reclassified as PASS.
- Build with `-Deapdf.build.directory=/tmp/...` to keep `target/` untouched. The original review probe runner remains observational; the integration test asserts the expected positive and negative outcomes.

The resumed acceptance run passes 124 Java tests and 130 Python tests with no skips, 30 top-level and 24 contained PDF/A checks, and 11 PREMIS schema checks. Two deliberately mutated PDFs correctly fail EA-PDF rules while retaining PDF/A conformance. Durable results and artifact hashes are in [followup-evidence.json](followup-evidence.json).

The remaining complete ISO/TS 32005 audit, human fidelity/accessibility, live Gmail/GUI, target-platform, deployed-security and release-rights gates remain explicit. This follow-up is an implementation/handover delta, not certification.

The subsequent additional-rule acceptance supersedes the counts above: **129 Java** and **139 Python** tests pass without skips. All six primary samples and the existing single-message alias were regenerated; requested collection auxiliaries are sidecars. The suite, samples and source-set probes retain **42 top-level PDF/A**, **29 child PDF/A** and **10 PREMIS schema** passes; four deliberate mutations fail their intended EA-PDF rules. Original inventory distinguishes omitted companions from independent sources. See [additional-rules-evidence.json](additional-rules-evidence.json) for hashes and the Supplement/RF/missing-companion probes. The large PST sample also exposed and regressed a Python/Java disagreement about apostrophe-wrapped SMTP domains; exact raw preservation remains required.


### Later requested finding-aid nesting — current checkpoint

The nesting contract above supersedes the earlier collection-auxiliary sidecar behavior. Fresh **130 Java/140 Python tests** pass with no skips; all six supported primary samples, four exact extracted PDF/XLSX examples and the existing single-message alias were regenerated. Combined acceptance/sample evidence: **36 top-level PDF/A**, **32 child PDF/A**, **10 PREMIS XSD** passes, **34 overall PASS/two intentional mutation FAILs**, zero INCOMPLETE. See [nested-finding-aid-evidence.json](nested-finding-aid-evidence.json) and [DEVELOPMENT_STATUS.md](../DEVELOPMENT_STATUS.md) for current hashes, settings, generated-source rationale and remaining external acceptance.


### R12 correction — collection finding aid/index embedded only

Removed the four separate PDF/XLSX auxiliary examples and their extraction/publication logic. For collections, `excelEnabled` now embeds the XLSX in the root finding-aid EA-PDF and never writes an external index; it also implies the containing PDF if other embedding flags are off. Stale collection sample auxiliary paths are removed after all candidates validate. Fresh Maven 131-test package, four targeted packaged Python tests, six primary PDF/A/EA-PDF sample checks and four child checks PASS. Current evidence: [embedded-only-index-evidence.json](embedded-only-index-evidence.json). Previous extracted-copy snapshots remain historical.
