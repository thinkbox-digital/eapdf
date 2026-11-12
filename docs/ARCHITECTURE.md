# Architecture and separation decisions

Source review: 11 November 2026. The agreed responsibility refactor is implemented locally.
The boundaries below define its scope; retained orchestration is deliberate, and
further decomposition is future work rather than an unfinished extraction.
Public CLI interfaces are unchanged. Start with the
[current status](PROJECT_STATUS.md) and [contributor guide](../CONTRIBUTING.md).

## Extraction progress

| Boundary | Local implementation |
| --- | --- |
| PDF metadata | `PdfMetadataWriter` consumes records, configuration and an explicit asset interface. The writer facade retains its call sites. |
| Rendering resource policy | `MailRenderPolicy` owns resource resolution, remote-resource blacklisting, image checks/orientation and the existing thread-local state. It has no dependency back on `PdfMailWriter`; compatibility fields reference the same state, not copies. `MailHtmlRenderer` owns HTML preparation, canonicalisation, CID asset materialisation, renderer setup, fallback and wide-content fitting. Accessibility/profile/display-file callbacks are injected; assembly and publication remain in the facade. |
| Attachment/collection ownership | `PdfAttachmentSupport` owns archive file plans/bindings, filename reservation, media parameters, checksums and badge appearances; its plan builder borrows the inclusion policy. `PdfCollectionSupport` owns member/folder/segment values and payload staging. The attachment helper no longer depends on the writer. Collection assembly still calls the writer for child PDF creation and shared structure mutation. |
| Creator GUI | `GuiConversionProcess` owns one run's process, cancellation, merged durable output and private configuration/password cleanup. Swing retains EDT dispatch and user-facing outcomes. `GuiRunConfiguration` assembles the existing run options. Tests cover cancellation before/after startup, UTF-8 output, start failure and cleanup. |
| Validator GUI | `ValidationReportReader` implements parsing behind the existing result API, reusing `ReportFindings` and its outcome rules. |
| Viewer Files pane | `FilesPanel` owns attachment presentation, description caching and virtual-list cleanup. Explicit actions return to the existing viewer operation/session owner. `attachment-types.ts` and `viewer-elements.ts` hold shared presentation helpers. Sorting, empty-file downloads, preview availability and Conversations disclosure defaults are preserved. |
| Viewer message tree | `MessageTree` owns document/message rows, folder presentation, embedded-PDF alias placement, virtual-list lifetime, mounted subject labels, folder counts and adjacent-row traversal. The viewer keeps current selection, sessions, generation/cancellation, background indexing and navigation operations. Four explicit callbacks connect the controller to that owner. |
| Viewer search/filter | `SearchController` owns cursors, isolated search workers, result/virtual lists, filter revisions and metadata-index work. Live navigation accessors share the existing operation/session owner; background subject indexing uses the same metadata promise. Whole-word matching, cancellation, stale-index fallback and keyboard/scroll navigation are preserved. |
| Python report model/presentation | `eapdf_validation.report_model` owns report accumulation, delegation, precedence and summaries; the root public `Report` binds four live policy hooks and retains its constructor and instance state. `eapdf_validation.reporting` owns combined outcomes and HTML rendering. Public imports, monkey-patching and report serialization retain their meaning. |
| External validation engines | `eapdf_validation.engines` owns veraPDF/Docker command construction, execution/report interpretation and pinned ISO-profile verification. Root facades pass immutable settings and explicit live callbacks; rule checks and public monkey-patching contracts remain in the root module. |
| Python rule families | `eapdf_validation.pdf_rules` owns PDF version, page-count, output-intent, catalog and font checks. An explicit borrowed document/report context and object-helper interface keep file lifetime and root callbacks unchanged. The XMP, embedding, logical-structure, message/source and media families live in separate modules. Frozen explicit dependency contexts are assembled from current root bindings on each call; root facades preserve patching and signatures. Shared parsers/source helpers, recursion and cross-family orchestration remain central. |
| Web services/presentation | `eapdf_web.jobs` owns filesystem capabilities, expiry and admission slots; `eapdf_web.pages` owns page templates. The root app supplies immutable settings/contexts per operation, retaining live configuration, shared deadlines, authentication and routes. |

The full creator and browser gates pass for these extractions; focused Python
API checks and exact HTML comparisons pass. Full public Python discovery passes
in the hash-locked runtime. Earlier JBIG2 failures came from system Python's older
pikepdf; the source lock already contains the fix. See the
[current verification summary](PROJECT_STATUS.md).

