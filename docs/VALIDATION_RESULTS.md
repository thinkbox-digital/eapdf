# Saved PDF validation results

**C09 accepted — 2026-10-22:** Originals with direct valid UTF-8 top-level headers retain exact bytes under `message/global`; ASCII encoded words and body-only UTF-8 keep their prior label. **204 Java tests and eighteen ordinary/international profiles pass** unchanged V85 EA-PDF, PDF/A and verified ISO checks. Nested-only/reconstructed source classification remains open. [Evidence](improvement/creator-09/README.md). C10 repairs escaped CID image lookup and literal Content-ID export; continue creator work.

**C08 accepted — 2026-10-22:** Malformed MIME text recovery emits a diagnostic while preserving decoded recovery, valid charset behavior and original bytes. **199 Java tests and eighteen ordinary/decoding profiles pass** unchanged V85 EA-PDF, PDF/A and verified ISO checks. [Evidence](improvement/creator-08/README.md). C09 is reviewing media labels for originals with direct UTF-8 headers; continuous creator work remains active and validator development stays stopped.

**C07 accepted — 2026-10-22:** Flowed plain-text display interprets soft breaks, DelSp, quotes and stuffing while retaining exact original bytes and MIME parameters. **194 Java tests and eighteen ordinary/flowed profile checks pass** unchanged V85 EA-PDF, PDF/A and verified ISO checks. Receiver reflow is optional under RFC 3676. [Evidence](improvement/creator-07/README.md). Continue creator work with malformed-text decoding diagnostics; validator development stays stopped at V85.

## C06 native MSG address probes — 2026-10-22

Reopened PDFs and children preserve supplied raw-address text, show known recipient mailboxes and retain exact MSG source bytes. All six native profiles pass PDF/A/ISO without mandatory failures; isolated profiles PASS EA-PDF and non-isolated MSG source comparisons remain INCOMPLETE. Twelve ordinary profiles pass. Existing saved samples retain their preceding bytes. [Evidence](improvement/creator-06/README.md).

## C05 native MSG body probes — 2026-10-22

The six native MSG profiles select the declared plain/RTF/HTML bodies, preserve exact source bytes where retained, and pass PDF/A/ISO with no mandatory failures. Isolated profiles PASS EA-PDF; non-isolated profiles retain V85 unsupported-source INCOMPLETE. Twelve ordinary normal/accessibility profiles pass the established checks. Saved examples are unchanged. [Evidence](improvement/creator-05/README.md).

## C04 attached-message probes — 2026-10-22

Twelve ordinary normal/accessibility profiles and six encoded attached-message profiles pass unchanged V85 EA-PDF, actual PDF/A and the verified ISO profile. Reopened annotations retain exact decoded native payloads or intentional isolated placeholders, original source bytes are exact, and parent rendering excludes attached content and false notices. Saved examples retain their preceding bytes. [Evidence](improvement/creator-04/README.md).

## C03 international header probes — 2026-10-22

Twelve ordinary normal/accessibility profiles and six international-message profiles pass unchanged V85 EA-PDF, actual PDF/A and the verified ISO profile after the creator header-decoding repair. XMP and extracted PDF text retain the correct Subject/participant names; non-isolated source bytes are exact. Ten ordinary collection children and four configured PDF/XLSX pairs pass. These retained probes do not replace the saved C02/C01 examples. [Evidence](improvement/creator-03/README.md).

## Current PST samples after C02 — 2026-10-22

`sample_1m.pdf` and `sample_1mi.pdf` are refreshed with the PST fallback repair. Both pass PDF/A and the verified ISO profile, with zero mandatory failures. The 130 unavailable Cc/Bcc comparisons caused by fictitious NUL-valued headers are eliminated. All 71 messages preserve their other compared headers and decoded MIME bodies/attachments. Both archives remain EA-PDF INCOMPLETE for V85 structure traversal limits; 1mi also retains unavailable native attachment consistency. Other saved examples and sidecars retain their preceding bytes and results. Fresh acceptance: 167 Java tests, twelve normal/accessibility profiles, ten collection children and four collection PDF/XLSX sidecar pairs pass. [Exact evidence and hashes](improvement/creator-02/README.md).

