# EA-PDF Browser Extension — Objective C Implementation Plan

**Status:** Viewer beta 0.3.0-beta.1 prepared for the 1.6.0-beta.1 release; see [release results](browser-viewer/docs/release-0.3-results.md). Original milestone brief retained below.
**Target:** Self-contained, open-source Firefox/Chromium extension with PDF.js rendering and targeted PDF.js object-access enhancements
**Primary repository:** `thinkbox-digital/eapdf`
**Date:** 2026-10-27

## 1. Goal and scope

Build an EA-PDF 1.0 viewer that opens a PDF locally, renders its pages, and provides email-aware navigation, metadata, and attachment extraction. It must support the six profiles (`1s`, `1si`, `1m`, `1mi`, `1c`, `1ci`), including embedded child PDFs and mixed/nested collections where allowed by the specification.

The distributed extension must **not** require Java, a native messaging host, a server, a network connection, or installation of a separate application. Bundle an audited, locally packaged version of PDF.js. Do not send document content to third-party services.

**Out of scope for the initial viewer:** Creating or modifying PDFs; converting source email; reimplementing the existing validator; claiming PDF/A, ISO 32005, or EA-PDF certification; automatically intercepting all PDF URLs; and full-text indexing across all embedded PDFs. Add these only through separately reviewed milestones.

**Important distinction:** A file that *declares* EA-PDF conformance is not thereby validated. The viewer should identify declared profile, surface extraction problems, and show conformance as **not evaluated** unless an actual validation result is present and verified.

## 2. Existing assets — preserve and reuse

Work inside the current project unless the repository owner decides otherwise. Do **not** refactor unrelated converter or validator code as part of this work.

| Existing resource | Intended use |
| --- | --- |
| `README.md` | Current output conventions and profile behavior |
| `docs/reference/EA-PDF-v1.pdf` | EA-PDF 1.0 normative requirements; verify the physical file exists before referencing it |
| `docs/reimplementation/05-pdf-and-metadata.md` | Map of this converter's object graph and metadata |
| `validate_eapdf.py` | Reference structural inspection logic and expected semantics |
| `validator-desktop/` | Independent Java validator reference, not a browser dependency |
| `validator_rule_text.json` | Rule descriptions and applicability; do not turn all rules into viewer behavior |
| `tests/fixtures/eapdf/` | Regression and intentionally malformed PDF cases |
| `examples/sample_1s.pdf` through `examples/sample_1ci.pdf` | First-pass sample smoke tests; confirm each file exists |
| `docs/VALIDATOR_COVERAGE.md` | Known limitations and evidence for validation claims |

The existing Python validator includes routines such as `get_dpart_nodes`, `collect_body_content_sets`, `flatten_email_records`, `get_af_specs`, `collect_object_af_specs`, `collect_file_attachment_annotations`, and `read_metadata_stream`. Review their actual source and tests; port *semantics* rather than blindly translating implementation details.

The repository currently documents Java 21, PDFBox 3.x, six EA-PDF profiles, a standalone validator, and an extensive comparison corpus. These assets are reference oracles and test fixtures; none must be shipped in the browser package.

**Do not assume that the converter's conventions are the complete EA-PDF standard.** In particular, its current PDF/A-4f focus and choice of `1s`/`1si` collection children must not become arbitrary format restrictions. Implement broader interoperability after the first proven vertical slice.

## 3. Architectural decision

```text
Browser extension (Manifest V3 where supported)
├── Extension shell
│   ├── Open local PDF via file picker
│   ├── Open from supported URL/context menu (later milestone)
│   └── Browser-specific adapters and permissions
├── Bundled PDF.js
│   ├── Existing rendering/search/zoom/print
│   └── Small, generic object-access extensions
│       ├── DPart/DPM tree access
│       ├── Associated-file discovery and extraction
│       ├── PDF Collection metadata/folder access
│       └── Object-associated metadata streams
├── EA-PDF interpretation (separate TypeScript package)
│   ├── Profile and version identification
│   ├── Mail_GUID ↔ XMP ↔ DPart mappings
│   ├── Message ↔ content-set ↔ page mapping
│   ├── Attachments and source asset associations
│   └── Embedded/nested document graph
└── Viewer UI
    ├── Email/folder tree
    ├── PDF page viewport
    ├── Message metadata
    ├── Files and downloads
    └── Diagnostics
```

