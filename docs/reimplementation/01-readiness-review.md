# Production readiness review

This is the original pre-repair assessment. See [fixed.md](../../fixed.md) for subsequent verified repairs and [unfixed.md](../../unfixed.md) for the active restart ledger. The PST/MBOX item is clarified below against §7.2's explicit export-before-conversion policy allowance; it is not an unconditional requirement to embed a complete mailbox container after a documented export step.

The latest [repair follow-up](09-repair-follow-up.md) supersedes the implementation defects below, including R8 process/resource controls and the R9 publication, raw-address, packaging and deployment-template changes. [Follow-up evidence](followup-evidence.json) records 124 passing Java tests, 130 passing Python tests and independent PDF/A/schema checks. Complete standards coverage, human/account/platform acceptance and a verified live deployment remain unestablished.

## Verdict

**No: production readiness and complete standards coverage are not established.** The implementation has substantial creator functionality and real preservation safeguards, but accepts inputs that produce lost visual content or EA-PDF-invalid collection names. Its validator can also accept some mandatory-rule violations and reject some permitted behavior. Optional PREMIS output is not valid against the official version 3 schema.

Controlled conversion with retained originals, explicit limits, independent PDF/A checks and human fidelity review is useful. The creator exit code, a PDF/A pass, or this validator's combined PASS is insufficient evidence for unattended archival acceptance.

## Fresh verification

| Check | Result |
| --- | --- |
| Offline Maven package with build directory in `/tmp` | Success; 97 tests, zero failures/errors/skips |
| Default Python suite | 112 tests; 109 pass, 3 generator integrations skipped |
| Python suite with newly packaged generator | 112 pass, zero skips |
| Generated integration documents | 15 top-level documents: six normal profiles, six with enhanced accessibility, three accuracy cases |
| Independent PDF/A checks on those 15 documents | All PASS, PDF/A-4F; collection children also inspected recursively |
| New synthetic probes | Confirm the findings below; results are separate from the passing regression suites |

Build command used `mvn -o -Deapdf.build.directory=/tmp/eapdf-review-20261017/build package`. No repairs were made to make the tests pass. A passing existing suite measures its assertions, not every source-preservation requirement.

The full 112-test run was also repeated in a fresh environment installed from `requirements-web.txt`, with inherited Python user-site paths disabled: all 112 pass, including all 15 independent PDF/A checks. Actual imported versions were pikepdf 10.17.0, pypdf 6.21.0, Flask 3.1.3 and Werkzeug 3.1.9. Container report versions were veraPDF apps 1.31.18, validation-model 1.31.17 and core 1.31.1. This verifies the exercised dependency combination; it is not a vulnerability scan.

## Findings

### R1 — High: image-only HTML bodies lose their images silently

At `src/main/java/eapdf/PdfMailWriter.java:639`, `looksEmpty` tests only Jsoup text (`:3289`). A valid HTML body containing two `data:image/png` images and no text is replaced with plain-text HTML. Data images are not MIME-associated files, so `drawInlineImageFallback` cannot restore them.

The `image_only` probe exits 0, records no conversion warning, and produces **zero image XObjects** for two source images. The local validator reports PASS. The embedded original EML remains intact in profile `s`; its rendered representation loses the entire visual body. In isolated mode, retaining the external original becomes especially important.

Required correction: determine renderability using text and visual elements, preserve image-only bodies, and compare source/rendered visual inventory. Do not consider the header's presence evidence that the body was preserved. The fallback that draws only the first MIME image at a guessed location (`:1384`) also needs explicit fidelity review.

### R2 — High: collection filenames can violate EA-PDF after successful creation

`Common.portablePdfFilename` (`src/main/java/eapdf/Common.java:719`) removes paths but does not enforce the stricter collection filename rules. Non-isolated collections retain original EML sources through `PdfCollectionSupport.embedCollectionFiles`; their names pass through this weaker sanitizer. Child PDF filenames use a different sanitizer.