## Current saved samples — 2026-10-22

All six primary samples are regenerated with the repaired C01 creator. All pass actual PDF/A and the verified ISO profile, with zero mandatory failures. V85 EA-PDF returns PASS for 1s, 1si, 1c and 1ci, including both collection children. The 71-message PST 1m and 1mi samples remain INCOMPLETE: structure ownership exceeds the validator traversal budget; 1m also has unavailable original Cc/Bcc normalization, and 1mi has unavailable native attachment consistency. These results supersede earlier saved-sample statuses below.

Both collection finding-aid PDFs pass PDF/A; both XLSX sidecars contain workbooks. Archive m/mi embedded indexes and the identical `output/sample_1s.pdf` alias are retained. A fresh isolated Maven package passes 165 Java tests. Validator code/rules and the standard sample helper are unchanged. [Exact published hashes, reports and reproduction](improvement/creator-samples-c01/README.md). Sample regeneration is complete; full archive acceptance remains open.

## Current B12 verification — 2026-10-18

**B12 — normalized input reconciliation verified (2026-10-18):** Current single-message, archive and collection writers freeze an immutable inventory before rendering and reconcile reopened final bytes. SHA-256/length come from input streams. GUID, attachment-owner and occurrence checks reject missing messages/assets, updated wrong payload checksums, swaps, reused annotations and missing named children. Isolated source omissions remain valid and native placeholders retain their occurrence checks.

Verification: **152 Java tests pass; 249 Python tests run, 248 pass and one optional PREMIS schema skip**, no failures/errors, in 587.656 seconds. Twelve normal/accessibility profile reports PASS including children. All 25 new corpus candidates and 21 child checks PASS PDF/A: nine inventory accepts and sixteen expected rejects. Nine rejected candidates still PASS PDF-only EA-PDF; seven fail their exact declared PDF-only rules. Six saved samples and collection sidecars were regenerated and verified.

Next: original reader/input enumeration and reconstruction provenance, followed by complete independent staged-output/sidecar acceptance. The captured boundary begins at writer entry; it cannot discover reader or dispatch losses before that point. Broader source/per-occurrence XMP associations, legacy helper integration, arbitrary mixed-profile imports and full standards coverage remain open. Thirty-one scoped register records and the unchanged 72-site N/A inventory remain partial. Earlier checkpoints are historical.

[Exact reports and hashes](improvement/batch-12-latest-evidence.json).

## Current B11 verification — 2026-10-18

**B11 — final collection child acceptance verified (2026-10-18):** The creator now reopens each child embedded in a final c/ci output and recursively applies its existing consistency checks before atomic replacement. Missing child dates/page connections and corrupt native-attachment checksums reject the parent; existing destination bytes survive. Eight-level nesting and shared count/expanded-byte bounds reject unchecked excess.

Verification: **142 Java tests pass; 249 Python tests run, 248 pass and one optional PREMIS schema skip**, no failures/errors, in 566.697 seconds. All twelve normal/accessibility profile reports PASS independent PDF/A and mandatory EA-PDF, including children. All eight new corpus parents and their children PASS PDF/A: two valid collections PASS EA-PDF; six mutations fail exactly their declared child rule. Six saved samples and collection sidecars were regenerated and verified.

The recovered thread's user-approved priority is preservation assurance. Next: independently inventory expected message/attachment occurrences before rendering and compare their owners/bytes with final outputs. B11 checks a bounded creator subset; original-input completeness, broader ownership, integrated independent acceptance/sidecar gates and complete standards coverage remain open. Thirty scoped register records and the unchanged 72-site N/A inventory remain partial. Earlier checkpoints below are historical.

[Exact reports and hashes](improvement/batch-11-latest-evidence.json).

## Current B10 verification — 2026-10-18

**B10 — encoded-header failure handling verified (2026-10-18):** Malformed optional source headers no longer abort unrelated validation through HeaderParseError. Decoded comparisons reject supported complete B/Q payloads that require repair and record unavailable evidence; valid words still decode. UserProperties and observed occurrences share this boundary, while raw comparisons remain literal and unexpected engine errors remain visible. This adds no mandatory source-format rule; rule 107 and overall rendered-header coverage remain partial.

