# Changelog

This file tracks human-readable release history for the repository.

Current beta version:
- `1.6.0-beta.16`

Notes:
- Release tracking starts here.
- This changelog is meant to track feature-level changes for users and operators.
- It does not replace commit history.

## 1.6.0-beta.16 - 2026-11-06

- Require an output directory in the creator GUI instead of falling back to out.
- Expand Other files by default in viewer 0.5.0-beta.15.

## 1.6.0-beta.15 - 2026-11-06

- Include inactive memory in the macOS conversion memory estimate, retaining the existing reserve.
- Rebuild viewer 0.5.0-beta.14 with the release.

## 1.6.0-beta.14 - 2026-11-06

- Label the signing-password field inside the dialog, including when the window title is hidden.
- Rebuild viewer 0.5.0-beta.13 with the release.

## 1.6.0-beta.13 - 2026-11-06

- Fix Windows creator progress-log encoding so punctuation and accented text display correctly.
- Rebuild viewer 0.5.0-beta.12 alongside the creator and validator release.

## 1.6.0-beta.12 - 2026-11-06

- Keep filtered email results active and follow their order when scrolling in viewer 0.5.0-beta.11.
- Prevent child-validator diagnostics from corrupting worker acknowledgements.
- Refresh beta installation, navigation and known-limitations guidance.

## 1.6.0-beta.11 - 2026-11-06

- Fix browser extension manifest versions and guard against stale package versions.
- Update website and PDF manual release instructions; reject stale versions during website builds.

## 1.6.0-beta.10 - 2026-11-05

- Viewer 0.5.0-beta.9 improves large-archive loading and responsive navigation.
- Show source-omission notices only for isolated profiles; simplify file details and empty-file controls.
- Normalize tabs in CSV exports for spreadsheet import.
- Remove internal development records and the local regression corpus from the public source distribution.

## 1.5.0-beta.1 - 2026-10-26

- Improve ordinary EML, MSG, PST, MBOX and Gmail creation, including header/body encodings, recipient groups, native reply fields and stored images.
- Preserve original messages, native attachments and stored PST transport headers; keep omitted-source/attachment policies explicit.
- Fix missing body text, damaged character references, clipped wide tables and duplicate attachment markers. File lists stay plain.
- Share archive footer resources: the checked 1,433-message archive is 43% smaller and creates at the same 512 MB heap.
- Protect existing output during naming collisions and interrupted/failed creation.
- Retain explicit unavailable-evidence results for unsupported MSG comparisons and large-file resource limits; repair isolated placeholder ownership and native Outlook source comparisons.
- Include the reviewed regression fixtures, current examples and beta evidence. Remove the retired social-media generators from the pending source update.

- Add the standalone Java validator with bundled PDF/A/selected ISO engines and all 213 rules in specification order.
- Decode adjacent encoded subject words, preserve native Outlook recipient order and use stored sent timestamps.
- Separate runtime rule data from review records, preserve the newer reviewed wording, and synchronize packaged checksums and summaries.
- Include the specification-author review DOCX with verified source quotations, complete table extracts and all 19 original source tables.
- Publish creator CLI/GUI and standalone validator JARs, checksums and license notices as release assets.
- Verification: 277 creator tests; 154 validator tests with one optional skip; 1,142 Python passes and 40 optional skips.

This is a beta for ordinary email creation and validation on the tested Linux setup. Existing source-format, large-archive and specification-interpretation limits remain; this release does not claim complete standards or accessibility certification.

## 1.4.0 - 2026-10-16

- reconcile normalized input GUIDs, source/native bytes, attachment owners and occurrences against final PDFs before replacement; retain explicit isolated-source/placeholder policy


- reject damaged embedded collection children before atomic replacement; apply existing creator checks recursively to final child bytes with bounded traversal


- keep malformed optional encoded-header failures local to their comparisons; prevent decoder repairs from proving fidelity and retain raw-source/engine-failure distinctions