The agreed extractions are complete. Keep DPart/logical-structure mutation,
collection child creation, Python recursion/source parsing and cross-family
coverage coordination with their existing owners. Moving these solely to shrink
files would create another dependency layer without a separate lifetime contract.
No speed or memory improvement is claimed from structural extraction.

Final acceptance passes creator and standalone Java validator suites, both browser
acceptance suites, 59 public Python regressions and real EML/MSG/whole-MBOX fidelity,
visual and Java compliance gates. The broader local rule suite and its isolated
quality module also pass within their documented fixture/optional-test scopes.
Complete findings and summaries match the previous validator on three real controls.
See [current status](PROJECT_STATUS.md) for counts and limits.

## Post-refactor large-archive comparison

Authorised on 11 November 2026, explicitly after the refactor and its acceptance
checks. The [controlled comparison](PDFJS_LARGE_ARCHIVE_COMPARISON.md) records the
method, measured results and scoped recommendations. Selection now includes
upstream suitability: favor small generic changes with a credible PDF.js adoption
path, and keep EA-PDF inspection and local resource policy separate. Local wins
alone do not establish upstream readiness. Compare pinned stock PDF.js, an EA-PDF feature-equivalent control with
patch 0003 optimizations reversed, and the complete patch stack. Review and record
any compatibility edits needed to construct the control. Stock PDF.js supplies
the ordinary rendering/text/attachment comparison; complete custom inspection
requires the feature-equivalent control.

Use immutable real archives plus small controls and isolated edge cases, recording
input/build hashes. Check text, page pixels, attachment bytes, metadata and folder/
message membership before assessing performance. Run identical fresh-process and
warm workloads with equal worker counts and external budgets in Chromium and
Firefox, using 5–10 alternating trials. Record latency, peak/retained memory,
variability and explicit failures. Ablate compact xref storage, parsed-object
cache limits, decoder-buffer reclamation and the single page-tree traversal
individually. Deliver per-case results and a keep/simplify/remove recommendation
for each optimization; establish acceptance thresholds before measurements.
Keep experimental builds separate from the shipped viewer and do not change
production patches based solely on code size.

## Data and execution ownership

| Flow | Existing owners |
| --- | --- |
| Local inputs to records | `EmailToPDFA`, `EmlReader`, `MsgReader`, `PstReader`, `MboxReader`, `Models` and `Common` in `src/main/java/eapdf/` |
| Gmail to records | `GmailDownloadToPdf`, with its OAuth/checkpoint workflow, feeds the same conversion pipeline. |
| Conversion lifecycle | `ConversionSupervisor` owns worker lifetime/budgets; `OutputTransaction` owns staged publication/recovery; `PreservationInventory` checks source/payload coverage. |
| Records to PDF | `PdfMailWriter` coordinates HTML rendering, PDF construction, XMP, structure and embedding. Existing `PdfAttachmentSupport`, `PdfCollectionSupport`, `FontSupport`, `PdfFontMetadata` and index helpers already own parts of this work. |
| Java validation | `JavaValidation` and `ValidationSession` coordinate the checks and engines; `MetadataChecks`, `SourceHeaders`, `OutlookSource`, `MimeSourceStore` and `ReportFindings` supply separate responsibilities. `ValidatorGui` presents results. |
| Python/web validation | `validate_eapdf.py` retains orchestration, source/object helpers and compatibility facades. `eapdf_validation/` owns report modelling, presentation, external-engine adapters and the PDF/XMP/embedding/structure/message/media rule families. `validation_worker.py` supplies the process boundary; `pdf_stream_limits.py` supplies bounded decoding. `validator_web.py` owns Flask routes/auth/policy, with job and page components under `eapdf_web/`. |
| Browser parsing and lifetime | `browser-session.ts` owns document workers, extraction, identity and caches. `domain/` owns interpretation. Patched PDF.js stays behind the documented inspection APIs. |
| Browser presentation | `viewer.ts` coordinates selection, navigation and operations. `message-tree.ts`, `files-panel.ts` and `search-controller.ts` own their presentation/search state. `scrolling-pages.ts`, `portfolio.ts`, `portfolio-view.ts`, `thread-preview.ts`, search/index modules, `metadata-dialog.ts`, `metadata-export.ts`, `message-download.ts` and `pane-resizer.ts` supply existing components. |