Verification: **249 Python tests run, 248 pass, one optional PREMIS schema skip**, no failures/errors, in 497.391 seconds. All six profiles PASS independent PDF/A and mandatory EA-PDF in normal and accessibility integrations, including children. Six new PDFs all PASS PDF/A and mandatory EA-PDF with exact expected attribute PASS/WARN/N/A and per-occurrence decoder outcomes. The B09 baseline reproduces the source-only VALIDATION_ERROR. Creator/JAR and saved samples match accepted hashes; B08’s 138 Java passes are historical, with no fresh Java build or sample regeneration.

Resume from [the work log](EA_PDF_WORK_LOG.md) and [B10 evidence](improvement/batch-10-latest-evidence.json). The register has 29 scoped records; 72 explicit N/A sites remain a partial inventory. Next: rendered-header enumeration across standard/untagged content, occurrence/aggregation semantics, broader source/asset ownership and remaining applicability predicates. Full contextual RFC 2047 semantics, custom-set interpretation/external review, ISO/TS 32005 access and complete standards/inclusion-rule coverage remain open. Earlier checkpoints below are historical.

## B09 verification — 2026-10-18

**B09 — source-header occurrences verified (2026-10-18):** Header evidence now retains parsed source order, raw values and occurrence ordinals. Observed custom fields map to a repeated source occurrence only when one supported display value matches within the fixed message/source owner. Identical, unsupported and unmatched repeated values remain unavailable. Observed text survives optional source absence. Header recovery stays partial; unrelated MIME body defects remain separate. Rule 107 remains partial WARN because complete rendered-header enumeration is still open.

Verification: **240 Python tests run, 239 pass, one optional PREMIS schema skip**, no failures/errors, in 466.932 seconds. All six profiles PASS independent PDF/A and mandatory EA-PDF in normal and accessibility integrations, including children. Six new PDFs all PASS PDF/A: two expected EA-PDF PASS and four exact MESSAGE_SUBJECT_XMP failures for malformed duplicate-Subject probes, independently reproduced with B08. Creator/JAR and saved samples match B08 hashes; its 138 Java passes are historical, with no fresh Java build or sample regeneration.

Resume from [the work log](EA_PDF_WORK_LOG.md) and [B09 evidence](improvement/batch-09-latest-evidence.json). The register has 28 scoped records; 72 explicit N/A sites remain a partial inventory. Next: complete rendered-header enumeration across standard/untagged content, arbitrary occurrence/aggregation semantics, broader source/asset ownership and remaining source-dependent predicates. Custom-set semantics/external review, full ISO/TS 32005 access and complete standards/inclusion-rule coverage remain open. Earlier checkpoints below are historical.

## B08 verification — 2026-10-18

**B08 — observed custom header inventory verified (2026-10-18):** Header diagnostics now decode text from each field’s actual page/Form MCID scope and compare it with the retained source selected through its enclosing message identity. Duplicate IDs, ambiguous owners, repeated headers and unsupported decoding stay unavailable. Known field pages outside the message’s DPart ranges are diagnostic differences. Rule 107 remains partial WARN because standard-tagged, untagged and unrecognized rendered headers are not enumerated.

Verification: **227 Python tests run, 226 pass, one optional PREMIS schema skip**, no failures/errors, in 459.097 seconds. Fresh offline Maven package: **138 Java tests pass**. All six profiles PASS independent PDF/A and mandatory EA-PDF in normal and accessibility integrations, including children. Six new PDFs all PASS PDF/A: five expected mandatory EA-PDF PASS and one exact missing-GUID FAIL. Saved samples and collection sidecars are unchanged by hash; no creator/CLI edits or sample regeneration.

Resume from [the work log](EA_PDF_WORK_LOG.md) and [B08 evidence](improvement/batch-08-latest-evidence.json). The register has 26 scoped records; 72 explicit N/A sites are inventoried without certifying every predicate. Next: complete rendered-header/source occurrence mapping, broader source/asset inventories and ownership assurance, and remaining source-dependent conditions. Custom-set semantics/external review, full ISO/TS 32005 access and complete standards/inclusion-rule coverage remain open. Earlier checkpoints below are historical.

