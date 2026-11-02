# Engineering review and rewrite reference

Review date: 2026-10-17. Application version: 1.4.0.

**Latest implementation checkpoint — B01, 2026-10-18:** [Work log](../EA_PDF_WORK_LOG.md), [partial requirements register](../improvement/requirements.json) and [verified evidence](../improvement/batch-01-latest-evidence.json). Arrays, creator attribute preservation and unseen-rule completion are repaired; the generated corpus and all current saved samples pass their expected checks. Full tests: 134 Java passes, 150 Python passes and one schema skip. Priorities 1 and 2 remain in progress; older evidence below is historical.

**Current collection sidecar policy:** [collection-sidecars-evidence.json](collection-sidecars-evidence.json), [DEVELOPMENT_STATUS.md](../DEVELOPMENT_STATUS.md) and [unfixed.md](../../unfixed.md) record completed R14: c/ci auxiliaries are external PDF/XLSX sidecars, their ordinary index PDFs contain no embedded files or synthetic email, and m/mi retain XLSX embedding. All six regenerated samples and both PDF/A sidecars pass. Full Maven: 132 passes; packaged Python: 139 passes and one optional schema skip. The strict validator/creator remain unchanged. The R13 decision is resolved; R11–R13 embedding/review snapshots below are historical.

**Repair follow-up:** The assessment and `review-evidence.json` below are the original, pre-repair snapshot. Subsequent specification-driven fixes and verified outcomes are recorded in [fixed.md](../../fixed.md); remaining work and restart instructions are in [unfixed.md](../../unfixed.md). The latest [repair contracts](09-repair-follow-up.md) and [follow-up evidence](followup-evidence.json) include supervised workers, recoverable PDF/sidecar publication, raw non-SMTP metadata and release packaging. Do not treat a historical finding as a description of the repaired code. In particular, §7.2 explicitly permits export-before-conversion of mailbox messages; the follow-up documents that policy and records its provenance rather than claiming original-container retention.

**This implementation is not ready for unrestricted production archival use, and complete EA-PDF/ISO conformance is not established.** New executable probes found defects after the existing suites passed. Start with [the assessment](01-readiness-review.md).

These documents describe the inspected implementation and provide a language-independent starting point for a replacement. They are not a certified design or a substitute for the referenced standards. Existing behavior, confirmed defects, and proposed replacement requirements are distinguished explicitly. A replacement should preserve the useful behavior and resolve the defects, rather than treating every current output as an oracle.

## Reading order

| Document | Purpose |
| --- | --- |
| [01-readiness-review.md](01-readiness-review.md) | Verdict, ranked findings, evidence, limits and release gates |
| [02-standards-and-coverage.md](02-standards-and-coverage.md) | Applicable specifications, profile matrix, clause coverage and unresolved interpretations |
| [03-architecture.md](03-architecture.md) | Every production module, boundaries, dependencies and execution sequences |
| [04-data-and-ingestion.md](04-data-and-ingestion.md) | Current model, source readers, MIME selection, identity, staging and recovery |
| [05-pdf-and-metadata.md](05-pdf-and-metadata.md) | PDF object graph, DPM, XMP, annotations, collections, tagging and finding aids |
| [06-interfaces-and-operations.md](06-interfaces-and-operations.md) | CLI/configuration/web contracts, limits, storage, errors and deployment |
| [07-validation-and-acceptance.md](07-validation-and-acceptance.md) | Validator architecture, known blind spots, portable acceptance cases and release evidence |
| [08-porting-plan.md](08-porting-plan.md) | Replacement component contracts, implementation order, decisions and handover checklist |
| [09-repair-follow-up.md](09-repair-follow-up.md) | Current specification-driven repair contracts and acceptance tests; supersedes corresponding pre-repair behavior |
| [message.schema.json](message.schema.json) | Proposed normalized interchange model; not an existing application API |
| [message.example.json](message.example.json) | Synthetic message with exact source bytes and fixity |
| [review_probes.py](review_probes.py) | Reproducible synthetic review probes; generates private temporary evidence |
| [review-evidence.json](review-evidence.json) | Durable summary of this review's test and probe results |
| [repair-evidence.json](repair-evidence.json) | Earlier repair-batch snapshot, retained with its historical hashes and remaining-work list |
| [followup-evidence.json](followup-evidence.json) | Earlier resumed acceptance, artifact hashes, repeat packaging and remaining external verification |
| [additional-rules-evidence.json](additional-rules-evidence.json) | Subsequent file-set, collection-content and UTF-8 repairs, explicit rule 96 tests, regenerated samples and final 129/139-test acceptance |
| [nested-finding-aid-evidence.json](nested-finding-aid-evidence.json) | Historical R11 nested generated-message finding aid and acceptance |
| [sample-refresh-evidence.json](sample-refresh-evidence.json) | Historical sample cleanup/regeneration and artifact hashes |
| [embedded-only-index-evidence.json](embedded-only-index-evidence.json) | Historical R12 embedded-only correction and acceptance |
| [direct-xlsx-review-evidence.json](direct-xlsx-review-evidence.json) | Historical R13 direct-attachment review and strict conformance failures |
| [collection-sidecars-evidence.json](collection-sidecars-evidence.json) | Current R14 collection-only sidecar policy, full tests and regenerated sample hashes |

## Snapshot and scope

The reviewed HEAD is `0b08414534c4841c03183c767c1e4ad2377bf9ad`, but **the review is of the modified working tree**, including untracked production classes and tests, not that commit alone. Existing user changes were retained. Production behavior was not changed by this review. Build artifacts and probes were written outside `target/` and existing debug directories.

The review covers all 24 Java production source files, both Python application files, Maven packaging, configuration, deployment units, helper scripts, existing test coverage, local EA-PDF normative clauses and relevant PDF/A-4/PDF 2.0 requirements. Correctness-sensitive paths were read and exercised with targeted probes. This is an engineering review, not formal verification of every branch or every dependency, a penetration test, or a complete audit of the thousands of underlying ISO PDF requirements. Browser/GUI interaction, live Gmail, arbitrary PST corpora, assistive technology, and production load were not fully exercised.

The earlier review in [CREATOR_COVERAGE_REVIEW.md](../CREATOR_COVERAGE_REVIEW.md) contains both historical defects and subsequent fixes. The findings here refer to newly inspected current behavior; do not import its pre-fix findings as current defects.

## Reproduce

From the repository root, with Java 21, Maven, the Python validator dependencies and Docker/veraPDF available:

```sh
mvn -Deapdf.build.directory=/tmp/eapdf-review-build package
EAPDF_GENERATOR_CLASSPATH=/tmp/eapdf-review-build/emailtopdfa-1.4.0.jar \
  EAPDF_INTEGRATION_REPORT_DIR=/tmp/eapdf-review-integration \
  python3 -B -m unittest discover -s tests -v
python3 -B docs/reimplementation/review_probes.py \
  --jar /tmp/eapdf-review-build/emailtopdfa-1.4.0.jar \
  --output /tmp/eapdf-rewrite-probes
```

The probe runner reports observed behavior, including expected current defects; its successful execution does not mean production acceptance. Its output directory must be new. Add `--pdfa` for independent checks and `--premis-schema /path/to/premis.xsd` with `lxml` installed to check the official PREMIS schema. No network fetch is needed for the default probe run.

The original review's detailed logs are under `/tmp/eapdf-review-20261017/`; temporary logs are not durable handover material. Preserve the referenced standards, a source snapshot, dependencies/assets with rights information, fresh outputs and reports as a versioned release bundle before beginning a rewrite.