On Linux, an EML named `colon:source.eml` converts to `1c` with exit 0. The resulting collection includes that filename and fails `COLLECTION_FILENAMES_VALID`, consistent with EA-PDF §9.5 and the referenced PDF collection restrictions. `PdfCreationValidator` checks path portability but does not check all collection constraints before publication.

Required correction: apply the collection naming policy to every file and folder, including sources and auxiliary files; disambiguate after sanitization and Unicode/case normalization; keep original names separately in metadata. Cover all restrictions and reserved cases from ISO 32000-2 rather than copying this validator's partial character check.

### R3 — High: mandatory violations can remain a combined PASS

`validate_eapdf.py:3251` and `:3256` report an incorrect single-message `NonFullScreenPageMode` as WARN. EA-PDF §9.1 specifies this value with a mandatory requirement. `Report.summary` only turns ordinary warnings into INCOMPLETE when the separate `VALIDATION_INCOMPLETE` code is present.

A copy of the plain-text probe with `/NonFullScreenPageMode /UseNone` therefore has no local FAIL findings. A second mutation removes a non-empty Subject from message XMP while leaving the source/header intact; missing Subject is reported as WARN (`check_single_message_metadata`) and the current field-tag path does not check completeness of the required field set. These are acceptance-rule defects, not PDF/A violations.

Both mutated PDFs independently pass PDF/A-4f and receive **combined PASS** from `validate_one`. The five generated probes also independently pass PDF/A-4f, including the collection with the invalid filename and the PDF whose visual body lost both images. This directly demonstrates the limits of the current acceptance oracle.

Required correction: define requirement strength and applicability independently of finding presentation. Every applicable failed SHALL requirement must block acceptance. Test mutations against the exact clause, including missing fields and values, not only malformed Sent.

### R4 — Medium: selected body media-type parameters are discarded

EA-PDF §9.6, Table 8 describes `Mail_Subtype` as the original rendered body's media type and parameters. `EmlReader.extractBodyChoiceInto` records bare `text/plain` or `text/html`; `Common.contentSetSubtype` uses it directly. A body declared `text/plain; charset=UTF-8; format=flowed` produces `/Mail_Subtype /text#2Fplain` with neither parameter.

This is distinct from the attachment Content-Type parameter fixes already present. The validator also reduces source body types to their base types (`validate_eapdf.py:4716`) and its subtype regex (`:5033`) rejects parameterized values. Resolve writer and validator together against Table 8; do not blindly apply the embedded-file bare-Subtype rule to DPM.

### R5 — Medium: PREMIS 3 output fails the official XML schema