## B07 verification — 2026-10-18

**B07 — tagging recommendation evidence verified (2026-10-18):** A structure root or isolated field tag no longer proves complete coverage. Content Set recommendations inspect marked paint, artifacts, Form content, annotation references, MarkInfo and structure correspondence across declared DPart ranges as a conservative superset of custom sets. Header tagging is conditional on custom EA-PDF tags; observed field pairs remain partial WARN until a complete rendered-header inventory exists. Known valid non-body ranges now give explained N/A for body-media comparison; unknown/unresolved bodies remain incomplete.

Verification: **213 Python tests run, 212 pass, one optional PREMIS schema skip**, no failures/errors, in 393.575 seconds. All six profiles PASS independent PDF/A and mandatory EA-PDF in normal and accessibility integrations, including children. Six new PDFs all PASS PDF/A: four expected EA-PDF PASS, one exact two-rule FAIL and one intentional INCOMPLETE for unavailable Core Field text ownership. B05 creator/JAR and saved samples are unchanged by hash; its 138 Java passes are historical.

Resume from [the work log](EA_PDF_WORK_LOG.md) and [B07 evidence](improvement/batch-07-latest-evidence.json). The register has 24 scoped records; 72 explicit N/A call sites are inventoried without certifying all predicates. Next: source/rendered-header inventories, remaining source-dependent conditions and wider ownership assurance. Custom-set semantics/external review, full ISO/TS 32005 access and complete standards/inclusion-rule coverage remain open. Earlier checkpoints below are historical.


## B06 verification — 2026-10-18

**B06 — recommendation applicability verified (2026-10-18):** Four source/policy recommendations now show unavailable WARN instead of unconditional N/A. Extraction exceptions and missing/duplicate page-analysis results produce unverified WARN and INCOMPLETE. Context assessed on only some pages cannot produce an all-pages text-context PASS. SHOULD findings retain recommendation strength, and mandatory failures remain separate.

Verification: **197 Python tests run, 196 pass, one optional PREMIS schema skip**, no failures/errors, in 397.921 seconds. All six profiles pass PDF/A and mandatory EA-PDF in both normal and accessibility integrations, including children; their unavailable recommendations remain visible. Six new assessment scenarios use two PDF/A-valid PDFs: two normal PASS and four fault-injected INCOMPLETE. B05 creator/JAR and saved examples are unchanged by hash; its 138 Java passes are historical.

Resume from [the work log](EA_PDF_WORK_LOG.md) and [B06 evidence](improvement/batch-06-latest-evidence.json). The register has 21 scoped records; 73 explicit N/A call sites are inventoried without claiming their predicates are all reviewed. Next: custom Content Set/header-tagging applicability, remaining source-dependent conditions and broader inventory/ownership assurance. Full ISO/TS 32005 access and complete applicable errata/inclusion-rule review remain open; public clause 7 corrections are recorded separately. Earlier checkpoints below are historical.


## B05 verification — 2026-10-18

**B05 — effective UserProperties verified (2026-10-18):** ClassMap and direct attributes now share array-order resolution; direct A overrides C and the last P replaces the entire earlier property list. Revisions track updates independently of precedence. Creator updates copy shared attributes locally and preserve unrelated data. Malformed resolution remains visible as INCOMPLETE.

Verification: **138 Java passes; 188 Python tests run, 187 passes and one optional PREMIS schema skip**, no failures/errors. Eighteen new PDFs all pass PDF/A: ten legal cases PASS EA-PDF and eight mutations fail exactly their declared rules. Both normal and accessibility integrations pass all six profiles, including children. Six saved samples and collection PDF/XLSX sidecars were refreshed and verified.

Resume from [the work log](EA_PDF_WORK_LOG.md) and [B05 evidence](improvement/batch-05-latest-evidence.json). The register remains partial. Next: audit remaining explicit N/A recommendations and complete applicable standards/errata records, then wider metadata/source ownership checks. General namespace/attribute inheritance and full source-association assurance remain open. Earlier checkpoints below are historical.


