# EA-PDF Creator Coverage And Accuracy Review

Review date: 2026-10-16. Reviewed working-tree release: `1.4.0`, after the
PDFBox 3 / dependency upgrade. This is an engineering assessment, not EA-PDF,
PDF/A, ISO TS 32005, or PDF/UA certification. The initial findings below are
retained as review evidence; the targeted implementation follow-up is recorded
in the next section.

## Implementation Follow-Up

The reproduced date, attachment, MIME/body-type, filename, CSS visibility, and
validator false-positive defects have targeted fixes and regression coverage:

- Creation rejects messages without a verifiable sent date and preserves an
  existing destination. Recoverable invalid dates keep `Raw-Sent`, normalized
  `Sent`, and a warning; no conversion-time sent date is invented.
- Non-isolated body attachment annotations now embed the actual native bytes.
  Catalog attachment listings retain their source/finding-aid policy. Isolated
  placeholders have stored size zero and no misleading original-payload XMP
  checksum; their descriptions retain original size/checksum provenance.
- Embedded-file parameter checksums now store the actual 16 MD5 bytes, not
  32 bytes of hexadecimal text. XMP retains the required hexadecimal encoding.
- Readers retain original attachment media parameters, including through
  reconstructed EML export. Body DPM retains the selected source body type.
  File specifications and name-tree keys use portable names; original source
  and attachment names remain available in metadata and source bytes.
- Supported stylesheets are scoped to the email subtree and rendered from the
  document head. Hidden-content rules no longer disappear silently. Unsupported
  CSS, unsafe-to-scope selectors and external stylesheet omissions warn; this
  is a print-oriented subset, not universal browser-quality rendering.
- A Java consistency check validates staged PDFs before atomic replacement,
  including required dates, profile cardinality, DPart presence, declarations,
  filenames, and native attachment metadata/visibility. This is deliberately
  narrower than independent PDF/A or complete EA-PDF validation.
- The shared CLI/web validator checks annotation-only file specifications,
  binary MD5 representation, and source/body media-type agreement, in addition
  to native annotation payloads, full MIME parameters and the actual ISO TS
  32005 RDF declaration. Header field tags now identify actual PDF text by
  default; the validator follows nested MCID references. Older English headers
  have an XMP-consistent text fallback, while unfamiliar layouts remain
  `INCOMPLETE` rather than requiring optional tags for conformance.
- Valid RFC 1123 GMT dates no longer generate unnecessary `Raw-Sent`. PST
  rendered senders agree with transport-header metadata, MAPI subject padding
  is removed, and original RTF bodies survive reconstructed EML export.
  Multi-page outputs have visible page context, and body URLs have annotations
  without silently rewriting original HTTP targets to HTTPS.

The original assessment and evidence below describe the pre-fix working tree,
not the current behavior. Difficult-email visual fidelity, complete ISO TS
32005/accessibility verification, unfamiliar-layout Core Field checks, viewer-level
interoperability and the separately documented release/licensing work remain
outside these targeted repairs. The bottom-line warning against universal or
independently certified archival-conformance claims still applies.

### Follow-Up Verification

Maven packaging runs 96 Java tests, including fifteen focused creator-accuracy
regressions and the existing six-profile/real-PST coverage. The Python suite
runs 110 tests with all three packaged-generator integrations enabled. Fresh
outputs exercise all six profiles with default and enhanced accessibility,
plus three targeted accuracy cases (15 top-level PDFs); recursive collection
validation also checks contained messages. All exercised PDF/A checks pass and
there are no local mandatory-check failures. The saved real-PST non-isolated
sample remains `INCOMPLETE` for malformed original address tokens, not the
previous generated-header text gap. Isolated zero-byte policy omissions remain
explicit warnings; no original attachment bytes are invented to remove them.

Reproduce with the build and integration commands in `README.md`. This run uses
`/tmp/eapdf-validator-update/` for Java artifacts and
`/tmp/eapdf-validator-final-integration/` for logs and JSON/HTML evidence; those
paths are temporary, not durable release artifacts. The six current saved
email examples are separately regenerated and independently validated.
`target/` and project debug directories remain untouched. The complete saved
PDF inventory, including failing historical/imported files, is recorded in
`docs/VALIDATION_RESULTS.md`; those results are not universal conformance claims.