### Disposition of the 11 November Qodana export

Source/dependency changes cover 33 of the 40 exported findings: exception
preservation, the Log4j API dependency update, redundant checks/stores, explicit
long arithmetic, simpler conditions and normalization, and removal of an unused
collection. All folder-resolution validation remains. These counts describe a
review of the supplied export, not a claim about a subsequent scanner run.

Seven findings have intentional behavior: three sleep loops implement parent
watchdogs or interruptible network backoff; the shared job monitor protects the
same state as other job operations; an image-size probe retains its documented
unknown-size fallback; and two equivalent regular-expression spellings belong to
the pinned upstream PDF.js bundle. No inspections are disabled and the PDF.js
patch stack is unchanged. Avoid editing generated vendor bundles for stylistic
findings. The native JBIG2 regression is checked separately in the locked Python
environment and passes there.

## Central-file decisions

Line counts below record the original source review; they are not quality scores
or performance measurements. The progress table above identifies completed
extractions; these decisions describe the overall separation plan.

| File | Lines | Decision |
| --- | ---: | --- |
| `src/main/java/eapdf/PdfMailWriter.java` | 3,977 | Separate by responsibility. Keep the existing class as the conversion facade while extracting metadata generation and rendering/resource policy. Extend the existing attachment and collection helpers instead of creating competing implementations. |
| `browser-viewer/src/viewer.ts` | 1,138 | Separate navigation/tree, file-panel presentation and search/filter orchestration. Keep one owner for current document, generation tickets, operation state and cancellation. |
| `src/main/java/eapdf/EapdfGui.java` | 913 | Extract conversion-process lifecycle and run-configuration assembly first. Keep Swing layout together until that boundary is established. |
| `validator-desktop/src/main/java/eapdf/ValidatorGui.java` | 644 | Extract report parsing/result modelling using existing finding storage. Keep the window/controller together initially; size alone does not justify more UI classes. |
| `validate_eapdf.py` | 10,689 | Separate reporting and external-engine adapters first; then rule families behind an explicit validation context. Retain the root module/CLI contract during staged migration. |
| `validator_web.py` | 3,080 | Separate report presentation and job/storage management from routes. Keep authentication, authorisation and admission policies central and preserve the WSGI entry point. |

The completed facade sizes are 2,459 lines (`PdfMailWriter`), 744 (`viewer.ts`),
870 (`EapdfGui`), 572 (`ValidatorGui`), 7,477 (`validate_eapdf.py`) and 1,889
(`validator_web.py`). Code moved into named owners; these reductions are not
claims of less total code, better speed or lower memory use.

### Writer: metadata, rendering and attachment ownership

`PdfMetadataWriter` consumes records/profile/configuration plus explicit asset
callbacks and returns metadata without owning a `PDDocument` or publishing files.
`MailRenderPolicy` owns resource resolution and the original per-thread render state.
`MailHtmlRenderer` owns canonicalisation, CID files, renderer setup and fallback;
accessibility/profile/display-file callbacks connect it to writer policy.
The compatibility methods keep existing call sites and debug output.

`PdfAttachmentSupport` owns file plans/bindings, filename allocation, checksums,
media parameters and badge appearances. The planner borrows the existing inclusion
policy. `PdfCollectionSupport` owns members/folders/segments and staged payloads.
Collection assembly still uses the writer for child PDF creation and shared
structure changes. DPart/logical-structure mutation deliberately stays together;
extracting it needs a separate page/file ownership contract and is future work.

### Viewer: one operation owner, separate presenters

`FilesPanel` owns attachment presentation/actions and reuses existing sorting and
download helpers. `MessageTree` owns document rows, aliases, folder counts, virtual
lists, subject presentation and adjacent-row traversal. `SearchController` owns
queries/cursors, isolated workers, result lists and filter/index revisions.

The composition root `viewer.ts` still owns the current document, selection,
generation tickets, operation state, navigation and background subject indexing.
Search uses live navigation accessors and shares the metadata-index promise with
subject indexing. Portfolio and Conversations use this same owner/session budget.
No panel creates an independent active selection. Existing sort order, disclosure,
focus, scroll anchors and Confirmed/Inferred labels are unchanged by extraction.

### Java GUIs: process/report contracts

`GuiConversionProcess` owns one creator run's process/output/cancellation and
private configuration cleanup. `GuiRunConfiguration` assembles run options.
Swing retains layout, EDT dispatch and outcome presentation; worker supervision
remains the existing process boundary. CLI flags and password handling are preserved.