## B04 verification — 2026-10-18

Final-source acceptance runs **180 Python tests: 179 passes and one optional PREMIS schema skip**. All six profiles pass PDF/A and EA-PDF in both normal and accessibility integrations, including recursive children. The new 17-case source-value corpus has eight recommendation PASS, eight deliberate WARN and one isolated N/A; every file passes PDF/A and mandatory EA-PDF checks. The expected warnings remain recommendations, preserving their SHOULD strength.

Exact final-run PDFs/reports are archived under `docs/improvement/batch-04-corpus/`; twelve profile JSON/HTML reports are under `docs/improvement/batch-04-profile-reports/`. The earlier 179-test run is provisional and retained separately; it predates final date-default/raw-folding guards. The accepted source is frozen by hash. [B04 evidence](improvement/batch-04-latest-evidence.json) records commands, outcomes and limitations; resume from [the work log](EA_PDF_WORK_LOG.md). Creator and saved examples are unchanged, with hashes checked; no fresh Java test claim is made. Earlier checkpoints below are historical.

## B03 verification — 2026-10-18

The date repair passes **170 Python tests: 169 passes, one optional PREMIS schema skip**. All six profiles pass PDF/A and EA-PDF in both normal and accessibility integrations, including recursive children. The separate 25-file date corpus has nine valid files and sixteen deliberately invalid files with exact expected USERPROPS_TYPE failures; all 25 independently pass PDF/A. Reports/PDFs: `docs/improvement/batch-03-corpus/`; twelve profile JSON/HTML reports: `docs/improvement/batch-03-profile-reports/`.

No creator edits or sample regeneration were required. The reused B01 creator JAR matches its accepted hash; its earlier Java test totals remain historical. [B03 evidence](improvement/batch-03-latest-evidence.json) records exact source/artifact hashes, logs and commands. [The work log](EA_PDF_WORK_LOG.md) gives the next task. Earlier results below are historical.

## B02 verification — 2026-10-18

The final validator passes **162 Python tests: 161 passes and one optional PREMIS schema skip**. All six profiles pass PDF/A and EA-PDF in normal and accessibility integrations, with complete recursive child results. The new 27-case corpus has nine valid files and eighteen deliberate type/scope mutations with exact expected failures; all 27 pass PDF/A. Durable PDFs/reports are under `docs/improvement/batch-02-corpus/`; twelve profile JSON/HTML reports are under `docs/improvement/batch-02-profile-reports/`.

The B01 creator JAR was reused with a verified matching hash. No Java changes or fresh Java test claim; saved examples were not regenerated. Source hashes, logs, limitations and reproduction commands: [B02 evidence](improvement/batch-02-latest-evidence.json). Resume from [the work log](EA_PDF_WORK_LOG.md). Earlier B01 sample/build results below are historical.

## B01 verification — 2026-10-18

All six primary examples were regenerated after the creator attribute-preservation repair and checked with the final validator. All six pass PDF/A and EA-PDF, both original collection children pass, and both ordinary finding-aid sidecars pass PDF/A. c/ci contain no auxiliary PDF/XLSX; m/mi retain XLSX embedding; the single-message alias matches.

The new creator-derived rule corpus has three valid representations (dictionary, array, untagged) and four deliberate mutations. All seven pass PDF/A; the mutations fail exactly their intended EA-PDF rule. Full tests: **134 Java passes; 151 Python run, 150 passes and one optional PREMIS schema skip**. Source, artifact hashes and exact outcomes are in [B01 evidence](improvement/batch-01-latest-evidence.json); durable reports are under `docs/improvement/batch-01-{corpus,saved-reports}/`. Resume from [the work log](EA_PDF_WORK_LOG.md). The register and broader plan remain incomplete. Earlier verification records below are historical.

## Current collection-sidecar samples — 2026-10-17

All six primary samples were regenerated from the original EML/PST fixtures using the completed R14 creator. Each passes independent PDF/A and EA-PDF validation. The two contained original email PDFs also pass both checks.