## Overall Assessment

The project is a substantial, usable EA-PDF creator implementation, not merely
an email-to-PDF printer with a metadata label. It implements all six EA-PDF
profiles, actual document-part metadata, profile-specific navigation, collection
schemas/folders, source-file embedding, structured email XMP, and optional richer
semantic tagging. Its Java package can create PDFs outside the source checkout.

However, it is **not yet a reliably conforming creator for arbitrary accepted
email inputs**, and successful conversion does not establish either EA-PDF
conformance or faithful rendering. New probes reproduced outputs that pass
independent PDF/A validation but fail EA-PDF requirements. Additional semantic
inconsistencies escape the project's validator. This needs more than the one
accessibility item currently listed in `unfixed.md`.

The strongest current use case is controlled conversion of well-formed email,
with original inputs retained and a separate acceptance/validation process.
Unattended archival acceptance solely on the creator's exit code or the local
validator's PASS is not recommended.

## Reference And Evidence Scope

- Primary reference: [EA-PDF 1.0, February 2025](reference/EA-PDF-v1.pdf), focusing
  on normative sections 8–9 and the corresponding preservation/rendering
  explanations in sections 3 and 7. Page numbers below are the printed pages.
- [Validation requirements.docx](reference/Validation%20requirements.docx) is a
  useful checklist, not a substitute for the specification or a clause-by-clause
  proof of its coverage.
- The implementation emits PDF 2.0 / PDF/A-4f, so the relevant supplied base
  references are PDF/A-4 and ISO 32000-2. PDF/A-3 output is not implemented simply
  because several methods retain `PdfA3` in their names.
- The supplied accessibility standard is PDF/UA-1. This review does not treat it
  as a PDF/UA-2 certification reference for the PDF 2.0 output. EA-PDF does not
  generally require PDF/UA conformance. Its conditional PDF 2.0 tagging
  requirement is ISO TS 32005 plus the corresponding declaration (§9.7).
- Inspected the Java entry points, readers, data model, PDF/XMP/collection/semantic
  writers, configuration, Python validator, and existing regression/integration
  tests. This is not a manual re-audit of every underlying ISO PDF requirement.
- Existing upgrade evidence: 81 Java tests pass; the 90-test Python suite ran
  with the two opt-in integration tests initially skipped, and those two tests
  then passed separately across all six profiles with and without enhanced
  accessibility. The latter includes independent PDF/A checks. These are tested
  cases, not a coverage percentage or proof over all possible emails.
- This review adds five targeted email probes, a CSS fidelity probe, direct PDF
  inspection, and an out-of-checkout execution test. Evidence is retained under
  `/tmp/eapdf-creator-review/`; generation scripts and validation logs are also
  under `/tmp/eapdf_creator_review.py`, `/tmp/eapdf_creator_revalidate.py`,
  `/tmp/eapdf-creator-revalidate.log`, and `/tmp/eapdf-style-review.png`.
  These temporary files are not durable release artifacts.

## Coverage Matrix

“Substantial” means a real implementation and useful test evidence, not that
every requirement in that section has been independently verified.