- retain ordered parsed source-header occurrences and map supported distinct repeated values to observed custom fields; preserve ambiguous matches and observations without original sources

- decode observed custom header fields within their actual content scopes and compare source/message ownership; retain partial coverage warnings and explicit unavailable comparisons

- inspect Content Set paint/structure and observed header field connections for tagging recommendations; preserve incomplete header-inventory evidence and distinguish known non-body ranges from unresolved body comparisons

- expose unavailable source/policy recommendation assessments as warnings; keep extraction failures incomplete and prevent partial page-context assessments from passing all pages

- resolve class and direct UserProperties with array-order/whole-list precedence; copy shared attributes before creator updates; add 18 attribute fixtures

- compare declared mail attributes with the correct owning source; warn for source-value changes under the existing recommendation and record explicit unavailable comparisons; add 17 multi-message fixtures
- validate Mail_Sent user properties as PDF dates, including syntax/calendar/timezone boundaries and permitted shortened/legacy forms; add a separate 25-case date corpus
- enforce actual PDF object types for EA-PDF user properties, including ASCII-string names, nonnegative integer counts and generic-header value/tag restrictions
- fix absent-header/embedded-file applicability boundaries and make profile tests reject incomplete recursive validation; add a separate 27-case reviewed type corpus
- handle PDF array forms of structure attributes/root children and preserve existing creator attributes when adding mail properties
- report unperformed checks as incomplete instead of inventing N/A; validate declared collection attachment fields and preserve optional-tagging/profile boundaries
- add the first creator-derived rule corpus and a durable implementation audit with a partial requirements register

### Fixed

- publish finding-aid PDF and XLSX sidecars for `1c`/`1ci`, preserving strict collection membership; remove generated-email wrappers and all index-PDF attachments; retain XLSX embedding in `1m`/`1mi` archives

- repaired creator-review defects in date handling, original body/media metadata, MIME parameters, portable filenames, stylesheet visibility, and direct native attachment annotations
- made isolated attachment XMP describe the stored zero-byte representation while retaining original-payload provenance separately
- encoded embedded-file MD5 parameters as 16 binary bytes, distinct from hexadecimal XMP checksum text
- added staged-PDF consistency checks before atomic output replacement and regressions for malformed metadata, hidden annotations, stylesheet fidelity and source-date rejection
- tightened validator declaration/attachment checks and report unidentified Core Field text encoding as incomplete instead of silently not applicable
- verified PST attached-email reconstruction against a real Apache Tika fixture, including its nested DOCX attachment
- bounded mailbox parsing, metadata accumulation, rendering concurrency, and message/body processing with explicit limits
- streamed completed message PDFs and collection children through temporary files instead of retaining full output byte arrays
- preserved multilingual body and finding-aid text with bundled and configurable fallback fonts; missing glyphs now identify affected fields and code points
- retained semantic headings, paragraphs, tables, image descriptions, and annotation connections across message, archive, and collection output when accessibility support is enabled
- retained collection front-matter structure and corrected attachment-link annotation rewrites without losing structure references

### Added

- opt-in Gmail resume storage keyed by account, export settings, and message ID, with checksum verification, stable selection order, and concurrent-export locking
- a repeatable six-profile sample regeneration script that validates every candidate before replacing tracked PDFs
- regression coverage for real PST nesting, interrupted Gmail recovery, multilingual extraction, processing limits, and semantic structure in all six profiles

### Changed