| Published artifacts | PDF/A | EA-PDF / structure |
| --- | --- | --- |
| `sample_1s.pdf`, `sample_1si.pdf`, `sample_1m.pdf`, `sample_1mi.pdf` | PASS | PASS; m/mi retain embedded XLSX indexes |
| `sample_1c.pdf`, `sample_1ci.pdf` | PASS | PASS; no auxiliary PDF/XLSX embedded |
| `sample_1c_finding_aid.pdf`, `sample_1ci_finding_aid.pdf` | PASS (PDF/A-4) | Ordinary index sidecars; no embedded files or synthetic EML |
| `sample_1c_index.xlsx`, `sample_1ci_index.xlsx` | N/A | Valid separate XLSX workbooks |

The `output/sample_1s.pdf` alias matches its example. Full Maven package: **132 tests pass**. Packaged Python: **140 run, 139 pass, one optional PREMIS schema check skipped** because no official XSD was configured. The Java creator gate and Python validator were not relaxed. [Current evidence](reimplementation/collection-sidecars-evidence.json) records hashes and validation summaries; raw reports are under `/tmp/eapdf-collection-sidecars-20261017/`. Earlier failing review PDFs were retired to its `superseded-direct-xlsx-review/` directory. All records below are historical.

## Earlier R13 direct-XLSX review — 2026-10-17

The recovered direct-attachment implementation passes its focused build/test. Both ordinary finding-aid PDFs now pass independent PDF/A-4F validation after the PDF/A revision metadata correction; each directly embeds a valid XLSX and no synthetic EML.

| Review profile | PDF/A | EA-PDF | How prepared |
| --- | --- | --- | --- |
| `1s`, `1si`, `1m`, `1mi` | PASS | PASS | Fresh CLI generation from the original EML/PST fixtures |
| `1c`, `1ci` | PASS | FAIL | Saved R12 parents with newly generated direct-XLSX finding aids substituted |

Each collection fails `COLLECTION_CONTENT_TYPES` and `COLLECTION_CHILD_VALIDATION`: §7.6 permits only EA-PDF children except original source assets, and the index is an ordinary PDF. The original email children still pass PDF/A and EA-PDF validation. The strict creator likewise rejects the ordinary index before publication and preserves existing destinations.

Six review PDFs are under `.eapdf-direct-xlsx-review-20261017/`. Published samples and the existing alias were not replaced. Full Java/Python suites were not rerun; no current full-suite PASS is asserted. Source/artifact hashes, focused JUnit results, summarized failures and temporary raw-report paths: [direct-XLSX review evidence](reimplementation/direct-xlsx-review-evidence.json). The output policy remains a required user decision. All records below describe earlier sample bytes and verification stages.

## Earlier regenerated supported email samples — 2026-10-17

All six primary examples (`sample_1s.pdf`, `sample_1si.pdf`, `sample_1m.pdf`, `sample_1mi.pdf`, `sample_1c.pdf`, `sample_1ci.pdf`) were regenerated from `in/email/test3.eml` / `in/email/test.pst` using the current creator. Each receives independent PDF/A **PASS** and automated EA-PDF **PASS**, including the new file-set, collection-content and UTF-8 rules. Both contained email PDFs in the collection samples pass independently. The existing `output/sample_1s.pdf` alias has identical validated bytes.

Collections no longer embed miscellaneous finding-aid PDFs, spreadsheets or container PREMIS (§7.6). Four enabled collection sidecars are published beside the samples: `_finding_aid.pdf` and `_index.xlsx` for `1c` and `1ci`. Copied finding-aid PDF sidecars are auxiliary PDFs, not standalone EA-PDF examples. Legacy Facebook/Twitter PDFs are retained as historical; their current generators and source fixtures are deleted. Other imported/historical PDFs were not overwritten.

Regeneration reports: `/tmp/eapdf-additional-rules-20261017/verified-samples`. Full acceptance: 129 Java tests, 139 Python tests, zero skips. Durable sample hashes, rule mappings and positive/negative evidence: [additional-rules-evidence.json](reimplementation/additional-rules-evidence.json). The audit below is historical and does not describe these regenerated sample bytes.