### Boundary rules

1. **Only PDF.js parses arbitrary PDF object graphs.** Avoid a competing general-purpose PDF parser.
2. PDF.js enhancements should be useful for general PDF consumers, not hard-coded with EA-PDF vocabulary. Put email semantics in the EA-PDF package.
3. Expose safe, normalized, structured data across PDF.js worker boundaries; do not hand extension/UI code raw internal parser objects or executable references.
4. Keep the EA-PDF interpretation API browser-independent so it can be reused in Firefox, Chromium, and a future website or desktop app.
5. Open embedded child PDFs from extracted bytes **in memory**; track parent-child identity and resource budgets.
6. The extension is read-only. Extracted files must retain original bytes. Never silently rewrite an attachment.
7. Never require Mozilla to merge the enhancements before the extension works. Use a pinned PDF.js fork or patch stack if needed; consider upstreaming generic APIs separately.

### Suggested repository structure

```text
eapdf/
  browser-viewer/
    README.md
    package.json
    package-lock.json
    tsconfig.json
    src/
      domain/
      pdfjs-adapter/
      extension/
      viewer/
    tests/
      fixtures/
    scripts/
    vendor/
      pdfjs-patches/
    docs/
      architecture.md
      inspection-schema.md
      compatibility.md
      test-matrix.md
      decisions.md
```

Adapt names to existing conventions. Do not overwrite existing files or introduce a second repository without documenting the reason.

## 4. Required inspection data model

Define a typed, versioned, *read-only* document model before building much UI. A proposed top-level shape:

```ts
type InspectionResult = {
  schemaVersion: 1;
  documentId: string;
  identification: {
    pdfVersion: string | null;
    pdfaDeclaration: string | null;
    eapdfVersion: string | null;
    eapdfRevision: string | null;
    declaredProfile: 's' | 'si' | 'm' | 'mi' | 'c' | 'ci' | null;
    validationStatus: 'not-evaluated';
  };
  parts: DocumentPart[];
  messages: MailMessage[];
  files: AssociatedFile[];
  collection: CollectionNode | null;
  diagnostics: InspectionDiagnostic[];
};
```

Define referenced `DocumentPart`, `MailMessage`, `AssociatedFile`, `CollectionNode`, and `InspectionDiagnostic` in the actual TypeScript implementation. The snippet above is a conceptual interface, not a completed source file.

Minimum data requirements:

- Preserve PDF object identity (object number/generation or equivalent stable locator) and owning document ID.
- Use stable message identities; distinguish `Mail_GUID` from Internet `Message-ID` rather than conflating them.
- Preserve raw vs. normalized values where they differ, including PDF strings/names and XMP values.
- Represent page ranges with an explicit indexing convention; use zero-based indices at the PDF.js adapter boundary and clearly label any one-based UI values.
- Represent attachments and source assets separately, with physical file locator, owning message(s), MIME type, embedded byte length, declared checksum, and extraction availability as appropriate.
- Distinguish **present**, **missing**, **invalid**, **ambiguous**, **unsupported**, and **not evaluated** information. Do not invent a message owner or attachment association.
- Model collection folders and embedded child PDFs recursively, with cycle detection and explicit resource limits.
- Record extraction warnings and unsupported structures without mistaking them for conformance judgments.

The inspection model must be serializable as JSON for test comparison, excluding the actual binary payloads. Offer binary retrieval through a separate bounded extraction operation.

## 5. Development milestones

### M0 — Repository baseline and runnable PDF.js viewer