| Area | Assessment | Implementation / important qualification |
| --- | --- | --- |
| Six EA-PDF profiles, §3.2 | Substantial | `s`, `si`, `m`, `mi`, `c`, and `ci` are implemented and exercised. Multi-message cardinality and distinct internal identities are checked. |
| PDF/A base, §9.1 | Substantial for PDF/A-4f | PDF 2.0 header/catalog, PDF/A-4f XMP, embedded fonts, ICC output intent, and removal of document Info. All five new probes pass veraPDF PDF/A-4f, including the three that fail local EA-PDF checks. No integrated creator acceptance gate. |
| Catalog and navigation, §9.1 | Substantial | Profile-dependent PageMode/NonFullScreenPageMode, DisplayDocTitle, outlines, and catalog AF are implemented. Interoperability across actual viewers still needs acceptance testing. |
| Content sets and document parts, §§9.2, 9.6 | Substantial structure; semantic gap | Real indirect DPart objects, parent links, per-page membership, page ranges, front matter, and first-page outlines. Body media types can describe synthesized HTML rather than the original selected body. |
| Searchable text and rendering, §9.3 / §7.4 | Partial fidelity | Text objects, MIME body selection, CID assets, fallback fonts, and explicit fallback notices. CSS removal, complex layouts/scripts, missing glyphs and external-resource policy limit faithful visual reproduction. |
| Source preservation, §§7.2, 9.4 | Strongest for EML/MSG; policy-dependent for mailbox sources | EML/MSG source bytes are retained. MBOX is split into exported messages; PST messages are reconstructed as EML. A rendered mailbox export is not a bit-faithful retained PST/MBOX container. |
| Embedded file metadata, §9.4 | Partial | Source files, checksums, descriptions, sizes and modification dates are written. A source filename containing a backslash produces a forbidden PDF file specification. MIME parameters are not retained in the attachment model. |
| Attachment representation, §§7.5, 9.3, 9.8.4 | Significant qualification | Native attachments are intentionally not separately embedded. Non-isolated annotations open the containing source email; isolated annotations use zero-byte placeholders. This differs from the specification's direct attachment model, and isolated XMP sizes disagree with stored payload sizes. |
| Collections, §9.5 | Substantial | Core schema fields/types, collection dictionaries, hierarchical folder data, case-normalized naming, contained EA-PDFs, and front matter are implemented. Child outputs and extraction/navigation need independent viewer-level acceptance tests. |
| Logical structure, §9.7 | Substantial plumbing; conditional conformance unproven | Custom roles, namespace mappings, DPM/XMP identities, marked content and ParentTree links exist. Enhanced mode retains more HTML semantics and annotation ownership. Default coarse tagging and enhanced semantics are not equivalent to a full semantic or accessibility audit. |
| Identification and assets XMP, §§9.8.1–9.8.2 | Substantial | Required namespace prefixes, version `1`, revision `2024`, profile, creation metadata, asset metadata and checksums. Revision `2024` is specified even though the specification publication is February 2025. |
| Header XMP, §9.8.3 | Partial; confirmed defects | Structured agents, raw address fields, identifiers and reply metadata are implemented. Missing Date and invalid calendar dates can produce invalid EA-PDF metadata while conversion exits successfully. |
| Attachment XMP, §9.8.4 | Partial; confirmed inconsistencies | Descriptions and email identity linkage exist, but raw Content-Type parameters are discarded and zero-byte placeholders retain the original nonzero size in XMP. |
| Standalone creator operation | Working with Java 21 | Shaded Java dependencies, bundled font/ICC/branding, CLI and GUI entry points. A fresh CLI process successfully generated outside the checkout without explicit font/ICC/config paths. Validation is a separate Python/Docker workflow, not bundled acceptance logic. |
| Complete EA-PDF-aware reader/processor, §8 | Not supplied by this product | This is principally a creator plus validator, not a full viewer implementing every extraction, rendering, interaction and read-only UX function described in §8. Do not equate creator coverage with complete processor coverage. |

## Reproduced Findings

### 1. High: Date handling can produce invalid EA-PDF with a success exit

Specification: §9.8.3, Table 18, printed pages 87–88. `Sent` is required. An
invalid source date needs its original value retained as `Raw-Sent`.

Two fresh inputs reproduced separate failures:

- No Date header: the creator exits `0` and writes a PDF without XMP `Sent`.
  Local validation fails `MESSAGE_SENT_XMP`.
- `Date: Tue, 31 Feb 2026 12:00:00 +0000`: the creator exits `0`, emits a
  normalized March 3 timestamp, but omits `Raw-Sent`. Local validation fails
  `MESSAGE_RAW_SENT_REQUIRED_IF_INVALID`.

Both outputs independently pass PDF/A-4f. This demonstrates why PDF/A success is
not equivalent to EA-PDF success.

Code: `src/main/java/eapdf/EmlReader.java:34`,
`src/main/java/eapdf/PdfMailWriter.java:1814`,
`src/main/java/eapdf/PdfMailWriter.java:2061`.

