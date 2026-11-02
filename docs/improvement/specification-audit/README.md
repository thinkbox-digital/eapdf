# Specification-to-requirements audit

**Current review — 2026-10-27:** 213 active rules and 301 inventory records. The
[reexamination report](specification-reexamination-2026-10-27.txt) records all 52
findings, questions and user-authorized working assumptions. The creator and both
validators apply the documented ownership and PDF-version interpretations. These
are local decisions, not published errata or exhaustive conformance certification.
[Current implementation evidence](reexamination-verification-2026-10-27.json)
records the test results, six-profile comparisons, Word checks and artifact hashes.

**Historical local decisions V61 — 2026-10-21:** 301 records, 225 check codes, zero unmapped mandatory/conditional file entries. All eleven review questions have user-authorized local interpretations; no official errata or external expert approval is claimed. Cited PDF/A draft-edition equivalence and full obligation coverage remain unverified. [Decisions](review-questions.md), [verification](../validator-61/evidence.json). Earlier dated checkpoints below retain historical counts.

[Open the offline two-pane rule browser](rule-browser.html) for all 301 inventory records beside highlighted original specification pages. [Usage, limits and regeneration](../rule-browser.md). This generated view retains the authoritative JSON and pinned source hashes.

**Table 11 implementation checkpoint — 2026-10-19 (before upstream review):** The user approved `Mail_ContentField`→`Mail_Field` and authorized validator improvements. Rule 220 now covers the Table 11 Mail_ContentSet child row; immediate-child constraints, required FieldValue parent, optional custom-tag applicability and report attribution are repaired. There are now **220 codes, 301 records, eight open questions and six unmapped mandatory/conditional file records**. [Implementation evidence](../validator-01-evidence.json). The requirements-only verification below is historical; validator logic has now changed while creator sources remain unchanged.

**The authoritative rule document is [validator_rule_text.json](../../../validator_rule_text.json).** Edit runtime descriptions in that file. Edit requirement records, ownership, questions and dispositions in [validator-rule-review.json](validator-rule-review.json); development tools merge the two. This directory holds generated supporting views and evidence. The pinned local **EA-PDF v1.0, 2025-02** specification is the specification authority, licensed CC-BY-4.0 by the PDF Association.

**Historical upstream review — 2026-10-19:** All 26 reports and 47 comments have dispositions. MCAF evidence, ADBE object types and CreateDate-provenance reporting are repaired and verified. Eleven expert/dependency questions and six unmapped file records remain. [Review and evidence](../upstream-issues/README.md).

## Expert examination

Open [expert-review.html](expert-review.html) locally. The self-contained document includes every existing rule description, requirement record, profile reference, covered/uncovered fragment, representation contract and open question. Rules can be searched and filtered by ownership; internal links connect rules to their requirements. Review proposed interpretations against the cited specification pages and record dispositions in the authoritative JSON. This document has not been submitted to an external party or hosted publicly.

Current inventory:

- **97 PDF pages, 46 numbered sections/subsections and all 19 tables** have dispositions.
- **301 requirement/guidance records**, including four added declaration dependency records. Guidance, definitions, permitted alternatives, software behavior and diagnostics are visible; this is not a count of independent mandatory checks.
- **213 current rule codes**, uniquely numbered and linked in both directions. Links and ownership dispositions do not certify implementation or execution.
- **11 locally resolved questions**, with exact authority locations, rationale and bounded verification in [expert-questions.json](expert-questions.json).
- **Zero mandatory/conditional file records without a rule mapping**, in [unmapped-file-requirements.json](unmapped-file-requirements.json). Mapping does not prove complete execution coverage.
- **Six representation contracts**, distinguishing permitted XMP equivalents, array kinds, FOAF agents, exact GUID identity, date types and byte metadata.

The user authorized scoped improvements despite unrelated questions; the subsequent upstream review expanded the current open question count to 11. The approved Table 11 interpretation is implemented and verified; other question dispositions and implementation gaps remain explicit.

## Custom scope and veraPDF evidence

The custom validator covers assertions not covered by the selected veraPDF profiles. Ownership is now dispositioned for all existing codes against the five pinned veraPDF 1.31.18 profiles: **138 custom, 11 delegated, 11 operational, 60 mixed** (including the separate declaration/structure split). [Coverage evidence](../verapdf-coverage/README.md).

Generic XMP presence, serialization and eligible PDF/A checks remain upstream. PDF/A-3 declared-type consistency does not establish that an author's custom schema declares the types or required fields actually prescribed by EA-PDF. EA-PDF-specific schema, association and identity checks therefore retain their uncovered fragments. Structure presence is required by PDF/A-3a, while other eligible profiles do not independently require tagging.