**Goal:** A standalone development viewer that opens a local ordinary PDF and uses a pinned PDF.js build. No EA-PDF parsing required yet.

Work:

1. Inspect the current repository, build tooling, and available test fixtures. Record baseline Git status and exact source revisions; do not clean unrelated work.
2. Create `browser-viewer/` with reproducible Node/npm tooling and strict TypeScript checks. Document exact Node and PDF.js version/commit.
3. Use an extension page and file picker to open PDFs locally; keep permissions minimal.
4. Integrate PDF.js rendering, zoom, and page navigation without altering PDF.js internals.
5. Add a minimal smoke test using a checked-in, redistributable PDF fixture.
6. Provide runnable commands (`npm ci`, `npm run build`, `npm test`) and manual loading instructions for Chromium and Firefox; verify each against the locally installed browser.

**Acceptance gate:** One ordinary PDF and one EA-PDF sample display correctly as PDF pages. The extension installs unpacked in a Chromium-compatible browser. Firefox packaging and permissions are evaluated, with remaining incompatibilities recorded. No Java runtime or network calls are used.

### M1 — Inspection contract and authoritative test fixtures

**Goal:** Establish the domain model and expected outputs before extending parsing.

Work:

1. Read EA-PDF 1.0 and the relevant `validate_eapdf.py` routines.
2. Define typed `InspectionResult` and related data types plus a documented JSON schema.
3. Select positive and negative fixtures covering all six profiles and nested collections where available.
4. Produce expected-inspection JSON using an independent Python/Java reference extractor *outside* the extension. If existing validator functions do not expose all viewer fields, create a read-only reference inspection script; do not repurpose a validation PASS into an extraction oracle.
5. Establish fixture manifests containing source file SHA-256, expected fields, required extraction relationships, and expected diagnostics.
6. Preserve ambiguous/unavailable outcomes and known limitations in the expected results.

**Acceptance gate:** Checked-in schema and fixture manifest; at least one documented expected-inspection record for each six-profile sample, with clear provenance. No viewer claims of complete conformance.

### M2 — PDF.js generic DPart/DPM access (highest-risk feasibility milestone)

**Goal:** Prove that a minimal PDF.js patch can expose EA-PDF's document-part tree with correct object and page references.

Work:

1. Inspect the **pinned** PDF.js version, especially its core catalog/object parsing, worker communication, and display APIs. Do not assume filenames or internal methods remain stable across releases.
2. Draft a generic read-only API proposal (for example `getDocumentParts()`), specifying safe object identity, dictionary types, metadata and page reference handling.
3. Add core parsing and corresponding worker/display transport with depth/count/cycle limits.
4. Return a normalized tree with DPart parent/child relationships, DPM key/value entries, start/end page references, and diagnostics for bad references.
5. Keep changes limited and isolated in `vendor/pdfjs-patches/` or a pinned fork. Record upstream commit and maintain a reproducible patch application step.
6. Compare output against the independent fixtures, including malformed leaf ranges, missing parents, duplicate refs, and optional entries.

**Acceptance gate:** For representative `1s` and `1m` PDFs, the browser returns a DPart tree and correct page mappings that agree with the reference extractor; malformed cases terminate safely. Patch application succeeds on a clean checkout.

**Stop/go rule:** If this requires extensive redesign of PDF.js core or an unstable private API leak, record evidence and compare MuPDF.js or a Java-backed approach before expanding the implementation.

### M3 — Associated files, metadata, PDF Collections and child PDFs

**Goal:** Discover and safely extract the files needed for all EA-PDF profiles.

Work:

1. Expose retrieval of catalog XMP and object-associated metadata streams as needed.
2. Enumerate file specifications from the embedded-file name tree, attachment annotations, and relevant `/AF` arrays on arbitrary reachable objects. Preserve actual association origins and owners.
3. Implement binary extraction by validated locator; preserve exact extracted bytes and verify fixture checksums independently.
4. Expose PDF Collection metadata, folders and entries where present, without assuming a flat attachment list.
5. Recursively open embedded EA-PDF child PDFs, retaining parent/child identities and displaying appropriate errors for unsupported files.
6. Apply configurable but enforced byte, depth, child-count, object-count, and decompression limits. Detect repeated references/cycles.