Required direction: validate the raw date separately from the lenient parsed
date; retain raw invalid values and record any normalization. Establish a
truthful missing-date policy, including rejection/quarantine where necessary,
rather than silently inventing a sent date from the conversion time.

### 2. High: Omitted-attachment metadata does not describe the stored bytes

Specification: §9.8.4, Table 19, printed pages 89–90. Attachment `SizeInBytes`
describes the attachment as stored in the EA-PDF embedded stream; the text
explicitly allows zero-length policy omissions.

For a seven-byte `original.txt` attachment converted to `1si`:

- XMP: `Filename=original.txt`, `SizeInBytes=7`, original MD5 checksum.
- File Attachment annotation: `original.txt`, actual embedded bytes `0`,
  embedded-file size `0`, checksum of the empty payload.

The output passes PDF/A and the local validator reports `INCOMPLETE`, not an
attachment-size failure. The validator deliberately exempts omitted payloads
from size/checksum comparison. Omission itself is permitted; inconsistent
metadata is the issue. Preserve original-payload evidence separately from the
metadata describing the stored representation.

Code: `src/main/java/eapdf/PdfMailWriter.java:1845`,
`src/main/java/eapdf/PdfMailWriter.java:2597`,
`validate_eapdf.py:2272`.

### 3. High-risk interpretation: source-email links substitute for native attachments

Specification: §§7.5 and 9.3, printed pages 43–45 and 65, describe attachment file
specifications/streams and visible File Attachment annotations for each email
attachment. Table 19 describes those attachment representations.

The current non-isolated policy deliberately embeds only source emails and
enabled finding aids. Its attachment references ultimately point to the source
email's file specification, not a file specification for `original.txt`.
The original native bytes remain recoverable inside the source email, which is
valuable, but that is not the direct attachment representation described by the
specification or the same extraction experience in a generic PDF reader.

The local validator explicitly accepts this substitution by parsing the source
email. The seven-byte attachment probe receives local `PASS` plus PDF/A `PASS`
even though its File Attachment annotation opens `message.eml`.

Treat this as a specification-alignment decision requiring independent review,
not as conformance proved by the project's PASS. Align a strict creation mode
with the attachment/placeholder model or obtain explicit independent support
for the substitution. Do not silently change the established attachment-panel
policy as part of an unrelated fix.

Code: `src/main/java/eapdf/PdfMailWriter.java:345`,
`src/main/java/eapdf/PdfMailWriter.java:2551`,
`validate_eapdf.py:4908`.

### 4. Medium: original MIME semantics are lost

Two related observations:

- The source attachment header `text/plain; charset=iso-8859-1; format=flowed`
  becomes XMP `Content-Type=text/plain`. The attachment model only retains the
  base type. Table 19 calls for the original attachment's media-type string,
  including its parameters. The writer also has no `Mail_MediaTypeParameters`
  emission path for separately represented attachment streams.
- Plain-text body probes receive DPM `/Mail_Subtype /text/html`, because the
  reader creates an HTML rendering intermediary and `contentSetSubtype()` tests
  whether that intermediary exists. Table 8 describes the email body's source
  media type, not merely the renderer's interchange format. This is a semantic
  accuracy gap even when DPM, structure and generated metadata agree internally.

The non-isolated attachment probe still passes the local validator, whose
source-attachment comparison reduces media types to their base type.

Code: `src/main/java/eapdf/EmlReader.java:291`,
`src/main/java/eapdf/EmlReader.java:311`,
`src/main/java/eapdf/Common.java:1174`,
`src/main/java/eapdf/PdfDocumentParts.java:91`,
`validate_eapdf.py:2324`.

### 5. Medium: source filenames are not always made into legal PDF file specifications

Specification: §9.4, printed page 66. PDF file specification strings must not
contain prohibited path components/backslashes.

A valid Linux input filename `bad\name.eml` is copied into the embedded file
specification unchanged. Creation exits `0`; PDF/A passes, but local validation
fails `EMBEDDED_FILESPEC_NO_PATH_COMPONENTS`.

