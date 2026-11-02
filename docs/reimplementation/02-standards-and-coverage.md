# Standards baseline and coverage

## What “the ISO spec” means here

EA-PDF v1 is a **PDF Association industry specification**, published February 2025; it builds on ISO PDF/PDF/A standards. It is not itself one universal ISO email-archive standard. The official [PDF Association resources list](https://pdfa.org/resources/) identifies that distinction. The local [EA-PDF v1](../reference/EA-PDF-v1.pdf) and [official publication](https://pdfa.org/download-area/specifications/EA-PDF-v1.pdf) are the baseline for this review.

| Reference | Role in this implementation | Coverage boundary |
| --- | --- | --- |
| EA-PDF v1 §§8–9 | Normative software and file-format requirements | Creator + diagnostic validator supplied; complete aware reader/viewer not supplied |
| ISO 32000-2:2020, with applicable errata | PDF 2.0 object model, DPM, collections, structure, embedded files | PDFBox handles serialization; custom objects still need validation |
| ISO 19005-4:2020 | PDF/A-4; current output selects PDF/A-4f | Fresh exercised files pass veraPDF; not all possible accepted emails proven |
| ISO TS 32005:2023 and applicable errata | Conditional requirement when PDF 2.0 output has logical structure | Declaration exists; complete inclusion-rule validation remains unproven |
| ISO 16684-1 / XMP and RDF | Metadata encoding, arrays, structured properties and identifiers | Current writer manually builds XML; RDF/property-type validation is selective |
| ISO 14289-1:2014, locally supplied PDF/UA-1 | Accessibility reference for PDF 1.7 | Not a certification reference for this PDF 2.0 generator |
| ISO 14289-2 / PDF/UA-2 | Relevant if a future PDF 2.0 accessibility claim is made | No current complete conformance claim or acceptance workflow |
| RFC 822/5322 and MIME RFCs 2045–2049, 2231 | Message fields, MIME selection, encoding and filenames | Library parsing plus project-specific selection/reconstruction policies |
| PREMIS 3.0 | Optional preservation sidecar and embedded XML | Separate schema compliance; currently fails, see R5 |

The repository also contains PDF/A-1 and PDF/A-2 references. They do not mean this generator emits those formats. EA-PDF permits PDF/A-3a/-3u and PDF/A-4/-4f/-4e; it does not permit every PDF/A flavor. Methods named `PdfA3` currently produce **PDF 2.0/PDF/A-4f**.

The [ISO TS 32005 inclusion-rule resource](https://pdfa.org/resource/iso-ts-32005-hierarchical-inclusion-rules/) and [errata](https://pdf-issues.pdfa.org/32005-2023/clause07.html) are required follow-up references for the tagged replacement. Use the actual standards and preserve editions/errata in the release requirements register. The local Word checklist and `validator_rule_text.json` are secondary implementation aids, not normative authorities.

## Supported profile behavior

The XMP conformance value excludes the leading `1`.

| Value | Current rendered contents | Source policy | Navigation |
| --- | --- | --- | --- |
| `s` | One email; all its pages form one BodyRendering content set | Original EML/MSG or staged source embedded | Outlines without email attachments; attachments otherwise |
| `si` | One email | Original source and native attachment bytes omitted; zero-byte attachment representations | Same email-attachment rule as `s` |
| `m` | At least two emails; separate content sets; optional finding aid | Sources embedded; native attachments in page annotations | Outlines |
| `mi` | At least two emails; optional finding aid | Sources omitted; zero-byte attachment representations | Outlines |
| `c` | Collection front matter + contained `s` PDFs, grouped in folders | Child PDFs and sources embedded | Attachments |
| `ci` | Collection front matter + contained `si` PDFs | Source/native bytes omitted; child PDFs retained | Attachments |

Single-message PST conversion currently selects `si` even without `--omit-source`; explicit collection `c` children use `s`. This is implementation policy, not a universal inference about what users intended. Multi-message archive creation rejects one message; the monthly utility explicitly chooses `s` for singleton months.

Isolation is an implementation preservation choice: it removes original native payloads, not rendered images or every auxiliary embedded file. Finding aids can remain. PREMIS remains sidecar-only for isolated outputs. Keep original inputs when bytes are required later.

## Clause traceability

“Implemented” means identifiable code and observed behavior. It never means every normative subclause has been proven. R-numbers refer to [the assessment](01-readiness-review.md).

| Clause | Requirement family | Creator implementation | Validation / unresolved coverage |
| --- | --- | --- | --- |
| §8 | Rendering, read-only opening, navigation, extraction, XMP access, annotation interaction, no automatic URL activation | Creator and GUI wrapper do not implement a complete EA-PDF reader | Not a full product capability; test with external aware readers |
| §9.1 | Header/effective version and permitted PDF/A | Writer forces 2.0, writes part 4/rev 2020/F, embeds fonts and sRGB intent | veraPDF plus local declaration checks; creator gate is narrower |
| §9.1 | Info crosswalk for PDF/A-3; no PieceInfo; EA-PDF keywords | Info removed for current PDF/A-4f; keyword set | Validator has conditional PDF/A-3 logic; not a generator capability |
| §9.1 | Profile-dependent viewing preferences, title, AF, outlines, DPartRoot | `configureCatalog`, `configureArchiveCatalog`, embedding and DPM helpers | R3 severity defect; R6 auxiliary-file false positive |
| §9.2 | Content-set/page/email cardinality, nonblank content and first-page outlines | Single body per email; optional FrontMatter; collection has only front-matter DPM | Tests cover six profiles; nonblank check is a painting-operator heuristic, not full visibility analysis |
| §9.3 | Core fields as text objects; visible native attachment annotations in each body set | Header HTML text and native/zero-byte notice-page annotations | Field-tag checks do not prove every required field represented; R1 fidelity gap |
| §9.3 | Searchable bodies, page context, color, actionable links, object-level source associations | Print rendering, optional page context, sRGB intent, link annotations | Recommendations have limited source-aware verification; no full object-associated provenance |
| §9.4 | AF files also in name tree; path-free file specs; bare stream MIME; original parameters | Catalog source/finding aids listed; native attachments annotation-only without AF association | Attachment parameters/checksums tested; broader AF/Related Files/object provenance coverage not complete |
| §9.4 / §7.2 | Original raw source preservation for non-isolated files | EML/MSG unchanged; mailbox outputs use exported/reconstructed EML | §7.2 permits export-before-conversion; follow-up documents policy and derivation. Retain external original containers when required. |
| §9.5 | Collections, no default child/hidden view/SWF navigator, required schema, non-editable fields and valid unique names | `PdfCollectionSupport` writes `/Collection`, schema and folders | R2 source-name defect; all custom schema fields and all ISO filename constraints need independent coverage |
| §9.6 Tables 7–8 | Indirect DPart hierarchy, intermediate identities, leaf type/subtype/description, page coverage | `PdfDocumentParts` writes actual objects, parent links and page backreferences | Strong structural tests; R4 body parameter loss; compare identities as relationships, not only global sets |
| §9.7 | Optional logical structure, Document root, namespaces, mappings, content connections and custom-tag rules | `SemanticStructure` imports renderer tree and rebuilds ParentTree/annotation ownership | Correspondence checked; complete TS 32005 inclusion rules and semantic equivalence not demonstrated |
| §9.7 | PDF 2.0 TS 32005 declaration | Exact RDF declaration generated | Declaration presence is not proof of compliance |
| §9.8 / §9.8.1 | Required namespaces/prefixes, software/creation metadata, version/revision/profile | Manual XMP generator with version `1`, rev `2024`, profile | Correct revision is 2024 despite February 2025 publication; complete RDF/schema/type validation still needed |
| §9.8.2 Table 16 | Source assets: original name, size, type, MD5; optional NumberMessages | Assets generated from embedded source plans | Match sizes/hash to preserved bytes; distinguish exported sources from original containers |
| §9.8.3 Table 18 | Message containers, required Sent/GUID, nonempty core fields, FOAF arrays and raw invalid dates | Reader metadata + strict creation date requirement + XMP Bag | Date regressions pass; R3 missing-field acceptance defect; MSG/PST source-header inspection is limited |
| §9.8.4 Table 19 | Attachment name, stored size, original type/parameters, owner, optional MD5 | Native sizes for non-isolated, zero sizes/no original checksum for isolated | Existing annotation/source checks useful; schema/type/severity completeness needs work |

## Required register for a conforming replacement

Use one record per obligation, including inherited/conditional obligations:

```text
requirementId, specification, edition, clause/table,
strength (SHALL/SHOULD/MAY), appliesWhen,
writerComponent, validatorComponent,
positiveFixture, negativeMutation, humanReview,
evidenceArtifact, status (verified/failed/unverified/not-applicable),
approvedInterpretation, reviewer, reviewDate
```

Do not calculate “percent ISO compliant” from the 216 local rule codes: codes combine normative checks, heuristics, recommendations and operational diagnostics; some requirements are absent and some codes are inapplicable. “Not checked” and “not applicable” require different states in a replacement. A full standards claim remains blocked until applicable obligations, independent evidence and interpretations are closed.