The local ISO/TS 32005 selection retains 1636 unchanged ISO predicates from the pinned composite and excludes its 91 PDF/UA predicates. Its model selector does not impose PDF/UA. Actual applicable execution is required; a bundled profile alone cannot establish PASS. The PDF/A-4 profiles identify the 2020 edition; equivalence to EA-PDF's specifically cited 2024 draft is not established.

## Main requirement corrections

- Table 18 Sent is required; NumberAttachments is conditionally required. In-Reply-To is a collection of strings, with its printed “unordered sequence” terminology now an explicit expert question.
- Table 9 contains all nine backward role-map rows, including Hn for n≥7 and Title→H1 or P. Custom-tag Table 10 mappings are separate.
- `dc:creator` is not reserved for a software name. `pdf:Producer` identifies the PDF creation tool; creation-date provenance cannot be inferred solely from equality with source values or filesystem times. No product-name grammar is required.
- GUIDs are normal simple strings with exact identity comparison, not necessarily UUIDs or URI values. Checksum letter-case tolerance must not leak into GUID comparison.
- XMP permits selected equivalent XML forms, not arbitrary RDF/XML. Generic syntax checking remains upstream; custom extraction still needs to preserve value, namespace, container kind and ownership.
- Original-source fidelity, recommended attachment inventories and mandatory supplied-record schema remain distinct. Standard-only tagging, optional custom tags and namespace applicability are not interchangeable conditions.
- Section 8's 17 software obligations remain outside this custom input-PDF validator. Source/creation guidance is retained separately from file-schema requirements.

The rendered Table 11 spelling is resolved by the explicit user-approved interpretation. The eleven questions have local project dispositions recorded in the authoritative JSON. The PDF/A-4 draft/profile equivalence remains explicitly unverified; the operational profile choice does not establish it. No official errata are claimed by the local scope resolutions.

## Reproduction and integrity

Run from the repository root with the project Python dependencies and Poppler available:

```sh
env -u PYTHONPATH python3 -B scripts/build_eapdf_requirements_audit.py
```

The recipe verifies authoritative IDs, unique numbers, forward/reverse rule links, all tables, all 11 starting DOCX sections, 97 extracted pages, the pinned specification hash, all five exact profile XML/index pairs and every claimed upstream rule reference. It regenerates expert/ownership views and the audit integrity manifest. Do not independently edit generated views. Mechanical checks do not prove semantic completeness.

**Historical requirements-only verification:** 30 focused Python tests pass, with no failures or skips. The expert document contains all 219 rule articles, 538 unique anchors and 795 resolved internal links. Validator checking logic and Java source hashes match the continuation before-state. Initial attempts with isolated system Python could not import user-installed pikepdf; the successful run used the available project dependencies. No full application suite, Maven build, sample regeneration or runtime repair is claimed for this requirements continuation.

## Artifact map

| Artifact | Purpose |
| --- | --- |
| [expert-review.html](expert-review.html) | Complete local expert examination document |
| [requirements.md](requirements.md) / [requirements.json](requirements.json) | All 301 reviewed assertions, strengths, conditions and candidate code links |
| [expert-questions.json](expert-questions.json) / [findings.json](findings.json) | Eleven local interpretation questions and historical dispositions |
| [representation-contracts.json](representation-contracts.json) | XMP/FOAF/date/GUID/byte semantic requirements |
| [unmapped-file-requirements.json](unmapped-file-requirements.json) | Explicit mandatory/conditional mapping gaps |
| [clause-inventory.json](clause-inventory.json) / [table-inventory.json](table-inventory.json) | Forward section and table traceability |
| [rule-crosswalk.json](rule-crosswalk.json) | Current and original descriptions, requirements and static source references |
| [sources.json](sources.json) / [external-sources-reviewed.json](external-sources-reviewed.json) | Pinned inputs, dependency status and reviewed official sources |
| [continuation-evidence.json](continuation-evidence.json) | Current verification and unchanged runtime-source evidence |
| [artifact-hashes.json](artifact-hashes.json) | Integrity manifest for durable files in this directory |

`specification-layout.txt`, the rendered ambiguity images, original 215-description JSON and earlier runtime baseline are retained. The starting DOCX is historical supporting material. The public Adobe April-2012 XMP text supports this selected representation review; it does not establish full equivalence to the cited 2019 ISO edition. The raw downloaded Adobe PDF and FOAF snapshot are held under `/tmp/eapdf-requirements-continuation/`; source URLs and hashes are recorded, and ephemeral downloads are not a durable standards archive.

Current author-review Word synchronization: `python3 -B scripts/sync_eapdf_author_review.py`. It preserves original source exhibits and adds the 52 working decisions. Regenerate audit views after editing the runtime or review JSON. See the reexamination report for remaining source-definition and evidence limits.