Audit date: 2026-10-16; creator release: `1.4.0`.

## Focused `output/sample_1s.pdf` repair

The requested output has been recreated from `in/email/test3.eml` using the
current creator. Its final independent PDF/A and local EA-PDF reports are PASS,
with zero WARN or FAIL findings. Reports are in
`/tmp/eapdf-sample1s-repair/sample_1s.json` and `sample_1s.html`.

The old output retained 32 ASCII hexadecimal bytes in the source stream's
`/Params/CheckSum`, omitted original attachment MIME parameters, and linked
attachment markers to the source email instead of their native payload.
Those creator defects were already fixed in the current writer; regenerating
the stale output applies those fixes without changing the original email bytes.
The Java pre-publication gate now also rejects malformed or incorrect embedded
source checksums, including a separately corrupted Unicode file stream.
Regression tests verify that rejection preserves an existing destination.
Maven packaging passes 97 Java tests; the fast Python run passes its applicable
checks (110 discovered, three opt-in generator integrations skipped).

The original requested output and other sample files were removed externally
during this follow-up. Only `output/sample_1s.pdf` is recreated; other removed
samples remain untouched. The inventory below is the earlier audit snapshot,
not a live directory listing. Its old `output/sample_1s.pdf` failure is superseded
by this focused repair.

## Scope and outcome

The inventory covers all 26 PDFs found in `examples/`, `output/`, `out/`,
`compare/`, `.validator_web_tmp/`, and the generated documentation diagram.
Reference standards under `docs/reference/`, `target/`, and project debug
directories are excluded. Identical BFO/UIUC copies share validation evidence.
Collection validation also checks embedded email PDFs recursively.

All 24 EA-PDF candidates pass independent veraPDF PDF/A validation. That does
not establish EA-PDF conformance: five current examples receive overall PASS,
one receives INCOMPLETE, and 18 historical/imported files still FAIL local
EA-PDF checks. The two diagram copies are not EA-PDF and are N/A.

The six current email examples are regenerated from the existing EML/PST
fixtures. No mandatory local EA-PDF failures remain in them. Three have no
warnings; the remaining findings describe real source-data or omission policy
limits, not suppressed validator results.

## Current examples

| File | PDF/A | Overall EA-PDF | Remaining findings |
| --- | --- | --- | --- |
| `examples/sample_1s.pdf` | PASS | PASS | None |
| `examples/sample_1si.pdf` | PASS | PASS | Explicit zero-byte attachment omissions under the isolated policy |
| `examples/sample_1m.pdf` | PASS | INCOMPLETE | Four malformed original address comparisons: three To and one From |
| `examples/sample_1mi.pdf` | PASS | PASS | Explicit zero-byte attachment omissions under the isolated policy |
| `examples/sample_1c.pdf` | PASS | PASS | None, including contained email validation |
| `examples/sample_1ci.pdf` | PASS | PASS | None, including contained email validation |

The malformed PST tokens are non-SMTP names, not recoverable mailboxes. Raw
source headers remain available; inventing addresses or relaxing comparisons
would not be an accurate repair. Isolated profiles deliberately omit original
native bytes. Keep originals or select a non-isolated profile when those bytes
are required; the existing policy is unchanged.

## Historical and imported failures

All files in this table pass PDF/A but FAIL local EA-PDF checks. Listed issues
are representative; full per-rule findings are retained in the audit reports.