Retain the original filename in source metadata, but derive a separately
sanitized portable PDF filename. These are different data fields.

Code: `src/main/java/eapdf/EmlReader.java:53`,
`src/main/java/eapdf/Common.java:696`,
`src/main/java/eapdf/PdfMailWriter.java:2423`.

### 6. High for visual accuracy: HTML transformation can silently change visible content

`canonicalizeEmailHtml()` removes every `style` element and stylesheet link,
including safe, local CSS that can control meaningful appearance and visibility.

A fresh email containing `.hidden { display: none; }` and a paragraph using
that class was converted. The supposedly hidden text was visibly printed in
the resulting PDF. A PNG rendering was inspected, not just extracted text.
The creator nevertheless reported `conversion warnings: 0`.

This does not by itself prove a PDF-format conformance failure: the raw source
is retained in non-isolated output. It does disprove a broad claim of faithful
visual reproduction. Existing fallback warnings only cover render exceptions,
not all lossy preprocessing that occurs before rendering.

Code: `src/main/java/eapdf/PdfMailWriter.java:738`,
`src/main/java/eapdf/PdfMailWriter.java:747`,
`src/main/java/eapdf/PdfMailWriter.java:493`.

## Preservation And Fidelity Qualifications

- **EML:** strongest bit-preservation path. Original bytes are embedded and
  checksummed. Their presence does not prove that displayed headers/body match
  them; the new date and CSS cases demonstrate the distinction.
- **MSG:** retains the native source bytes. Nested message reconstruction and
  inherited named-property handling have regression coverage, but the local
  validator cannot independently reconstruct every MSG/MAPI semantic.
- **MBOX:** messages are exported from the container using variant-aware parsing.
  Exported per-message data is preserved; the original mailbox envelope,
  escaping and whole-container bytes are not preserved as a single raw asset.
- **PST:** the reader reconstructs RFC 822 messages rather than embedding the
  entire original PST. Custom MAPI properties and original container-level
  evidence are not thereby preserved. Default profile selection also treats
  PST differently from native EML/MSG. Do not promise bit-faithful PST retention.
- **Important qualification:** §7.2, printed pages 34–35, expressly permits
  exporting a multi-email format to EML before creating EA-PDF. Exporting rather
  than embedding the whole PST/MBOX is therefore not automatically a format
  violation. It is a preservation-policy/provenance decision that must be made
  visible to the operator. PREMIS is optional and disabled by default.
- **Isolated profiles:** intentional source/attachment loss is not inherently
  nonconformance. Distinguish permission to omit data from accuracy of the
  metadata describing what was actually stored.
- **Rendering:** blocking network/local-file loads is a useful security policy,
  but remote images, CSS-dependent layouts, unusual MIME, complex scripts and
  missing glyphs need explicit policy and review. Current fallback fonts improve
  coverage; they do not establish universal language/layout fidelity.
- **Gmail:** retry/resume/ordering/checksum behavior has mock-based coverage.
  This review did not exercise a live Gmail account or OAuth consent flow.

## Validator Accuracy And Independence

The validator is valuable. It checks actual serialized objects, recursively
checks collection children, checks two-way structure ownership and performs
independent veraPDF PDF/A validation. It does not convert a skipped PDF/A run
into a PASS.

Nevertheless, it is not an independent EA-PDF oracle:

1. Its annotation acceptance rules incorporate this creator's source-link policy.
2. It exempts zero-byte placeholders from stored size/checksum comparisons.
3. MIME comparison can ignore the parameters the specification preserves.
4. `has_ts32005_declaration()` checks only whether the XMP bytes contain `32005`.
   A description saying `No ISO 32005 conformance is declared` returns true.
   This neither validates the declaration's RDF structure nor proves ISO TS
   32005 tag-inclusion conformance. See `validate_eapdf.py:4590`.
5. Core-field text checks become N/A when custom field-name/value marked-content
   sequences are absent. This is not evidence that every rendered Core Field
   satisfies §9.3. See `validate_eapdf.py:4857`.