- upgraded all direct Java dependencies and explicit Maven build plugins to their latest stable releases checked on 2026-10-16; migrated to PDFBox/XMPBox 3.0.8, the community OpenHTMLtoPDF 1.1.97 renderer, RTF conversion 2.0.3, and JUnit 6.1.3
- aligned POI scratchpad with POI/OOXML 5.5.1 and Google HTTP modules with the 2.2.0 BOM; explicitly pinned the imported Gson and HTTP APIs
- raised Python minimums to Flask 3.1.3, Gunicorn 26.2.0, pikepdf 10.17.0, pypdf 6.21.0, Werkzeug 3.1.9, and ReportLab 5.0.1, allowing their current major releases
- preserved shared page references across repeated PDFBox 3 saves so collection outlines, document parts, and accessibility structures continue to address the actual page-tree pages
- refreshed dependency/version/license tables and third-party notices for the upgraded stack
- CLI/GUI artifacts and release documentation now use version `1.4.0`
- all six tracked email PDF samples are refreshed using the current generator
- accessibility remains labeled partial: automatic structure checks do not establish PDF/UA certification or replace assistive-technology review

## 1.3.1 - 2026-03-27

### Changed

- release metadata now tracks the current repository state at `1.3.1`
- GitHub release assets are refreshed from the current `mvn package` build outputs

## 1.3.0 - 2026-03-24

### Added

- tracked sample output PDFs in [examples](/media/lepore/Work/thinkbox/convert/examples) for the six email profile examples plus Twitter and Facebook `1m` examples

### Changed

- release documentation now points at the checked-in `examples/` sample set

## 1.2.0 - 2026-03-23

### Added

- GUI feature checkboxes for branding, finding aid, PREMIS, and PDF/UA settings in [EapdfGui.java](/media/lepore/Work/thinkbox/convert/src/main/java/eapdf/EapdfGui.java)
- tooltip help text for the new GUI feature toggles

### Changed

- desktop GUI subtitle now uses `EmailToPDF` wording and no longer appends a dev version label
- GUI-run conversions now write a temporary config override so existing CLI/config behavior stays intact

### Fixed

- archive finding-aid generation now honors the enabled or disabled finding-aid config state from GUI-driven runs

## 1.1.0 - 2026-03-23

### Added

- JSON config-driven feature control via [config/eapdf_config.json](/media/lepore/Work/thinkbox/convert/config/eapdf_config.json)
- optional PDF/UA-style catalog/accessibility features
- optional PREMIS metadata embedding
- optional PREMIS pretty-printed sidecar XML output
- Gmail setup documentation in [GMAIL.md](/media/lepore/Work/thinkbox/convert/docs/GMAIL.md)
- searchable workflow decision tree PDF in [eapdf_decision_tree.pdf](/media/lepore/Work/thinkbox/convert/docs/eapdf_decision_tree.pdf)
- feature inventory in [FEATURES.md](/media/lepore/Work/thinkbox/convert/docs/FEATURES.md)
- per-file logs in local per-file logs
- per-file validation report links in the web validator
- MBOX reader support
- Twitter import support for CSV and JSONL exports
- Facebook import support for export JSON and scrape JSONL
- live-style Twitter rendering
- live-style Facebook rendering
- PDF finding-aid/index generation for archive/container outputs
- optional Excel finding-aid index generation

### Changed

- project root was reorganized into `config/`, `assets/`, `docs/`, and `archive/`
- branding assets moved out of the repository root
- config files moved out of the repository root
- standards/reference material moved into [docs/reference](/media/lepore/Work/thinkbox/convert/docs/reference)
- Twitter and Facebook outputs now support richer media handling
- decision-tree output now exists as both PNG and searchable PDF
- validator web UI now surfaces saved validation-report HTML links
- PDF/A failures are shown as EA-PDF failures in the web UI

### Fixed

- validation-report visibility gaps in the web UI
- multiple validation wording/expected-value issues
- duplicate embedded social-media payloads in archive output
- doubled attachment filename extensions in some social outputs
- remote image fetch normalization for problematic email assets
- several archive/collection validation regressions
- PDF decision-tree wrapping and box-fit issues

## 1.0.0

Initial baseline version from the existing Maven project version in [pom.xml](/media/lepore/Work/thinkbox/convert/pom.xml).