| Files | Main remaining defects |
| --- | --- |
| `.validator_web_tmp/01_bfo.pdf` | Imported/uploaded sample: DPart page coverage, native attachment metadata/annotations, identification, XMP and logical structure |
| `compare/bfo.pdf`, `output/bfo.pdf` | Imported BFO sample: page 2 lacks its DPart link and content-set range; additional identification, XMP, attachment and logical-structure failures |
| `compare/uiuc.pdf`, `output/uiuc.pdf` | Imported UIUC sample: page/content-set structure, core metadata, portable file specifications and native attachment annotations |
| `compare/thinkbox.pdf` | Old creator output: DPart/XMP/structure, native attachment matching and textual rather than binary embedded MD5 |
| `output/thinkbox_old.pdf` | Historical creator output: DPart/XMP/structure, native attachment metadata/annotations and embedded MD5 format |
| `examples/sample_facebook_1m.pdf` | Legacy social example: identity, body media type, DPart/XMP/structure, attachment preservation and embedded MD5 format |
| `examples/sample_twitter_1m.pdf` | Legacy social example: identity, DPart/XMP/structure, attachment preservation and embedded MD5 format |
| `out/Agenda and timeline slides_000001.pdf`, `output/Agenda and timeline slides_000001.pdf` | Old attachment payload/type metadata and embedded MD5 format |
| `out/test_1c.pdf` | Old collection and contained email failures; embedded MD5 format |
| `output/sample_1s.pdf` | Old native attachment payload/type metadata and embedded MD5 format |
| `output/sample_1si.pdf` | Old isolated attachment metadata and embedded MD5 format |
| `output/sample_1m.pdf` | Old native attachment/type metadata, invalid-date Raw-Sent handling and embedded MD5 format |
| `output/sample_1mi.pdf` | Old isolated attachment metadata, portable file specifications and embedded MD5 format |
| `output/sample_1c.pdf` | Old collection/contained email failures and embedded MD5 format |
| `output/sample_1ci.pdf` | Old collection/contained email failures and embedded MD5 format |

These files are not silently marked conforming or rewritten by validation.
The safety reviewer blocked replacement of historical `output/` and `out/`
artifacts pending explicit approval because replacing them could destroy
comparison evidence or original content. They remain unchanged. Imported
third-party PDFs require source-aware repair/recreation rather than relabeling
metadata. The legacy social readers and their original fixtures have been
removed from the working tree; faithful regeneration is not currently available.

The duplicate diagrams `docs/eapdf_decision_tree.pdf` and
`out/eapdf_decision_tree.pdf` are documentation, not email archives.

## Repaired copies and evidence

Validated replacement candidates are written separately, without overwriting
the historical files:

- `/tmp/eapdf-refreshed-outputs/agenda.pdf`: regenerated from
  `in/Agenda and timeline slides.eml`; PDF/A PASS, overall PASS, no warnings.
- `/tmp/eapdf-refreshed-outputs/pst_collection.pdf`: regenerated from
  `in/email/test.pst`; PDF/A PASS with no mandatory local failures; malformed
  source-address comparisons in four contained messages keep overall INCOMPLETE.
  All 71 contained PDFs pass PDF/A; 67 receive overall PASS and four INCOMPLETE,
  with no contained FAIL results.
- The current six `examples/sample_1*.pdf` files provide validated regenerations
  of the canonical EML/PST profile samples; historical `output/` copies are not
  replaced without approval.

Detailed machine-readable/HTML evidence is temporary and may need preserving
outside `/tmp` for a release:

- `/tmp/eapdf-all-pdf-audit/final-inventory.json`: merged final inventory with
  regenerated example SHA-256 identities and current findings.
- `/tmp/eapdf-all-pdf-audit/inventory.json`: original-file inventory, SHA-256
  identities, status, complete WARN/FAIL items and per-file report names.
- `/tmp/eapdf-all-pdf-audit/`: historical/imported JSON and HTML reports.
- `/tmp/eapdf-validator-final-samples/`: final reports against regenerated
  current example bytes, superseding the example rows in the original audit.
- `/tmp/eapdf-refreshed-outputs/`: separate replacement candidates and reports.
- `/tmp/eapdf-validator-final-integration/`: 15 fresh top-level test outputs'
  validation reports, including six default and six enhanced-accessibility
  profiles, three targeted accuracy cases, and recursive collection checks.

Maven packaging passes 96 Java tests. The Python suite passes 110 tests with
all three packaged-generator integrations enabled. The build directory is
`/tmp/eapdf-validator-update`; `target/` and project debug directories are
untouched. Reproduction commands are in `README.md`.

These are engineering checks, not independent EA-PDF, ISO TS 32005 or PDF/UA
certification. Unrecognized header layouts, difficult source rendering and
meaningful accessibility/reading-order review remain documented limits.