**Acceptance gate:** Extract representative root-level and annotation-only attachments with exact byte hashes; enumerate and open a collection child; identify and report an intentionally malformed association without hanging or guessing ownership.

### M4 — EA-PDF semantic interpretation

**Goal:** Map generic parsed structures to emails and collections according to EA-PDF 1.0.

Work:

1. Parse profile, version, and revision claims from XMP; retain the precise declaration and warn on unrecognized values.
2. Implement the `Mail_GUID` mapping between DPM, email metadata records, and message-related content sets. Keep Internet `Message-ID` separate.
3. Build message/page navigation for `s/si/m/mi` and distinguish source-preserving vs. isolated profiles without assuming all isolated attachments are absent.
4. Build folder/child navigation for `c/ci`, including legal mixed and nested collections rather than only this converter's current `1s`/`1si` children.
5. Associate original sources, page-annotation attachments, and other associated files using explicit references or supported metadata; retain ambiguity if ownership is not provable.
6. Normalize output into the versioned inspection model. Add deterministic diagnostics for missing, inconsistent, or unsupported inputs.

**Acceptance gate:** All six sample profiles are interpreted correctly; selecting each message resolves the expected page or embedded child; attachment ownership matches reference data. No false claims of validation PASS.

### M5 — Browser interface and cross-browser packaging

**Goal:** A usable Firefox and Chromium extension based on the same shared packages.

Work:

1. Implement message/folder tree, PDF viewport, metadata panel, attachment panel, document/profile information, and diagnostics panel.
2. Connect message selection to page navigation and collection child loading.
3. Support safe, user-initiated attachment downloads without executing attachments automatically.
4. Provide in-document PDF.js search; defer cross-child global indexing unless separately scoped.
5. Support opening from a file picker first. Add URL/context-menu support only after permissions and network/privacy behavior are reviewed.
6. Build browser-specific manifests from the same codebase; test extension permissions and file URL behavior separately in Firefox and Chromium.
7. Add clear indicators for unsupported structures and incomplete extraction.

**Acceptance gate:** User opens a file, selects messages, views pages, browses collections, and extracts an attachment in both supported browsers. Normal use requires no helper app, external server, or network connection.

### M6 — Security hardening, interoperability, release

**Goal:** A reproducible, maintainable initial release.

Work:

1. Test malformed PDFs, broken indirect references, deep nesting, decompression bombs, repeated embedded children, invalid XMP, and excessive attachment counts.
2. Use a restrictive extension Content Security Policy. Do not use remote scripts, `eval`, or active content from PDFs. Avoid unsafe HTML injection when displaying email metadata.
3. Disable automatic remote-resource loading and script execution in embedded content. Require user interaction for extracted-file opening.
4. Test representative documents created by software other than this EA-PDF converter when available. Clearly separate tested interoperability from nominal standard support.
5. Run PDF.js existing tests affected by patches plus extension unit/integration and browser tests. Record failing or skipped tests accurately.
6. Produce a software bill of materials, dependency licenses, update procedures, and a patch-maintenance guide for future PDF.js revisions.
7. Produce distributable Firefox/Chromium builds and extension-store submission documentation; do not claim store approval before review.

**Acceptance gate:** Builds are reproducible; tests and coverage are documented; security/privacy behavior is reviewed; Firefox and Chromium packages work from a clean installation; release notes accurately state known limitations.

## 6. Milestone sequencing and gates

**Recommended execution order:** M0 → M1 → M2 feasibility → M3 → M4 → M5 → M6. Implement only the minimum UI in M0; avoid investing heavily in UX until M2 proves that PDF.js can expose the required objects.