6. Source-fidelity checks and normative file conformance are different questions.
   The specification intentionally makes mandatory technical validation possible
   from PDF/XMP, without parsing original sources (§9 introduction). An inability
   to reconstruct MSG/PST, or a permitted omission policy, should not be confused
   with proof of a mandatory format failure; conversely source recoverability
   should not excuse incorrect PDF/XMP representations.

The existing coverage matrix's “Implemented” labels should be read as “a check
exists,” not “the whole requirement is exhaustively enforced.” A reliable
percentage cannot be assigned without a clause-level positive/negative test
matrix and independent results.

## Targeted Probe Results

All five rows below were checked with veraPDF from a temporary directory under
the checkout, then the temporary directory was removed. An initial `/tmp` bind
attempt was inaccessible to the installed Snap Docker and produced validator
execution errors; those were environmental errors, not PDF conformance results.
The table uses the completed rerun, not those initial errors.

| Fresh probe | Creator exit | Independent PDF/A | Local EA-PDF result | What it demonstrates |
| --- | --- | --- | --- | --- |
| Valid dated EML, seven-byte attachment with MIME parameters | 0 | PASS | PASS | Happy path, but raw MIME parameters/direct attachment representation are not independently enforced. |
| Same message, isolated output | 0 | PASS | INCOMPLETE | Stored zero-byte attachment disagrees with nonzero XMP size. |
| EML without Date | 0 | PASS | FAIL | Missing required XMP Sent. |
| EML dated February 31 | 0 | PASS | FAIL | Lenient normalization without required Raw-Sent. |
| EML source filename containing backslash | 0 | PASS | FAIL | Illegal embedded file specification. |

Additional checks: out-of-checkout Java generation succeeds; CSS-hidden text
becomes visible with no warning; the declaration helper accepts a non-declaration
containing the substring `32005`.

## Priorities Toward A Dependable Standalone Creator

1. **Correct representations first:** fix date handling, stored-versus-original
   attachment metadata, original MIME/parameters, selected body type, and portable
   PDF filenames. Add each reproduced case to the regression corpus.
2. **Resolve the attachment-policy interpretation:** establish a strict,
   independently defensible mapping to §§7.5/9.3 and Table 19. Keep any access-copy
   or source-only convenience policy explicitly distinguishable.
3. **Add a creation acceptance boundary:** validate staged outputs before treating
   them as conformant completed artifacts. Preserve atomic replacement and make
   inability to validate distinct from success. The current Java CLI does not
   invoke the separate validator before reporting a completed conversion.
4. **Separate three outcomes:** PDF/A validity, EA-PDF normative conformance, and
   source/rendering fidelity. Add a fourth, independent accessibility result when
   requested. A single green status must not imply all four.
5. **Make fidelity policy explicit:** safe CSS handling, body selection, remote
   assets, unsupported/encrypted content, font coverage, normalization, omission
   and PST/MBOX export decisions should be recorded as conversion evidence.
6. **Build a normative acceptance corpus:** each applicable SHALL gets generator
   locations, positive and negative fixtures, expected outcomes and independent
   evidence. Include declarations, malformed/unknown dates, placeholder metadata,
   MIME parameters, duplicate identifiers, nested mail, Unicode names, mixed
   namespaces, multilingual/RTL text and complex HTML. Keep true SHOULD warnings
   distinct from mandatory failures.
7. **Test real usability and deployment:** inspect attachment extraction, native
   and reconstructed source recovery, collection folders, bookmarks, text search,
   and reading order across independent PDF viewers. Keep the existing bounded
   processing, private staging, recovery and atomic-write work.
8. **Finish release packaging:** font/dependency notices, LGPL replacement/source
   arrangements, provenance and a documented validation runtime are still
   separate release-readiness requirements. See the third-party license audit.

## Bottom Line

The architecture and core PDF feature coverage are strong enough to build on.
The remaining work is primarily **semantic correctness, explicit preservation
policy, trustworthy acceptance checks, and difficult-email fidelity**, not adding
another collection of basic PDF features. The creator is usable today under a
controlled, reviewed workflow, but should not yet be advertised as an automatic,
universally accurate or independently certified archival creator.