`ValidationReportReader` owns report parsing through the existing result API and
finding storage. FAIL/INCOMPLETE, malformed/duplicate-field rejection, automatic
validation on selection, cancellation and report-save meanings remain intact.
The validator window/controller stays together deliberately; its size alone does
not justify another UI abstraction.

### Python: public facades and separate rule families

`eapdf_validation` owns report accumulation/presentation, external engine adapters
and PDF/XMP/embedding/structure/message/media rule families. Root functions keep
their signatures and supply explicit contexts from current policy/helper bindings
on each call. No star re-export, dynamic function cloning or second report policy
stands in for compatibility. Library, CLI, worker and WSGI checks cover that contract.
Shared source/object parsers, recursion and cross-family coverage coordination
remain central and retain ownership of open documents and accumulated findings.
In particular, `check_additional_rule_coverage` remains a large integration check
with direct rules and cross-family evidence sharing; `check_collection_children`
retains recursion. These are explicit retention decisions, not extracted modules.
Further decomposition needs an evidence/ownership contract rather than another
large collection of callback dependencies.

`eapdf_web` owns job/storage services and page templates; `validator_web:app`
retains routes, authentication, authorisation, admission/deadline policy and the
WSGI entry point. Extraction adds no per-user isolation to the shared-team service.

## Completed extraction sequence and acceptance contract

The following sequence governed the completed refactor and remains the contract
for reviewing its separate boundaries. Further decomposition is future work.

1. Record a reproducible baseline and source/fixture hashes. Resolve unrelated
   local changes separately. Preserve intentional local fixture deletions.
2. Extract writer metadata and creator GUI process/config contracts, separately.
   Run the affected metadata/profile/GUI tests plus real-input Java validation.
3. Extract viewer file panel, message tree and search controller in separate
   changes. Run the full viewer unit/Chromium/Firefox gates, six profiles, aliases,
   Conversations permutations, initial destinations, export/download bytes,
   cancellation, recovery and worker/memory checks.
4. Extend attachment/collection helpers and extract validator report reading.
   Run collection/DPart/annotation/source-fidelity and validator outcome/report
   regressions. Retain independent PDF/A checks; a self-check alone is insufficient.
5. Package Python internals behind compatibility boundaries, then split rule
   families. Run public Python regressions, worker/web acceptance and documented
   Java/Python comparisons where applicable. Keep unavailable evidence incomplete.

Make one extraction per review. Preserve source and attachment bytes. Compare
structured metadata and rendering; explain any legitimate nondeterministic PDF
bytes rather than silently refreshing baselines. No performance improvement is
claimed or expected solely from dividing files. A new maintainer must demonstrate
the dependency direction and regression coverage before expanding the refactor.

## Repository layout and root files

| Location or files | Decision and reason |
| --- | --- |
| Root README, contribution/security guides, AGENTS, license/notices, VERSION | Keep discoverable project/automation entry points at the root. |
| `pom.xml`, `mkdocs.yml`, requirement/lock files and Git/IDE ignore policy | Keep tool-discovery/configuration contracts. Separate dependency sets serve different runtimes and are not duplicate files to merge. |
| Root Python modules and `validator_rule_text.json` | Keep the compatibility entry points, shared parsing/orchestration and runtime rule contract. Their internal packages are extracted; imports, launchers, WSGI, worker paths and Maven resources still use these root names. |
| `docs/specification-review/` | Historical specification discussion DOCX files and their provenance guide; generator defaults follow this location. These do not define current product coverage. |
| Ignored `docs/private/` | Local development history, correspondence and unpublished plans. No website input or public evidence links. |
| `docs/PROJECT_STATUS.md` and this file | Current handover/status and architectural decisions. Maintained prose, not generated audit dumps. |
| Dated release/audit records | Preserve provenance and label as historical snapshots. Do not overwrite old test counts to look current. |

The root review documents were relocated with their bytes unchanged; no runtime
entry point was moved and no original email/PDF input was deleted. Root Python
consolidation is an architectural migration, not deletion of unused files.

The FilesPanel controller owns one resize observer and one scheduled overflow check for description disclosures; newly mounted virtual rows request that same check. Its close lifecycle disconnects the observer. The XMP interpreter normalizes supported FOAF attribute/element forms for display and search while retaining ambiguity diagnostics and original source bytes.