| Gate | Question | Decision |
| --- | --- | --- |
| G0 | Can the extension reliably render PDFs locally? | Proceed to contract/fixtures |
| G1 | Are expected metadata and relationships defined independently? | Proceed to parser modifications |
| G2 | Can targeted PDF.js patches expose DPart/DPM safely? | Proceed or reconsider architecture |
| G3 | Can attachments and collections be extracted accurately? | Proceed to semantics/UI |
| G4 | Do all six profiles map accurately to the inspection model? | Proceed to production polish |
| G5 | Does one codebase work in Firefox and Chromium safely? | Prepare release |

At each gate, require: changed-file list, exact commands run, test results, outstanding uncertainty, and next scoped task. Do not turn incomplete or skipped checks into successful claims.

## 7. Codex operating procedure (IntelliJ IDEA)

Use one milestone per Codex task. Require Codex to inspect the repository and plan first, then implement a limited vertical slice and run relevant tests. Avoid asking it to implement the entire extension in one prompt.

### First Codex prompt — initialize M0

Paste this into Codex after saving this file in the repository root or `docs/`:

> Read `EA_PDF_Objective_C_Implementation_Plan.md` fully and inspect the existing repository before changing anything. Implement **M0 only** under `browser-viewer/`. This is a self-contained EA-PDF viewer project that uses a pinned PDF.js distribution, with no Java helper, backend, or remote service. Preserve all existing converter/validator behavior and existing print statements. Do not refactor unrelated code. Establish a reproducible Node/TypeScript extension project with local file-picker opening, PDF.js page rendering, page navigation, zoom, minimal automated tests, and build instructions. Use the simplest maintainable implementation. Verify dependency licensing, package-lock consistency, and Manifest V3 requirements for Chromium. Document any Firefox-specific compatibility work still needed. Run the relevant commands and report the exact results, changed files, and unresolved issues. Do not start M1 until M0 acceptance is met.

### Second Codex prompt — M1 contract and fixtures

> Implement **M1 only** as described in `EA_PDF_Objective_C_Implementation_Plan.md`. Inspect actual EA-PDF samples, the specification, `validate_eapdf.py`, and repository evidence. Define a versioned TypeScript inspection model, JSON schema, and fixture manifest. Where needed, create an independent development-only reference extractor; do not ship Python or Java in the extension. Distinguish actual metadata from inferred, unavailable, or invalid information. Ensure all six EA-PDF profiles have representative expectations and traceable provenance. Run focused checks and report results. Do not change the converter or validator.

### Third Codex prompt — M2 PDF.js feasibility

> Implement the smallest **M2** vertical slice only: using the pinned PDF.js source, expose a generic read-only DPart/DPM inspection API through the supported display/worker boundary. Do not expose raw core objects or create EA-PDF-specific dependencies inside generic PDF.js code. Cover one `1s` and one `1m` fixture plus malformed references; preserve object identities and page mappings. Maintain reproducible patches against an exact upstream revision. Demonstrate parity with independent expected inspection data. If a clean API cannot be implemented without invasive changes, stop and document the blocker rather than inventing a workaround.

### Subsequent Codex tasks

For M3–M6, use the milestone heading and acceptance gate verbatim as the scope. Require one reviewable change set per feature. After every task: run tests, report real outputs, and update `browser-viewer/docs/decisions.md` only with decisions actually made.

### Persistent engineering constraints

- Keep the existing converter and validators untouched unless a separate change is explicitly approved.
- Do not remove existing print statements.
- Do not silently rewrite existing tests or expected results to make new behavior pass.
- Keep the EA-PDF interpreter separate from generic PDF.js changes.
- Never introduce hidden network requirements or telemetry.
- Do not claim conformance from metadata declarations or omitted validation steps.
- Use exact versions for PDF.js, dependencies, and fixtures. Track hashes.
- Prefer small, reviewable changes. Do not use unsupported internals in the extension application code.
- Document assumptions and unresolved specification interpretations explicitly.

## 8. Test matrix