With `premis.enabled=true`, generated sidecar XML fails the official [Library of Congress PREMIS 3 schema](https://www.loc.gov/standards/premis/premis.xsd). Validation identifies unexpected `objectCategory` and `eventDetail` elements. Source anchors: `src/main/java/eapdf/PremisBuilder.java:122`, `:148`, `:168`, `:194`. The PDF object also writes an unwrapped profile identifier after `originalName` (`:133`), which needs schema-correct placement.

The schema check uses the generated XML, not a guessed schema rule. PREMIS is optional and is not itself an EA-PDF requirement; this finding affects a claimed preservation feature. Events currently state success before final publication/independent validation. Required correction: generate valid PREMIS 3 structures, validate every enabled output, distinguish conversion from validation outcomes, and record original versus reconstructed source formats truthfully.

### R6 — Medium: enabling PREMIS produces a validator navigation false positive

The `premis` probe has no email attachments. The writer correctly chooses `UseOutlines` based on email attachment count. The validator instead treats every catalog embedded file other than Source as an email attachment (`validate_eapdf.py:2935`, `:3208`), including the generated PREMIS XML. It fails `PROFILE_PAGEMODE_SINGLE_WITH_ATTACHMENTS` and warns about NonFullScreenPageMode.

EA-PDF §9.1 conditions this behavior on **email attachments**. Adding a preservation sidecar inside the PDF is not evidence of a source email attachment. Required correction: use message/attachment metadata and actual provenance, with an explicit unverified state if that evidence is insufficient. Do not change the writer merely to satisfy this heuristic.

### R7 — Medium: missing image descriptions bypass the advertised warning

`canonicalizeEmailHtml` adds `alt=""` to an image lacking alt (`src/main/java/eapdf/PdfMailWriter.java:791`). The later generic-description/warning path (`:501`) runs only when alt is absent, so this path is bypassed. The enhanced-accessibility `image_with_text` probe retains its image but has an empty Figure alternative and no conversion warning.

This is a partial-accessibility feature defect, not proof that PDF/UA is required for all EA-PDF. Preserve the distinction between intentionally decorative images and missing descriptions. Record a review requirement when an informative source image has no description.

### R8 — High operational gap: resource limits do not bound all work

Static review identifies unbounded Java MIME recursion in `EmlReader`, decoding via `getContent`/`readAllBytes` before some checks, full image decoding to inspect dimensions (`PdfMailWriter.sniffImageSize`), and worker shutdown that waits until termination without an overall deadline (`Common.stopWorkers`). A small compressed image can require much more memory than its encoded source size. Java acceptance limits do not establish a pixel, recursion, wall-clock, output-size or temporary-disk budget.

Web subprocess memory/time/file-size restrictions provide additional containment; CLI/GUI callers do not automatically receive that boundary. No destructive decompression-bomb or exhaustion load test was run. Required correction: budget decoded bytes, pixels, MIME depth/parts, pages, output/temp bytes and runtime; use an isolated worker process with termination and cleanup for untrusted inputs. Test cancellation and disk exhaustion safely.

### R9 — Release/coverage gaps requiring explicit decisions

These are verified design boundaries or static observations, not newly reproduced exploits:

- Non-isolated PST/MBOX archives preserve reconstructed/exported EML rather than the original mailbox container. `PstReader`, `MboxParser`, `PayloadStore.stage` and `Common.synthesizeEml` implement this. EA-PDF §7.2 explicitly permits exporting a multi-email source to a singular format before conversion as a writer policy. The follow-up documents this limited export contract and records the derivation in PDF/source descriptions and enabled PREMIS. A checksum of exported EML is still not proof that the original PST/MBOX was retained unchanged; retain the original separately when that is required.
- Tagged PDF 2.0 files declare ISO TS 32005, but the validator checks selected mappings and correspondence, not the complete standard's inclusion rules. No complete independent TS 32005 proof is supplied. PDF/UA identification is deliberately omitted; accessibility remains partial.
- The web application has no application-level authentication or account ownership checks. UUID download URLs act as bearer capabilities. The supplied nginx example listens on HTTP and has no identity/rate-limit configuration. Suitability for private mail requires a concrete authenticated/TLS deployment and retention policy. This is not a claim that any deployed site lacks an external protective proxy.
- The CLI's reuse of an existing destination is intentional and is not classified here as a bug. Multi-file jobs and PDF/sidecar publication are not one transaction; partial success needs operational handling.
- Shading logs report duplicate RTF implementation classes/resources. Both Shade executions merge only manifests; release notice/font/renderer obligations remain open in the repository's licensing notes. No new dependency vulnerability or legal clearance claim is made.
- Missing explicit `EAPDF_CONFIG` paths silently select built-in defaults; built-in `findingAid.excelEnabled=true` differs from the checked-in false value. Effective configuration must be captured for reproducible operation.
- Some web expected-value explanations are wrong (`infer_expected_value`: DPM prefix is described as `/DPM_`, and required subtype as `/DPart`). Source-of-truth clauses and structured expected values need reconciliation.

## Production exit criteria

Resolve R1–R8 with regression/mutation evidence. Decide the PST/MBOX preservation boundary. Establish a clause register for EA-PDF and the selected ISO base, including conditional tagging. Validate the actual release artifact and its children independently; never convert unavailable checks into PASS. Complete representative fidelity, viewer, international-text, failure-recovery, resource-containment and privacy testing. Finish release assets/notices and dependency/security review. Preserve signed-off results with the source/config/tool versions.

The subsequent design documents are an as-built/replacement reference while these gates remain open. They do not turn this verdict into a production approval.