| Input category | Must verify |
| --- | --- |
| Ordinary non-EA-PDF PDF | Renders; extension does not falsely classify it |
| `1s`/`1si` | One message; correct content sets; attachment/source omission semantics |
| `1m`/`1mi` | Multiple messages; correct GUID relationships and page navigation |
| `1c`/`1ci` | Collection membership and embedded child opening |
| Mixed/nested collections | Recursive navigation, document identity, cycle/resource bounds |
| Catalog `/AF` source | Correct file association and exact bytes |
| Page `FileAttachment` annotation | Correct owning page/message and exact bytes |
| Other-object `/AF` | Discovery without inventing ownership |
| Names-tree embedded file | Discovery and correct role/owner attribution where provable |
| Object metadata | Correct XMP extraction from supported object locations |
| Incorrect DPart references | Safe diagnostics, no crash/hang |
| Missing or ambiguous `Mail_GUID` | Unavailable/ambiguous interpretation, not guesswork |
| Large PDFs and attachment payloads | Bounded memory, clear limits and cancellation |
| Third-party EA-PDF | Interoperability beyond the existing converter's conventions |
| Firefox and Chromium | Same visible navigation and extraction results |

Use representative real data only when legally redistributable. Keep sensitive email archives out of public test fixtures and logs. Include independent SHA-256 checks for extraction fidelity.

## 9. Documentation and release deliverables

Required final outputs:

1. Extension source and reproducible builds for Firefox and Chromium.
2. Pinned PDF.js upstream revision and a documented, reproducible patch set or fork.
3. Independent typed EA-PDF interpretation package with versioned inspection schema.
4. User interface with message navigation, collections, metadata, and file extraction.
5. Positive and negative fixtures plus automated extraction comparisons.
6. Privacy/security model and explicit resource limits.
7. Installation instructions, supported feature matrix, and known limitations.
8. Upstream proposal for **generic** PDF.js APIs, as a separate optional deliverable; upstream acceptance is not required for product release.

## 10. Reference materials

- Repository: `https://github.com/thinkbox-digital/eapdf` (private; Codex must use the locally available checkout)
- EA-PDF standard: `https://pdfa.org/resource/ea-pdf/`
- PDF.js repository: `https://github.com/mozilla/pdf.js`
- PDF.js getting started and internal architecture: `https://mozilla.github.io/pdf.js/getting_started/`
- PDF.js `PDFDocumentProxy` API: `https://mozilla.github.io/pdf.js/api/draft/module-pdfjsLib-PDFDocumentProxy.html`
- Chrome extension Manifest V3: `https://developer.chrome.com/docs/extensions/develop/migrate/what-is-mv3`
- Mozilla cross-browser extension guidance: `https://developer.mozilla.org/en-US/docs/Mozilla/Add-ons/WebExtensions/Build_a_cross_browser_extension`

**First executable objective:** Complete M0 and prove a locally installed unpacked extension can render `examples/sample_1s.pdf` using bundled PDF.js, without any changes to your existing Java/Python implementation.


## 2026-10-31 implementation checkpoint

Local security controls now cover the creator and both validators as well as the
viewer. [Hardening record](docs/security/HARDENING.md) and
[verification](docs/security/verification.json) give resource policies,
reproducibility evidence, test skips and limits. All 24 ordinary examples and four
fresh real-source collections pass the standalone Java validator. Large PST work
is excluded. Full public M6 release acceptance remains partial: store signing and
review, native permission-prompt acceptance, wider platforms and independent
review are unverified. Candidate artifacts are local and unreleased.


The [beginner user manual](docs/EA-PDF_User_Manual.pdf) is complete: 31 pages,
ten real application screenshots using fictional mail, linked contents and
bookmarks, and separate creator, Java validator, Python/web validator and viewer
chapters. Bundled and CLI veraPDF both pass PDF/A-2b (31,449 checks, zero failures).
Editable HTML, build/capture instructions and receipts are under `docs/manual/`.
The manual reproduces byte for byte and identifies this checkpoint as unreleased.
