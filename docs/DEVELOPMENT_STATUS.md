# Development status and restart guide

**GitHub release — 2026-11-04 (published):** Published [`v1.6.0-beta.8`](https://github.com/thinkbox-digital/eapdf/releases/tag/v1.6.0-beta.8) as a prerelease
with creator/validator beta.8, reader `0.5.0-beta.6`, and 13 downloadable assets.
The user clarified the standing instruction: “bump and push to GitHub” includes
publishing the corresponding GitHub Release; this is recorded in AGENTS.md.
The code commit passed GitHub Conversion quality and Documentation workflows.
Assets use the previously verified reproducible JARs, current unsigned reader
packages, public configuration, and validated examples with their original
provenance. The manual/specification-review documents retain their beta.7
editions. Release-binary license inventories cover Java and the reader.
Local packaging and upload-verification evidence: `output/release-1.6.0-beta.8/`.
All 13 assets are uploaded; GitHub SHA-256 digests match every local artifact.
Publication confirmed at 2026-11-04T12:42:00Z; the release is public and marked prerelease.

**Version update — 2026-11-03:** Creator and standalone validator source
versions are `1.6.0-beta.8`; reader source version is `0.5.0-beta.6`.
This update includes the accumulated full-content processing, source association,
reader, Accessibility and Arlington repairs below. Existing examples retain
their actual beta.7 build provenance and validation hashes. Published beta.7
download links remain unchanged until a new GitHub Release is published.
The final quality gate passes: clean reproducible creator/validator builds,
Java tests, and five real-input fidelity, visual and standalone validation
regressions. Reader unit and browser integration suites pass; the stale Reading
Notes assertion now checks the retained checksum warning. All seven Arlington
regressions pass. Reviewed visual baselines reflect the compact branding and
reclaimed footer space; the pixel threshold is unchanged and timestamp masks
follow the relocated generated-time row. Evidence is retained locally under
`output/version-beta8-quality-final/` and `output/version-beta8-review/`.
Local CLI, GUI and validator distributions are rebuilt at beta.8.

**Arlington integration and repairs — 2026-11-03 (complete):**
All **88 PDFs in `out/` PASS** the pinned Arlington checker with the documented
implementation corrections: zero deviations, deprecations or incomplete jobs.
All **34 example PDFs PASS** as well. The runner verified that every audited
PDF remained unchanged. Reports include full raw results, logs, file hashes,
per-file summaries and aggregated findings:
`output/arlington-repair-2026-11-03/out-arlington/` and
`output/arlington-repair-2026-11-03/examples-arlington-complete/`.

Generator fixes write integral FontWeight values as integers, remove deprecated
PDF 2.0 CIDSet after completing font subsets, retain file sizes only in the
proper Params dictionary, retain collection counts/sizes in CI and ordering in
Sort, and omit empty AF arrays in isolated profiles. Font programs, glyphs,
attachment bytes and folder metadata remain preserved. This supersedes the
older CIDSet compatibility decision for newly generated PDF 2.0 documents.
Both CLI and GUI distributions and their checksums are updated.

The baseline had 88 DEVIATIONS. Its reports and exact artifacts remain under
`output/arlington-2026-11-03/`. Two verified veraPDF implementation defects were
corrected using pinned released sources: binary CheckSum lengths are measured
in bytes, and number-tree children receive their parent dictionary through the
Kids array. The Arlington profile XML and rule predicates are unchanged; no
rules were disabled. Negative controls still reject 15/17-byte checksums and
forbidden root Limits. The reporting adapter also removes the upstream hidden
10,000-result retention ceiling. See [checker scope and reproducible corrections](ARLINGTON_VALIDATION.md).
Arlington checks on-disk PDFs; embedded PDF payloads are not separately opened.
This result is not a full-batch Java EA-PDF/PDF/A or accessibility certification.

All 82 eligible inputs regenerated with Accessibility on and creation checks
skipped, followed by the separate Arlington audit. Six PST inputs over
300,000,000 bytes remain excluded. All 88 original source hashes match.
Conversion logs, source/output inventories and readable size tables are under
`output/arlington-repair-2026-11-03/`; current tables are also in `out/`.
The final creator additionally fixes isolated profiles; the retained-source
`out/` batch and final example builds are identified separately in `builds.json`.
Early collision-suffixed filenames are retained with their matching references;
the preserved baseline was separated and the final inventory reconciled.

The 24-example profile matrix passes standalone Java EA-PDF, PDF/A and selected
ISO structure checks; all eight finding-aid companions pass PDF/A. The full
1,433-message Enron example also passes Java validation after increasing the
report/scratch execution allowances; the initial incomplete attempt is retained.
The missing-date negative example retains its expected MESSAGE_SENT_XMP failure
and PDF/A PASS. Creator verification: 406 tests, four skipped, no remaining
failures/errors after the affected-test rerun. Seven Arlington regression tests
pass. Temporary builds/probes are cleaned, retained evidence is in the repair
report directory, and validation staging is empty.

**Clean output regeneration, validation skipped — 2026-11-03 (complete):**
The user requested cleaning `out/`, regenerating all eligible inputs, skipping
creation validation, and reporting source/output sizes and profiles. The prior
large-PST validation and its finalization job were stopped; retained reports
must not be interpreted as completed validation of the new outputs. Old `out/`
PDFs were removed. Prior logs and unmodified debug files were retained under
`output/clean-regeneration-2026-11-03/`.

The new batch uses Accessibility on, explicit annotation page references,
1s for EML/MSG and singleton Drafts.mbox, 1m for other MBOX, and 1c for PST.
The previous exclusion of PST files over 300,000,000 bytes remains in force.
`validateOnCreation` defaults to true; only the batch configuration sets it
false. Both serialized-file and in-memory child creation checks are bypassed;
receipts say SKIPPED and preservation comparison NOT_RUN. Atomic publication,
byte-integrity checks and resource safeguards remain active. No standalone
validation is run. These outputs must not be described as validation PASS.
Creator build: 405 tests, four skipped, no failures/errors.

All 82 eligible inputs were regenerated successfully: 82 primary PDFs and six
finding-aid PDFs. Six PSTs over 300 MB were skipped. All 88 source hashes match.
The largest PST completed in 658.74 seconds. This is a conversion result, not
a validation PASS. The distribution also corrects a stale checking-stage label
for skipped checks; the batch manifest retains the actual creator build hash.
Logs/settings/statuses: `output/clean-regeneration-2026-11-03/`.
`out/FILE_SIZES.md` and `out/file-sizes.csv` list every input,
primary-PDF size and profile in readable decimal units (KB/MB/GB); both CSV
reports also retain exact byte counts. `out/output-files.csv` itemizes all primary PDFs,
finding aids and XML/XLSX sidecars. Exact bytes and source/output hashes are
retained. Earlier output paths and checkpoints below are historical.

**Accuracy and completeness:** Resource limits are execution safeguards, not
permission to omit content. A result is complete only when all required content
and checks have been examined. Reaching a limit must produce an explicit
INCOMPLETE result; it must not be reported as PASS or silently truncate the
inventory. Completeness claims below apply only to the named files and measured
coverage. They do not establish support for arbitrary file sizes or counts.
For the current regeneration, content cutoffs are removed and affected validation
is rerun; resource allowances may be adjusted, with prior attempts retained. Any unresolved
limit or unavailable source evidence remains explicitly incomplete.

**Explicit annotation page references — 2026-11-03:** A reported Arlington
`ObjectReference.Pg is missing` warning/error prompted inspection of the local
`acute-dev_1m.pdf`. Its 85 OBJRs omitted direct `/Pg`, but every immediate parent
supplied it; this is permitted by the current
[Arlington ObjectReference model](https://github.com/pdf-association/arlington-pdf-model/blob/master/tsv/latest/ObjectReference.tsv).
The screenshot reported 219 instances, so its exact generated version/checker
has not been reproduced. The creator now writes an explicit indirect `/Pg` on
existing renderer annotation references using actual page annotation membership,
including links spanning pages. This removes the dependency on parent-page
interpretation without changing referenced annotations. CIDSet deprecation
warnings remain subject to the existing documented compatibility decision.

The Accessibility-enabled replacement is
`out/objr-repair-2026-11-03/acute-dev/acute-dev_1m.pdf`.
All 85 references have explicit correct pages; Java EA-PDF and PDF/A checks PASS.
The source hash is retained in the regeneration inventory. The creator suite
runs 404 tests, four skipped, no failures/errors, including the multi-page
annotation regression. Evidence: `output/regeneration-2026-11-03/objr-repair/`.
Existing bulk PDFs and their original validation reports are preserved.

**Regeneration preference — 2026-11-03:** Accessibility is enabled in the project
configuration and must be enabled for future file regenerations, including custom
configurations. Runs already in progress retain their recorded settings. The GUI
checkbox now reads `Accessibility`; its tooltip retains the distinction between
enabling features and establishing PDF/UA conformance.

**Current work: full-content processing and regeneration — 2026-11-03:**
The user authorized reader development and requested no content cutoffs.
Creator message, MIME-part, page and aggregate image-count stops are removed.
Java validation no longer stops at fixed file, child-PDF, object, role-chain,
metadata-record, comparison-work or nesting counts. Reader inspection, XMP,
child navigation, supplement association, CSV, search and bookmarks likewise
have no fixed content-count cutoffs. MIME preflight, native MSG inventory,
nested-array inspection and bookmark rendering use explicit work queues.
Cycles remain checked. Streaming paths release extraction reservations after use;
already processed bytes and characters do not consume a lifetime allowance.
Per-stream allocation, retained cache/results and active-resource limits remain. Actual memory/stack exhaustion, byte budgets, disk
allowances, cancellation and timeouts must produce explicit failure/incompleteness.
Legacy reader count/depth fields remain in the inspection contract for compatibility
but no longer stop traversal. User-requested source selection remains applicable:
six PSTs exceeding 300,000,000 bytes are intentionally excluded from this batch.

Creator: 404 tests, four skipped, no failures/errors. Java validator: 254 tests,
nine skipped, no failures/errors, including 10,000-level shared-array traversal
and actual-cycle rejection. PDF.js patches: 20 source tests and 133 selected
upstream specs, zero failures (one upstream OffscreenCanvas skip). Reader type,
contract and focused browser checks pass; the unrelated third-party BFO test
fixture is absent and explicitly skipped. All 53 reader unit tests and 13 UI cases/17 message selections pass. A separate
edge fixture verifies all 1,034 bookmarks, including depth 33. Final bulk
validation is still running. Evidence and build hashes:
`output/regeneration-2026-11-03/no-cutoffs-*` and `live-resource-*`.
The resource-accounting regression processes nine 8 KiB files after a failed
oversized read under an 8 KiB concurrent extraction allowance. Supplement
association also releases reservations on success, failure and cancellation.

All 82 eligible real inputs have been converted; all 88 original hashes match.
Outputs: `out/regenerated-2026-11-03/{eml,msg,mbox,pst}/<input-stem>/`.
Individual emails use 1s, multi-message MBOX uses 1m, and PST uses 1c;
`Drafts.mbox` contains one message and uses 1s. Conversion logs, source hashes,
initial reports and retry evidence are retained. All outputs are being revalidated
with the updated standalone Java validator, two sources concurrently. The largest
PST has 15,856 messages and 41,627 embedded files; its initial validation was
INCOMPLETE at the former file-count cutoff. The final run has passed that point
and is checking child PDFs. `full-validation-settings.json` records resource
allowances; `full-validation/` preserves the new reports separately. Final results
must be reconciled from per-source status files, not the initial batch summary.

The 24-profile example matrix has been refreshed with Accessibility enabled
and explicit annotation page references; an independent 50-PDF audit (including
collection children) finds all 88 annotation references correctly paged. All
24 PDFs pass Java validation and all eight finding aids pass PDF/A. The full
1,433-message Enron example and existing missing-date negative fixture were also
regenerated; the latter intentionally retains `MESSAGE_SENT_XMP` failure and
PDF/A PASS. Native MSG named-property streams are no longer mistaken for RTF
bodies; affected input retries and their initial attempts are retained.

Supplement linking is implemented: all 1,190 transport-header files in the retained
1,909-message collection match the exact independently established owners.
Two child workers rotate after 16 children to bound cumulative worker allocation;
this batching does not stop the scan. Byte/time exhaustion or ambiguous evidence
leaves ownership explicitly unresolved. Failure, cancellation, refresh and worker
cleanup checks pass. Evidence: `output/banner-xmp-review-2026-11-03/`
`supplement-owner-verification.json` and `supplement-rotating-opening.json`.

The following dated checkpoints are historical; their old count/depth ceilings
are superseded by the policy above.

**Reading Notes removed; full discovery verified for five named PDFs — 2026-11-03:**
The Reading Notes panel and all user-facing references to it are removed.
Detailed inspection diagnostics are retained through `console.debug`. Incomplete
file discovery uses the local warning `Some embedded files could not be listed.`
An email selection with unresolved ownership uses `Some files could not be linked
to an email.` Checksums and unavailable metadata retain concise contextual warnings.

The generic PDF.js asset scan now queues shared objects once, uses breadth-first
traversal, and treats bookmark Next/Prev links as siblings rather than nested
children. Real archives also exceeded the old finite capacities: the current
browser ceiling is 1,000,000 objects, 16,384 files and 16,000,000 scanned edges;
structural depth remains 64. Document-part projection allows 16,384 node/array
visits, 65,536 DPM entries and 8 MiB aggregate raw strings; depth remains 32 and
individual raw strings remain capped at 64 KiB. Lower caller-supplied object
limits still work; excessive/deep graphs remain explicitly incomplete. File
limits and other failed discovery paths now mark coverage incomplete instead of
silently reporting complete coverage.

Independent MuPDF filespec/stream inventories match every filename, decoded size
and file multiplicity for five real PDFs; available computed hashes also match.
This reference includes associated attachments outside the name tree. The first
name-tree-only reference missed one such real attachment and was superseded by
the explicit filespec inventory. Every source email has exactly one known owner.
All five final runs report complete associated-file and document-part coverage.

| Input PDF | Embedded files found | Source emails with unique owners | Full opening, seconds |
|---|---:|---:|---:|
| 0.31 MB PST sample | 15 | 7 | 0.51 |
| 9.23 MB Enron archive | 1,435 | 1,433 | 6.31 |
| 35.15 MB prior Enron archive | 1,435 | 1,433 | 32.87 |
| 91.21 MB Enron collection | 2,866 | 1,433 | 7.81 |
| 320.78 MB PST collection | 5,008 | 1,909 | 29.83 |

These are single final-build warm-cache Chromium trials, with other tests running;
not controlled performance averages. Earlier faster measurements stopped at the
old scan limits and must not be described as complete inspection. The two large
collections previously stopped at 2,048 files. Final measurements, input hashes,
coverage, ownership and panel-absence assertions are in `discovery-accepted-*`.

The PST's 1,190 supplements are all native transport-header files. Their unresolved
viewer ownership is a matching limitation, not missing data or an inherently
unrecoverable relationship. All start with the Microsoft Internet Headers marker,
which the current strict source-header parser rejects. After recognizing it,
310 have a unique Message-ID match; the other 880 have different transport and
exported-source IDs. An independent check of all 1,909 child PDFs proves that
**all 1,190 supplements have one unique owner, with zero ambiguous/unmatched cases**:
match byte-identical header copies in the child catalog AF, require its explicit
single-email profile, and match its source bytes to the uniquely owned outer
source. This initial cross-PDF proof is now implemented as described in the current
checkpoint above. No filename/order matching or silent ownership guesses were added.

Verification: 20 source-patch tests pass; 133 selected upstream specs have zero
failures and one Node OffscreenCanvas skip. Type/contract checks, 45 unit tests,
13 UI cases/17 message selections, warning-panel removal/debug-output checks,
source-association differential checks and browser document-part checks pass.
PDF.js bundles were rebuilt from the hash-verified pinned archive; patch/test and
bundle hashes were updated. Local browser builds are refreshed; nothing published.

Evidence in `output/banner-xmp-review-2026-11-03/`: `discovery-expected.json`,
`discovery-accepted-opening.json`, `discovery-accepted-verification.json`, source
rebuild and test logs, `transport-header-link-analysis.json`,
`transport-header-child-links.json`, and `verify_transport_header_child_links.py`.
Intermediate limited scans and failed fixture/setup checks are retained separately.
The standalone comparison is reproducible with `scripts/discovery-reference.py`
and `scripts/discovery-check.mjs` in `browser-viewer/`.


**Compression savings across real inputs — 2026-11-03:**
The full Enron MBOX (2.22 MB, 1,433 emails) now produces a 9.24 MB branded PDF,
versus the earlier 35.15 MB user output: about 74% smaller. The controlled,
matching-font/settings unbranded comparison is 34.63 MB to 9.23 MB: 73.3% smaller.
The earlier user output was signed and had an additional page, so the controlled
comparison is the stronger evidence. Source emails are preserved byte-for-byte.

Matched small-input regression outputs below compare the retained November 3
optimization and metadata/branding checkpoints. The baseline already includes
earlier compression/font work; these percentages are additional savings, not
claims for the entire optimization series. Main PDFs only; decimal KB/MB.

| Real input | Total input bytes | Combined-email PDF before → after | Reduction | Individual PDFs reduction | Collection PDF reduction |
|---|---:|---:|---:|---:|---:|
| Two EMLs | 4,288 | 107.8 → 88.6 KB | 17.8% | 24.1% | 25.0% |
| Two MSGs with attachments | 378,880 | 630.8 → 610.5 KB | 3.2% | 5.1% | 4.7% |
| Small MBOX | 2,826 | 90.0 → 68.4 KB | 24.0% | 30.1% | 29.5% |
| Seven-message PST | 2,302,976 | 202.7 → 171.1 KB | 15.6% | 23.8% | 21.5% |

Savings come from shared/subsetted archive fonts, compressed PDF objects and
embedded streams, compressed XMP, and compact once-per-output branding. Enron's
stored font programs fell from 19.64 MB to 43.5 KB; the later metadata/branding
step reduced its PDF from 12.26 MB to 9.23 MB (24.7%). Attachment-heavy inputs
benefit less. No matched large-PST or Gmail output-size comparison is established;
large viewer benchmarks measure speed, not compression. The previously measured
43% PST heap reduction is a memory result, not an output-size saving.

Exact comparisons for all six profiles, source hashes and checkpoint paths are
in `output/banner-xmp-review-2026-11-03/compression-savings.json`.


**Recovered session completion — 2026-11-03:**
The prior thread remains indexed under the old `convert` directory; work resumed
in `eapdf`. The viewer opening stall was traced with stage timings and a Chromium
CPU profile to `BrowserSession.associateFiles()`: extracting each unresolved
source email reparsed all archive XMP and rebuilt the entire interpretation.
Association now collects the same bounded extraction/hash facts and interprets
once per batch, only when facts changed. Individual attachment reads still
refresh immediately. Extraction limits, cancellation and unavailable-evidence
handling remain in place. The real-file differential test compares batched and
eager interpretation for four Enron source emails, including an injected read
failure: ownership, hashes, diagnostics and metadata details match exactly;
XML parses drop from five to one (four to one with the failure).

Final opening benchmark: fresh offline headless Chromium 156 extension context
per trial, two trials per file/variant, alternating variant order, warm filesystem
cache. Controls remove only the loading-dialog presentation. All 20 trials
completed without page errors. Times below are means in seconds; file sizes use
decimal MB. The first rendered page remains behind the modal until opening ends.

| PDF size / source | First page, dialog | Fully open, dialog | Fully open, control |
|---|---:|---:|---:|
| 0.31 MB PST sample | 0.35 | 0.52 | 0.56 |
| 9.23 MB compressed Enron 1m | 1.10 | 5.59 | 5.41 |
| 35.15 MB prior Enron 1m | 1.19 | 10.48 | 10.13 |
| 91.21 MB Enron collection | 0.91 | 6.30 | 6.50 |
| 320.78 MB PST collection | 1.26 | 13.12 | 12.43 |

Before batching, the 9.23 MB archive exceeded 150 seconds with the dialog; fresh
control runs without it exceeded 45 seconds for both the 9.23 MB and 320.78 MB
files. Those timeouts are retained as incomplete runs, not successful timings.
The evidence identifies a shared association bottleneck, not the dialog itself
as its cause. Two warm-cache trials are diagnostic measurements, not a general
performance guarantee; Firefox timing and cold-cache behavior were not measured.

Viewer type/contract checks, 45 unit tests, loading and retry browser checks pass.
The initial final-build UI check could not save its report because `test-results/`
was absent after the move; the setup failure log is retained, and the directory
was created for a fresh run. The final rerun passes UI/navigation, exact saved
attachment bytes, offline cancellation, loading-dialog and page-retry checks.
The association differential regression is included in `npm test`, along with the
loading checks. Final builds are in `browser-viewer/dist/{chromium,firefox}`.

Local creator CLI/GUI JARs and SHA-256 sidecars in `dist/` now contain the final
centered banner and finding-aid spacing adjustment. The retained targeted build
passed nine geometry/finding-aid/collection/archive tests. A fresh bounded Enron
conversion produces 9,238,302 bytes and 1,943 pages, with one centered banner,
clear heading spacing and `1,433 messages across 1 folder`. All 1,433 embedded
EMLs match the previously verified unbranded output byte-for-byte. Standalone
Java validation reports EA-PDF PASS and PDF/A PASS, zero failures and 30
recommendations. The validated example is `examples/mbox/mbox_enron_1m.pdf`.

Evidence: `output/banner-xmp-review-2026-11-03/` contains
`viewer-resumed-diagnostic.json` and its CPU profile, `viewer-before-control.json`,
`viewer-final-opening.json` (source hashes and all stage timings), association and
UI logs, `centered-creator-build.log`, `centered-conversion.log`,
`centered-geometry.json`, `centered-source-comparison.json`, and
`centered-validation.json` with its log and summary. No release was published.


**Compact branding, compressed metadata and review fixes — 2026-11-03:**
Creator outputs now use an 18-point-high text/vector ThinkBox banner once, in the
first page's existing top margin. Archives no longer repeat branding per email or
reserve large footers; collection children and separate finding aids each get
one banner. Branding-off remains supported. Finding-aid and collection run counts
now use comma grouping and singular/plural nouns, for example
`1,433 messages across 1 folder`.

Final PDF/A-4/4f XMP streams use lossless Flate compression. The matching unbranded
Enron run is **9,231,511 bytes (9.23 MB)** versus 12,263,708 bytes before this change.
Its stored XMP is 371,491 bytes. The decoded XMP round-trip test preserves exact
UTF-8 bytes, including non-ASCII text; regenerated documents still get fresh IDs
and timestamps. PDF-aware metadata readers decode the stream normally; raw-byte
XMP scanners may no longer find it. All 1,433 embedded emails match prior bytes
and independent MBOXRD decoding, and all 1,943 pages match prior text and pixels
at 108 DPI, masking only the generated timestamp. Standalone Java validation
passes EA-PDF and PDF/A-4f (zero failures, 30 recommendations).

`PageContentChecks.pageContext()` now builds candidates from every document page
through `PageTextStore.get()`. Pages omitted by extraction receive the existing
page-context WARNs, not a null dereference or automatic pass. The separate
`EmptyPageContextTests` cover absent and explicitly empty streams, leading,
trailing and middle empty pages, all populated pages, zero pages, exact one-based
`pages_unassessed` for both recommendation codes, subsequent checks, and full
validation without `VALIDATION_ERROR`. Namespace tests remain separate. Genuine
extraction/storage exceptions are not caught or replaced with empty text.

Creator full suite: **395 tests, 4 skips, zero failures/errors**. Validator:
**252 tests, 9 skips, zero failures/errors**, including ten new empty-page cases;
the focused empty-page/namespace run also passes. Real EML/MSG/MBOX/PST conversion
matrix: **24 combinations / 42 main PDFs**, all standalone Java PASS. Recursive
inspection of **102 generated PDFs**, including children/finding aids, confirms
one first-page banner and compressed XMP. Count wording received a subsequent
focused finding-aid/collection test run.

Viewer README and font-source notice now match the canonical PDF.js 6.5.438 /
`0a4153349dc473ba950fc17941c5ca7eda43c1de` pin. `verify:pdfjs` also checks current
documentation, package/lock versions and integrity, and both bundle headers.
Seven drift regression tests pass, and CI runs verification plus these tests.
Historical M0/M2 references remain unchanged; source patches and bundles were
not rebuilt. Current installation instructions precede historical notes and link
explicitly to beta.7's two viewer ZIPs and examples ZIP; all three filenames were
verified against GitHub's release API. A release checklist requires tag/asset
existence checks. No GitHub release or extension-store publication was performed.

Evidence: `output/banner-xmp-review-2026-11-03/` retains source hashes, conversion
logs, Java reports, test summaries, browser checks and the API response. Initial
conversion runs overlapped JAR packaging and are explicitly retained as incomplete
setup runs; accepted reruns use a stable packaged JAR.

**Review of the seven supplied findings — 2026-11-03:**

| Finding | Current assessment |
|---|---|
| Large archive limits | Open, but “fixed 512 MiB default” is historical. Heap selection now considers available host/cgroup memory and controller heap. Fixed 64 MiB work files and 256 descriptors remain; large Enron validation still uses explicit expanded finite limits. |
| PST fractional sent time | Confirmed: the warning discloses loss; exact native fractions are not yet retained in Sent metadata. |
| Native MSG comparison | Partially implemented since the cited V85 record: native fields, dates, recipients and supported attachments are compared. Missing transport history, unsupported storage and ambiguous evidence remain unavailable. |
| Original-record completeness | Writer-entry inventory protects final output against that snapshot, but does not prove complete reader enumeration or pre-dispatch ownership/provenance. |
| Creator receipts | Correctly say PDF/A and full EA-PDF `NOT_RUN`; preservation checks are not independent conformance validation. Retain separate Java reports. |
| Skipped cases | Current suites still skip 4 creator fixture cases and 9 validator cases (8 fixture cases plus interactive desktop). |
| Full upstream PDF.js suite | Still outside the focused patch tests. Pin verification is not full upstream regression testing or source reproduction. |

Detailed source references and next steps are in
`output/banner-xmp-review-2026-11-03/screenshot-review.json`. These findings were
examined; no broad resource-policy, PST timestamp, native-MSG or receipt feature
change is claimed by this checkpoint.

**Enron space-usage update and generator optimization — 2026-11-03:**
The exact 2,220,104-byte `in/enron.mbox` now produces a **12,263,708-byte
(12.26 MB / 11.70 MiB)** `1m` archive. The previous user output was
35,154,522 bytes (33.53 MiB): **65.1% smaller**. The controlled unchanged-build
baseline with matching options/font was 34,625,535 bytes: **64.6% smaller**.
The validated example is `examples/mbox/mbox_enron_1m.pdf` in both checkouts.

| Final output component | Stored bytes |
|---|---:|
| Font programs (4 shared subsets, including generated text) | 43,545 |
| XMP metadata | 3,403,677 |
| Page content streams | 2,926,870 |
| Compressed PDF object streams | 3,411,467 |
| Cross-reference stream | 534,141 |
| Original emails | 1,096,249 |
| Embedded PREMIS XML | 348,600 |
| Embedded spreadsheet | 91,000 |

Fonts previously occupied 19,642,233 bytes. The writer now shares full rendering
fonts within `m`/`mi` archives and subsets them once using the union of all page
usage. Glyph collection is keyed by the PDF dictionary, not Java wrapper identity;
a failed earlier prototype proved that distinction and was rejected. Existing
subsets, unusual encodings, and large fallback-font registration keep their safe
paths. Final archive object streams use bounded groups of 2,000 objects, and
lossless Flate level 9 is the default (explicit PDFBox overrides are honored).
`c`/`ci` outer embedded files now use Flate compression: the real Enron collection
had 2,866 streams with correct decoded sizes/MD5s and **7,181,400 bytes** of
measured stream savings. Its 52,716,303-byte test output used branding disabled,
so it is not a like-for-like size comparison with the earlier branded collection.
Source-sharing between container and children was discussed but not changed.

Verification: all 1,943 pages match a fresh unchanged-build baseline in extracted
text and 108-DPI pixels, masking only the first-page generated timestamp. All
1,433 embedded emails are byte-identical to baseline and their headers/bodies
match an independent MBOXRD decode (7 messages require mailbox unquoting).
The earlier signed user PDF has 1,944 pages and is not the visual baseline;
original signed files were not modified, and test conversions are unsigned.
Final archive and full collection pass standalone Java EA-PDF/PDF-A checks.
Initial resource-limited validator runs remain explicitly INCOMPLETE; expanded,
finite-limit runs pass. Creator suite: 394 tests, 4 skips, zero failures/errors.
The independent five-case EML/MSG/MBOX/PST quality gate passes. All six profiles
pass for small real EML, MSG, MBOX and PST inputs: 24 combinations / 42 PDFs,
plus 8 final archive-packing rechecks. Gmail uses the same writer but was not
live-tested. These are scoped preservation checks, not universal certification.

CLI/GUI JARs and SHA-256 sidecars in both local `dist/` folders are updated;
reload the GUI to use them. Published releases are unchanged. An additional
re-save experiment reached 12,234,660 bytes; the supported generator output and
stats above are 12,263,708 bytes. All logs, hashes, source/visual comparisons,
validation reports, rejected-experiment evidence and detailed statistics are in
the public checkout's `output/enron-optimization-2026-11-03/` (`summary.json`,
`space-usage.json`, `final-packed-size-breakdown.json`).

**GUI controls rebuild — 2026-11-03:** Removed Debug checkboxes and their
argument wiring from Local and Gmail tabs in the public source. CLI debug
support and Details logging remain intact. Creator package tests pass. A desktop
smoke check against the built GUI JAR verified both absence of Debug checkboxes
and actual/minimum window dimensions of 1200x955. Both checkouts' creator CLI/GUI
JARs and checksums are updated. Close and relaunch existing GUI processes to load
the replacement. Build log: public checkout
`output/jar-rebuild-2026-11-03/gui-controls-build.log`.

**Local JAR rebuild — 2026-11-03:** Rebuilt creator CLI/GUI and standalone
validator from the public checkout. Creator package tests pass (393 tests,
4 skips, zero failures/errors); validator packaging verification passes.
Both checkouts' `dist/` JARs and SHA-256 sidecars are synchronized. GUI minimum
size is 1200x955; signing password focus and friendly wrong-password handling
are included. Debug controls remain unchanged. Published release bundles are
unchanged. Build logs and checksums: public checkout
`output/jar-rebuild-2026-11-03/`.

**Exact Enron collection size audit — 2026-11-03:** Read-only analysis of the
91,207,186-byte public-checkout `out/enron_1c.pdf` (SHA-256
`b8f0403e978b7358aebdeacba2e3b2d66863a0bd668f02843ee05dbe46bf1fd6`)
against the 2,220,104-byte `in/enron.mbox` confirms 1,433 plain-text messages,
no MIME attachments/images, and 1,433 generated child PDFs. Child font programs
consume 34,152,899 bytes; the identical 1200x300 branding image consumes
18,494,298 bytes across children. Child XMP is 7,797,314 bytes and outer XMP
3,455,423 bytes. All 1,433 originals occur both inside children and at collection
level. Outer embedded PDFs and email streams are unfiltered; independent zlib
measurement suggests 8,718,331 bytes of stream savings there, before PDF
serialization. This is a compression estimate, not a validated replacement PDF.
No generator changes, reconversion, or validation were performed in this audit;
the original conversion receipt says PDF/A and EA-PDF validation NOT_RUN.
Evidence, source/output hashes, analysis script, and copied conversion log/receipt:
public checkout `output/enron-size-audit-2026-11-03/`.

**Signing password prompt — 2026-11-03:** Public-checkout source now requests
password-field focus when the dialog gains keyboard focus. Incorrect PKCS12
passwords produce “Incorrect signing certificate password. Please try again.”
Nested exception lines no longer overwrite the main GUI error; full technical
output remains in Details. Compilation and PdfSigningTests pass, including the
wrong-password message assertion. Desktop focus was not runtime-tested; creator
JARs have not been rebuilt.

**Viewer loading dialog — 2026-11-03:** The public checkout now shows an
Opening EA-PDF dialog after one second while opening an archive. The email tree
stays hidden until ready. Cancel and Escape stop the active session; opening
errors appear in the dialog. Progress is indeterminate. TypeScript/contract
checks and both browser builds pass. Chromium loading lifecycle and existing
Retry regression checks pass; Firefox runtime was not retested. Local unpacked
builds in both checkouts are updated; reload the extension and reopen its viewer
tab. Published ZIPs are unchanged. Regression: `browser-viewer/scripts/loading-smoke.mjs`
in the public checkout.

**Missing-date demonstration — 2026-11-03:** Created
`examples/eml/eml_missing_date_1s.pdf` from a derivative of the real Agenda EML
with only its top-level Date header removed. The original is unchanged. First-page
inspection confirms the INCOMPLETE SOURCE banner and missing-sent-date explanation.
Java validation: PDF/A PASS; EA-PDF FAIL (one finding), intentionally defective
source demonstration, not a conforming regression example. Source hashes, conversion
log, Java report and preview are in `output/missing-date-example-2026-11-03/`.

**Retry page visibility — 2026-11-03 (local fix):** The viewer now hides Retry
page until the current page fails to render. Background page failures are tracked
separately; successful rendering, navigation and closing hide the control. Invalid
file/opening errors do not expose it. Both browser builds compile; 38 existing unit
tests and a new Chromium regression pass, including injected render failure,
prefetch failure, successful retry, navigation, close and invalid-file opening.
Updated unpacked builds are in `browser-viewer/dist/` in the public checkout.
Reload the extension to use the fix. Published release ZIPs are unchanged.

**Documentation website — 2026-11-02:** Added Material for MkDocs at
https://thinkbox-digital.github.io/eapdf/. The public repository remains the home
for source, issues, Releases and Packages. Website chapters are generated from
the existing manual HTML and screenshots; selected Markdown guides are reused
for reference and developer pages. Sidebar navigation, local full-text search,
light/dark themes and responsive layout are enabled. Fonts are local and no
analytics are configured. Site width is controlled by one stylesheet variable.

The strict build checks 25 HTML pages and their local links/assets. Chromium
checks pass for desktop search and mobile navigation/layout. A stale coverage
link was corrected. The Documentation workflow builds pull requests and deploys
only main through GitHub Pages. No creator, validator or viewer runtime code
changed. GitHub Pages deployment [41522412226](https://github.com/thinkbox-digital/eapdf/actions/runs/41522412226) passes.
Live guide URLs, search and mobile navigation were checked successfully.
See [website maintenance](WEBSITE.md).

**Fresh public snapshot — 2026-11-02:**
Published at https://github.com/thinkbox-digital/eapdf with fresh history.
The previous repository remains a private archive; its commits, tags, issues,
Actions records and historical credentials are not imported. The owner confirmed
that the email samples are public. Local comparison files, ISO reference PDFs,
raw debug captures and the superseded rule-drafting document are excluded.

Application artifacts belong in Releases. The two patched OpenHTMLtoPDF libraries
are published through Packages with their corresponding source JARs; verified
local build copies preserve token-free builds. See [package usage](PACKAGES.md).
The isolated source snapshot passes the Java release gate and viewer checks.
GitHub conversion quality run 41519981174 passes; package verification run
41519993090 confirms all six Maven artifacts match the locked bytes. All 13 release
assets match their SHA-256 checksums. The first package verification failed on a
storage redirect; the corrected check follows HTTPS redirects without forwarding
authentication. An earlier quality run was superseded, not a test failure.
Private vulnerability reporting, dependency alerts, secret scanning and push
protection are enabled. See [launch verification](PUBLIC_LAUNCH.txt).
Earlier entries below are historical records of private development.

**Public-release preparation — 2026-11-02:**
The namespace-test fix passes the full GitHub quality job (run 41515122417).
Only GitHub release v1.6.0-beta.7 remains; 11 older releases were deleted.
Removed tracked social-media debug captures and five licensed ISO reference PDFs,
expanded private-file ignores, cleaned redundant upstream HTML and added public
contribution/security guidance. Existing local reference PDFs are preserved.

Repository visibility remains PRIVATE. Public launch is blocked by historical
Gmail client secrets, retained old branches/tags (including beta.7 source archives
predating cleanup), and unconfirmed redistribution rights for real mail/reference
material. No history rewrite or credential revocation was performed.
See [the concrete owner checklist](PUBLIC_READINESS.txt) before changing visibility.

**CI comparison-file dependency corrected — 2026-11-02:**
The GitHub quality job failed because `NamespaceDiagnosticTests` still loaded
`compare/bfo.pdf`, which is intentionally local-only. The test now creates its
own small PDF and XMP in a temporary directory. It retains all four assertions:
missing namespace, incorrect namespace, and correct declarations in either order.
The targeted test and GitHub run 41515122417 pass without comparison documents. No validator runtime code,
rules or release binaries changed.

Separate observation during fixture preparation: a page with no content stream
exposed a null lookup in `PageContentChecks.pageContext`; this is outside the
missing-file CI correction. Fixed on 2026-11-03 with separate empty-page regressions; see the current checkpoint above.

**Release 1.6.0-beta.7 / viewer 0.5.0-beta.5 — 2026-11-02:**
Published on GitHub as a prerelease. Release includes updated creator CLI/GUI and standalone validator
JARs in `dist/`, both viewer packages, input-named examples, and the PDF/A manual.
Creator changes include explicit file ownership, matching printed/embedded source
names, input-based output filenames and the stable Stop-button layout. Validator
logic/rules are unchanged; its binary is rebuilt at the release version.

Viewer improvements include collection email search, folder/attachment CSV fields,
quicker sidebar position lookups, paced downloads and PDF.js 6.5.438. Printing,
rotation controls and custom shortcuts remain outside the demonstration scope.
The manual's viewer text and screenshots match the new interface. Existing desktop
screenshots remain identified as beta.6. Example path regressions were corrected;
historical incomplete-file tests retain frozen fixtures. The reviewed PST visual
baseline now reflects matching source filenames; thresholds/masks were not relaxed.

Release verification and limitations: [release receipt](improvement/release-1.6.0-beta.7/summary.json).
Large PST exports are excluded. Viewer packages are unsigned; no store approval
or new cross-platform certification is claimed. GitHub release:
[v1.6.0-beta.7](https://github.com/thinkbox-digital/eapdf/releases/tag/v1.6.0-beta.7).

**Viewer search, scrolling and downloads — 2026-11-02:**
Local viewer **0.5.0-beta.4** adds Find emails across unopened collection members,
with cached metadata, exact result destinations and explicit incomplete coverage.
Sidebar position lookups no longer scan every previously measured row.

Reproduced Chromium blocking rapid downloads: 10 of 15 accepted without spacing,
15 of 15 with 1.1-second spacing. Shared download handling now spaces requests
across file cards, previews and CSV export, checks cancellation, and keeps download
URLs alive for 60 seconds even when the PDF/preview closes. Browser settings can
still prevent saving. The full Chromium workflow passes in one tab, with no fresh-tab
workaround or test-side delay; the dedicated burst test verifies 16 exact downloads.

38 Node tests and Chromium/Firefox reading checks pass, including finding emails
inside unopened c/ci members. Full Firefox attachment/security tests and large-file
benchmarks were not rerun. No large PST conversions were performed. Creator and
validator code/JARs are unchanged in this viewer step. Packages are local, unsigned
and unpublished. See [viewer details](../browser-viewer/docs/reading-improvements.txt)
and retained logs in `output/viewer-next-2026-11-02/`.

**Viewing demonstration, finding aids and example cleanup — 2026-11-02:**
Local viewer **0.5.0-beta.3** focuses on reading, folders, search, zoom, file
previews/downloads and CSV finding aids. Removed printing, rotation controls,
custom keyboard navigation/shortcuts and the redundant Email Details pane.
Native browser controls and intrinsic PDF page orientation remain. Next work is
limited to download reliability, finding emails across collections and responsive
opening/navigation; printing and shortcut systems are out of scope.

File ownership is automatic. The manual source-finding step and unlinked-source
notice are removed. New creator output has explicit DPart file associations;
older files can use bounded header matching. The panel names the containing PDF
and links owning emails. Missing or ambiguous evidence remains a document error,
never a guessed owner. “Reopen PDF” is a recovery action hidden during normal use.

CSV finding aids include PDF paths, folder paths, addresses, subject, sent date,
Message-ID, attachment counts and attachment names. Mail GUID is omitted. Nested
folders and multiple memberships are retained; missing counts stay blank. Names
come from email metadata and do not imply original payloads are embedded in ci.
Actual c/ci downloads pass in Chromium and Firefox.

Creator output PDF names now retain the input stem, including individual-message
output: `auriza__subject_000001.pdf`. Archive mode uses `auriza_1m.pdf`; explicit
output names remain respected. Printed archive source filenames now match embedded
names, including all seven PST email sources. Shared files retain all owners.
Both creator JARs in `dist/` are updated local patches of 1.6.0-beta.6, including
the earlier Stop-button layout correction. Validator binaries are unchanged.
The real auriza archive and Enron archive/individual PDFs pass Java and PDF/A checks.

Cleaned and regenerated all 24 examples with input-based names, such as
`pst_quality-attached-message_1m.pdf` and `mbox_enron-sample_1m.pdf`. All pass Java
PDF/A, EA-PDF and selected ISO checks; all eight finding-aid PDF sidecars pass
PDF/A only. Generic old sample names were removed. Ten older signed/accessibility/
failing artifacts were preserved byte-for-byte as historical regression fixtures.
Original inputs and pinned browser fixture PDFs are unchanged; large PSTs were
excluded. Current documentation and test paths use the new example names.

Checks: 36 Node tests, 27 independent interpretation comparisons, 15 Python
reference tests, full Chromium UI plus Chromium/Firefox reading suites, and
18 targeted creator tests pass. Full creator/Firefox suites were not rerun for
this correction. A repeated-download Chromium automation timeout remains a known
investigation item; the full attachment suite uses documented fresh-tab isolation.
Local unsigned viewer packages are in `browser-viewer/releases/`; nothing was
published to GitHub or an extension store during this work.

[Examples](../examples/README.md) ·
[Evidence](improvement/viewer-file-ownership/summary.json) ·
[Viewer details](../browser-viewer/docs/reading-improvements.txt).

**Viewer 0.5.0-beta.1 — 2026-11-02 (local, unsigned):** PDF.js is upgraded to
6.5.438 with both generic patches preserved and reproducible. Source emails,
attachments and supporting files have clearer groups; exact unique Message-ID
matching links all seven EML sources in the current small PST sample. Added
occurrence and collection search, printing, password entry, fit/rotation,
collapsible panels, internal bookmarks/links, optional email details and filtering.
Timeout recovery, immediate integrity warnings, bounded extraction caching,
explicit budget restart, early first-page rendering and keyboard/structure support
improve reliability and access. Existing documents and original inputs are unchanged.

Verification: 33 unit tests, 27 unchanged semantic cases, 15 resource cases,
Chromium/Firefox interface/attachment/navigation checks, and new reading checks
pass. The patched engine passes 16 focused tests and 132 selected upstream specs;
one OffscreenCanvas-dependent spec is pending in Node. Both engine bundles and
both ZIPs reproduce exactly. Chromium's print engine produced the expected two
pages; password retry works in both browsers. npm audit reports zero advisories.
Native printer dialogs, screen-reader use, browser permission prompts and broader
platform acceptance remain unverified. Chromium's intermittent rapid-batch download
event timeout is recorded; attachment acceptance uses a fresh viewer tab without
removing any byte checks. Packages are not published or store-approved.

[Implementation and remaining limits](../browser-viewer/docs/reading-improvements.txt)
· [Verification record](improvement/viewer-0.5.0-beta.1/summary.json).
Packages: `browser-viewer/releases/eapdf-viewer-0.5.0-beta.1-{chromium,firefox}.zip`.
Reload the extension and open a new viewer tab. Creator/validator JARs are unchanged
by this viewer work; other major dependency migrations remain separate.

**Desktop conversion controls — 2026-11-02 (local, unreleased):** Stop, Open
output folder and the progress bar now occupy a fixed left-hand group. Changing
conversion status appears to their right and cannot move or wrap the Stop button.
Verified with Maven compilation in a temporary build directory. Distributed JARs
have not been rebuilt for this change.

**1.6.0-beta.6 — 2026-11-02:** the four requested work areas are complete.
Conversion reports now include stable warning codes, message identity, effects
and suggested actions while preserving existing warning text. MIME reconstruction
and diagnostic reporting have separate small classes. Nested attached-email
depth limits now apply; MSG attachment display order no longer depends on hash
codes. New tests cover malformed text, nested messages, cancellation, multilingual
tables, warning identity/publication and bounded diagnostic storage.

Verification: creator 367 tests/4 documented skips; Java validator including
packaging 244/9; isolated Python suite 1,287/43; 19 independent checker tests.
No failures/errors. All five small-corpus PDFs pass content, visual, Java EA-PDF
and veraPDF checks. Three JARs match repeat clean builds. All 24 standard examples
are regenerated and Java-validated across six profiles; the older special-purpose
examples retain their recorded provenance. The 34-page manual passes PDF/A-2b,
with refreshed creator/validator screenshots and warning guidance. Runtime SBOMs
and dependency license inventories are refreshed. `/dist` contains the verified
beta.6 CLI, GUI and validator JARs and checksums.

[Release files](https://github.com/thinkbox-digital/eapdf/releases/tag/v1.6.0-beta.6) ·
[Verification and limitations](improvement/release-1.6.0-beta.6/summary.json) ·
[User manual](EA-PDF_User_Manual.pdf).
Local release bundle: `dist/releases/1.6.0-beta.6/`.
Evidence: `output/release-beta6-2026-11-02/`.
Large PST exports remain excluded. Native PST fractional seconds still become
whole seconds in reconstructed message/PDF dates, with an explicit warning.
PST/MBOX containers are not embedded. Viewer binaries remain 0.4.0-beta.2;
no new Firefox or cross-platform certification is claimed. The tests are a
small acceptance set and targeted adverse-input regressions, not comprehensive
fuzzing or full layout/accessibility coverage.

**Small-format quality gate extended — 2026-11-02 (local, unreleased):**
independent checks now cover two EMLs, a MSG with two Office attachments, a
two-message MBOX and a seven-message PST with an attached email and DOCX.
The source expectations use separate email/OLE readers and retained libpff/libpst
exports. Checks detect wrong message/page/attachment ownership, missing folders,
messages and derivative sources, changed dates/recipients and corrupted nested
attachments. All 22 reference pages are reviewed; only archive generation times
are masked in visual comparisons.

This work exposed and fixed base64 wrapping on reconstructed message/rfc822
attachments. Encapsulated bytes now remain unchanged under a permitted binary
transfer encoding, including non-UTF-8 content. Native PST fractional sent times
still become whole seconds in reconstructed email/PDF metadata: the creator now
warns explicitly, and the manifest/results record the exact native values and
this known limit. Original PST/MBOX containers are not embedded; checked message
sources are derivatives.

The expanded gate passes: creator 357 tests/4 documented skips; validator
including packaging 244/9; 19 independent checker tests; all five fresh PDFs pass
content, visual, standalone Java EA-PDF and veraPDF checks. All three JARs match
repeat clean builds. No large PST exports. Current tested JARs:
`output/format-quality-2026-11-02/release-gate-verified/artifacts/`.
[Results and limitations](improvement/format-quality-2026-11-02/summary.json) ·
[Gate instructions](QUALITY_GATE.txt) ·
[Independent source evidence](../tests/quality/provenance.txt).
Task-owned temporary builds and superseded payload-phase JAR copies are removed;
source hashes, conversion logs, Java reports and earlier test evidence remain.
Existing dist/ and published releases are unchanged. Next work: structured
diagnostics and targeted reader/writer refactoring, supported by this safety net;
then broader layout/accessibility coverage and malformed-input fuzzing.

**Payload handling improved — 2026-11-02 (local, unreleased):** missing
attachment/source MD5 and SHA-256 fingerprints are calculated together using an
8 KB buffer, rather than loading staged payloads into full arrays. Inline-image
rewriting reuses each payload's loaded bytes, including repeated CID references.
Failed/truncated reads cannot cache partial fingerprints; attachment link identity
no longer silently falls back after a payload read failure. Nine new regression
tests include a 64 MB file fingerprinted with a 32 MB Java heap. The small release
gate passes: creator 353 tests/4 explicit skips; validator including packaging
244/9; eight independent Python oracle tests; two real EML outputs pass content,
visual, EA-PDF and veraPDF checks. Three JARs reproduce byte for byte. This gate
excludes the optional large-corpus test class and performs no large PST exports.
[Changes, evidence and limits](improvement/payload-quality-2026-11-02/summary.json).
Current tested JARs: output/payload-quality-2026-11-02/release-gate/artifacts/.
Temporary builds and superseded phase-one JAR copies are removed; their hashes
and test records remain. Existing dist/ and published releases are unchanged.

**Storage cleanup — 2026-11-02:** project size reduced from 60.2 GiB to
9.9 GiB, freeing about 50.3 GiB (54.0 GB). Removed oversized historical
conversion PDFs, obsolete candidate JARs/profiling recordings, superseded local
beta.1/beta.4 release packages and regenerable caches. Large historical reports
and logs are retained as `.gz`; every compressed copy was decompressed and
SHA-256 checked before the original was removed. Original inputs, tracked source,
examples, reference PDFs, current dist/beta.5 deliverables and latest quality
results are preserved. No tracked files were deleted; eight independent quality
checks still pass. Historical links may now refer to removed generated PDFs or
reports with an added `.gz` suffix. The exact action list is in
[cleanup record](maintenance/cleanup-2026-11-02.json). The older development
archive is retained because it is the only recorded copy of some historical
evidence. No conversion jobs were running during cleanup.

**First quality phase implemented — 2026-11-02 (local, unreleased):** the
creator's existing progress.json now records final PDF hashes, creation and
normalized-record preservation checks, known source problems, job warnings and
publication state. It explicitly leaves full EA-PDF/PDF/A validation as NOT_RUN.
Two pinned real EML inputs now have independent source/attachment/text checks,
four reviewed visual reference pages, and standalone Java/veraPDF acceptance.
Eight deliberate-loss/oracle tests pass. A local release gate builds/tests both
Java tools, rejects unexpected Java skips and compares clean builds; all three
JARs reproduce byte for byte. A matching GitHub workflow is added but has not
run remotely. Full local suites: creator 368 tests/4 skips; validator including
packaging 244/9; Python 1,276/43; no failures/errors. No large PST exports.
[How to run and interpret the gate](QUALITY_GATE.txt) ·
[Verification and limitations](improvement/quality-phase1-2026-11-02/summary.json).
Evidence and tested JARs: output/quality-phase1-2026-11-02/release-gate/.
The new independent oracle is deliberately limited to two EML examples;
broader format/layout coverage, architecture, streaming and fuzzing remain.
Existing dist/ and published releases are unchanged. Task-owned temporary
builds are removed after retaining evidence.

**Validator sidecar mode removed — 2026-11-02:** both validators always run
EA-PDF validation, including PDF/A and applicable ISO 32005 checks. Desktop/web
target dropdowns, the PDF/A-only execution/report branches and scope plumbing
are removed. The manual is updated and passes PDF/A validation. The tested Java
validator is installed in local `dist/` with its checksum; the published beta.5
release is unchanged. Full Java/Python suites and a real 229-rule EA-PDF check
pass. Evidence: `output/remove-sidecar-validation-2026-11-02/`.

**Specification decision brief — 2026-11-02:** the 18-page
[Word document for the experts](../EA_PDF_Specification_Questions_for_Experts.docx)
covers 25 decisions, with source locations, examples, current interpretations
and suggested resolutions. It includes publication corrections and original
Core Field table pages. Public discussions were rechecked, including today’s
closure of project issue #2 pending expert adjudication of remaining matters.
Sources and verification are in `docs/improvement/specification-questions-2026-11-02/`.

**Release 1.6.0-beta.5 — 2026-10-31:** creator and validator JARs rebuilt,
tested and installed in `dist/`; viewer packages are 0.4.0-beta.2. This release
includes security/workflow corrections, the 229-rule refinement, the user manual
and the concise 290-page author review. Examples retain their beta.4 provenance.
[Release verification](improvement/release-1.6.0-beta.5/summary.json).

**Rule-review presentation simplified — 2026-10-31:** the Word review now uses
Rule Text, Source Reference and original source quotations. Repeated Finding,
evidence and generic review-note sections are removed; only specific additional
interpretations remain. The historical-rule mapping appendix is removed, and
project issues are now Appendix E. All 229 rules retain specification-page order
and all source exhibits are preserved. This is a document-only update.

**Rule refinement complete — 2026-10-31 (local, unreleased):** the JSON now has
229 independent top-level rules, numbered in specification-page order. Creator,
both validators, viewer and Finding/Specification displays are aligned. PDF/A
remains a veraPDF prerequisite on the finished bytes; applicable ISO/TS 32005
checks remain separate. The source inventory retains 301 records and 418 linked
contexts. [Plain-language results](improvement/rule-refinement-2026-10-31/summary.txt)
· [Rule migration](improvement/rule-refinement-2026-10-31/top-level-rule-migration.txt)
· [Verification](../output/rule-refinement-2026-10-31/verification.json).

At the user's request, all three **local `dist/` JARs are updated**, with matching
checksums and build records. Their embedded version remains `1.6.0-beta.4`;
these are newer local builds, distinct from the unchanged published release.
Creator: 366 tests, 4 skips. Python: 1,271 tests, 43 skips, no failures/errors.
Java: 243 main tests; the initial invocation passed 234, skipped 8 and failed one
native GUI test. That test passed an isolated rerun unchanged; its early stop
has no established cause. Two executable-JAR packaging checks passed. Both
attempts remain in the evidence; the counts are not presented as one clean run.

Ten ordinary EA-PDF outputs and four PDF/A-only sidecars pass. A duplicate-source
collection remains INCOMPLETE only for unassignable per-occurrence counts, with
zero failures; its sidecar passes. All 16 PDFs pass PDF/A. The validators agree
on all 229 outcomes for 15 focused files, including six PDF/A-valid negatives.
Offline report checks verify every rule row, all 418 source contexts and 273
observations. Viewer Chromium checks pass; fresh Firefox runtime testing was
unavailable, while both package builds and integrity checks pass. Large PST
files remain excluded.

The [Word author review](../EA_PDF_Rule_Review_for_Specification_Authors.docx)
now has 229 rules in the same order and 408 rendered pages. Original source
quotations, tables and images are preserved; all 146 source-table hyperlinks
resolve. Appendix F and the [project issue review](improvement/rule-refinement-2026-10-31/project-issues-review.txt)
account for both project issues and their comment. Historical BFO/UIUC files
pass PDF/A and resolved Document-root checks but retain EA-PDF failures. The
updated external sample returns HTTP 404 and was not substituted with an older
file. Two historical headerless MSG fixtures have stronger recipient evidence
in Python; Java conservatively leaves that comparison unverified.

Candidates, real outputs, logs, screenshots, source snapshots and test receipts
are retained in `output/rule-refinement-2026-10-31/`. Temporary task builds and
staging are cleaned after retention. The earlier 213-rule checkpoint remains
historical evidence, not acceptance evidence for the new catalogue. Original
working files and the source delta preserve the preexisting dirty workspace.
Published specification ambiguities and local interpretations remain explicit;
passing tests do not independently certify every permitted representation.

**Rule refinement scope — 2026-10-31:** the user approved valid PDF/A as the
starting prerequisite, with PDF/A validation delegated to veraPDF. Refine the
additional EA-PDF requirements without rebuilding the PDF/A rule catalogue.
Record a passing result for the applicable profile and final file bytes;
revalidate if subsequent EA-PDF assembly changes them. EA-PDF-specific profile,
metadata, association and structure constraints remain in scope, including any
separate ISO/TS 32005 requirement. The audit report and work order now reflect
this decision. Its final section gives a seven-step refinement plan in plain
English, including clear wording, missing requirements, evidence limits,
specification disagreements, software review and focused testing. This scope
decision preceded the implementation checkpoint above.

**Specification and rulebook reexamination — 2026-10-31:** reviewed all 19 tables
and clauses 1–9 against the current 213 rules, plus all 26 upstream issues and
47 comments. The audit identifies nine work items: lost requirement conditions,
unclear recommendation strength, interpretation records, incomplete semantic
coverage and the distinction between the complete source inventory and runtime
checks. Existing local decisions are distinguished from official corrections.
The registry audit passes for 301 inventory records; this proves traceability,
not complete implementation. Upstream issue bodies, comments and states match
the earlier review. No rule JSON or product code was changed in this audit.
[Plain-language findings and priorities](improvement/specification-audit/specification-reexamination-2026-10-31.txt)
· [Evidence](improvement/specification-audit/reexamination-2026-10-31-verification.json).

**Records-workflow corrections — 2026-10-31 (unreleased):** fixed web creator
startup under inherited native limits and Python/Docker address-space handling.
Both validators now offer an explicit **PDF/A-only** target for sidecar finding
aids; the main email PDF still uses strict EA-PDF validation by default. PDF/A-only
reports leave EA-PDF and ISO 32005 unevaluated, including incomplete runs.
The creator GUI now flags source exceptions instead of showing an unqualified
Finished result. A deterministic public-configuration packager excludes local
credentials, tokens and signing keys; historical release files remain unchanged.

The viewer reads the real 1,433-message MBOX metadata within a 16 MiB metadata
ceiling, explains unavailable metadata and exports 1,433 CSV rows. Collection CSV
exports 71 actual small-PST messages instead of counting the parent index twice;
repeated identities in distinct children remain separate. New documents open at
the left edge. Existing object/part traversal limits can still produce partial
inspection even when the flat email metadata is complete.

Creator: 360 tests (4 skips). Java: 215 unit tests (9 skips) plus 2 packaging
checks. Python: 1,223 full-suite tests (43 skips), then 65 affected checks after
the final scope changes. All pass. Chromium and clean temporary Firefox UI
checks pass. PDF.js patch tests pass; affected upstream tests have one documented
OffscreenCanvas skip. Web 1s/1m outputs and ordinary PDF/A sidecars pass Java
validation. All 78 selected originals retain their recorded hashes. Larger PSTs
remain excluded. The three JARs reproduce byte for byte.

Tested local candidates and evidence are in
`output/records-fixes-2026-10-31/candidate/` and
`output/records-fixes-2026-10-31/verification.json`. Embedded candidate version
labels remain beta.4; these are unreleased corrections, distinct from the public
beta.4 binaries. Root `dist/`, GitHub releases and store submissions are unchanged.
The three missing-date MBOX failures and one uncertain MSG source binding from
the first-use review remain documented exceptions; no source facts were invented.

**User manual — 2026-10-31:** [33-page PDF/A manual](EA-PDF_User_Manual.pdf)
covers the creator, standalone Java validator, Python/web validator and viewer,
with ten real application screenshots using fictional email. Linked contents
and bookmarks are included. PDF/A-2b validation passes; the PDF reproduces byte
for byte. Editable HTML, screenshot/build instructions and verification receipts
are in [manual sources](manual/user-manual.html). The guide identifies the
hardening checkpoint as unreleased and preserves PST/MBOX derivation limits.
The opening page now identifies EA-PDF, its purpose and its creator,
Thinkbox.DIGITAL. Setup steps and edition notes remain in the relevant chapters.

**2026-10-31 security checkpoint:** creator, both validators and viewer hardening
is implemented and locally verified. Creator: 358 tests (4 skips); Java validator:
214 tests (9 skips) plus 2 packaging checks; Python: 1,217 tests (43 skips), all
without failures/errors in the final runs. All 24 standard PDFs and four fresh
EML/MSG/small-PST/MBOX collections pass standalone Java checks. Builds reproduce;
public downloads/root dist JARs remain beta.4. Store/manual-platform acceptance
and other limits remain explicit. [Hardening and limits](security/HARDENING.md) ·
[Verification](security/verification.json). Large PST files remain excluded.


**Current release — 2026-10-30:** creator and validator version
**1.6.0-beta.4** includes the creation/validation code repairs below. The three
local `dist/` JARs now contain the tested beta.4 code, with updated checksums and
build records. Creator tests: 355 (four skips); validator tests: 208 (nine skips),
plus two executable-JAR checks, all without failures/errors. All 24 standard examples and their collection sidecars are regenerated with
these JARs. Each primary PDF passes Java EA-PDF, PDF/A and selected ISO checks;
finding-aid PDFs pass PDF/A. Source hashes are unchanged.
[Release and checksums](https://github.com/thinkbox-digital/eapdf/releases/tag/v1.6.0-beta.4) ·
[Verification](improvement/release-1.6.0-beta.4/summary.json). Large
PST files remain excluded. Viewer code and version are outside this release.
Task-owned `/tmp` files are cleaned at build/test checkpoints; unrelated and
active temporary files are preserved.


**Earlier local example refresh — superseded by beta.4 above:** all 24 EML/MSG/small-PST/MBOX
examples and their collection sidecars are regenerated with the current local
1.6.0-beta.3 creator and final close-out validator. All 24 primary PDFs pass
standalone Java EA-PDF, PDF/A and selected ISO checks, including collection
children; all generated finding-aid PDF sidecars pass PDF/A. Source hashes are
unchanged. The creator identity is the explicitly labelled demonstration value
`EA-PDF example test organization`. Specialty signed/accessibility/negative
examples retain their previous provenance. [Examples](../examples/README.md) ·
[Refresh evidence](improvement/example-refresh-2026-10-30.json).

**Current work close-out — 2026-10-30:** the creation/validation performance
work is closed out using the completed runs and ordinary-input regressions.
Large PST exports are excluded from further profiling, reruns and acceptance
gates at the user's direction. Do not resume the stopped run or start an
all-seven-PST campaign unless explicitly requested. Keep existing inputs,
outputs, logs and reports as reference evidence. Machine RAM requirements remain
a known operational limit; recorded source-comparison findings remain explicit.

The final workspace retains the memory repairs and removes the identity-file
mapping limit that caused excessive map/unmap activity. Final verification passes
208 validator tests (nine skips), two packaging checks and all 22 retained small
PDFs, with unchanged finding counts, PDF/A assertion totals and source/PDF hashes.
[Final workspace acceptance](improvement/profiling-2026-10-30/closeout-acceptance.json). At this close-out checkpoint, the installed JARs and
published release had not yet been replaced; beta.4 delivery is recorded above. The stopped run and its completion watcher
are inactive, and its scratch files have been removed.

Earlier large-file acceptance requirements below are historical and superseded
by this scope decision. Signing and other independent checks retain their own
previously documented status.


**Renderer merged and performance acceptance — 2026-10-30:** main contains
`c2d6065`, merging patched OpenHTMLtoPDF `1.1.105-eapdf.1`. PDFBox/XMPBox stay
3.0.8. Existing assembly supplies final EA-PDF/PDF/A-4f structures.
[Renderer provenance and reproduction](improvement/openhtmltopdf-1.1.105/INTEGRATION.md).

**PST/MBOX preservation scope:** the PDF embeds derived RFC 822 messages, while
the original PST/MBOX container remains outside the PDF by design. Completed
conversion and preservation checks cover the exported messages and recovered
attachments, not the complete native container or all its storage properties.
Original-container embedding is not an outstanding repair. The derivation
notice alone does not set the current validator to INCOMPLETE; this largest PDF
also has recorded header-fidelity and unresolved source-comparison findings.

The seven performance recommendations are implemented within the current
architecture. Remaining PDF finalization/import copies are retained to preserve
document ownership. The combined creator suite passes 353 tests (four skips);
the Java validator suite now passes 208 (nine skips), plus two executable-JAR
checks, with no failures/errors.
All 22 final matrix conversions pass EA-PDF/PDF/A-4f checks; the 11 enabled outputs also pass PDF/UA-2 machine checks. Largest-PST creation and exported-message/attachment preservation checks now pass: 48,129 messages,
88,765 pages, 1,742.5 seconds, 11,694 MiB process-tree RSS, unchanged source hash.
Independent validation first failed on veraPDF's above-2-GiB xref decoding and
our retained association graph. Both defects have targeted fixes and regressions.
Subsequent PDF/A retries exhausted 10 and 14 GiB heaps; the EA-PDF-only probe
also exhausted 10 GiB during page-content checks. A bounded decoded-object-stream
cache has positive/negative equivalence regressions, but its 14 GiB and 21.25 GiB
full retries were interrupted. The maximum-available-memory retry now **finishes**
with a 19 GiB heap and four processors: **PDF/A-4f PASS** (201,183,288 assertions),
**selected ISO 32005 PASS** (71,118,152 assertions), **zero failed EA-PDF findings**.
Overall **INCOMPLETE** remains because four findings record missing source
information and unresolved source/header/body comparisons. This is no longer a
memory failure. The monitored run took 6,157 seconds and peaked at 25.0 GiB process
RSS, including mapped identity files. Source/PDF hashes are unchanged; logs,
the complete 2.4 GiB Java report and profiling evidence are retained. Scratch was
removed after that run. The newer candidate’s largest-file comparison was stopped at the user’s
request during ISO 32005 checking. PDF/A passed; ISO 32005 and EA-PDF acceptance
remain unfinished. No validation or completion watcher remains running.
[Stopped-run evidence](improvement/profiling-2026-10-30/followup-stopped.json). IntelliJ’s available run
configurations did not expose its entry point; that is an IDE configuration
limitation, not an unavailable validator.
[Completed retry evidence](improvement/profiling-2026-10-30/largest-memory-retry.json).

Three further memory changes—deferred diagnostic paths, compact ASCII identity
records and bounded PDFBox property wrappers—pass 199 validator tests, two
packaging checks and all 22 retained matrix PDFs with unchanged outcomes.
A one-million-ID probe reduces encoded key data by 25.3%, preserving exact
membership. The largest retry used the frozen pre-change candidate; these new
changes have completed their own largest-PST run: identical per-code/per-level
findings and PDF/A/selected ISO assertions, 5,395 seconds and 23.2 GiB peak RSS.
These are local, non-isolated measurements.
[Initial candidate comparison](improvement/profiling-2026-10-30/initial-candidate-largest.json).
Further repairs to retained
structure/text, mapped windows, filter allocations and dictionary storage pass
208 validator tests (nine skips), two packaging checks and all 22 retained PDFs
with unchanged outcomes. Their frozen largest-file comparison was stopped by the user during ISO 32005;
PDF/A passed, but final acceptance is unfinished. Profiling identifies repeated
identity-file mapping/unmapping as a major cost. The workspace revision removes
that mapping limit and passes the final small-regression acceptance above. The installed JAR is unchanged.
[Follow-up changes and source evidence](improvement/profiling-2026-10-30/FOLLOWUP.md).
[Runtime findings and candidate evidence](improvement/profiling-2026-10-30/MEMORY_RETRY.md).
The bounded-cache candidate now passes all 22 retained real-email matrix PDFs
with unchanged rule outcome counts and PDF/A assertion totals; original PDF and
source hashes match. This is separate from largest-file acceptance and does not
replace the installed JAR. [Continuation evidence](improvement/profiling-2026-10-30/cache-acceptance.json).
The largest 2.5 GiB file cannot open in cached Chromium 145 because PDF.js reserves
a full-file array; smaller real archives and Gmail still view successfully.

On the identical largest staged file, metadata enumeration now completes with a
2 GiB heap (148 seconds, 2,231 MiB peak process RSS); the earlier whole-XMP-copy
check exhausted that heap. On the same 32,764-page PST 06 viewer file, DOM nodes
fell from 233,070 to 1,123, browser process-tree memory fell 26%, and page jumps
were 76–93% faster. Smaller-job automatic budgets reduce MBOX/PST creator memory
by about 71–76%, with longer runtimes. Fresh Gmail creation, validation and viewing
pass. These are local single-run comparisons with overlapping workloads.

PST 06 retains its previously documented INCOMPLETE source-comparison verdict;
performance changes do not restore unavailable source information. The large-PST campaign is excluded from current acceptance at the user’s
direction. Signing retains its separate acceptance status.
The validator JAR was rebuilt locally with verified executable packaging; the creator release and GitHub remain unchanged. [Validator checksum and launch evidence](improvement/validator-packaging-2026-10-30.md).
[Measurements, acceptance and remaining limits](improvement/profiling-2026-10-30/REPORT.md).

**Architecture decision — 2026-10-30:** retain PDFBox and the current
OpenHTMLtoPDF rendering pipeline. The user has deferred WeasyPrint evaluation
and migration. Continue creation/validation reliability work within this stack.

**Expert feature-list intake — 2026-10-30:** reviewed the supplied local
`/media/lepore/Work/features.txt` and the linked BFO `OutputProfile.Feature`
API documentation. This is PDF/A comparison evidence, not an EA-PDF rulebook
or proof of full validator parity. Initial observations:

- Keep base PDF/A-4 restrictions separate from PDF/A-4f overrides; the latter
  permits arbitrary attachment types and requires an embedded file.
- Our main attachment writers already set MIME Subtype and AFRelationship.
  This source inspection does not establish coverage of every object/path.
- Reconcile edition-specific differences before changing rules. The supplied
  base list still includes `CIDFontSubsetWithCIDSet`, despite the email's relaxed
  deprecated-feature policy. `AnnotationZeroSizeFlagsMissing` appears both in
  the base list and as a revision addition. These need reconciliation, not
  automatic changes to passing/failing outcomes.
- A complete feature-to-veraPDF/custom-rule mapping remains outstanding.
  No renderer, validator rules or validation profiles changed in this intake.

**Previous release — 2026-10-29:** creator/validator **1.6.0-beta.3**, viewer
**0.4.0-beta.1**. Includes the pending creation/validation repairs, issue #1/#2
rule clarifications and requested interface changes. Viewer 0.4.0-beta.1 is unchanged;
the 24 standard example PDFs now use the local beta.3 creator with explicit
demonstration attribution, as recorded in the refresh above.
The earlier large-PST acceptance gate is superseded by the 2026-10-30
close-out scope decision above; historical unfinished runs are retained as evidence.

**Creator identity correction — beta.3:** `creator` in the configuration
now supplies the responsible person, organization or service as `dc:creator[0]`.
The GUI has a **Creator identity...** button for the conversion session. A blank
value stops local-email/Gmail conversion before source processing; no software,
email-sender or operating-system identity is substituted. `xmp:CreatorTool` and
`pdf:Producer` continue to identify software. Both validators distinguish the
required name-array structure from attribution, which still needs independent
provenance. Rules 152–153 already express that distinction; their requirements
are unchanged. Set `creator` in the configuration for persistence across sessions.

The creator suite and targeted Java/Python validator tests pass. A fresh real EML
conversion with an explicit test organization passes full Java validation and
preserves the original source hash. Local JARs are installed; restart GUIs.
Evidence and counts: `output/creator-identity-2026-10-29/summary.json`.
Beta.3 packages these corrections; the 24 standard example PDFs were refreshed
on 2026-10-30, as recorded above.

**Spreadsheet, dialog and diagnostic corrections — beta.3:** spreadsheet index values over Excel's
32,767-character cell limit now continue in numbered chunks on continuation
sheets, with email/field identifiers and a link from the main index. Unicode
characters remain intact and full values round-trip through XLSX. The signing
password dialog has room for its full title and requests entry focus on opening.
The validator now labels expected versus observed namespace bindings explicitly.
The unchanged BFO file passes PDF/A but still fails EA-PDF checks; its document
XMP declares `pdfmailid` and `pdfmailmeta`, but no `xmlns:pdfmail` binding. XML
namespace declaration order is irrelevant. No rule requirement was changed.

Checks: **20 creator/index tests and 17 validator tests pass**. Both local JARs
were rebuilt and installed; restart open GUIs. The live signing-dialog interaction
check was inconclusive; focus acceptance remains a manual check. PST 16 has not
been reconverted on this fix. Published beta.2 assets and example PDFs are unchanged.
Evidence: `output/spreadsheet-dialog-namespace-2026-10-29/`.

**Latest external large-PST run:** the six recorded results and PST 16 conversion log supersede earlier
successful spot checks for acceptance of the current corpus run.

| PST | Result | What completed / remains unresolved |
| --- | --- | --- |
| 06 | INCOMPLETE | Converted; full checking finished; PDF/A PASS; unavailable source comparisons remain. |
| 24 | INCOMPLETE | Converted; full checking finished; PDF/A PASS; unavailable source comparisons remain. |
| 23 | VALIDATION_FAILED | Converted; PDF/A PASS; required Sent metadata absent on native notifications. |
| 22 | INCOMPLETE | Converted; full checking finished; PDF/A PASS; unavailable source comparisons remain. |
| 20 | INCOMPLETE | Converted; PDF/A PASS; validator exhausted memory during EA-PDF page-content checks. No complete verdict. |
| 25 | CONVERSION_FAILED | Malformed stored attachment MIME type `,bmp/octet-stream` stopped RFC 822 reconstruction. |
| 16 | CONVERSION_FAILED (log/progress evidence) | Spreadsheet index exceeded Excel's 32,767-character cell limit after rendering 48,129 messages. No final result JSON or validation report. |

PST 25 now falls back to `application/octet-stream` for malformed MIME labels,
retaining original payload bytes and the stored label in a warning. Its focused
regression passes; the complete 79,830-message input has **not** been rerun on
this repair. The corpus used frozen creator `d16f49b61500` and validator
`a735c63b32cd`; the new release is not retroactively credited with those results.
Missing source evidence, genuine metadata failures and unfinished validation
remain separate outcomes. Full seven-file verification, native comparisons,
large-output signing and complete signed validation remain outstanding.

Reports: `output/large-pst-beta/pst/*/result.json` and referenced logs/reports.
Release checks and artifact hashes: `docs/improvement/release-1.6.0-beta.3/summary.json`.

**Beta.3 verification:** 348 creator tests (four missing-source skips), 191 Java
validator tests (nine source/desktop skips), and 27 focused Python tests (one
optional skip), with no failures or errors. A fresh real EML conversion passes
full Java validation; original input hash is unchanged. Local JARs match beta.3.

**Previous beta.2 verification:** all 24 standard examples pass standalone Java validation;
all six small signed profiles verify and pass the machine checks. The deliberately
incomplete MBOX/PST examples retain FAIL/INCOMPLETE. Creator tests: 344 cases,
zero failures, four absent-source skips locally and 27 optional-input skips in the
clean source tree. Validator: 190 cases, zero failures, nine absent-source/desktop
skips. Python: 1,202 cases, 43 skips; the stale review checksum was synchronized
and its three checks rerun successfully. Viewer: 20 unit tests and Chromium HTTP
CSV/folder-scrolling checks pass; extension installation and Firefox were not rerun.
These are historical beta.2 checks. Beta.3 checks and artifact hashes are in
`docs/improvement/release-1.6.0-beta.3/summary.json`.

## Earlier working-build evidence (before this release)

The entries below retain their original build scope and dates. Earlier memory
repairs and isolated completions do not establish that the latest large run passed.

**Release-note preference:** keep future GitHub release updates to the basics:
versions, a few user-visible changes, essential setup links, and beta status.
Detailed implementation and verification evidence belongs in project documentation.

**Korean rendering repair — latest creator build:** PST 20 converted all 34,249
emails and its native audit matched all messages and 7,143 readable attachment
payloads. Two emails exposed missing Korean glyphs in the bundled font. The
creator now includes unmodified Nanum Gothic through its existing fallback path.
Both real emails render the Korean name correctly, emit no missing-glyph warning,
and pass full Java validation without external font settings. All **343 creator
tests pass**. The 84 ordinary/medium inputs have now been refreshed: **79 PASS, 2 INCOMPLETE,
3 missing-date failures; all converted and source hashes unchanged**. The 282-email collection also passes again, including its children. The seven
large archives are still being refreshed. Earlier large results below remain
evidence for the previous creator, not new-build passes.

**PST 20 validation memory repair — completed check:** the deferred resource
checks now retain the live resource-name sets without retaining parsed drawing
operators. The installed validator (`a735c63b32cd5d99`) completed checking the
unchanged 34,249-message PDF in approximately 3,530 seconds. PDF/A PASS,
7,107,625 passed findings, zero EA-PDF failures. Overall **INCOMPLETE** remains:
missing source information and unresolved source/attachment comparisons prevent
an unqualified pass. The controller's exit status was unavailable; the saved
report and Finished progress establish completion, not an invented exit code.
Evidence: `deferred-resource-retention/after-pst20-result.json` and its referenced
full report. Validator suite: **184 tests, zero failures/errors, one GUI skip**.
The previous failure with the older validator remains recorded separately.

**External large-run handoff:** run `bash scripts/rebuild_large_psts.sh` from the
repository. It recreates PSTs 06, 16, 20, 22, 23, 24 and 25 under
`output/large-pst-beta/`, one archive at a time with four rendering jobs, and
runs standalone Java validation after each conversion. Matching completed work
is reused on restart. Source/build/PDF/report hashes and logs are retained.
Exit 2 means at least one result requires review, including source-caused
failures or incomplete comparisons; it does not imply conversion failed.
No long corpus job was started during this handoff. Await the user's completed
run before native comparisons, signing the regenerated PST 24 copy, full signed
validation, and final reconciliation. These acceptance steps remain outstanding;
no new features or reader work are included.

**PST 22 validation memory failure fixed:** its full check now finishes.
The validator was retaining the completed veraPDF document while loading another
copy for EA-PDF checks. Each upstream pass now ends its own working thread first.
A live memory check confirms the retained veraPDF objects dropped from 66.9 million
to six. PDF/A and ISO 32005 pass, with zero EA-PDF failures. The overall result
remains **INCOMPLETE** because some source comparisons cannot be resolved; details
are below. Evidence: `output/beta-gap-repair-2026-10-28/upstream-context-retention/`.

The main repairs are implemented. Full corpus verification is still in progress;
this is not yet a completed beta acceptance report.

A parallel HTML-rendering crash was reproduced during the 282-email collection
refresh. The creator now avoids iterating a shared empty jsoup child list. All 342 creator tests pass, as does the exact crash reproduction. The
real 282-email collection passes full Java validation again with the thread-lifetime fix (109 seconds for validation alone); the
corpus sequence is running on that build. The earlier completed collection remains separately preserved.

## Current code

- **Creator:** default PST `s` preserves exported emails and attachments. Large
  folders receive the same workload allowances as large PSTs. The old 16 GiB
  output cap is removed; disk, heap and time limits remain finite. Signing and
  collection repair use the indexed loader. Large structure parent trees use
  bounded branches.
- **Preservation repairs:** encode literal percent signs correctly in mailbox
  URIs; preserve repeated PST recipient headers; recover UTF-7 text; extract a
  valid mailbox from native MAPI name-and-address values. Malformed addresses
  remain unchanged. Unsupported source header characters produce an explicit
  source warning. Original source and readable attachment bytes remain preserved.
- **PDF repairs:** omit hidden HTML form inputs from rendering, remove empty
  renderer-created forms, and repair heading/list structure without removing
  visible content. Actual affected emails pass full Java validation.
- **Java validator:** disk-backed upstream visited IDs, bounded caches, indexed
  source/page/ownership lookups, disk-backed MIME and collection inspection,
  streamed full reports, adaptive GUI worker memory and durable incomplete
  reports. Inspections remain bounded; interrupted checking is not a proven
  PDF violation.
- **Web/Python:** workload-based time/memory/disk allowances, bounded console
  output with complete reports/logs on disk, and explicit incomplete outcomes.
  Large Python/Docker validation capacity remains unproven; corpus runs use Java.

Installed local creator: `dist/emailtopdfa.jar` and `dist/emailtopdfa-gui.jar`,
creator hash prefix **d16f49b61500ae144**. Validator: `dist/eapdf-validator.jar`,
hash prefix **e0814dbc61995923**. Restart an open GUI to load the changes.
Build identities and source hashes are recorded in `dist/*-build.json`.
No GitHub publication occurred.

The ordinary-input sweep has now been repeated with the thread-lifetime repair: **80/80 converted**, **75 PASS**,
**2 INCOMPLETE**, **3 source-caused validation failures** (missing Sent dates).
No conversion failures. The incomplete MSG cases remain DEP response (unresolved
SMTP sender) and test_Blammo (missing ANSI code page). The three failing MBOX
cases are Drafts, mbox_01_fmt_720_MBOX_quoted and msg. Their PDFs all pass PDF/A.
Final PST 06 conversion and independent validation also completed; details below.
All three medium PSTs (02, 14, 21) pass full validation on the current build,
using 3 GiB creator / 4 GiB validator heaps. Their unchanged outputs also pass with the latest validator. Together with the small sweep and
71-email test PST, **84 inputs are fully checked: 79 PASS, 2 INCOMPLETE, 3
source-caused validation failures; zero conversion failures**. PST 06 (INCOMPLETE, zero failures) and PST 23 (three source-caused missing-date
failures) have also finished, leaving five large PST results and the large
signed-file check outstanding. PST 22 conversion and full validation now finish after the memory fix described above. PST 20 is converting next.

## Completed checks

| Check | Result and limits |
|---|---|
| Creator full suite | **343 tests, zero failures/errors/skips** |
| Java validator full suite | **190 tests, zero failures/errors, one GUI skip**; separate GUI validation/cancel smoke passed |
| Python suite | **1,195 passed, 40 optional skips** |
| Java/web parity | **24/24** current example PDFs, no differences in 213 root rule outcomes |
| Accessibility | **37/37** current example PDFs pass the explicit PDF/UA-2 machine profile, tied to exact hashes; no claim of human accessibility certification |
| Large GUI report | Actual report parser/grouping/Save handles **7,202,075 findings / 1.7 GiB**, 331 groups, exact saved bytes, with a 1 GiB heap in 118 seconds; report-model check, not a visual assertion |
| Native recipient repair | PST 06 descriptor **5210276** now passes PDF/A, ISO 32005 and EA-PDF on the current creator |
| Large collection | **282 real MBOX emails**, current creator and all embedded PDFs PASS; current creator/validator refresh passes, including all embedded PDFs |
| Original large PST 24 | **34,601 emails / 65,855 pages / 1.28 GB**; PDF/A PASS (149,522,102 assertions), ISO 32005 PASS (50,716,966), EA-PDF stage finishes in 794 seconds. Old PDF still FAILS 25 address checks; targeted repaired emails all PASS. Full regeneration is queued. |

The current PST 06 run finished all **15,856** emails in **677 seconds**;
full validation finished in **1,046 seconds**. PDF/A and ISO 32005 PASS,
**zero EA-PDF failures**, overall **INCOMPLETE** because some source data/header
comparisons remain unavailable. Independent native comparison matches all messages
and all **1,543 readable attachment payloads**, with zero unexplained differences.
Four native attachment entries could not be recovered; six plain bodies omit
XML-invalid control characters. The corrected native recipient now matches; one
malformed source address still prevents a unique validator source comparison.
Other address headers remain uninterpretable. All limitations remain explicit.

The current PST 23 run finished all **8,205** emails in **349 seconds**;
full validation finished in **499 seconds**. PDF/A PASS;
overall **FAIL** from three missing Sent fields. All three are native non-read
notifications with neither submission nor delivery time; no date is invented.
The output records the missing-date warnings. Independent comparison matches all
messages and all **1,316 native attachment payloads**. This is successful
conversion with source-caused compliance failures, not a compliance pass.

PST 22 now finishes full validation in **1,914 seconds** with the memory fix.
PDF/A and ISO 32005 PASS; **zero EA-PDF failures**, **4,755,754 passed findings**.
Its overall result is **INCOMPLETE**. The 23 unmatched source comparisons are:
19 Exchange/X.500 address fields, two fields with mailbox-like text in comments,
one malformed angle-address containing a leading comma, and one subject with
an XML-invalid control character. All 22 address fields retain their original
raw text. The separate native audit matches all **23,173 messages** and all
**4,045 readable attachment payloads**. This is a completed run with explicit
source-interpretation limits, not an unqualified compliance pass.
Evidence: `pst22-unmatched-source/diagnosis.json` and `upstream-context-retention/`.

The PST 20 queue controller ended unexpectedly with SIGTERM while its supervised
creator continued. The creator subsequently reported COMPLETED after publishing
all 34,249 emails. Its process exit status was not captured and is recorded as
null, not invented as zero. Full validation and source-hash checking resumed on
the completed PDF. See `controller-recovery/` and `pst20-recovered-validation.log`.

## Work still running

1. Rerun PST 06/16/20/22/23/25, then reconcile every one of the 91 current inputs
   using the final creator and Java validator. Large inputs run one at a time,
   with four requested rendering jobs. A separate small-input sweep runs two
   bounded jobs (768 MiB creator / 1 GiB validator each) alongside the large run;
   its per-input results are reused only when build/input/output/report hashes match. Retain rejected, failed and incomplete
   results separately; source defects are not fabricated away.
2. Repeat independent native comparisons against the final seven large outputs.
   Native inventories are complete. These comparisons cover message identity,
   plain-body data and readable native attachment payloads; they do not certify
   all headers, HTML/RTF semantics or visual/human accessibility.
3. Sign a copy of the corrected full PST 24, verify signature bytes and full-file
   coverage, then run full validation on the signed file. This uses an ephemeral
   test certificate, not a production identity or a trust/timestamp claim.
4. Finish the current ledger and resource measurements; remove obsolete scratch.

Combined archives still retain a document object graph and email-record list
that grow with archive size. Finite heap/disk limits can still be exceeded. No
claim of arbitrary-size completion, external specification certification or
human accessibility certification is made.

## Evidence and restart

- Evidence root: `output/beta-gap-repair-2026-10-28/`.
- Live current ledger: `output/beta-gap-repair-2026-10-28/current-corpus-snapshot.json`
  refreshes every 30 seconds from matching current-build per-input receipts.
- Main corpus ledger: `output/beta-gap-corpus-2026-10-28/run-results.json`; each input
  directory has its own `result.json`, original-source hash, conversion log and
  Java report. The main runner merges independent sweeps during its final complete pass; use
  the live snapshot and per-input progress while the runs overlap.
- Active sequence: `final-korean-recovered-sequence.log`; details in
  `pst_*_korean-run.log` and `whole-corpus-korean.log` under the evidence root.
- Small-input sweep: `small-corpus-current.log` and `small-corpus-results.json`
  under the evidence root; helper `run-small-corpus.py` in the temporary workspace.
- Preservation: `native-corpus/`; targeted repairs: `mailbox-uri-regression/`,
  `native-header-regression/`, `native-utf7-regression/`, `hidden-form-regression/`,
  `structure-regression/`, `native-mailbox-regression/`.
- Earlier PST 23 report/source-date diagnosis: `pst23-before-native-mailbox/`.
- GUI report: `large-report-gui-model/`; collection: `real-collection-over-256/`;
  signing: `large-signing/`; current parity: `parity/summary-context.json`.
- PDF/UA reports: `output/pdfua2-examples-2026-10-28/beta-gap-parent-lookup/summary.json`.
- Memory/CPU: `resource-observations.jsonl` and `resource-peaks.json` under the
  evidence root. These are per-process high-water marks and sampled stages,
  not a sum of simultaneous peak memory.
- Temporary scripts/builds: `/tmp/eapdf-beta-gap-20261028/`. Active frozen JARs:
  `creator-korean.jar`, `validator-context.jar`. Keep while jobs run.
  Sequence: `finish-korean-recovered.py`; native comparison:
  `compare-native-when-ready.py`. Stop `resource-observer.py` only after work
  finishes, using the evidence root's `resource-observer-stop` marker. Stop the live
  `snapshot-corpus.py` observer with `corpus-snapshot-stop` in the same directory.

**Viewer folder scrolling — separately requested:** c/ci message PDFs now support
forward/backward edge scrolling within their immediate collection folder, in
sidebar order. Transitions update the selected file, breadcrumb and page counter;
forward opens page 1, backward the last page. Folder ends, nested collections,
unknown/conflicting folder membership and folder cycles never trigger a crossing.
Wheel momentum is guarded against skipping several files. Non-message PDFs are
skipped; unreadable candidates stop with an error. Three folder-policy tests and
real c/ci browser interaction checks passed, including first/last-page landing,
folder ends and a temporary two-folder copy retaining real embedded messages.
All 20 viewer unit tests passed. Browser checks also cover keyboard transitions,
standalone-file boundaries and nested-collection stops, using cached Chromium
through a local HTTP server. Original source PDFs were not modified. Both browser
builds were regenerated.

**Viewer CSV export — separately requested:** the viewer now offers
**Export metadata CSV** for the currently displayed PDF and recursively for
embedded collection PDFs. It exports stored email metadata locally, preserves
Unicode and full-length values, leaves missing values blank, and neutralizes
spreadsheet formula prefixes. Unreadable children and inspection limits stop
export without a partial download. The existing 10,000-record-per-category XMP
inspection cap remains; this is not yet an export route for larger single-PDF
archives. Limits and columns are documented in `browser-viewer/README.md`.
Five focused tests passed. Browser checks of real 1s/1mi/1c/1ci examples,
long subjects, Bcc, ordinary PDFs and corrupt children passed through a local
HTTP server using cached Chromium. Extension-mode testing was blocked by the
cached browser being older than the manifest minimum. Both browser builds were
regenerated. This user-requested viewer change does not advance the separate
large-PST creation/validation acceptance gates.

**Role-mapping repair — 2026-10-29:** the follow-up identified below is now
implemented in both validators and the authoritative rule registry. Root roles
resolve through namespace-aware, bounded mapping chains; namespace targets are
checked rather than merely required to exist. Compatibility RoleMap obligations
remain separate. Invalid targets/cycles fail; traversal limits remain unavailable.
Desktop rule summaries are synchronized. Java: 190 tests, zero failures/errors, one GUI skip
(after isolated fixture/test-setup reruns). Python affected suites: 105 tests,
zero failures/errors, one optional skip. Current EML 1s and 1c examples pass full
Java validation, including collection children. DPart End, provenance and CIDSet policy
clarifications preserve existing behavior. Verification and build identities are
recorded in `output/rule-mapping-repair-2026-10-29/`. The running large-PST batch
continues with its frozen earlier validator; its reports do not establish results
for this new build. No creator change, regeneration or GitHub publication is part
of this repair.

**GitHub issues #1/#2 review — 2026-10-29:** inspected both issues and all comments,
current source, pinned standards and historical/current sample PDF objects.
The old sample reproduces malformed DPart arrays/parents, 32-byte checksums and
unconnected tags; the current EML example has corrected arrays/parents, 16-byte
checksums and 31 page MCID sequences. Missing DPart End is already supported.
Retain pinned namespace, navigation and body-set attachment requirements despite
the disputed comments. Root role-map resolution remains an implementation
follow-up in both validators; namespace-specific resolution requires care.
Issue rule numbers predate the current registry. Evidence and qualifications:
`docs/improvement/github-issues-1-2-review.json`. Review only: no implementation
change, new full validation verdict, or GitHub reply.

## Historical review and work log

Entries below describe the state when written. Current progress and verdicts are
listed above; an older “running” entry does not mean that old process still runs.

**Creation/validation review — 2026-10-28:** A fresh source/evidence review found
several remaining beta gaps. Detailed findings, source locations, evidence limits
and acceptance targets: [review](improvement/creation-validation-review-2026-10-28.txt).
No implementation changes were made during this review.

Recommended order:
1. Resolve the default PST `s` choice producing isolated `si` output and omitting originals.
2. Make full large-file independent validation finish; veraPDF still stalls at 16 GiB.
3. Remove repeated source/asset scans and bound child/report memory; Java collection
   checks still stop at 256 children / 256 MiB, after loading child bytes.
4. Address the desktop validator's fixed 512 MiB worker and differing web limits;
   give stopped/failed checks a durable, clearly incomplete outcome.
5. Rerun PST 06/16/20/22/23/25 with the current creator, then reconcile all 91 inputs.
6. Extend source-fidelity and large-signed-file verification, refresh current-byte
   accessibility/parity/full-build evidence, and continue reducing archive memory.

The latest 40 recorded creator source hashes and 19 validator source hashes match
their build manifests; the validator embeds the current 213-rule JSON. However,
33 of 35 files in the last PDF/UA audit have changed since their recorded hashes.
Historical passes and older running-job snapshots below are not current corpus
or current-file validation results.

**Full PST regenerated successfully — 2026-10-28, 19:19 local:** The fixed
creator completed all **34,601 emails** and published
`output/pst-memory-repair-2026-10-28/pst24-fixed/pst_24_1m.pdf` in **22 minutes
53 seconds**, exit code 0. Normal creator checks, including original-source
inventory reconciliation, passed. Source-data warnings remain recorded in
`conversion.log`: 9,549 normalized date warnings, 8,992 conflicting Message-ID
warnings, 67 missing sent-date warnings (mostly attached emails), and six RTF
bodies with no visible text. Missing dates were not invented. Completion does
not mean that the source was complete or free of problems. The category counts
are retained in `conversion-warning-summary.json`.
The original PST hash is unchanged. Output/source hashes and build identities
are recorded in `result.json` and `source.json` alongside the PDF.

**Independent validation remains incomplete:** The standalone Java validator
was run twice on the published PDF, first with an 11,881 MiB heap and then with
16 GiB plus disk-backed temporary storage. Both attempts reached sustained
memory pressure inside bundled veraPDF's PDF/A checks. The second attempt held
99.96% of its old generation after full collections; six full collections took
56 seconds of a roughly 70-second interval. Both read-only checks were stopped
(exit 143), rather than left thrashing. **No independent PDF/A or EA-PDF
compliance verdict was produced.** This is a validator capacity limitation,
not evidence of a particular PDF compliance failure. Source sent-date gaps
also remain and must not be mistaken for clean source data.

The completed PDF has **65,855 pages / 1,281,462,219 bytes** and its hash is
unchanged after both validation attempts. Conversion and validation outcomes
are recorded separately in `pst24-fixed/result.json`; independent validation
status is also in `validation-outcome.json`. No `java-validation.json` findings
report was produced. Logs, thread traces, source warning counts and memory-retry
evidence are retained beside the PDF. Abandoned validation scratch files were
removed (including approximately 844 MB from the first attempt); the finished
creator's temporary JAR was removed after confirming it was no longer in use.
The next large-file validation task is reducing veraPDF's memory requirements;
this does not require regenerating the archive.

**Standalone validator loader updated — 2026-10-28:** The validator now uses
the same shared-key-index PDFBox loader as the creator for saved PDFs. All rules
remain unchanged. The full suite passes: **168 tests, zero failures/errors, one
existing skip**. The real 71-email PST report has an identical summary, PDF/A
result and complete finding multiset compared with the prior validator; only
finding order differs. `dist/eapdf-validator.jar` and its build manifest are
updated. Evidence: `output/validator-loader-2026-10-28/`.
The full PST regeneration under `pst24-fixed` has completed; this standalone
build was used for both incomplete independent validation attempts described above.

**Full PST regeneration authorized and started — 2026-10-28:** The user
explicitly approved cancellation and regeneration after the checking fixes.
The old supervisor/worker (207992/208011) were stopped, and their saved PDF and
logs remain intact. The earlier cancellation-approval block is resolved.

The original `in/pst_24_GovernFJan-MAr2003.pst` is being regenerated with the
tested creator, four requested jobs, disk-backed working storage and automatic
memory sizing. New output and live progress:
`output/pst-memory-repair-2026-10-28/pst24-fixed/` (`progress.json` and
`conversion.log`). The expected final filename is `pst_24_1m.pdf`. The input
SHA-256 and frozen creator JAR hash are recorded in `source.json`. The frozen
running JAR is `/tmp/eapdf-pst-regenerate-20261028/creator.jar`; retain it until
the run finishes (now finished and the temporary JAR removed). Normal original-source inventory checks are enabled.
Standalone Java validation will follow successful creation; its PDFBox loader
is receiving the same tested shared-index correction to avoid the known slowdown.
No completed full-regeneration or validation result is claimed yet.

**Creator checking speed fixed — 2026-10-28:** Both local creator JARs in
`dist/` are updated. The preserved 1.28 GB PST PDF contains **8,223,635 objects**,
including **7,777,155 compressed objects in 38,886 object streams**. PDFBox 3.0.8
was building a full cross-reference key cache for each short-lived object-stream
parser. Reusing the outer parser's canonical key cache removes that repeated work
without changing parsed object keys or validation rules.

The checker now visits parents before children and does not retain a second
list of all dictionaries. This avoids PDFBox's recursive document-state overflow
seen in the first index-only probe. Unreachable metadata and metadata lacking a
Type entry remain covered and have rejection regressions. Attachment annotation
checks also index pages by Mail_GUID once, preserving the all-page fallback for
an absent identifier. Progress now counts examined objects and checked attachments.

**Measured on the actual saved PST PDF:** the optimized metadata-only check
passed in **209 seconds with an 8 GiB heap**. All saved-PDF intrinsic creator
checks passed in **156 seconds with a 10 GiB heap**, including checks of all
68,189 embedded file specifications. The old run had spent over 90 minutes in the
metadata step without finishing. These timings are for checking the saved file,
not rerendering or full conversion time. The independent large-file probe did
**not** rerun the original in-memory source-inventory reconciliation, standalone
Java validation, signing or publication. It does not establish a completed job.

**40 focused regressions pass.** A new 71-email real PST conversion with four
requested jobs passes its normal source-inventory checks and standalone Java
PDF/A/EA-PDF validation (81 warnings, zero failures). Source and diagnostic PDF
hashes are unchanged. Evidence and failed-prototype diagnostics:
`output/checker-speed-2026-10-28/summary.json`, full-check logs/progress, test
reports and real-PST source/conversion/validation records in that directory.

At the time of this earlier diagnostic, automatic approval review rejected
stopping the old job without explicit cancellation permission. The user has
since authorized cancellation; those processes were stopped and regeneration
completed as recorded above. The diagnostic copy remains safe. IDE configurations
require Make into the prohibited target directory and the live worker has no
debugger connection, so diagnosis used jcmd stacks, local PDFBox source and isolated
saved-file probes. No IDE breakpoints or run configurations were changed.

**PST status — 2026-10-28, 18:27 local:** The retry is **not finished**.
All 34,601 emails were rendered and a 1.28 GB PDF saved, but creator checks have
spent about 80 minutes in the metadata-encoding stage (about 98 minutes total).
A fresh live stack still shows `documentDictionaries` loading PDFBox compressed
objects. JVM counters report about 2,121 seconds of garbage collection and 601
full collections. CPU activity is not evidence of useful forward progress here.
No final PDF is published and standalone validation has not started. The saved
unvalidated copy remains available; investigation should focus on the checker,
not repeat rendering. The process was left running. Evidence:
`output/pst-memory-repair-2026-10-28/pst24-disk/checking-status.json` and
`checking-status-threads.txt` in the same directory.

**Viewer page-number entry — 2026-10-28:** At the user's request, the Page
control is now a plain text field. Type a whole page number and press Enter (or
leave the field) to jump. Invalid values do not navigate; Esc restores the current
page, and scrolling does not overwrite unfinished typing. The Page x of x display
and navigation arrows remain. TypeScript/contract checks and a real-PDF Chromium
interaction check pass (entry, invalid values, Escape, scrolling during editing,
blur and arrows). Both unpacked extensions and local beta ZIPs are rebuilt;
reload the extension and open a new viewer tab. Firefox is rebuilt but this
focused interaction check ran in Chromium. Evidence:
`browser-viewer/test-results/page-entry-chromium.json`. GitHub assets are unchanged.

**Large-PST size-fix verification — 2026-10-28:** The disk-backed retry rendered
all **34,601 emails / 65,855 pages**, completed assembly and metadata writing,
and saved a **1,281,461,049-byte PDF**. Its scratch file reached **3,807,010,816
bytes**, beyond the old failing 3,479,175,168-byte limit. The specific `File too
large` failure is therefore passed on the real input, not just a small test.

The creator's subsequent checks are still running. Live stacks show PDFBox
loading compressed objects from `PdfCreationValidator.documentDictionaries`
during `validateXmpEncoding`; heap use fluctuates substantially. This is a
separate checking performance/memory issue, and **full conversion/validation
success is not claimed**. A hashed `unvalidated-working-copy.pdf` is retained
in `output/pst-memory-repair-2026-10-28/pst24-disk/` so a checking retry need not
repeat rendering. It is a diagnostic copy, not published validated output.
`size-fix-verification.json` records the evidence; `checking-threads*.txt` hold
live thread traces. The worker was not launched with a debugger connection, so
`jcmd` supplied runtime evidence without restarting it. No IDE breakpoints were
changed. Keep its active `/tmp/eapdf-stage-progress-20261028/build` JAR and disk
workspace until the job finishes. The queued standalone Java validation follows
only if creator checks and publication succeed.

**Progress reporting and scratch-file limit fixed — 2026-10-28:** Both local
creator JARs in `dist/` are updated. Reopen the GUI to load the new status display.
`progress.json` now reports completed/total work within archive stages, elapsed
stage/job time, saving bytes, process CPU time and heap use. The GUI and log show
stage changes, final counts and updates every ten seconds; JSON refreshes every
second. Checking and publication remain visible after email rendering finishes.
Unknown totals remain unknown; there is no invented whole-job percentage or ETA.

The previous PST 24 attempt **failed after about 68 minutes**, after rendering
all 34,601 emails, with `java.io.IOException: File too large` while writing XMP
metadata into the shared stream cache. Its native per-file limit was 3,479,175,168
bytes, derived from the final-output allowance but incorrectly applied to scratch
files too. No final PDF was published. Logs and `result.json` remain under
`output/pst-memory-repair-2026-10-28/pst24-adaptive/`.

Native per-file limits now allow the temporary-storage budget as well as the
output budget; final-output limits remain separately enforced. Large PST work
moves to `<output>/.eapdf-work/` when the default temp directory is memory-backed.
The automatic temporary budget uses 80% of usable working-disk space instead of
an input-size multiplier; explicit limits still win. Normal success/failure
cleanup removes each job workspace; abandoned work is reclaimed on the next run
using that working root.

**46 focused tests pass**, including a real native-limited sparse write beyond
4 GiB and continued enforcement of the smaller final-output limit. A fresh
71-email real PST with four jobs passes standalone Java EA-PDF/PDF/A validation
and PDF/UA-2 machine checks. The progress-only candidate also passed real EML
validation. Logs, source hashes and reports: `output/stage-progress-2026-10-28/`.
GUI status updates are compiled; an interactive desktop check has not been run.

The full 34,601-email retry is running with four jobs and disk-backed scratch at
`output/pst-memory-repair-2026-10-28/pst24-disk/`; follow its `progress.json` or
`conversion.log`. Standalone Java validation is queued after successful conversion.
Full large-file completion is still unproven. Earlier running-status notes below
are historical and are superseded by this failed attempt and replacement run.

**Tagging page lookup fix — 2026-10-28:** Archive tagging now builds one page
index and shares it across message tags, semantic parent remapping and attachment
lists. It no longer repeatedly walks the archive page tree for those associations.
Page order, content and tag relationships are preserved. Both local creator JARs
in `dist/` are updated for future conversions.

All **30 focused regressions pass**. A fresh Accessibility-enabled conversion of
all 71 real emails in `in/test.pst`, using four jobs, passes standalone Java
EA-PDF and PDF/A validation and veraPDF PDF/UA-2 machine checks. Validation retains
81 warnings and has zero failures; source bytes are unchanged. Evidence, source
hash, conversion log and reports: `output/tagging-lookup-2026-10-28/`.
The current 34,601-email PST run remains on its frozen earlier JAR and has not
been restarted. This removes the observed repeated lookup, but a whole-conversion
speedup has not yet been measured and large-PST completion remains unproven.

**Latest large-PST check — 2026-10-28, 16:18 local:** The restarted PST 24
conversion is still active after about 46 minutes. All 34,601 emails are rendered;
bookmarks, document parts, embedding and page information have completed. It is
building the logical structure (`tagging`). A live stack confirms active page-tree
lookups in `addArchiveLogicalStructure`; this is another remaining assembly speed
bottleneck. Java heap use is about 5.6 GiB against the 10.5 GiB maximum. Saving and
validation have not yet started, and no final PDF has been published. The running
build predates the periodic progress refresh fix, so an old timestamp does not
by itself show a stall. Evidence: `pst24-adaptive/threads-status.txt` under the
memory-repair output directory. No complete large-PST success is claimed yet.

**Progress snapshot correction — 2026-10-28:** The restarted PST reached
34,601 rendered emails, but its progress file briefly remained at 34,565/rendering.
The one-second write throttle discarded the last counter/stage change before a
long bookmark-building step. Runtime stacks confirmed active assembly, not a
rendering stall. Future builds now force stage transitions and final email counts
to disk and refresh the snapshot once per second during long stages. A regression
covers the previously missed immediate transition and periodic refresh. The active
trial remains unchanged; its next ordinary update advanced to **34,601/34,601,
page_information** at about 18 minutes elapsed.

The same runtime trace exposed additional indexed page-tree scans in bookmarks
and page numbering. These now use forward traversal/indexed references, preserving destinations and
labels. All **30 focused regressions pass**. A fresh conversion of all 71 real
messages in `in/test.pst` reports 71/71 COMPLETED and passes standalone Java PDF/A
and EA-PDF validation. Both local creator JARs in `dist/` are updated. Evidence:
`output/progress-refresh-2026-10-28/`. The running large PST uses its frozen earlier
JAR and is not restarted or modified by this installation.

**PST restart, progress counts and memory settings — 2026-10-28:** At the user's
request, the earlier 4 GiB PST 24 trial was cancelled; its logs remain under
`output/pst-memory-repair-2026-10-28/pst24-compact/`. The replacement run uses
four rendering jobs, the lookup fixes, and an automatically chosen **10,782 MiB
(about 10.5 GiB) Java heap**. It is running under
`output/pst-memory-repair-2026-10-28/pst24-adaptive/`.
Its `progress.json` now updates automatically with completed emails, exact total
when known, and current stage. No complete large-PST result is established yet.

Future conversions choose half available memory instead of a fixed 4 GiB cap,
respect container headroom and explicit limits, and no longer inherit the
launcher's automatic heap cap. Counts stay explicitly unknown until inputs have
been read. Reaching the email count is not the same as finishing final assembly
and publication. [Details](CONVERSION_OPERATIONS.md#live-conversion-progress).

Both local creator JARs in `dist/` are updated. **28 focused regressions pass**,
including concurrent progress counting and memory policy; the prior full signing
build passed 321 tests. All 71 real messages in `in/test.pst` convert with four
jobs, show **71/71 COMPLETED**, and pass standalone Java PDF/A and EA-PDF checks.
`in/2.eml` shows **1/1 COMPLETED** and passes PDF/UA-2 machine checks with
Accessibility enabled. The two premature renderer title/description warnings
are now suppressed by diagnostic ID; ordinary renderer logs and other warnings
are retained. Evidence: `output/progress-memory-2026-10-28/`.

The stopped trial reported about 52 seconds of GC time after 60 minutes, while
runtime stacks showed long assembly scans. Memory was tight, but those figures
point mainly to assembly work as the measured slowdown; they do not measure all
concurrent GC or operating-system memory costs.

**Digital signing implemented — 2026-10-28:** The local creator GUI now has
**Sign output** and **Signing settings...**. Select a `.p12`/`.pfx` certificate and
private key; its password is requested when conversion starts. CLI/Gmail use the
same optional configuration without new arguments. Only the outer PDF is signed,
after all repairs; embedded files and finding-aid sidecars remain unsigned.
Signing is disabled by default. Invalid credentials or signature failures stop
publication and leave existing output untouched.

All **321 creator tests pass**. Six fresh signed real-email profiles pass standalone
Java PDF/A, EA-PDF and PDF/UA-2 machine checks; independent `pdfsig` checks confirm
valid signatures covering each complete file. Collections contain no newly signed
children. RSA/EC, altered bytes, trailing modifications, bad credentials, atomic
failure handling and signature reads beyond 2 GiB are covered by regression tests.
The latter is a sparse-file range test, not a full multi-gigabyte signing run.
All **24 normal unsigned examples** were also regenerated and pass Java validation.
Reports: `output/signing-2026-10-28/final/` and
`output/real-examples-signing-disabled-2026-10-28/`. An initial child-signing error
was caught and corrected; its rejected results remain under
`output/signing-2026-10-28/initial-child-signing-diagnostic/`.

The local `dist/emailtopdfa.jar` and `dist/emailtopdfa-gui.jar` are updated;
GitHub assets are unchanged. [Setup and limits](DIGITAL_SIGNATURES.md).
[Signed email](../examples/eml/eml_signed_1s.pdf) and
[signed collection](../examples/eml/eml_signed_1c.pdf) use a test-only certificate;
the private key has been deleted. Production needs the user's certificate/key.
Trusted timestamps, revocation/issuer trust checking and long-term validation
are not implemented. GUI controls compile, but an interactive desktop walkthrough
has not been performed. Large-PST completion remains unresolved separately.

**User signing/accessibility check — 2026-10-28:** The exact GUI output
`output/message_000001_3.pdf` from `in/2.eml`, with Accessibility and signing
enabled, passes standalone Java PDF/A and EA-PDF validation and veraPDF PDF/UA-2
machine validation. Independent `pdfsig` verification confirms a valid signature
covering the complete file; its certificate issuer is unknown to that checker.
The renderer's missing-title/description warnings concern the intermediate PDF;
the creator adds final metadata afterward. They are not final-file UA results.
Reports and checked-file hashes:
`output/signing-2026-10-28/user-2eml-accessible-check/`.
The earlier `_2.pdf` had Accessibility disabled: PDF/A and EA-PDF pass, but a forced
UA-2 check fails identification, destination and table rules. Those results are
separately retained in `output/signing-2026-10-28/user-2eml-check/`.

**Incomplete source recovery — 2026-10-28:** Missing sent dates and missing PST
attachment data now produce clearly marked output instead of stopping the whole
conversion. A warning appears above the affected email, in the finding aid and
spreadsheet, in document metadata, and in the conversion log. Available message
content and attachments are retained. Dates and attachment bytes are never invented.
Unsupported PST attachment methods also receive an explicit warning.

The creator permits a missing date only when its input inventory recorded that
absence. Losing a date or attachment that was available still fails the creation
checks. Both Java and web validation report the creator's incomplete-source
notice. Mandatory rules remain enforced: missing Sent still fails EA-PDF validation;
a missing native payload produces an INCOMPLETE result when no other rule fails.
Creating a PDF is not the same as passing validation.

**Attachment warning clarification:** The first attachment record in PST 06
has no data property in our native PST reader, before EML writing. An independent
LibPST 0.6.76 export also does not recover that attachment. This does not conclusively
distinguish missing original data from a reader limitation. The warning now says
“Attachment data could not be recovered from the original PST file.” The review
PDF has been regenerated with this wording. Evidence is retained in
`output/pst-memory-repair-2026-10-28/`.

**Real-source checks:** All three previously rejected MBOX files (`Drafts.mbox`,
`mbox_01_fmt_720_MBOX_quoted.mbox`, and `msg.mbox`) now convert, including through the
normal CLI. The actual problem message in `pst_06_1999.pst` (descriptor 5068004)
also converts with its remaining attachment preserved. Across applicable profiles,
**18 PDFs were created: all pass PDF/A; 14 fail mandatory sent-date checks and four
are INCOMPLETE for the missing attachment.** The PST check covers this message,
not the whole archive. Original source hashes are unchanged. Logs, reports,
hashes and the reproduction harness are in `output/incomplete-source-2026-10-28/`.

Review examples:

- [Missing sent date](../examples/mbox/mbox_missing_sent_date_1s.pdf).
- [Missing PST attachment](../examples/pst/pst_missing_attachment_1s.pdf).

**Earlier source-recovery build (superseded by the signing build above):** All **24 normal real-email examples** have
been regenerated with four rendering jobs and pass standalone Java PDF/A and
EA-PDF checks, including collection children. Reports are in
`output/real-examples-source-warnings-final-2026-10-28/`. Creator regression results:
**312 tests, zero failures**; Java validator: **168 tests, zero failures, one desktop
skip**; **36 focused Python regressions pass**. The local creator, GUI and validator
JARs in `dist/` are updated. GitHub assets are unchanged. The two warning examples
above are deliberately incomplete and are separate from the 24 passing examples.

**Accessibility:** The Accessibility checkbox enables the PDF/UA-2 declaration
(part 2, revision 2024), including collection finding aids. Empty table cells now
retain their column positions. Both validators previously rejected an absent K
entry in an empty structure element; ISO 32000-2:2020 Table 355 (printed page 725)
makes that entry optional. Both checks and their regression expectations are
corrected; invalid child entries still fail. Human accessibility review remains
outstanding. The earlier **35 saved example PDFs** passed PDF/UA-2 machine checks
(24 normal examples, eight finding aids, one alias, and two incomplete-source
examples). That audit predates the latest unsigned-example rebuild and is not
a validation of its new bytes. Reports: `output/pdfua2-examples-2026-10-28/final-source-warnings/`.

**Memory repair in progress — 2026-10-28:** A controlled comparison using the
same 600 real PST messages (1,070 pages) reduced retained Java heap from
109,406,064 to 62,419,200 bytes — **43% less**. The candidate creator shares a
bounded stream cache, reuses identical font resources, and stores small PDF
dictionaries more compactly. This measures retained heap in an assembly probe,
not peak memory for a complete conversion. Targeted regression checks pass.
The full PST 24 retry with the earlier cache/font repair **failed again with a
4 GiB Java heap exhaustion during rendering**; no final PDF was created and
validation could not run. A new retry uses the compact-dictionary candidate,
four rendering jobs, and the same 4 GiB heap (`pst24-compact/`). No repaired
large-PST conversion/validation result is established yet.
Before cancellation: after about 53 minutes, the compact-dictionary PST 24 run
has finished message rendering and document-part construction and is embedding
source emails/attachments. Its Java heap was about 3.7 GiB of the 4 GiB limit.
PDF saving and validation remain outstanding. Runtime stacks identified repeated
page-tree and filename scans during assembly; targeted lookup fixes have passed a separate 71-message PST check. The running trial is unchanged. There is no reliable completion
percentage yet.
The memory changes are now included in the local signing build in `dist/`;
that does not establish full large-PST success. Measurements and run logs:
`output/pst-memory-repair-2026-10-28/`.

**Large-PST assembly speed fixes — 2026-10-28:** Runtime stacks showed repeated
page-tree traversal and case-folded filename scans. Document-part construction now
indexes pages once; archive attachment planning/embedding uses a filename lookup
set. A 5,000-entry isolated timing probe reduced document-part work from 1.25 to
0.07 seconds and filename checks from 2.93 to 0.01 seconds. These are component
timings, not a whole-PST speed claim. All 30 focused regressions pass. The repaired
creator converted all 71 messages in `in/test.pst` using four rendering jobs;
standalone Java PDF/A and EA-PDF validation both PASS. These speed fixes are now installed in `dist/` and included in the restarted
PST 24 trial described above.
Evidence: `output/pst-memory-repair-2026-10-28/speed-real-pst/` and the
`lookup-baseline.log` / `lookup-repaired.log` files in its parent directory.

**Large PSTs remain unresolved:** The older strict-policy reruns failed on a
missing attachment in PST 06 and a missing sent date in PST 23. PST 20 and PST 22
exhausted their 4 GiB Java heaps. PST 24 first hit its metadata allowance; a corrected-budget
retry then also exhausted its 4 GiB heap during rendering. The older batch has now finished: PST 25 hit its metadata allowance and PST 16
also exhausted its heap. Those runs predate the
new incomplete-source policy and are not evidence of a complete repaired corpus.
See `output/pst-rerun-2026-10-28/` for individual results. Next priorities are the
large-PST memory failures and full reruns with the repaired creator. Outermost-file
signing is now implemented as described above.

**Corpus accounting:** The last-known clean count remains 79 PASS and two
INCOMPLETE out of 91 email inputs. Of the other ten, three missing-date mailboxes
now have usable PDFs that still fail conformance, and seven PST inputs still lack
a full successful conversion/validation result. This combines historical passes
and repair checks; it is not a new full-corpus pass rate. The four non-email items
have been removed by the user. Preserve the original audit and report incomplete
or rejected files explicitly. [Original audit and repairs](improvement/corpus-failures-2026-10-27.md).

**Temporary storage:** The old 48,129-child PST run was stopped for the requested
reruns. Its verified disk spill copies have been removed; active jobs retain their
own scratch files. The previously requested viewer sentence is in the local
extension packages. No viewer work is part of this repair.

Earlier checkpoints follow; their counts and limitations describe those builds.

**Release 1.6.0-beta.1 / viewer 0.3.0-beta.1 — 2026-10-27:** The release includes
the accumulated creator and validator fixes, the CIDSet revert, and the browser
viewer. Opening and scrolling are faster; retained page canvases share a
32-million-pixel budget. The interface has clearer navigation, less indentation,
visible build information and extension icons. Versions are bumped together;
JARs, browser ZIPs, fresh EML/MSG examples, notices and checksums are release assets.

**Verification:** Clean builds run 302 creator tests (23 private-input checks
skipped) and 164 validator tests (one desktop check skipped). A separate local
run passes all 24 real-input checks, and the desktop check passes on the graphical
desktop. Python runs 1,188 tests with 40 optional skips and no failures. Chromium
and Firefox pass the viewer checks. Fresh conversions pass Java validation for
all six EML and MSG profiles, plus PST and MBOX archives. Original source hashes
are unchanged. Raw private inputs are excluded from publication. See
[release evidence](improvement/release-1.6.0-beta.1/verification.json) and
[viewer changes and limits](../browser-viewer/docs/release-0.3-results.md).

Entries below describe earlier checkpoints and may refer to superseded builds or scope.

**CIDSet revert — 2026-10-27:** Restored PDFBox's normal save-time font subsetting.
Removed forced CIDSet deletion, the custom PDFBox queue helper, and the mandatory
absence check/tests. The [linked clarification](https://github.com/veraPDF/veraPDF-library/issues/1647#issuecomment-6457552448)
says the forthcoming ISO 19005-4 revision changes “shall” to “should”. Existing
PDFs and historical verification receipts are unchanged; their absence checks are
not current acceptance requirements. No validator rules or veraPDF checks were disabled. Maven packaging and 10 targeted creator tests pass;
[evidence](improvement/cidset-revert-2026-10-27/verification.json). Existing dist JARs were not replaced.

**GitHub source update — 2026-10-26:** Clean exported source builds pass **277 creator tests** and **154 standalone-validator tests (one optional desktop smoke test skipped)**. Python runs **1,182 tests: 1,142 pass and 40 optional checks skip** against the newer user-approved rule wording. The JSON syntax, bundled rule checksum and summaries, review fingerprint and author DOCX are synchronized. The author document retains 213 entries in exact JSON order, verified source quotations and all 19 original tables. Seven superseded root drafts/snapshots are preserved in the verified ignored local archive. Source publication targets `beta/1.5.0-beta.1`; no tag or GitHub release is part of this update. [Cleanup and checks](improvement/repo-cleanup-20261026/evidence.json), [author review](../EA_PDF_Rule_Review_for_Specification_Authors.docx).

**Fresh creator accuracy review completed — 2026-10-25:** Generated and compared **100 ordinary emails: 4 EML, 6 MSG, 78 from two PST mailboxes and 12 live Gmail emails**. Fixed a real subject-decoding error that left MIME encoding text in the visible subject and XMP. Both creator apps now decode the original header once; Gmail filenames use the corrected subject. Original email bytes are preserved.

**Validator fixes and delivery:** Java now compares the corrected subject properly and counts decoded email-attachment bytes rather than base64 wire bytes. Web no longer invents a mailbox from a whole quoted display-name token. All three `dist/` JARs are updated. **277 creator and 154 Java validator checks pass.** Python runs **1,182 tests: 1,142 pass and 40 optional checks skip**. Both engines agree on **198,942 rule outcomes across 934 reports**. All fresh ordinary outputs pass PDF/A with no EA-PDF failures; two MSGs retain known incomplete source checks. Gmail parent/all twelve children and all six unchanged public samples PASS. The public old subject control FAILs the subject rule; its corrected counterpart fully PASSes. [Review and numerical evidence](improvement/creator-fidelity-review/README.md). Example: `output/creator-review/fidelity/Decoded subject.pdf`.

**Content preservation:** All 22 directly retained originals are exact; 78 reconstructed PST exports retain their selected native body text. Checked 84 by-value asset occurrences, five stored pictures and 440 readable address/date fields. Across 210 owned body pages, 99 emails render identically; only the repaired Gmail subject changes its email's pages. Private Gmail details are excluded from project evidence; only numerical summaries are published. Gmail access was read-only and credential originals are unchanged.

**Current beta limits:** Missing-header/unsupported MSG comparisons, very large archive limits and the three specification ambiguities remain. One native PST timestamp loses fractional seconds during reconstructed RFC822 export; that precision limit is recorded, not claimed repaired. Remote resources absent from the source cannot be proved preserved. Distribution remains deferred; reader work and new features remain out of scope.

**Native Outlook checks finished — 2026-10-25:** Both validators now compare readable native sender/recipient mailbox occurrences and ordinary by-value attachments without rebuilding missing headers. Native field checks cover **13 supplied MSG files**, up from eight, including **five with attachments**. An independent audit finds no mismatch in **87 readable mailbox occurrences across all 31 current outputs**. Unknown recipient addresses and original header order remain unavailable; no creator address defect was found.

**Delivery:** Updated `dist/eapdf-validator.jar`. **149 Java checks pass**, including the actual desktop; Python runs **1,180 tests: 1,140 pass and 40 optional checks skip**. Java and web agree on all **193,191 rule outcomes across 907 reports**, including upstream statuses and diagnostics. All six public samples remain PASS. The 31 current MSG outputs retain twelve full PASS and nineteen source-comparison INCOMPLETE results, with zero EA-PDF failures. Four old negative PDFs now also fail proven wrong addresses/nonexistent attachments; no overall result changes. The exact final single JAR passes through its GUI and cleans its worker files. Creator code/JARs, rulebook, PDF bytes and inspection limits are unchanged. [Changes and evidence](improvement/validator-msg-addresses/README.md).

**Ordinary-input beta checkpoint:** No further confirmed ordinary creator defect was found in this review. Full missing-header comparisons, unsupported/nested/inline MSG inventories and very large archive limits remain explicit limitations. The three specification ambiguities retain their current project policies; they are not declared resolved. Corrected the older review note that allowed an absent attachment Bag, which conflicts with the approved current rulebook. Distribution remains deferred; reader work and new features remain out of scope.

**All six public sample profiles now PASS — 2026-10-25:** Repaired the isolated archive's attachment comparisons in both validators. Each empty placeholder was being compared with attachments from every email, mixing unrelated media types. The matcher now uses its existing FileAttachment annotation, page DPart and unique email identity to limit the candidates. It uses no filename cleanup, size, checksum or media-type value to establish that owner. Unknown, conflicting or additional unassigned annotation contexts retain the global candidate set and remain unavailable when ambiguous.

**Delivery and checks:** Updated `dist/eapdf-validator.jar`. Creator JARs retain the recipient-order and seven sent-date fixes below; this repair changes no creator code or PDF bytes. **140 Java validator checks pass.** Python runs **1,175 tests: 1,135 pass and 40 optional checks skip**. The unchanged creator retains its complete **274-pass** result. Both engines agree on **193,191 rule outcomes across 907 reports**. All six samples received fresh full web checks and PASS PDF/A, EA-PDF and selected ISO checks. The exact standalone JAR also PASSes `examples/sample_1mi.pdf` through its GUI with only the JAR present. Incorrect sizes/checksums still fail; missing page ownership and extra unassigned references remain incomplete. Wrong-owner controls still FAIL. Rulebook, samples, protected PDFs and inspection limits are unchanged. [Changes and evidence](improvement/validator-isolated-attachments/README.md).

**Remaining limits recorded at the preceding checkpoint:** Full comparisons for native-only/unsupported MSG formats; the rulebook's three open namespace, creation-date and collection-size interpretations; default validation limits for very large archives. No further ordinary-input creator blocker is recorded by the current review. Reader work and new features remain out of scope. Distribution stays deferred.

**Wrong Outlook sent dates repaired — 2026-10-25:** Without stored email headers, the MSG parser could use a file modification date as the sent date. The creator now uses the actual stored submission timestamp, including its fractional seconds, while preserving a supplied transport Date header's precedence. This corrects **seven supplied MSG files**, including dates that were years wrong. Bodies, original/attachment bytes, subjects, names, mailboxes and recipient order are unchanged.

**Native property checks:** Both validators now compare known native Subject, Message-ID, size, attachment count and submission-time facts for one represented email with one MSG original and no attachment storages. This adds partial checks for **eight ordinary inputs**. Missing headers are not reconstructed and full header/body/address comparisons remain INCOMPLETE. Verified zero padding is ignored; truncated nonzero property records remain unavailable. Four old PDF controls now correctly fail known native metadata contradictions; their bytes are unchanged. Three change from INCOMPLETE to FAIL, and one already failed.

**Verification and delivery:** All three `dist/` JARs updated. **274 creator and 136 Java validator checks pass.** Python runs **1,171 tests: 1,131 pass and 40 optional checks skip**. Both engines agree on **193,191 rule outcomes across 907 reports**: the original 875 reports plus 31 current MSG outputs and the previous bad-date output. All 31 current MSG outputs pass PDF/A with zero EA-PDF failures: 12 full PASS and 19 retain source-comparison INCOMPLETE. The old bad-date PDF now FAILs the Sent comparison in both engines. Fresh web checks cover all 32 real cases; corpus upstream reports are reused only for identical bytes, while Java upstream checks are fresh. The exact single JAR also passes through its GUI. Rulebook, existing samples and protected PDFs are unchanged. [Changes and evidence](improvement/validator-msg-native/README.md). Review: `output/creator-review/msg-native/Correct Outlook sent date.pdf`.

**Next:** Review the isolated sample's ambiguous empty attachment placeholders. Remaining MSG format limits, the three specification interpretation questions and very large archive limits remain explicit. Distribution remains deferred.

**MSG comparisons and recipient order repaired — 2026-10-25:** Both validators now compare stored Outlook transport headers and ordinary native file attachments against PDF metadata. The tested scope covers **12 of 31 supplied ordinary MSG inputs**. MSG files without stored transport headers, nested MSG attachments and inline-resource inventories remain unavailable. Missing native MIME-type values are kept absent; filename, size and checksum comparisons still run, and XMP Content-Type remains required.

**Creator fixes found by these checks:** Two real MSG files stored recipients in a different order from their original headers. The creator now follows that header order when both lists contain exactly the same recipient occurrences, retaining native names and mailbox values. Both corrected outputs **PASS** full Java/web validation; both previous outputs correctly **FAIL**. A web parsing error for a valid folded quoted display name is also repaired. Java now recognizes the same existing Outlook redirect links as web validation; no PDF links are added.

**Verification and delivery:** All three `dist/` JARs updated. **273 creator and 130 Java validator checks pass.** Python runs **1,169 tests: 1,129 pass and 40 optional checks skip**. Both engines agree on **189,357 rule outcomes across 889 reports**: the existing 875 corpus reports plus 14 real MSG cases. Java corpus upstream checks use the exact delivered JAR; web corpus results reuse verified upstream reports for identical PDF bytes. All 14 MSG cases received fresh full web checks. The standalone JAR passes through its actual GUI from a folder containing only the JAR. Across 31 MSG inputs, native bodies, source/attachment bytes, subjects, sent dates, names and mailbox values are unchanged. Rulebook, existing samples and protected PDFs are unchanged. [Changes and evidence](improvement/validator-msg-source/README.md). Review: `output/creator-review/msg-validation/Recipient order.pdf`.

**Next:** Continue source comparisons for MSG files whose email fields are stored only as Outlook properties. The isolated attachment-evidence limit, three specification interpretation questions and very large archive limits also remain. Distribution remains deferred.

**Archive ownership checks repaired — 2026-10-25:** Both validators now reuse the page-ownership evidence for a content reference when checking its enclosing email, group and content set. Every structure path and cycle is still checked. The existing work and depth limits are unchanged. The ordinary multi-email sample `examples/sample_1m.pdf` now **PASSes**. The isolated sample `sample_1mi.pdf` completes all ownership checks; its only remaining INCOMPLETE reason is unavailable attachment-byte comparison evidence.

**Verification and delivery:** Updated `dist/eapdf-validator.jar`. **119 Java checks pass**, including the desktop. Python runs **1,163 tests: 1,123 pass and 40 optional checks skip**. Both engines agree on **186,375 rule outcomes and 875 overall results** across 808 PDFs and 67 embedded reports. Java upstream checks were rerun; the web corpus reused verified upstream reports for identical PDF bytes. Both affected samples also received fresh full web validation. The exact JAR passes through its GUI with only the JAR in its folder. No previously failing file becomes PASS. Creator code, creator JARs, rulebook and protected PDFs are unchanged. [Changes and evidence](improvement/validator-archive-ownership/README.md).

**Rule alignment completed — 2026-10-25:** Creation and both validators now require the document-level XMP attachment container in every profile, including when empty. Missing or wrongly typed containers fail; an empty Bag remains permitted and complete original-attachment reporting remains a recommendation. Java rule 200 now accepts nonempty original Content-Type text with source errors while still rejecting wrong XMP types. Web checksum explanations permit either hexadecimal case.

**Rule descriptions:** The active JSON and bundled Java specification text now explicitly identify the unresolved namespace exemption, creation-date interpretation and Table 5/6 size conflict. Existing namespace/date/size policy is retained. The 213-rule inventory, ordering and compact format remain. The old2 file is unchanged.

**Verification and delivery:** Rebuilt `dist/emailtopdfa.jar`, `dist/emailtopdfa-gui.jar` and `dist/eapdf-validator.jar`. **271 creator checks and 117 Java validator checks pass.** The Python suite runs **1,162 tests: 1,122 pass and 40 optional checks skip**. All **186,375 EA-PDF rule outcomes and 875 overall results agree** across 808 PDFs and 67 embedded reports. All EA-PDF checks were rerun; Java upstream checks were rerun, while the web corpus comparison reused previously verified upstream results for identical PDF bytes. Six samples also received fresh full web validation: all pass PDF/A and selected ISO checks with zero EA-PDF failures; 1m/1mi retain their existing inspection INCOMPLETE results. The exact standalone JAR passes from a folder containing only the JAR. [Changes, checks and evidence](improvement/validator-rule-alignment/README.md).

**Clearer Java findings — 2026-10-24:** Updated `dist/eapdf-validator.jar`. The repeated “Email metadata and source comparisons…” sentence is replaced with a specific explanation for each email rule in an outer collection. Generic “condition … absent/not present” messages now identify the profile, field or object involved.

**Rule 202:** Checked against section 9.8.4, Table 19, printed page 90 (PDF page 97). The optional attachment XMP CheckSum uses 32 ASCII hexadecimal characters. Its format check now reports the actual result and checksum value, and says explicitly that attachment-byte agreement is checked separately. The validation conditions are unchanged. All **105 existing checks pass**; a focused comparison of **16 PDFs and 2 embedded reports** preserves every rule outcome and covers all 67 previously generic rule/message combinations. The copied single JAR also passes through its actual GUI. [Details and screenshots](improvement/validator-finding-reasons/README.md).

**Java/web validator parity achieved — 2026-10-24:** The preceding parity build matches the web validator on **all 213 rule outcomes across 808 PDFs and 67 embedded reports: 186,375 of 186,375 comparisons**. All 875 overall results, PDF/A/selected ISO results, failing rule identifiers, object locations and error text match. Zero missed failures, extra failures, inspection errors, execution errors or child-inventory differences. No cases or rule outcomes were waived. The subsequent finding-text update above preserves the validation conditions.

**Build and GUI:** All **105 Java checks pass**, including the actual desktop; zero failures/errors/skips. The exact copied JAR also validates through its GUI with only the JAR in its working folder and Python/Docker unavailable on PATH. Specification excerpts load from the JAR and worker files are cleaned. Java 21 is the only runtime installation needed. The 16-point text, 2,000 × 1,100 window, four columns, grouped rows, keyword filter, Esc clearing and side-by-side Finding/Specification panes remain.

**Results and limits:** Removed the forced incomplete-port result. Files can now PASS when required checks complete; genuine violations FAIL and unavailable required comparisons remain INCOMPLETE. Existing web limits remain for unsupported original formats, ambiguous owners, isolated attachment evidence and large inspections. Finding wording and occurrence counts can differ because observations are aggregated differently. The creator, web checking code, rulebook and protected Agenda/BFO PDFs are unchanged. [Full evidence and screenshot](improvement/validator-desktop-parity/README.md).

The entries below describe earlier builds and their historical inventories/results.

**16-point text and explicit findings — 2026-10-24:** Current `dist/eapdf-validator.jar` uses **16-point text**. Findings now name concrete requirements: XMP version **1**, revision **2024**, allowed conformance values, namespace bindings, Producer text, date format, navigation values, schema subtypes, DPart names, role mappings and user-property types/tags. Observed scalar values remain in the details. Existing validation predicates are preserved; distinct requirements remain separate groups.

**Checks:** All **80 Java checks pass**, including the desktop and explicit required-value messages on invalid metadata. The 2,000 × 1,100 starting window, Esc filter clearing and four-column table remain. Rulebook unchanged; Java/web parity remains unfinished. [Screenshot and evidence](improvement/validator-finding-text/README.md).

**GUI readability update — 2026-10-24:** Current `dist/eapdf-validator.jar` uses text of at least **18 points** and starts at **2,000 × 1,100 pixels**. **Esc** clears the keyword filter. The **Count column is removed**; grouped rows and occurrence details remain. All **79 Java checks pass**, including the actual desktop. The validation engine and rulebook are byte-identical to the previous delivery.

**`validation_scope` is retained:** Python uses it to reject upstream-only rule entries. Audit tools use it to identify operational diagnostics, check ordering and review ownership. It does not select or execute veraPDF checks. Full Java/web parity remains unfinished. [Screenshot and evidence](improvement/validator-gui-display/README.md).

**Specification-ordered rules and grouped GUI — 2026-10-24:** Current delivery: `dist/eapdf-validator.jar`. The authoritative local rulebook now has **213 active rules**, ordered by their first mapped specification passage and renumbered consecutively. Removed 11 fully delegated PDF/A rules and moved the ISO upstream control/settings out of the rulebook. Both upstream passes still run, and mixed EA-PDF assertions remain. The EA-PDF conformance declaration stays locally checked. Operational diagnostics follow the specification rules. Retired rules, old numbers and review evidence remain separate from runtime data.

**Grouping:** The exact collection shown in the screenshot goes from **1,679 findings to 230 groups**. Its **73 embedded-stream findings are one counted row**. All occurrence details remain accessible; different results/messages stay separate. Saved JSON keeps every finding. Finding/Specification remain side by side and the keyword filter still works.

**Checks:** **79 Java checks pass**, including the actual desktop. The Python suite runs **1,161 tests**, zero failures/errors and 40 optional skips. The final JAR agrees with the previous delivery on **808 PDFs and 67 embedded reports** after the requested row/number/order changes. The copied JAR runs alone. Source mappings account for all 301 reviewed obligations, including every mandatory file assertion; this does not certify implementation completeness. **Full Java/web parity remains unfinished; overall PASS stays disabled.** Creator code, selected ISO profile and protected Agenda/BFO PDFs are unchanged. [Details, screenshot and evidence](improvement/validator-rule-ordering/README.md).

**Runtime rule cleanup — 2026-10-24:** Current delivery: `dist/eapdf-validator.jar`. The latest GUI build passes all **76 checks**, including the real desktop. `validator_rule_text.json` now contains only runtime/build data: **181,823 bytes**, down from **1,524,044 bytes** (88% smaller), with all 225 rules retained. Removed review data is preserved separately in `docs/improvement/specification-audit/validator-rule-review.json`; audit tools and review tests use a shared loader. The review file is not bundled in the JAR. Existing user edits are preserved.

**Cleanup checks:** The exact final Java JAR produces unchanged report content for **808 PDFs and 67 embedded reports**, including PDF/A/ISO results. Python custom-check reports also agree for all **808 PDFs**, and ten profile/version delegation scenarios agree. Only finding order and random child-directory names are normalized. The copied JAR runs alone with Python/Docker unavailable, PDF/A and ISO PASS, and zero EA-PDF failures on the ordinary sample. Two web ownership tests fail with both the original and reduced JSON because the supplied original has no reviewed profile references; those pre-existing failures are retained. [Cleanup details and evidence](improvement/validator-rule-cleanup/README.md).

**Java parity remains unfinished:** This cleanup is not evidence of full Java/web parity. Overall PASS remains disabled. The creator, Python checking logic and selected ISO profile are unchanged. Earlier D03/D04 entries below describe their own builds and comparison results.

**D04 in progress — full Java/web parity requested:** The GUI now has canonical rule numbers, Finding and Specification side by side, and a literal keyword filter for rule names. Desktop checks pass. Attachment annotation failures now agree with the web engine; source-property preservation, invalid dates, folder collisions and corrupt-PDF reporting are repaired. The latest completed comparison is 32 missed and 53 extra rule/file failures; the next source-binding repair is being compared. PDF/A and selected ISO results/diagnostics now agree in all 875 parent/child comparisons. Work continues through PASS/WARN/N/A differences as well. Overall PASS stays disabled until full parity is established. That D04 comparison was still in progress; the current `dist/` JAR now has the GUI and JSON cleanup described above.

**D03 — Java validator repairs:** Added source-header and date comparisons, original/stored attachment-byte checks, better email/source matching, mailbox checks, DPart checks and marked-content file discovery. Across the same 808 PDFs and 67 embedded-file reports, missed V85 failures fall from **187 to 55**; Java-only failures fall from **416 to 55**. These are rule/file comparisons, not counts of document defects. Both malformed-header inspection errors are fixed. [Repairs and full results](improvement/validator-desktop-03/README.md).

**D03 Java delivery (historical):** Its `dist/eapdf-validator.jar` contained the repaired engine. **66 checks pass**, including the desktop, cancellation and cleanup. The copied JAR runs with no supporting files and Python/Docker unavailable on PATH. All six standard EA-PDF samples have zero Java EA-PDF failures and pass PDF/A/selected ISO. **The Java port remains incomplete; overall PASS stays disabled.** [Hashes and evidence](improvement/validator-desktop-03/evidence.json).

**Next Java work, in order:** Attachment annotation ownership; raw-header/date preservation and source binding; content-set/structure ownership; collection filename and remaining XMP checks. The remaining 55 missed and 55 extra failures are listed by rule and file in the [comparison](improvement/validator-desktop-03/RESULTS.md). The creator, frozen web engine and protected Agenda/BFO PDFs are unchanged. No push or release was performed.

**D02 — complete Java/V85 corpus comparison and specification tab:** Compared all 808 saved PDFs and 67 matched embedded-file reports across all 225 rule codes. Three repairs reduce V85-only FAIL disagreements from 423 to 187. Java-only FAIL disagreements remain 416. These are rule/file comparisons, not counts of proven document defects. **The Java validator remains incomplete; overall PASS stays disabled.** [Full results and priorities](improvement/validator-desktop-02/RESULTS.md).

**Java gaps at D02:** Missing or wrongly typed email/source/attachment metadata; matching records to the correct email; incomplete email/page structure and tag ownership checks; missed associated-file entries; two malformed email-header cases that stop further inspection. Some available checks also report failures where V85 gives PASS or an unavailable result. Resolve these against the specification before treating the Java app as a replacement.

**Web validator:** The web app runs the unchanged Python V85 engine, which already contains the checks missing from the Java translation. Its existing MSG, isolated-attachment and large-archive limits remain.

**D02 GUI and checks:** Select a result and open **Specification** for actual specification page text, section/table references, printed page and PDF page. All 225 codes have reviewed references; summaries are clearly labeled as paraphrases. 20 checks pass, including the real desktop. The exact single JAR runs from an empty folder with Python and Docker unavailable on PATH. `dist/eapdf-validator.jar` needs Java 21 only. PDF/A results agree in 873 of 875 parent/child comparisons; two corrupt children use different failure/error labels and are rejected by both engines. Selected ISO results and upstream diagnostic locations/text agree in all 875 comparisons. [Evidence](improvement/validator-desktop-02/evidence.json).

The creator, frozen V85 files and protected Agenda/BFO PDFs are unchanged. No GitHub push or release was performed.

**D01 — standalone Java validator:** A new desktop app is in `validator-desktop/`; the runnable file is `dist/eapdf-validator.jar`. Java 21 or later is the only runtime installation needed. The PDF/A libraries, EA-PDF rule descriptions and selected ISO profile are included in the JAR. No Python, Docker, server or separate configuration files are needed. [Build and run instructions](../validator-desktop/README.md).

**D01 checks and limits:** 15 checks pass, including all five allowed PDF/A flavours with positive/negative controls, real desktop validation, stopping a running collection check and temporary-file cleanup. The copied JAR checks all six public samples from an empty folder with Python and Docker unavailable on PATH: all pass PDF/A/selected ISO with zero EA-PDF failures. **Overall results deliberately remain INCOMPLETE:** the Java translation still needs full comparison with frozen V85, especially source binding, decoding, structure coverage and applicability decisions. Listed rule codes do not prove complete assertion parity. Do not treat this initial Java port as a finished replacement for V85. [Evidence](improvement/validator-desktop-01/evidence.json).

The creator and frozen Python validator are unchanged. No GitHub push or release was performed for this app.

**C137 — GitHub preparation, version1.5.0-beta.1:** Version bumped, both local apps rebuilt and all six examples refreshed. A clean export of the staged source passes265 Java checks and1,121 Python checks (40 optional skips). All six examples pass PDF/A/selected ISO; isolated1mi retains its known comparison INCOMPLETE. Current creator repairs and frozen validator behavior are unchanged.

**Cleanup:** About2GB saved after keeping a verified672MB local backup. Removed only10,385 ignored historical report/candidate files; all Git-selected files, source, active fixtures and protected comparison PDFs remain. Private Gmail output, generated output, IDE files and bulk evidence are excluded from Git. The initial broad deletion was rejected by automatic review; the later ignored-only cleanup was accepted after backup verification and clean-checkout tests. See [cleanup inventory](improvement/cleanup-backup-manifest.json).

**GitHub:** The source update is prepared locally for the private `thinkbox-digital/eapdf` repository on `beta/1.5.0-beta.1`. No remote push or release has been performed. [Build and preparation evidence](improvement/creator-poc-day5/c137/result.json). Existing MSG, isolated-attachment and large-archive validation limits remain.

**C136 — viable creator beta checkpoint reached:** Normal email creation on the tested Linux setup is ready for beta use within the limits below. The latest user instruction permits stopping here. The current repairs are C134; no further ordinary-input creator defect was found in this review. Both apps rebuilt and all six examples refreshed from that exact build. Complete current Java suite: **265 passes, zero failures/errors/skips**.

**Output checks:** All six samples pass PDF/A and the selected ISO checks; five PASS the full available checks, while isolated PST 1mi keeps its known attachment comparison INCOMPLETE. The real31-message Outlook batch preserves all originals and24 attachments, with correct footers/no unwanted icons. Native body-word checks find no missing matches in31 MSG and71 PST messages. C128's read-only20-message live Gmail proof and C132's complete1,433-message archive proof remain supporting evidence, not new C136 live/large runs.

**Limits retained:** MSG source comparisons and the isolated PST attachment comparison remain INCOMPLETE. Large-archive full validation needs extra memory and inspection room; the default512MB upstream limit still fails for the tested1,433-message archive. This is a beta code checkpoint for ordinary inputs, not complete standards/accessibility certification or wider-platform acceptance. Distribution stays deferred. [Current evidence](improvement/creator-poc-day5/c136/result.json). Start with [the current sample](../examples/eml/eml_sample_1s.pdf); all six are in `examples/`.

**C135 ordinary creator check; code remains C134:** All 31 supplied Outlook emails convert successfully at 512 MB. Every original and all 24 native attachments are exact. Every output passes PDF/A and selected ISO checks with zero mandatory failures; all retain the existing MSG comparison/applicability INCOMPLETE. No unwanted note/first-page attachment icons remain, and every footer has the correct last-page placement and geometry. Body-word checks find no missing matches in these 31 files or the 71-message PST sample. These are content checks, not visual/accessibility certification. [Results](improvement/creator-poc-day5/c135/result.json). No new features or validator changes; distribution remains deferred.

**C134 stored picture body preserved; work continues:** A stored Outlook picture could still be discarded when its HTML used a matching web address. The readable-body check now recognizes that the picture is already available inside the message. Unrelated pictures still allow the supplied text fallback. Both apps updated.

**C134 checks:** Twelve focused Java checks pass. A normal valid controlled MSG now shows its native picture through the public CLI, with exact original message/JPEG/attachment and unchanged attachment-page pixels. The false unavailable-image warning is gone. PDF/A and selected ISO PASS; the same retained-MSG source-comparison/applicability INCOMPLETE limits remain. All 31 supplied MSG and 20 cached Gmail content/asset fingerprints stay unchanged. Six C132 samples unchanged; last full suite C132: 262 passes. Frozen V85 and default large-file validation memory limit remain. [Results](improvement/creator-poc-day5/c134/result.json). Review: `output/creator-review/c134/Stored picture body.pdf`.

**C132–C133 creator fixes; work continues:** The 1,433-email archive now shares its footer resources. Size falls from 70.5 MB to 40.4 MB (42.67%), with the same 512 MB creator heap. Every original email and all 2,106 page appearances are preserved, apart from the finding aid's expected run timestamp. A supplied Outlook email also regains its missing stored picture. Both apps updated; all six standard samples refreshed.

**Checks and limits:** C132 full Java suite: 262 passes. C133 focused suite: 12 passes; all 31 MSG content/attachment fingerprints unchanged. The restored picture uses the exact native JPEG bytes, with the original MSG, both attachments, all text and the attachment-page pixels unchanged. PDF/A and selected ISO pass; retained-MSG comparison stays INCOMPLETE. The complete large archive PASSes frozen checks with explicitly expanded trusted-input limits (2 GB external heap; 10 million objects/depth 8,192). Default 512 MB veraPDF still runs out of memory. Five standard samples PASS; isolated 1mi retains its known comparison INCOMPLETE, with all six passing PDF/A/selected ISO. No validator changes. [Archive results](improvement/creator-poc-day5/c132/result.json), [Outlook results](improvement/creator-poc-day5/c133/result.json). Review: `output/creator-review/c132/` and `output/creator-review/c133/Stored Outlook picture.pdf`.

**C130 missing readable body restored; work continues:** Invisible spacing could make an image-only HTML body look readable to the creator. When its images needed the web, the PDF could omit a useful supplied text body. The existing fallback now ignores invisible formatting when checking for words. Readable HTML and embedded images keep their priority. Both apps updated.

**C130 checks:** 38 focused Java checks pass, including EML/MSG originals and native image priority. The corrected payment example now shows the missing amount and reference, retains exact original bytes and PASSes existing EA-PDF/PDF-A/selected ISO checks. The reproduction is a normal valid controlled email; existing 31 MSG and 20 checked Gmail record/asset fingerprints remain unchanged. Six C124 samples unchanged; last full suite C124, 252 passes. Frozen V85 and prior limits retained. [Results](improvement/creator-poc-day5/c130/result.json). Review: `output/creator-review/c130/Payment reminder.pdf`.

**C129 valid email characters preserved; work continues:** The cleanup code could turn valid HTML apostrophes and currency symbols into fragments such as `39;` and `A3;120`. It now preserves those character references. The text fallback decodes HTML once, while literal code examples in plain emails stay literal. Both apps updated, retaining the C128 Gmail PDF/A repairs.

**C129 checks:** 35 focused Java checks pass. A normal valid HTML example now displays the intended apostrophes, pound sign, ampersand and reference, with exact original bytes and PASS EA-PDF/PDF-A/selected ISO. The same structural checks also pass the old incorrect display, so this repair adds needed content-fidelity evidence. All 78 PST record/export fingerprints, 31 MSG content/attachment multisets and 20 checked Gmail record fingerprints remain unchanged. No supplied raw-HTML case of the padded form is claimed. Six C124 samples unchanged; last full suite remains C124, 252 passes. Frozen V85 and known limits retained. [Results](improvement/creator-poc-day5/c129/result.json). Review: `output/creator-review/c129/Project approval.pdf`.

**C128 real Gmail PDF/A failures fixed; work continues:** Three emails in a real 20-email Gmail export produced invalid PDF text maps because of an invisible word-joining character. Rendering now uses its permitted equivalent; original emails and stored metadata stay unchanged. The creator also avoids unreadable-body warnings for alternatives it did not use. Genuinely unsupported calendar sections still warn. Both apps updated.

**C128 checks:** 33 focused Java checks pass. The complete 20-email collection, including every child PDF, now PASSes existing EA-PDF/PDF-A/selected ISO checks. All 20 originals, seven image attachments and 23 folder placements are exact; every child's page images are unchanged. A shareable invitation improves from PDF/A FAIL to full PASS, with identical page images and exact source. Gmail access was read-only; private data remains private and temporary credentials were removed. Six C124 samples remain unchanged. Last full suite: C124, 252 passes. Frozen V85 and prior comparison/resource limits remain. [Results](improvement/creator-poc-day5/c128/result.json). Public review: `output/creator-review/c128/Meeting invitation.pdf`.

**C127 native PST thread reference retained; work continues:** One supplied PST reply stored its thread reference outside the transport headers. That value was missing from the reconstructed source email. The creator now retains it through the existing source export. Both apps updated, including the C126 recipient and Outlook reply fixes below.

**C127 checks:** 32 focused Java checks pass. The actual corrected reply and seven-email collection PASS existing EA-PDF/PDF-A/selected ISO checks. All 78 PST messages retain the same headers, bodies and native assets; 77 exported sources are byte-identical and one gains only its exact missing References line. Reply page pixels/text are unchanged. All native reply fields in the 79-message check, including the attached email, are accounted for. Six C124 samples remain unchanged. Last full suite: C124, 252 passes. Frozen V85 and known comparison/resource limits remain. [Results](improvement/creator-poc-day5/c127/result.json). Review files: `output/creator-review/c127/`.

**C126 creator header fixes; work continues:** PST recipient groups could become one invalid address, leaving To and Cc out of the PDF metadata. The normal PST path now uses the existing group parser. Three supplied Outlook replies also lost their native reply fields when transport headers were absent; those stored fields now reach the existing metadata and email reconstruction paths. Both apps updated.

**C126 checks:** 40 focused Java checks pass. The reproduced PST file improves from two mandatory failures to PASS EA-PDF/PDF-A/selected ISO. Three real MSG reply PDFs recover their reply metadata with exact originals and identical page pixels/text; PDF/A and selected ISO pass, with the existing MSG comparison INCOMPLETE retained. All 31 MSG and 78 PST content/source/attachment fingerprints stay unchanged. Six C124 samples remain unchanged. Last full suite: C124, 252 passes. Frozen V85 and large-file validation limits unchanged. [Results](improvement/creator-poc-day5/c126/result.json). Review files: `output/creator-review/c126/`.

**C124 dropped PST delivery headers preserved; work continues:** Reconstructed PST emails retained core fields but discarded stored delivery and other headers. The supplied PST loses37Received lines from21emails. Retained source sets now also preserve the exact stored header block as UTF-8 text, including headers from attached emails. Existing source-file support is reused. Reconstructed EML bytes, displayed bodies and email attachment counts remain unchanged. Isolated profiles retain their existing omission policy. Both apps and all six samples refreshed.

**C124 checks:** Complete Java suite: 252 passes. All 78 real PST record/content/source/attachment fingerprints unchanged; all 77 supplied header blocks exact, including87Received lines. Actual71-message retained archive preserves71 header files; a real7-message collection and its children preserve6. Collection and all six samples pass PDF/A/selected ISO with zero mandatory failures; five samples overall PASS, isolated1mi keeps its known comparison INCOMPLETE. Corner icons remain absent on the reported email pages. Frozen V85 and C122's large-file validator limit unchanged. [Results](improvement/creator-poc-day5/c124/result.json). Collection example: `output/creator-review/c124/Native PST collection.pdf`. Continue substantial creator preservation work until stopped.

**C122 mailbox creation memory failure repaired; work continues:** The real 1,433-message mailbox rendered but ran out of memory during final checks. The writer still held the complete open archive and its object cache while reopening the saved PDF. Archive writing now returns and releases those references before the same final checks run. Both apps include the repair; no heap increase or skipped check.

**C122 checks:** 39 focused archive/policy/preservation/naming Java checks pass. The full mailbox now finishes in 177 seconds at the same 512 MB heap: 2,106 pages and all 1,433 original exported emails exact. Normal 20-message retained/isolated archives PASS existing EA-PDF/PDF-A/selected ISO checks. The separate full-file validator could not finish this large file: its veraPDF run exhausted 512 MB and its object traversal hit an existing bound. Its actual FAIL/uncompleted result is retained; whole-file conformance is not claimed. Frozen V85 unchanged; C120 samples unchanged; last full Java suite C117: 250 passes. [Results and runtime evidence](improvement/creator-poc-day5/c122/result.json). Smaller review files: `output/creator-review/c122/`. Continue core creation work until stopped.

**C120–C121 date and output preservation fixes; work continues:** Valid dates ending in comments such as `(PST)` no longer generate false invalid-date warnings or unnecessary Raw-Sent metadata. All 1,433 supplied mailbox dates are recognized, with identical source/header/body/instant/asset hashes. Monthly exports now use the existing numbered-filename safeguard instead of replacing earlier monthly PDFs. Both apps include both fixes and retain the corner-icon correction.

**C120–C121 checks:** 32 focused date/preservation Java checks and two existing archive-naming checks pass. Actual date example retains identical page pixels/text/source; Raw-Sent assessment improves from WARN to PASS. Both repeated monthly single-email and multi-email runs preserve earlier files; all four PDFs PASS EA-PDF/PDF-A/selected ISO with exact originals. All six standard samples refreshed with C120; known isolated PST `1mi` comparison INCOMPLETE remains. Frozen V85 unchanged; last full Java suite remains C117: 250 passes. [Date results](improvement/creator-poc-day5/c120/result.json), [monthly results](improvement/creator-poc-day5/c121/result.json). Reviews: `output/creator-review/c120/` and `c121/`. Continue substantial creator work until stopped.

**C118 unwanted corner icon removed:** The creator added a duplicate native attachment marker to the top-right corner of each email. That duplicate is removed. Each attachment retains one visible native record on the email's attachment continuation, within the same body rendering as required by EA-PDF. Filename lists remain plain text. Both apps and all six standard samples are refreshed.

**C118 checks:** 40 focused Java checks pass. All six samples pass PDF/A and selected ISO with no mandatory failures; five overall PASS, with the known isolated PST `1mi` attachment-comparison INCOMPLETE retained. Single-email text, page count and unique original/native payloads are unchanged. The only first-page pixel change is the removed corner marker. Frozen V85 is unchanged. [Results](improvement/creator-poc-day5/c118/result.json). Open `examples/sample_1si.pdf` to see the correction. The historical C36 margin-marker placement is superseded. Focused creator work continues until stopped.

**C119 Gmail check:** Current C118 creator also passes a fresh read-only, three-email inbox collection run. All three original emails match downloaded bytes; all parent/child checks PASS. Existing status/category filtering is retained. No code changes. [Results](improvement/creator-poc-day5/c119/result.json).

**User scope:** EA-PDF creation and validation only. No EA-PDF reader work. No new features without the user's express permission. Prioritize accurate output for ordinary email inputs.

**Current samples — C113:** Regenerated the six standard files in `examples/` and the `output/sample_1s.pdf` alias using the current C112 creator. Five PASS existing checks; isolated PST `1mi` retains its known attachment native-byte comparison INCOMPLETE. All six pass PDF/A and selected ISO checks. Finding aids and workbooks pass their existing checks. Protected Agenda/BFO comparison PDFs remain unchanged. [Refresh results](improvement/creator-poc-day5/c113/result.json). Creator work remains active; a missing supplied plain Outlook body has been reproduced next.

**Current — C117 mailbox folders and multi-folder collections repaired; work continues:** Mailbox imports now preserve accented, Chinese and encoded folder names. The final collection check exposed a larger failure: messages assigned to several folders were copied into several child PDFs and rejected. Each email is now stored once, with every folder assignment preserved through the standard collection structure. Both apps include these fixes.

**C117 checks:** Complete Java suite: 250 passes. Mailbox archive/collection and retained/isolated overlapping-folder collections PASS existing EA-PDF/PDF-A/selected ISO checks. Five folder entries share three unique email PDFs; all assignments, original/attachment bytes, isolation policy and three unique PREMIS child objects are verified. Same-folder repeats and independent duplicate children remain rejected without replacing earlier output. Frozen V85 is unchanged. [Results](improvement/creator-poc-day5/c117/result.json). Examples: `output/creator-review/c117/`. C113 samples and prior comparison limits remain. Focused work continues until stopped.

**C116 automatic archive overwrite repaired:** Separate folders with the same email filenames could produce the same automatic archive name and erase the earlier export. Automatic archive and collection names now gain a numbered suffix when needed. The helper is shared with automatic Gmail archives; ordinary app commands reach the same path. Both apps include the fix.

**C116 checks:** Complete Java suite: 247 passes. Actual public CLI archive and collection pairs keep earlier PDFs and sidecars unchanged. All four PDFs PASS existing EA-PDF/PDF-A/selected ISO checks, with both original emails exact. Explicitly selected archive destinations retain their existing replacement behavior. No live Gmail API rerun. [Results](improvement/creator-poc-day5/c116/result.json). Examples: `output/creator-review/c116/`. C113 samples and known source-comparison limits remain. Focused creator work continues until stopped.

**C115 supplied Outlook sender name retained:** The supplied Outlook message with two attachments stores the sender name Kees, but the parser ignored it and the creator printed only the address. Native sender names are now retained when they belong to the selected author. A delegate's different name is not assigned to the author. Both apps include the fix.

**C115 checks:** 15 focused Java checks pass. Actual supplied-message and delegate PDFs pass PDF/A/selected ISO with no mandatory failures, exact original MSG bytes and both native attachments. Known retained-MSG source comparison remains INCOMPLETE. [Results](improvement/creator-poc-day5/c115/result.json). Examples: `output/creator-review/c115/`. A separate audit finds all 25 stored Unicode subjects unchanged and correct. C113 standard samples retain checked bytes. Last full suite C110:239 passes. Focused work continues until stopped.

**C114 missing Outlook invoice body repaired:** Outlook HTML emails could omit a stored readable plain body when their only image needed the web. MSG reading now uses the existing readable-alternative rule after collecting attachments. The invoice amount and deadline appear. Readable HTML and embedded image bodies keep their priority; original MSG bytes remain exact. Both apps include the fix.

**C114 checks:** 16 focused Java checks pass. Actual fallback and embedded-image PDFs pass PDF/A/selected ISO with no mandatory failures and exact originals; retained-MSG source comparison remains INCOMPLETE. All 30 existing ordinary Outlook body/original/native asset hashes remain unchanged. [Results](improvement/creator-poc-day5/c114/result.json). Examples: `output/creator-review/c114/`. C113 standard samples retain checked bytes; last full Java suite remains C110:239 passes. Focused creator work continues until told to stop.

**C112 cut-off email table columns repaired:** A normal 900px table lost its sixth column and part of its fifth at the page boundary. The creator now measures the laid-out content and gives wide messages a wide enough page, retaining authored layout and text size. Footer clearance follows the actual page width. Normal messages keep their page size and pixels. Both apps include the fix.

**C112 checks:** 26 focused Java checks pass. Wide single, long table, mixed-width archive and collection PASS existing EA-PDF/PDF-A/selected ISO checks. Original emails match exactly; all 45 last-column row values remain, without footer overlap. A normal 600px table has identical page size and pixels. [Results](improvement/creator-poc-day5/c112/result.json). Before/after examples: `output/creator-review/c112/`. Last full Java suite C110:239 passes. Focused creator work remains active until told to stop.

**C110 PST author corrected:** Without transport headers, a message sent on someone else's behalf could identify the delegate as its author. The creator now uses the supplied native author address and name. Incomplete author records keep the existing sender fallback, preserving stored Exchange addresses. Both local apps include the fix.

**C110 checks:** Complete Java suite: 239 passes. Corrected-author PDF and ordinary seven-message PST archive PASS existing EA-PDF/PDF-A/selected ISO checks. All 78 existing PST body, exported-message and attachment hashes remain identical. Native PST bytes remain unchanged. [Results](improvement/creator-poc-day5/c110/result.json). Examples: `output/creator-review/c110/`. Existing MSG and isolated PST comparison limits remain. Focused creator work continues until told to stop.

**C109 missing Outlook sender address repaired:** Internal Exchange messages can keep the sender's SMTP address in native properties without internet transport headers. The parser ignored those properties, leaving just a name. The creator now recovers the supplied address, respects represented-author identity and keeps existing parsed mailboxes unchanged. Both local apps include the fix.

**C109 checks:** 13 focused Java checks pass. Actual internal Exchange and transport-header PDFs pass PDF/A and selected ISO with zero mandatory failures. Original MSG and attachment bytes match exactly. The known retained-MSG source comparison remains INCOMPLETE. [Results](improvement/creator-poc-day5/c109/result.json). Examples: `output/creator-review/c109/`. Last full Java suite remains C104:229 passes. Focused creator work continues until told to stop.

**C108 missing readable email body repaired:** Hidden HTML summary text could block the supplied plain body even when the HTML depended on unavailable web images. The creator now uses the readable alternative in that case. Visible HTML and CSS display overrides retain their priority. The original message stays exact. Both local apps include the fix.

**C108 checks:** 28 focused Java checks pass. Actual fallback and visible-override PDFs PASS existing EA-PDF, PDF/A and selected ISO checks, with exact original email bytes. [Results](improvement/creator-poc-day5/c108/result.json). Before/after examples: `output/creator-review/c108/`. Last full Java suite remains C104:229 passes. Focused creator work remains active until told to stop.

**C106 Outlook attachment names preserved:** Declared-encoding attachment names could still become garbled even though their file bytes survived. Native Cyrillic/Western filenames now survive in visible lists, XMP and embedded file specs; attached MSG names use the parent message encoding too. Recovery matches original parser names and exact payload bytes, keeping file identity intact. Both local apps include the fix.

**C106 checks:** 16 focused Java checks pass. Actual Cyrillic, Western and attached-message examples pass PDF/A and selected ISO checks with zero mandatory failures; original MSG bytes and native payload/storage contents are preserved. Retained-MSG comparisons remain INCOMPLETE. [Results](improvement/creator-poc-day5/c106/result.json). Before/after examples: `output/creator-review/c106/`. Last full suite remains C104: 229 passes. Continuous focused creator work remains active.

**C105 PST non-email items excluded:** The same false-email problem also existed inside PST handling. Known non-email Outlook items are now reported as skipped and excluded from message counts. All inspected items still count against processing limits. A non-email embedded object cannot be reconstructed as a fabricated attached email; unsupported objects produce an explicit error. MSG/PST share the same item-class check. Both local apps are current.

**C105 checks:** 17 focused Java checks pass. Controlled native item state proves the former false RFC822 reconstruction is blocked without changing the original PST. All 78 existing real PST messages retain identical body/export/attachment hashes. The actual seven-message archive PASSes frozen EA-PDF, PDF/A and selected ISO checks. [Results](improvement/creator-poc-day5/c105/result.json). Review: `output/creator-review/c105/`. Last full suite remains C104: 229 passes. Continuous core creator work remains active.

**C104 non-email Outlook objects no longer become emails:** The input detector previously accepted any OLE `.msg` object as an email. A real contact beside one email produced two EA-PDF emails. Contacts, contact lists, appointments, tasks, sticky notes and journal entries are now recognized as non-email objects. Directory conversion reports them as skipped; direct non-email input fails clearly. Meeting requests, delivery reports and legacy email classes remain accepted. Both local apps are current.

**C104 checks:** All 229 existing Java checks pass after the source/metadata changes. The real mixed folder now produces one email PDF and reports the skipped contact. That PDF retains its exact MSG source and native attachment; PDF/A and selected ISO PASS with no mandatory failures and the known MSG comparison INCOMPLETE. [Results](improvement/creator-poc-day5/c104/result.json). Review: `output/creator-review/c104/`. Continue core creator work until explicitly told to stop.

**C103 grouped-recipient metadata repaired:** Valid named recipient groups were written as one combined `mailto:` value, producing mandatory `MESSAGE_TO_FOAF` and `MESSAGE_CC_FOAF` failures. Group members now have individual mailbox metadata. Original group headers and source bytes stay exact; empty groups do not invent recipients. PST header recovery uses the same corrected parsing. Both local apps are refreshed.

**C103 checks:** 25 focused Java checks pass. Grouped and undisclosed-recipient singles, an archive and a collection PASS frozen EA-PDF, PDF/A and selected ISO checks, including collection children. Before/after examples: `output/creator-review/c103/`. [Results](improvement/creator-poc-day5/c103/result.json). Continue core creator work until explicitly told to stop.

**C102 valid Outlook date fallback repaired:** A valid MSG with a native submission timestamp but no transport headers previously crashed before reaching its fallback. It now uses that stored timestamp. Supplied transport dates retain their existing precedence; no conversion-time date is invented. Both local apps include the fix.

**C102 checks:** 17 focused Java checks pass. The public CLI creates the repaired email with the correct displayed date, metadata instant and exact original bytes. PDF/A and selected ISO PASS with no mandatory failures; retained MSG comparisons remain INCOMPLETE. [Results](improvement/creator-poc-day5/c102/result.json). Review file: `output/creator-review/c102/Native sent date.pdf`. Work continues on a reproduced grouped-recipient metadata failure.

**C101 Outlook participant names fixed:** Native ANSI sender/recipient names and display-only recipient names now honor the declared message encoding. The same supplied Cyrillic names previously appeared as garbled Latin characters in headers and structured metadata. Known mailbox addresses and exact original MSG bytes stay unchanged. Both local apps include this and the preceding missing-body/PST body-selection fixes.

**C101 checks:** 15 focused Java checks pass. Actual before/after PDF shows corrected names; PDF/A and selected ISO PASS with no mandatory EA-PDF failures. Retained MSG comparisons remain INCOMPLETE. [Results](improvement/creator-poc-day5/c101/result.json). Review examples: `output/creator-review/c101/`.

**C100 source check:** Converted 23 additional ordinary message-class MSGs and three existing EMLs. All 26 pass PDF/A and selected ISO checks. All MSGs have zero mandatory failures; two EMLs PASS. The older `test4.eml` has no sender and a fully quoted Cc token; its frozen-validator `MESSAGE_CC_XMP` failure is retained and outside this ordinary-input pass. [Recorded outcomes](improvement/creator-poc-day5/c100/result.json). Continue significant creator work until explicitly told to stop.

**C99 PST body selection fixed:** PST conversion now honors the stored native plain/RTF/HTML body type. It previously preferred any stored HTML, even when the native body was plain or RTF. Native RTF now remains in reconstructed source exports when other alternatives exist. Missing declared body forms report a fallback. Only PstReader changed from C98; CLI and default PST retention policy are unchanged. Both local apps are refreshed.

**C99 checks:** 41 focused Java checks pass. Three controlled retained-source body examples and their collection PASS frozen EA-PDF, PDF/A and selected ISO checks, including collection children. The 78 real messages in the existing PST fixtures retain identical body, exported-source and attachment hashes. Review examples: `output/creator-review/c99/`. Last complete suite: C97, 216 passes. [Results](improvement/creator-poc-day5/c99/result.json). Work remains active until the user explicitly says stop.

**C98 missing email body fixed:** An unrelated photo attachment could prevent the supplied plain-text alternative from being rendered when HTML depended entirely on unavailable web images. The body text now appears. HTML that can actually use an embedded image keeps that image. Original email and photo bytes remain exact. Only EmlReader changed; this path is shared by EML, Gmail and MBOX. Both local apps are refreshed.

**C98 checks:** 20 focused Java checks pass. The repaired email and matching-image control both PASS frozen EA-PDF, PDF/A and selected ISO checks. Before/after examples: `output/creator-review/c98/`. Last complete Java suite remains C97: 216 passes. [Results](improvement/creator-poc-day5/c98/result.json). Continuous creator work remains active until the user says stop; beta checkpoints no longer end work.

**C97 prior beta review:** Completed the requested priority review on the C96 creator. Seven fresh outputs cover ordinary HTML/RTF/plain MSG emails, multiple attachments, an attached email, a small MBOX and a seven-message PST. All pass PDF/A and selected ISO checks with zero mandatory EA-PDF failures. Both mailbox archives PASS EA-PDF; the five retained-MSG comparisons keep their known INCOMPLETE result. No further significant creator defect was reproduced in these cases.

**Data and batch checks:** Independently inspected native compound-file streams: all five original MSGs and seven by-value attachments match exactly; the embedded Outlook message retains its storage contents with the required standalone header expansion. Both MBOX messages retain their content bytes; separator-newline handling is recorded. All 216 Java checks pass, including parallel naming, rollback, previous-output protection and interruption cleanup. Prior live Gmail and six-profile sample results remain inherited; their reader/writer behavior was unaffected by C96's MSG text decoding. [Results and limits](improvement/creator-poc-day5/c97/result.json). Review files: `output/creator-review/c97/`.

**Current instruction:** Continue in priority order until told to stop or the code reaches viable beta quality for ordinary email creation. The latest user instruction restores that stopping point. Corner cases, minor polish, new features and reader work remain excluded. Distribution preparation remains deferred.

**C96 plain-text Outlook body fixed:** ANSI MSG bodies now use their declared message encoding. A valid Russian email previously came out as garbled Latin characters; a Western European email lost punctuation and its euro sign. Both now render the supplied text correctly and retain exact original MSG bytes. Unicode bodies keep their existing decoding. Reused the existing Subject decoder for the body; only `MsgReader` changed from C95. Both local apps are refreshed.

**C96 checks:** 17 focused Java checks pass. The two actual generated PDFs pass PDF/A and selected ISO checks with no mandatory EA-PDF failures. Retained MSG comparisons remain honestly INCOMPLETE in the frozen validator. [Results](improvement/creator-poc-day5/c96/result.json). Review examples: `output/creator-review/c96/`. At C96, the last full suite was C95 (214 passes); C97 now supersedes it with 216 passes. No distribution or release-preparation work was added.

**C95 creator fixes retained:** Recovered missing MSG recipient mailboxes from supplied transport headers, stopped rejecting a readable MSG with zero padding, and repaired broken structure references in later pages of HTML email. The last defect was found using the user-authorized live Gmail account. The repaired four-page PDF has identical page pixels, text, original email and attachment bytes. Only `MsgReader` and `SemanticStructure` production classes changed from C93.

**Live Gmail checked:** Three inbox messages convert individually, as an archive and as a collection; a fourth email includes a native PDF attachment. All outputs and collection children PASS the frozen EA-PDF, PDF/A and selected ISO checks. All 13 retained original streams and the native attachment match live source bytes. Repeating the three-message export skips all completed outputs correctly. Gmail access was read-only. Private review PDFs: `output/gmail-live-review/`. Working credential copies and private staging are removed after verification; originals remain in the user's home directory.

**C95 delivery (historical):** Both apps in `dist/` include all three fixes. Complete Java suite: 214 passes; focused checks: 22 passes. The 34 genuine MSG files in the existing corpus now parse; one non-MSG file is skipped. The previously rejected Outlook example preserves its original and both attachments exactly. Six saved examples are refreshed: five PASS; isolated PST `1mi` retains its reviewed INCOMPLETE. All six pass PDF/A and selected ISO checks. C93's Python results remain inherited: 1121 passes / 40 optional integration skips. [Results and limits](improvement/creator-poc-day5/c95/delivery-result.json), [Gmail results](improvement/creator-poc-day5/c95/gmail-result.json), [run guide](CREATOR_DEMO.md).

**Remaining limits:** Retained MSG header comparisons and isolated-attachment associations have known verification limits. The small live Gmail run establishes the checked ordinary workflows, not large-mailbox or all-desktop acceptance. Public redistribution still requires branding and dependency source/relinking review. This is a checked Linux/Java 21 proof of concept, not independent certification.

**C92 accuracy scope restored (superseded by C93):** The user rejected the attachment-preview and searchable-layer direction. That code is removed, including the unshipped Office conversion. Both apps now use a build with 101 production classes identical to the verified C88 creator. All six standard samples and affected current demos are regenerated. All standard samples pass PDF/A and selected ISO checks; the isolated archive keeps its documented native-byte comparison INCOMPLETE. [Results](improvement/creator-poc-day5/c92/standard-samples.json).

**Working-file cleanup — 2026-10-23:** Removed 193 obsolete generated verification/build folders and runtime-input archives, freeing 34.8 GiB. Project usage fell from 38.8 GiB to 3.9 GiB. Kept the latest V85 runtime-input archive, evidence summaries, source, original inputs, current samples and apps. The cleanup verified 323 retained files by SHA-256; current demos were subsequently regenerated for C92. Historical receipts may name deleted working copies. Use `/tmp` for temporary builds and remove obsolete copies. [Cleanup receipt](improvement/cleanup-2026-10-23.json).

The latest stopping condition is met at C136. Resume with a reported or reproduced ordinary-input creator defect: faithful email rendering, exact original files, correct metadata, correct document-part and tag ownership. File lists remain plain. Do not resume attachment previews, hidden search layers, Office conversion, minor GUI polish or validator development without a new request.

## Work order and completion checks

**User direction:** Distribution and release preparation are deferred. Work on creator correctness and necessary validation.

This order replaces older next-work directions below. Work on ordinary, valid inputs and repair demonstrated defects in existing behavior.

| Priority | Work | Completion check |
| --- | --- | --- |
| 1 | Prevent lost or altered email data. Check messages, headers, selected bodies and attachments through EML, MSG and PST conversion. | Every input message is accounted for. Retained originals and native attachments match their source bytes; PST exports preserve the supplied content. Any omission agrees with the selected profile and existing policy. |
| 2 | Fix EA-PDF conformance errors. Keep metadata, document parts, tags, embedded files and collection contents consistent. | Generated files pass the applicable existing EA-PDF, PDF/A and selected ISO checks. Investigate any failure in the creator; do not weaken validation to get a PASS. |
| 3 | Fix ordinary email rendering errors. Concentrate on missing text, wrong body selection, incorrect headers/dates, missing inline images and clipped tables. | Representative emails retain their meaning and readable content. Fix reproduced defects; further styling refinements wait. |
| 4 | Make batch creation dependable. Check mailbox/folder runs, output naming, failures, interruption and existing-file protection. | No silent skips, false success or damaged previous output. Report created, failed and skipped messages accurately. |
| 5 | Settle validation limits for the supported proof of concept. Review retained MSG comparisons, isolated-attachment comparisons and the large-archive inspection budget. | Distinguish a creator defect from an unavailable comparison. Keep genuine INCOMPLETE results and document the supported scope. Validator code changes remain deferred unless separately requested. |
| 6 | Keep corrected source and affected review examples consistent. Distribution, packaging and public beta preparation are deferred. | Verify each demonstrated creator repair with the existing checks needed for that change; record results and remaining limits. |

**C97 outcome:** The requested ordinary-input priority review reaches a good beta checkpoint after MSG body decoding, C95 creator repairs and authorized live Gmail checks. Future creator work starts from a reported or reproduced defect. The known validation and distribution limits remain explicit; do not restart optional feature or corner-case work to remove warnings.

No new features, reader development, attachment previews, Office conversion, added file-list links or GUI polish are planned. Keep current CLI arguments and preservation policies unless the user expressly asks to change them. Damaged/unusual inputs, signing and full accessibility certification remain outside this proof-of-concept cycle. Authorized live Gmail creation and resume checks now pass for the small checked sample; larger mailbox and desktop acceptance remain outside that evidence.

C89-C91 are withdrawn work. C92 restores the pre-detour code; it is not a new conformance certification. C88 focused Java suite: 24 passes; last full Java suite: C27, 210 passes.

**C09 accepted — 2026-10-22:** Originals with direct valid UTF-8 top-level headers retain exact bytes under `message/global`; ASCII encoded words and body-only UTF-8 keep their prior label. **204 Java tests and eighteen ordinary/international profiles pass** unchanged V85 EA-PDF, PDF/A and verified ISO checks. Nested-only/reconstructed source classification remains open. [Evidence](improvement/creator-09/README.md). C10 repairs escaped CID image lookup and literal Content-ID export; continue creator work.

**C08 accepted — 2026-10-22:** Malformed MIME text recovery emits a diagnostic while preserving decoded recovery, valid charset behavior and original bytes. **199 Java tests and eighteen ordinary/decoding profiles pass** unchanged V85 EA-PDF, PDF/A and verified ISO checks. [Evidence](improvement/creator-08/README.md). C09 is reviewing media labels for originals with direct UTF-8 headers; continuous creator work remains active and validator development stays stopped.

**C07 accepted — 2026-10-22:** Flowed plain-text display interprets soft breaks, DelSp, quotes and stuffing while retaining exact original bytes and MIME parameters. **194 Java tests and eighteen ordinary/flowed profile checks pass** unchanged V85 EA-PDF, PDF/A and verified ISO checks. Receiver reflow is optional under RFC 3676. [Evidence](improvement/creator-07/README.md). Continue creator work with malformed-text decoding diagnostics; validator development stays stopped at V85.

**C06 accepted — 2026-10-22:** MSG raw transport text and visible known recipient mailboxes are repaired. 185 Java tests, twelve ordinary profiles and six native MSG raw/rendered/source inspections pass their gates. Unsupported MSG source comparisons remain honestly INCOMPLETE. [Evidence](improvement/creator-06/README.md). Next is flowed plain-text rendering; continue creator work without stopping at the checkpoint. V85 validator development stays deferred.

**C05 accepted; C06 active — 2026-10-22:** Native MSG body selection is repaired with 181 Java passes, twelve passing ordinary profiles and six checked native MSG profiles. Visible plain/RTF/HTML bodies, DPart types and original bytes agree; non-isolated V85 MSG source limits remain INCOMPLETE. [Evidence](improvement/creator-05/README.md). C06 now repairs MSG transport-text/raw-XMP preservation and visible known recipient addresses; its two failing regressions and debugger proof are retained and final checks are pending. Continue creator work without stopping; V85 validator development stays deferred.

**C04 accepted — 2026-10-22:** Fixed unnamed international attachment omission, message transfer-decoding and false parent-body notices. 176 Java tests and eighteen ordinary/attachment profile checks pass, including native annotation/source byte checks. [Evidence](improvement/creator-04/README.md). Continue with MSG native body selection; the user's continuous-work instruction remains active. Validator development stays stopped at V85.

**C03 accepted; continuous creator work — 2026-10-22:** Fixed direct UTF-8 header corruption across EML/MBOX, Gmail naming and PST transport parsing. 172 Java tests, twelve ordinary and six international profile checks pass; rendered/XMP names and retained source bytes are correct. [Frozen C03 evidence](improvement/creator-03/README.md). C04 is actively repairing omitted/encoded international attachments and parent-body contamination. The user requested continued work without stopping; continue actionable creator repairs after each checkpoint. V85 validator development stays stopped.

**C02 accepted — 2026-10-22:** Fixed the PST display fallback that invented NUL-valued recipient headers. 167 Java tests pass; all twelve normal/accessibility profiles, ten collection children and four collection PDF/XLSX sidecar pairs pass. Both real PST archives pass PDF/A and the verified ISO profile with zero mandatory failures; 130 fictitious Cc/Bcc headers are removed without changing the other compared message headers or decoded bodies/attachments. The saved m/mi samples are refreshed. Their structure-budget and isolated-attachment INCOMPLETE limits remain. Creator work is active and validator development remains stopped at V85. [Current creator evidence](improvement/creator-02/README.md).

**Current direction and samples — 2026-10-22:** Creator improvements are active; validator development is stopped at V85. C01 fixes header operands and archive FrontMatter ownership. Recovered the interrupted sample request and refreshed all six saved PDFs, collection PDF/XLSX sidecars and the existing 1s alias using rebuilt C01 sources; 165 Java tests pass. All six pass PDF/A and the verified ISO profile with zero mandatory failures. Four profiles and both collection children PASS EA-PDF; the 71-message PST m/mi samples remain INCOMPLETE for source/structure/isolated-attachment assessments. [Current sample evidence](improvement/creator-samples-c01/README.md). Older validator-first directions below are historical; creator and broader acceptance work remain open.

**V84 accepted — 2026-10-22:** Subject comparisons use reliable original decoding or exact literal preservation. Unknown source normalization remains unavailable; supplied malformed words cannot borrow decoder repair into known text. **1104 unit passes / 40 skips; 766 freshly checked custom cases; ten Subject and four positive collection PDF/A/ISO controls, CLI/worker and 16 browser checks match.** All 756 prior hashes/statuses/failure sets remain. Two silent passes and two unsupported failures become INCOMPLETE; one missed mismatch fails. Creator repair remains deferred. [Evidence](improvement/validator-84/evidence.json).

**V83 accepted — 2026-10-22:** Child staging/execution exceptions produce unavailable comparisons and INCOMPLETE; returned child findings survive cleanup errors, and actual invalid children retain FAIL. **1096 unit passes / 40 skips; 756 freshly checked custom cases; four separate operational custom controls; fourteen collection PDF/A/ISO controls, CLI/worker and 16 browser checks match.** All prior custom hashes/statuses/failure sets remain. Four known-valid PDFs no longer receive operationally invented child failures. Creator repair remains deferred. [Evidence](improvement/validator-83/evidence.json).

**V82 accepted — 2026-10-22:** Downstream source-fidelity recommendations, ordered header inventories and source-derived attachment aliases reuse accepted email ownership, including unassigned originals. **1086 unit passes / 40 skips; 756 freshly checked custom cases; six source-binding and four positive collection PDF/A/ISO controls, CLI/worker and 16 browser checks match.** All 750 prior hashes/statuses/failure sets remain. False source attributions become unavailable without changing the six new overall statuses. Creator repair remains deferred. [Evidence](improvement/validator-82/evidence.json).

**V81 accepted — 2026-10-22:** Known nested mail candidates and mailbox envelopes prevent universal source contradictions based only on outer headers. Body-media checks reuse the accepted original binding. **1077 unit passes / 40 skips; 750 freshly checked custom cases; eight nested-source and four positive collection PDF/A/ISO controls, CLI/worker and 16 browser checks match.** All 742 prior hashes/statuses/failure sets remain. Six unsupported failures become INCOMPLETE. Creator repair remains deferred. [Evidence](improvement/validator-81/evidence.json).

**V80 accepted — 2026-10-22:** Viewer attachment conditions and final applicability use the represented email’s matched original. Unrelated retained originals do not activate the attachment branch; unknown counts stay unavailable, while modes forbidden by every possible count still fail. **1067 unit passes / 40 skips; 742 freshly checked custom cases; thirteen viewer-scope and four positive collection PDF/A/ISO controls, CLI/worker and 16 browser checks match.** All 729 prior hashes/statuses/failure sets remain. Three false failures pass; one unsupported failure becomes INCOMPLETE. Creator repair remains deferred. [Evidence](improvement/validator-80/evidence.json).

**V79 accepted — 2026-10-22:** Source-derived coverage and final date applicability follow the matched email original, including a second catalog Source. Unassigned ownership remains unavailable; optional raw-address omission and supplied empty text stay distinct. **1056 unit passes / 40 skips; 729 freshly checked custom cases; fourteen source-scope and four positive collection PDF/A/ISO controls, CLI/worker and 16 browser checks match.** All 715 prior hashes/statuses/failure sets remain. Five unsupported failures become INCOMPLETE; one false failure and three unperformed comparisons pass. Creator repair remains deferred. [Evidence](improvement/validator-79/evidence.json).

**V78 accepted — 2026-10-22:** Non-SMTP originals produce unavailable SMTP presence/normalization assessments, including coverage fallback. Exact literal raw preservation and understood SMTP subsets pass; known SMTP absence, changed supplied raw text and malformed FOAF still fail independently. **1046 unit passes / 40 skips; 715 freshly checked custom cases; eight non-SMTP and four positive collection PDF/A/ISO controls, CLI/worker and 16 browser checks match.** All 707 prior hashes/statuses/failure sets remain. Two unsupported failures become INCOMPLETE. Creator repair remains deferred. [Evidence](improvement/validator-78/evidence.json).

**V77 accepted — 2026-10-21:** Ambiguous source ownership no longer hides universally contradicted Core Fields. Every original is considered; unsupported/recovered candidates retain unavailable proofs. Incompatible field combinations receive a Source Match failure without guessing which field owner was wrong. **1033 unit passes / 40 skips; 707 freshly checked custom cases; twelve PDF/A/ISO controls, CLI/worker and 16 browser checks match.** All 695 prior hashes/statuses/failure sets remain. Four missed contradictions now fail; two unsupported owner failures become INCOMPLETE. Creator repair remains deferred. [Evidence](improvement/validator-77/evidence.json).

**V76 accepted — 2026-10-21:** Independently constructed c/ci collections pass complete parent and child validation in normal and accessibility fixture modes. Unavailable, invalid and corrupt child results propagate correctly; CLI preserves INCOMPLETE and worker returns PASS for the positive control. **Ten fresh custom controls and ten fresh PDF/A/ISO collection pipelines; 685 prior custom observations, 1020 unit passes / 40 skips, 16 browser checks and twelve known creator failures are inherited by unchanged input hashes.** Parent pages/metadata and corrected-child source assets/non-tag operands are preserved. Creator code remains deferred. [Evidence](improvement/validator-76/evidence.json).

**V75 accepted — 2026-10-21:** Source date parser limits no longer prove Raw-Sent violations. RFC negative-zero UTC and supported wide offsets preserve instant semantics; extended-year/UTC-overflow assessments and unperformed instant comparisons remain INCOMPLETE. Known wrong instants, missing Sent and invalid calendars still fail. **1020 unit passes / 40 skips; 685 freshly checked custom cases; ten PDF/A/ISO controls, CLI/worker and 16 browser checks match.** All 675 prior hashes/statuses/failure sets remain. One false failure passes; one unsupported failure and three silent passes become INCOMPLETE. Creator repair remains deferred. [Evidence](improvement/validator-75/evidence.json).

**V74 accepted — 2026-10-21:** Attached unsupported originals leave source-header comparisons INCOMPLETE. Isolated Source presence alone produces a provenance warning rather than an unsupported profile failure; isolated omission remains permitted. A matched EML remains verifiable in the presence of an extra unrendered original. **1008 unit passes / 40 skips; 675 freshly checked custom cases; eight PDF/A/ISO controls, CLI/worker and 16 browser checks match.** All 667 prior hashes/statuses/failure sets remain. One silent pass and two unsupported isolated-source failures become INCOMPLETE. Creator repair remains deferred. [Evidence](improvement/validator-74/evidence.json).

**V73 accepted — 2026-10-21:** Undecodable original mailbox and header values produce unavailable comparisons instead of invented text mismatches. Report copies escape unknown octets without changing retained bytes or internal evidence. Independent supplied-schema, known-presence and readable-field failures remain. **998 unit passes / 40 skips; 667 freshly checked custom cases; ten PDF/A/ISO controls, CLI/worker and 16 browser checks match.** All 657 prior hashes/statuses/failure sets remain. Seven unsupported FAIL results become INCOMPLETE. Creator repair remains deferred. [Evidence](improvement/validator-73/evidence.json).

**V72 accepted — 2026-10-21:** Valid direct UTF-8 source headers are restored losslessly before parsing and comparison. `message/global` sources and nested messages are recognized by media type, including non-`.eml` filenames. **985 unit passes / 40 skips; 657 freshly checked custom cases; ten PDF/A/ISO controls, CLI/worker and 16 browser checks match.** All 647 prior hashes/statuses/failure sets remain. Seven false failures now pass and one missed mismatch fails. Creator repair remains deferred. [Evidence](improvement/validator-72/evidence.json).

**V71 accepted — 2026-10-21:** Original Date presence is independent of text truthiness. Explicit blank/whitespace Date values require Raw-Sent, and present empty normal text preserves the blank; an absent Date does not invent an error value. **972 unit passes / 40 skips; 647 freshly checked custom cases; eight PDF/A/ISO controls, CLI/worker and 16 browser checks match.** All 639 prior hashes/statuses/failure sets remain. Two new false passes fail; empty element/attribute/qualified alternatives pass. Creator repair remains deferred. [Evidence](improvement/validator-71/evidence.json).

**V70 accepted — 2026-10-21:** Supplied empty raw text is distinct from omission and must reflect a known source value. Matching empty originals remain valid; unreadable text remains unavailable. Raw-Sent presence counts empty text, and ordinary optional Raw-To/From/Cc/Bcc absence is N/A without unsupported warnings. **966 unit passes / 40 skips; 639 freshly checked custom cases; ten PDF/A/ISO controls, CLI/worker and 16 browser checks match.** All 629 prior hashes/statuses/failure sets remain; three warning rows become N/A. Seven new false passes fail. Creator repair remains deferred. [Evidence](improvement/validator-70/evidence.json).

**V69 accepted — 2026-10-21:** Supplied Raw-To/From/Cc/Bcc values cannot hide changed quoted mailbox data behind whitespace normalization. Known ASCII addresses are compared with original header occurrences; optional absence and the existing outside-whitespace policy remain. **956 unit passes / 40 skips; 629 freshly checked custom cases; eight PDF/A/ISO controls, CLI/worker and 16 browser checks match.** All 621 prior hashes/statuses/failure sets remain. Four new false passes fail; matching text forms and the separate FOAF failure retain their results. Creator repair remains deferred. [Evidence](improvement/validator-69/evidence.json).

**V68 accepted — 2026-10-21:** Source address structure is parsed before display-name decoding, preserving quoted mailbox spaces and literal address tokens. A narrow SMTP atom fallback handles a header-parser false rejection; malformed mailbox parser errors become schema failures. **949 unit passes / 40 skips; 621 freshly checked custom cases; ten PDF/A/ISO controls, CLI/worker and 16 browser checks match.** All 611 prior hashes/statuses/failure sets remain. Six new false comparison failures pass, two false matches fail, and two parser aborts become specific mailbox failures. Creator repair remains deferred. [Evidence](improvement/validator-68/evidence.json).

**V67 reference review — 2026-10-21:** The exact June 2009 Adobe supplement cited for PDF 1.7 collections is identified and reviewed. The authoritative dependency record and generated views are updated; all validation scopes, requirement entries and runtime logic are unchanged. Sixteen fresh browser checks pass; V66 remains the latest full runtime acceptance. The [current exit review](improvement/coverage-exit-review/current-status.md) separates historical counts from remaining obligation work. [Evidence](improvement/validator-67/evidence.json).

**V66 accepted — 2026-10-21:** Unreadable RF companion filenames retain unavailable comparison while their streams remain available for independent asset/byte checks. Known malformed pairs and provably missing inventory members still fail. **941 unit passes / 40 skips; 611 freshly checked custom cases; eight PDF/A/ISO controls, CLI/worker and 16 browser checks match.** All 603 prior hashes/statuses/failure sets remain. Two decoder aborts become INCOMPLETE; two preserve their actual independent failures. Twelve retained creator outputs keep their known failures. [Evidence](improvement/validator-66/evidence.json).

**V65 coverage accepted — 2026-10-21:** Independent raw Cc/Bcc, invalid-date Raw-Sent, related-file, URL and PDF 1.7 collection-extension controls exercise all six checks missing from the V64 observation cohort. **934 unit passes / 40 skips; 12 new custom controls and real PDF/A checks match; ten applicable ISO checks pass, two PDF 1.7 checks are N/A.** The 591 prior receipts are inherited with identical runtime/rule/PDF hashes (603 total), not claimed as fresh executions. All 202 local assertion codes now have non-N/A observations; this is not obligation coverage certification. CLI/worker agree and twelve retained creator outputs retain their known failures. Creator repair remains deferred. [Evidence](improvement/validator-65/evidence.json).

**V64 accepted — 2026-10-21:** Explicitly empty supplied attachment GUIDs are checked against the email inventory even when Message-ID is present. Omission remains distinct; invalid parent identities cannot establish absence. **933 unit passes / 40 skips; 591 custom cases, eight PDF/A/ISO controls, CLI/worker and 16 browser checks match.** All 583 prior hashes/statuses/failure sets remain. Four new false passes fail; an existing annotation failure gains its independent identity failure. Twelve retained creator outputs keep their known failures. [Evidence](improvement/validator-64/evidence.json).

**V63 accepted — 2026-10-21:** FOAF mailto URIs decode percent escapes once, preserve literal plus/source percent data, and compare quoted mailbox local parts with exact case while ignoring domain case. Invalid URI escapes/UTF-8/control characters fail; unverified differing IDNA normalization stays unavailable without hiding definite mismatches. **930 unit passes / 40 skips; 583 custom cases, nine PDF/A/ISO controls and CLI/worker checks match.** All 574 prior hashes/statuses/failure sets remain. Four new false comparison failures pass and one false local-case pass fails. Twelve retained creator outputs keep their known failures. [Evidence](improvement/validator-63/evidence.json).

**V62 accepted — 2026-10-21:** Supplied attachment GUIDs identify an email in a complete typed XMP GUID inventory, independently of body rendering. Unknown owner metadata no longer manufactures a missing-annotation failure; proved annotation geometry failures remain. **921 unit passes / 40 skips; 574 custom cases, eight PDF/A/ISO controls and CLI/worker checks match.** All 566 prior hashes/statuses/failure sets remain. One new header-only false pass fails and four new annotation failures receive the correct identity failure. Twelve retained creator outputs keep their known failures. The eleven local decisions and their external limits remain as recorded in V61. [Evidence](improvement/validator-62/evidence.json).

**V61 accepted — 2026-10-21:** All eleven questions have explicit user-authorized project interpretations, with cited PDF/A draft-edition equivalence still unverified. Leaf DPM identities, conflicting Message-ID aliases, unordered reply Bags and attachment Contents are checked; collection ancestry follows the documented local exception. **915 unit passes / 40 skips; 566 custom cases, 18 PDF/A/ISO controls, CLI/worker and 16 offline browser checks match.** All 548 prior hashes/statuses/failure sets remain. Ten new false passes fail; one becomes INCOMPLETE and one operational exception becomes INCOMPLETE. Twelve retained creator outputs keep their known failures. Full obligation coverage and creator repairs remain open. [Evidence](improvement/validator-61/evidence.json).

**V60 accepted — 2026-10-21:** Typed unique parent MessageID binding verifies attachment annotations while retaining required GUID failures. **905 unit passes / 40 skips; 548 custom cases, eight exact-byte PDF/A/ISO controls and two entry points match.** All 540 prior hashes/statuses remain; 537 prior failure sets are unchanged and three unsupported annotation codes are removed. Four analogous new controls are corrected. URI/missing/duplicate identities do not activate a new match; GUID precedence, unreadable identity and actual geometry failures remain distinct. Twelve fresh creator runs retain known FAIL outcomes. Eleven questions and broader review remain open. [Evidence](improvement/validator-60/evidence.json).

**V59 accepted — 2026-10-21:** DPart identity selection uses canonical Table7 keys and retains unrelated extension data. **896 unit passes / 40 skips; 540 custom cases, eight exact-byte PDF/A/ISO controls and two entry points match.** All 532 prior hashes/statuses/failure sets remain. Five extension-induced false annotation failures now PASS with verified source/body matching. Shared XMP/source aliases, decoder guards and canonical contradictions retain their behavior. Twelve fresh creator runs retain known FAIL outcomes. Eleven questions and broader review remain open. [Evidence](improvement/validator-59/evidence.json).

**V58 accepted — 2026-10-21:** Required DPM GUID decoding preserves unavailable identity across mandatory checks, source selection, actual owners and global inventory. **886 unit passes / 40 skips; 532 custom cases, ten exact-byte PDF/A/ISO controls and two entry points match.** All 522 prior hashes/statuses/failure sets remain. Four operational failures become two INCOMPLETE results, one retained revision FAIL and one definite other-owner FAIL. Unknown body owners leave annotation applicability unavailable while known missing annotations still fail. Twelve fresh creator runs retain known FAIL outcomes. Eleven questions and broader review remain open. [Evidence](improvement/validator-58/evidence.json).

**V57 accepted — 2026-10-21:** Optional DPM MessageID decoding preserves unavailable comparison and readable GUID precedence in source selection. **868 unit passes / 40 skips; 522 custom cases, eight exact-byte PDF/A/ISO controls and two entry points match.** All 514 prior hashes/statuses/failure sets remain. Three operational failures become two INCOMPLETE results and one retained revision FAIL. Optional absence, readable differences, wrong classes and later known mismatches remain distinct. The first pipeline failure exposed a second consumer; the final runtime passed fresh complete gates. Twelve fresh creator runs retain known FAIL outcomes. Eleven questions and broader review remain open. [Evidence](improvement/validator-57/evidence.json).

**V56 accepted — 2026-10-21:** Unreadable DPM descriptions retain explicit unavailable assessment and continue validation. **853 unit passes / 40 skips; 514 custom cases, eight exact-byte PDF/A/ISO controls and two entry points match.** All 506 prior hashes/statuses/failure sets remain. Three decoder exceptions become two INCOMPLETE results and one independently retained revision FAIL. Valid UTF8/UTF16 text, empty descriptions and wrong object classes keep their distinctions. Twelve fresh creator runs retain known FAIL outcomes. Eleven questions and broader review remain open. [Evidence](improvement/validator-56/evidence.json).

**V55 accepted — 2026-10-21:** Art checks use actual Content Set counterparts, including implicit Art through Mail_ContentSet. **843 unit passes / 40 skips; 506 custom cases, six exact-byte PDF/A/ISO controls and two entry points match.** All 500 prior PDF hashes and statuses remain; four unsupported Art failure codes are removed while independent coverage/structure failures remain. Seven historical Art levels are reviewed. A new cross-set Article fails; an extra-Article count failure becomes INCOMPLETE for the separate role-map scope boundary. Twelve fresh creator runs retain known FAIL outcomes. Eleven questions and broader review remain open. [Evidence](improvement/validator-55/evidence.json).

**V54 accepted — 2026-10-21:** Actual content references replace tag-count equality for conditional Content Set coverage. **822 unit passes / 40 skips; 500 custom cases, six exact-byte PDF/A/ISO controls and two entry points match.** All 494 prior hashes/statuses/failure sets remain. One balanced-count case gains its missing-coverage failure; two empty-tag coverage passes become explicitly unavailable while separate structure failures remain. Twelve fresh creator runs retain known FAIL outcomes. Art-only semantics, eleven questions and broader review remain open. [Evidence](improvement/validator-54/evidence.json).

**V53 accepted — 2026-10-21:** Actual Content Set ancestry replaces the unsupported scalar FrontMatter exemption; the collection-parent conflict stays explicit. **805 unit passes / 40 skips; 494 custom cases, eight exact-byte PDF/A/ISO controls and two entry points match.** All 486 historical PDF hashes remain. 119 proved noncollection parent failures correct 32 prior PASS and eight INCOMPLETE results; 79 already failed files gain the violation. Thirty collection predicates become unavailable while their existing custom statuses/failure sets remain; other 337 prior outcomes are unchanged. Twelve fresh creator runs retain FAIL, with four m/mi outputs gaining parent failures and four c/ci parents remaining unresolved. Eleven listed questions and broader review remain open. [Evidence](improvement/validator-53/evidence.json).

**V52 accepted — 2026-10-21:** Unreadable source header values retain unavailable SHOULD comparisons instead of aborting validation. **793 unit passes / 40 skips; 486 custom cases, eight exact-byte PDF/A/ISO controls and two entry points match.** All 478 prior hashes/statuses/failure sets remain. Four operational failures become PASS with explicit recommendation WARN/unavailable; no mandatory incompleteness is added. Valid encoded comparisons, readable differences and separate date/type checks retain their outcomes. Twelve fresh creator runs retain known failures. Eleven listed questions and broader review remain open. [Evidence](improvement/validator-52/evidence.json).

**V51 accepted — 2026-10-21:** Guarded group-type and subtype correspondence preserves unavailable evidence and readable contradictions. **781 unit passes / 40 skips; 478 custom cases, eight exact-byte PDF/A/ISO controls and two entry points match.** All 470 prior hashes/statuses/failure sets remain. Two operational failures become INCOMPLETE; two retain FAIL with definite mismatches. Presence recommendations, exact group names, MIME signatures and attribute-array replacement retain their behavior. Twelve fresh creator runs retain known failures. Eleven listed questions and broader review remain open. [Evidence](improvement/validator-51/evidence.json).

**V50 accepted — 2026-10-21:** Guarded required GUID presence and every active matching occurrence retain explicit unavailable evidence and definite contradictions. **771 unit passes / 40 skips; 470 custom cases, eight exact-byte PDF/A/ISO controls and two entry points match.** All 462 prior hashes/statuses/failure sets remain. Three operational failures become INCOMPLETE; one retains FAIL with its readable mismatch. Valid encoded GUIDs, empty values, object types and inactive-array replacement retain expected outcomes. Twelve fresh creator runs retain known failures. Eleven listed questions and broader review remain open. [Evidence](improvement/validator-50/evidence.json).

**V49 accepted — 2026-10-21:** Unreadable PDF-date text fails the existing date predicate instead of aborting validation. **759 unit passes / 40 skips; 462 custom cases, eight exact-byte PDF/A/ISO controls and two entry points match.** All 454 prior hashes/statuses/failure sets remain. Two operational failures become USERPROPS_TYPE findings with FAIL status retained. Valid UTF8/UTF16 dates and source recommendation strength remain unchanged. Twelve fresh creator runs retain known failures. Eleven listed questions and broader review remain open. [Evidence](improvement/validator-49/evidence.json).

**V48 accepted — 2026-10-21:** Unrelated generic UserProperties retain ISO V(any); EA field types and Mail_* header constraints keep their scope. **747 unit passes / 40 skips; 454 custom cases, eight exact-byte PDF/A/ISO controls and two entry points match.** All 446 prior hashes/statuses/failure sets remain. Four false generic-property type failures now pass; required V, raw N ASCII and EA tag scope remain enforced. Eight controls represent seven distinct hashes because null canonicalizes to missing; direct parsed-null tests retain separate evidence. Twelve fresh creator runs retain known failures. Eleven listed questions and broader review remain open. [Evidence](improvement/validator-48/evidence.json).

**V47 accepted — 2026-10-21:** Guarded wrong-value diagnostics and actual-string owner identity handling preserve prescribed type failures. **730 unit passes / 40 skips; 446 custom cases, eleven exact-byte PDF/A/ISO controls and two entry points match.** All 435 prior hashes/statuses/failure sets remain. Seven operational exceptions become definite USERPROPS_TYPE findings while their FAIL statuses remain. Existing generic Mail_* rejections and Unicode strings are preserved. Twelve fresh creator runs retain known failures. Eleven listed questions and broader review remain open. [Evidence](improvement/validator-47/evidence.json).

**V46 accepted — 2026-10-21:** Guarded Text-string property-name decoding preserves raw ASCII validation and rejects object coercion. **716 unit passes / 40 skips; 435 custom cases, eight exact-byte PDF/A/ISO controls and two entry points match.** All 427 prior hashes/statuses/failure sets remain. Four malformed-name cases retain FAIL status and now report prescribed data violations instead of operational exceptions. Known encoded-name presence, legal empty ASCII extensions and Unicode V values remain distinct. Twelve fresh creator runs retain known failures. Eleven listed questions and broader review remain open. [Evidence](improvement/validator-46/evidence.json).

**V45 accepted — 2026-10-21:** The subtype SHOULD recommendation applies to every Mail_ContentSet, independently of optional self type metadata. **704 unit passes / 40 skips; 427 custom cases, eight exact-byte PDF/A/ISO controls and two entry points match.** All 419 prior hashes/statuses/failure sets remain. 119 prior recommendation passes and four new controls now WARN without changing conformance strength. Supplied values and unresolved attributes retain separate assessments. Twelve fresh creator runs retain known failures. Eleven listed questions and broader review remain open. [Evidence](improvement/validator-45/evidence.json).

**V44 accepted — 2026-10-21:** DPM entry presence, empty Name validity and media syntax are assessed separately; unreadable UTF8 remains unavailable for applicability. **694 unit passes / 40 skips; 419 custom cases, eight exact-byte PDF/A/ISO controls and two entry points match.** All 411 prior hashes/statuses remain; 410 prior failure sets are unchanged and one proved false presence code is removed. One false pass fails, one false failure passes, and an empty required subtype receives the correct syntax failure. Twelve fresh final creator runs retain known failures. Eleven listed questions and broader review remain open. [Evidence](improvement/validator-44/evidence.json).

**V43 accepted — 2026-10-21:** Connected subtype comparisons use reviewed MIME semantics without changing quoted parameter data. **681 unit passes / 40 skips; 411 custom cases, eight exact-byte PDF/A/ISO controls and two entry points match.** All 403 prior hashes/outcomes remain. One false pass now fails; five false comparison failures pass. Equivalent shared candidates match without choosing an owner; distinct compatible candidates remain unverified. Twelve fresh creator runs preserve known failures. Eleven listed questions and broader review remain open. [Evidence](improvement/validator-43/evidence.json).

**V42 accepted — 2026-10-21:** Every applicable active UserProperties occurrence is checked; actual name owners and connected subtype data determine matching. **667 unit passes / 40 skips; 403 custom cases, sixteen exact-byte PDF/A/ISO controls and two entry points match.** All 387 prior hashes/outcomes remain. Six false passes now fail; one unavailable required-owner case fails; two unsupported comparison failures become INCOMPLETE. Legal duplicates and whole-array replacement remain valid. Twelve fresh creator runs preserve known failures. The audit generator now accepts ordering-only mapping differences. Eleven listed questions and broader review remain open. [Evidence](improvement/validator-42/evidence.json).

**V41 accepted — 2026-10-20:** Supplied group types match independently resolved connected DPart data, with exact characters and explicit unavailable XMP binding. **643 unit passes / 40 skips; 387 custom cases, ten exact-byte PDF/A/ISO controls and two entry points match.** All 377 prior hashes/outcomes remain. Three false passes now fail; two unsupported passes become INCOMPLETE; two false comparison failures are corrected. Optional absence remains a recommendation. Twelve fresh creator runs preserve known failures. Eleven listed questions and broader review remain open. [Evidence](improvement/validator-41/evidence.json).

**V40 accepted — 2026-10-20:** UserProperties N names retain exact characters and raw PDF ASCII-string bytes; Unicode V text remains permitted. **626 unit passes / 40 skips; 377 custom cases, ten exact-byte PDF/A/ISO controls and two entry points match.** All 367 prior hashes/outcomes remain. Five false passes now fail. Twelve current creator checks and independent raw-name guards, including embedded children, preserve known failures and permit exact fresh V39 upstream reuse. Eleven listed questions and broader review remain open. [Evidence](improvement/validator-40/evidence.json).

**V39 accepted — 2026-10-20:** Mail_Message GUIDs are compared with independently resolved content/DPart owners; ambiguous or absent connections remain unverified. **618 unit passes / 40 skips; 367 custom cases, nine exact-byte PDF/A/ISO controls and two entry points match.** All 358 prior hashes/outcomes remain. Three false global-membership passes now fail; one already rejected DPM swap gains its proved owner mismatch. Twelve fresh creator runs keep their known failures. The combined-clause owner interpretation is explicit; eleven listed questions and broader review remain open. [Evidence](improvement/validator-39/evidence.json).

**V38 accepted — 2026-10-20:** DPartRoot/nodes require dictionary objects; leaf ranges uniquely cover pages in depth-first page order. **601 unit passes / 40 skips; 358 custom cases, eight exact-byte PDF/A/ISO controls and two entry points match.** All 350 prior hashes remain; 345 prior outcomes are unchanged and five gain independently proved overlap failures. Four further false passes now fail. Five retained negative controls pass PDF/A with ISO correctly N/A. Twelve current creator checks retain known failures and reuse exact fresh V37 upstream reports. Eleven listed questions and wider coverage remain open. [Evidence](improvement/validator-38/evidence.json).

**V37 accepted — 2026-10-20:** RecordLevel bounds, prohibited DPart Metadata, recursive DPM value classes/key spelling and all name-value UTF-8 are checked against published corrections. **593 unit passes / 40 skips; 350 custom cases and sixteen exact-byte PDF/A/ISO controls match, with one CLI and one worker check.** All 334 prior hashes/outcomes remain. Ten false passes now fail; two unresolved key cases become INCOMPLETE. Twelve fresh creator runs retain their known conformance failures. Eleven listed questions and broader review remain open. [Evidence](improvement/validator-37/evidence.json).

**V36 accepted — 2026-10-20:** Supplied optional DPart entries now retain their prescribed types and hierarchy counts; unresolved XML-name evidence stays INCOMPLETE. **581 unit passes / 40 skips; 334 custom cases and fourteen exact-byte PDF/A/ISO controls match, with one CLI and one worker check.** All 320 prior hashes/outcomes remain. Eight missed violations now fail; two unsupported complete passes become INCOMPLETE. Twelve fresh retained creator runs keep their known operand/child failures. Eleven listed questions and broader review remain open. [Evidence](improvement/validator-36/evidence.json).

**V35 accepted — 2026-10-20:** All eight Info/XMP entries retain actual PDF string types before comparison; optional nulls are absent. **571 unit passes / 40 skips; 320 custom cases and twelve exact-byte PDF/A-3U controls match, with one CLI and one worker check.** All 308 prior hashes/outcomes remain. Eight raw-type false pipeline passes now fail; normal numeric/name-like/empty strings remain valid. Date/text comparison policy is unchanged. Eleven decisions and broader coverage remain open. [Evidence](improvement/validator-35/evidence.json).

**V34 accepted — 2026-10-20:** PDF/A-3 Info Title/Subject use x-default, Keywords synchronize with XMP, and established absent optional fields receive N/A. **563 unit passes / 40 skips; 308 custom cases and twelve exact-byte PDF/A-3A/3U controls match, with one CLI and one worker check.** All 296 prior hashes/outcomes remain. Three false passes, three false comparison failures and two false INCOMPLETE results are corrected. ISO32005 is correctly inapplicable to these PDF1.7 inputs. Eleven decisions and broader coverage remain open. [Evidence](improvement/validator-34/evidence.json).

**V33 accepted — 2026-10-20:** Required creator/producer/creation-date metadata now retains standard XMP types and valid Date values. **555 unit passes / 40 skips; 296 custom cases and twelve exact-byte PDF/A/ISO controls match, with one CLI and one worker check.** All 284 prior hashes/outcomes are unchanged; seven false pipeline passes now fail. Qualified/attribute forms, reduced precision and unknown timezones remain valid. Twelve creator diagnoses are reused after native checks; eleven decisions and broader coverage remain open. [Evidence](improvement/validator-33/evidence.json).

**V32 accepted — 2026-10-20:** The title recommendation reads the explicit x-default Text item, including permitted empty Text. **545 unit passes / 40 skips; 284 custom cases and eight exact-byte PDF/A/ISO controls match, with one CLI and one worker check.** All 276 prior bytes/status/failure outcomes remain; three incorrect recommendation PASS results now WARN. Four exact V31 before-runs and twelve creator diagnoses are reused with explicit checks. Mandatory crosswalk, eleven decisions and broader coverage remain open. [Evidence](improvement/validator-32/evidence.json).

**V31 accepted — 2026-10-20:** Catalog Version and required Collection Subtype entries now require PDF names. Header/effective-version restrictions follow EA-PDF and ISO precedence. **537 unit passes / 40 skips; 276 custom cases and eleven exact-byte engine probes match, with one CLI and two worker checks.** All 265 prior hashes/outcomes are unchanged; five false pipeline passes now fail. Three independent PDF/A failures remain explicit. Twelve creator diagnoses are reused after native-value checks; eleven decisions and broader coverage remain open. [Evidence](improvement/validator-31/evidence.json).

**V30 accepted — 2026-10-20:** Profile navigation modes now require actual PDF names. **528 unit passes / 40 skips; 265 custom cases and six exact-byte pipeline probes match, with one CLI and one worker check.** All 259 prior hashes/outcomes are unchanged; three false PASS results now fail. Twelve exact V29 creator runs are reused after independent native-name/hash checks, with their known conformance failures retained. The observation queue is current for the bounded V30 cohort. Eleven decisions and broader coverage remain open. [Evidence](improvement/validator-30/evidence.json).

**V29 accepted — 2026-10-20:** Collection checks preserve exact decoded names, inspect every name-tree alias and compare case uniqueness in the actual folder scope. **524 unit passes / 40 skips; 259 custom cases, fourteen exact-byte PDF probes and six CLI/worker checks match.** All 245 prior hashes/outcomes are unchanged. Eight missed violations now fail; three false failures now pass. Twelve retained creator gates keep their known failures. The current observation queue is refreshed; eleven decisions and broader coverage remain open. [Evidence](improvement/validator-29/evidence.json).

**V28 accepted — 2026-10-20:** Catalog DisplayDocTitle and Collection field E now require exact PDF Boolean values. **511 unit passes / 40 skips; 245 custom cases, thirteen exact-byte PDF probes and six CLI/worker checks match.** All 232 prior PDF hashes/outcomes are unchanged. Seven former complete-pipeline false PASS results now FAIL; exercised PDF/A/ISO checks still PASS. All twelve retained creator gates keep the known operand/child failures. Collection filename counterexamples are saved for the next repair; eleven decisions and broader coverage remain open. [Evidence](improvement/validator-28/evidence.json).

**V27 accepted — 2026-10-20:** Source associations preserve exact decoded filename whitespace and raw PDF UF/F text. **505 unit passes / 40 skips; 232 custom cases, eight PDF probes and eight CLI/worker checks match.** All 224 prior PDF hashes/outcomes are unchanged. Four permitted filename forms now pass; an invented alias stays unavailable; real contradictions still fail. All twelve retained creator conformance gates remain failed on the known operand/child defect, with exercised PDF/A/ISO PASS. Eleven decisions and broader coverage gates remain open. [Evidence](improvement/validator-27/evidence.json).

**V26 accepted — 2026-10-20:** Unresolved declared source parameters now remain WARN/INCOMPLETE; verified checksum/name associations retain their comparisons. **495 unit passes / 40 skips; 224 custom cases, eleven PDF probes and eight CLI/worker checks match.** All 215 prior PDF hashes and overall status/failure outcomes are unchanged. Ten media-reporting rows improve. Twelve retained creator outputs retain their known failed conformance gates with PDF/A/ISO PASS. Exact source filename handling is the next confirmed gap; eleven decisions remain open. [Evidence](improvement/validator-26/evidence.json).

**V25 accepted — 2026-10-20:** Ambiguous source associations now report size/media values contradicted by every candidate. **485 unit passes / 40 skips; 215 custom cases, eight PDF probes and eight CLI/worker checks match.** All 207 prior PDF hashes/outcomes are unchanged. All twelve retained creator outputs still pass the exercised PDF/A/ISO checks and fail the same operand/child conformance gates. Source assignment and eleven decisions remain open. [Evidence](improvement/validator-25/evidence.json).

**V24 validator accepted — 2026-10-20:** Custom marked-content operands now match their actual page/Form structure owners. **473 unit passes / 40 skips; 207 custom comparisons; six PDF probes and six CLI/worker checks match.** All nine previously unexercised checks now have focused evidence. All 202 historical PDFs remain unchanged, but independent inspection proves operand mismatches in 194 and affected children in eight. **All twelve original creator conformance gates fail on this newly detected defect** while the exercised PDF/A and ISO checks pass. Creator unchanged; repair is pending. [Evidence](improvement/validator-24/evidence.json), [review](improvement/validator-24/README.md).

**Coverage exit review — 2026-10-20:** Recovered thread `01a159dd-2541-73c3-92d8-a63e0f535f7a` and completed its whole-validator checklist. All **224 checks / 301 obligations** now have evidence views and six explicit exit gates. The V23 census confirms **447 passes / 40 skips and 202 custom fixtures**; nine custom checks lack non-N/A production observations, and all 29 current full-pipeline reports use PDF/A-4F. Eleven questions remain open. [Exit checklist](improvement/coverage-exit-review/README.md), [review queues](improvement/coverage-exit-review/review-queues.md). Runtime and requirements are unchanged.

**V23 accepted — 2026-10-20:** Declared attachment parameters without a native association remain WARN/unavailable, including mixed verified/unmatched records. **447 unit passes / 40 skips; nine PDF cases, twelve generated profiles, 202 custom comparisons and eight CLI/web-worker checks match.** Ten parameter-rule results improve: nine N/A-to-WARN and one PASS-to-WARN. All 194 prior PDF hashes and status/failure outcomes are unchanged. No native-copy requirement is added; creator unchanged; eleven questions remain open. [Evidence](improvement/validator-23/evidence.json).

**V22 accepted — 2026-10-20:** Ambiguous native associations no longer hide universally contradicted media types or parameters. **440 unit passes / 39 skips; twelve PDF cases, twelve generated profiles, 194 custom comparisons and eight CLI/web-worker checks match.** Six new probes and two retained cases gain proved media/parameter failures; two compatible-parameter controls now report WARN instead of N/A. All 184 prior PDF hashes and 182 other prior status/failure outcomes are unchanged. Occurrence assignment remains open; creator unchanged; eleven questions remain. [Evidence](improvement/validator-22/evidence.json).

**V21 accepted — 2026-10-20:** Ambiguous native associations no longer hide a size/checksum contradicted by every possible payload. **430 unit passes / 38 skips; twelve PDF cases, twelve generated profiles, 184 custom comparisons and eight CLI/web-worker checks match.** Five new negative probes and two retained cases now fail; all 174 prior PDF hashes and 172 other prior custom outcomes are unchanged. Association remains unavailable; no owner or occurrence is selected. Creator unchanged; eleven questions remain open. [Evidence](improvement/validator-21/evidence.json).

**V20 accepted — 2026-10-20:** Media comparisons preserve malformed parameter text and quoted spaces. **421 unit passes / 37 skips; ten PDF cases, twelve generated profiles, 174 custom comparisons and eight CLI/web-worker checks match.** Five altered values now fail; two preserved-source cases now pass. All 164 prior fixture bytes and custom outcomes are unchanged. Creator unchanged; eleven review questions remain open. [Evidence](improvement/validator-20/evidence.json).

**V19 accepted — 2026-10-20:** Equivalent attachment metadata now permits stored-byte/media comparisons without assigning an owner. **412 unit passes / 36 skips; eight PDF cases, twelve generated profiles, 164 custom comparisons and eight CLI/web-worker checks match.** All 156 prior fixture bytes and custom outcomes are unchanged. Creator unchanged. Eleven review questions and unique occurrence assignment remain open. [Evidence](improvement/validator-19/evidence.json).

**V18 accepted — 2026-10-20:** Attachment source comparisons no longer depend on XMP record order. **404 unit passes / 35 skips; six PDF cases, twelve generated profiles, 156 custom comparisons and eight CLI/web-worker checks match.** All 150 prior fixture bytes and custom outcomes are unchanged. Creator unchanged. Native occurrence assignment and eleven review questions remain open. [Evidence](improvement/validator-18/evidence.json).

**V17 accepted — 2026-10-20:** Fixed Message-ID attachment ownership and missing/zero attachment counts. **396 unit passes / 34 skips; nine PDF cases, twelve generated profiles, 150 custom comparisons and sixteen CLI/web-worker checks match.** All 141 older PDF files and custom results are unchanged. Java creator unchanged. Eleven expert/dependency questions remain. [Evidence](improvement/validator-17/evidence.json).

**V16 accepted — 2026-10-20:** Attachment association preserves exact PDF/MIME filename text and keeps Alternative representations out of native annotation matching. **389 current unit passes / 33 skips; eight exact-byte PDF probes, twelve generated profiles, all 141 custom-only comparisons and twelve CLI/isolated-worker checks match.** Only six reviewed outcomes change; all 133 prior fixture bytes/custom outcomes are unchanged. Creator/JAR is unchanged. [Evidence](improvement/validator-16/evidence.json).

**V15 accepted — 2026-10-20:** A supplied attachment `Mail_GUID` must retain its normal text type when `Message-ID` permits omission. **381 current unit passes / 32 skips; eight exact-byte before/after PDF/A/custom/ISO probes, all 133 custom-only fixture comparisons and sixteen CLI/isolated-worker checks match.** V14's separately bounded 125 complete-pipeline cases and twelve generated profiles are unchanged prior evidence. Creator source/JAR is unchanged; its 161 Java passes remain historical. Eleven expert/dependency questions and broader ownership/occurrence review remain open; full coverage is not claimed. [Evidence](improvement/validator-15/evidence.json).

**V14 accepted — 2026-10-20:** Native attachment size/checksum/media comparisons now execute without BodyRendering. Collection member PDFs and Alternative representations retain their distinct roles; collection completion explicitly defers payload comparisons to owning children. **379 verified unit passes / 31 skips; all 125 retained complete-pipeline PDF outcomes, twelve generated profiles and ten CLI/isolated-worker checks match.** Fifteen exact-byte before/after probes and the recovered thread's failed/interrupted gates are preserved separately. Creator code/JAR is unchanged. [Evidence](improvement/validator-14/evidence.json).

**V07–V13 / creator structure — 2026-10-19:** Declaration/schema, verified upstream ISO execution, metadata and source-asset repairs are accepted. V13 fixes false attachment-annotation failures when the permitted native PDF filename differs from the original MIME filename, and closes a large-integer stored-size bypass; unavailable association stays INCOMPLETE. **365 current unit passes / 29 integration/schema skips; all 110 retained PDF outcomes, 12 generated normal/accessibility profiles and ten actual CLI/worker checks match.** The unchanged creator JAR has 161 Java passes; the historical V11 full packaged suite has 375 passes / one optional PREMIS skip. These are separately bounded gates. The audit has **301 records / 224 codes / 11 open expert/dependency questions**; full implementation coverage is not claimed. [Current evidence](improvement/validator-13/evidence.json), [creator diagnosis](improvement/creator-iso-structure/README.md).

**V06 XMP record schema — 2026-10-19:** Canonical Table 16/19 fields, normal-string shapes, nonnegative integer forms and supplied optional CheckSum validity are enforced. Malformed source Content-Type text remains permitted; parser defaults cannot fabricate equality. **339 Python passes / one optional schema skip (340 run); all twelve profile reports PASS; ten PDF/A-valid probes and six CLI/web-worker checks match exact expectations.** The recovered work continues with the confirmed declaration namespace/parser defect and separately reviewed upstream ISO-only execution. Eleven expert/dependency questions remain. [Evidence](improvement/validator-06/evidence.json).

**V05 DPM schema — 2026-10-19:** Enforced Table 7/8 text-string and dictionary types, allowed unrelated extension keys, validated decoded UTF-8 name values and made invalid-name diagnostics reportable. Body-dependent annotation checks distinguish N/A from unavailable evidence. **308 unit passes / 22 integration skips; eight focused passes; all twelve final-source profile reports PASS; six PDF/A-valid probes and six actual CLI/web-worker checks meet expectations.** Canonical XMP field schema, broader ownership/mixed-rule coverage and 11 expert/dependency questions remain open. [Evidence](improvement/validator-05/evidence.json).

**V04 file requirements — 2026-10-19:** All six previously unmapped mandatory/conditional file records now map to three executed checks: container Content Set prohibitions, associated-stream ModDate and media-type reconstruction. **321 Python passes, one optional PREMIS skip; all 12 profile reports PASS; all 12 PDF/A-valid probes match exact EA-PDF expectations; six actual CLI/web-worker checks agree.** The inventory has **301 records / 223 codes, zero unmapped mandatory/conditional file records and 11 open expert/dependency questions**. Mapping coverage is not implementation completeness. DPM field types/extensions, broader schema/ownership and mixed-rule routing remain open. [Evidence](improvement/validator-04/evidence.json).

**Current review — 2026-10-19:** [Open the standalone two-pane rule browser](improvement/specification-audit/rule-browser.html): all **301 records / 224 check definitions**, **249 file records**, and all **97 original specification pages** in one offline HTML file. Selecting a rule or scrolling either pane synchronizes browsing; relevant source passages are highlighted. Exact table matches and suggested passage matches are labeled separately. [Usage and regeneration](improvement/rule-browser.md), [browser verification](improvement/rule-browser-verification.json).

**Upstream issue review — 2026-10-19:** Reviewed all **26 issues and 47 comments**, including seven direct EA-PDF reports. Applied the published MCAF correction, strict ADBE object types, and honest unavailable CreateDate-provenance reporting. All **301 tests pass with one optional skip** (302 run); all **12 profile reports PASS**; all six PDF probes pass PDF/A and match their expected EA-PDF outcomes; four actual CLI/web-worker checks agree. **11 expert/dependency questions and six unmapped mandatory/conditional file requirements remain open.** [Decisions and limitations](improvement/upstream-issues/README.md), [evidence](improvement/upstream-issues/evidence.json). Earlier checkpoints below retain their historical counts.

**V03 XMP/GUID repair — 2026-10-19:** The validator now preserves exact Mail_GUID identity across XMP, DPM, structure and attachment ownership; reads tested equivalent XMP forms; rejects URI/array/foreign-name GUID substitutions; and checks record/address array kinds and actual email cardinality. Verification: **290 broad-suite passes and one optional PREMIS skip; 272 final-source unit passes with 21 skips; all 18 focused regressions pass; all 12 normal/accessibility profile reports PASS; all 11 PDF probes and four actual CLI/web-worker checks meet expectations.** The two final scalar/cardinality guards were verified after the broad run; see the recorded boundary. Eight questions and six unmapped requirements remain open. [Evidence](improvement/validator-03-evidence.json), [implementation](improvement/validator-03/README.md).

**V02 ownership routing — 2026-10-19:** The normal validator pipeline now delegates all 11 fully upstream-owned codes from `validator_rule_text.json`, recording N/A delegation evidence instead of duplicate local conformance results. The web view keeps EA-PDF-specific checks visible, hides delegated rows only after a matching reviewed profile/version passes, and reloads engine rule data after JSON-only edits. Verification: **274 Python passes, one optional PREMIS schema skip; all 12 normal/accessibility profiles PASS; both real CLI and web-worker fixture pairs meet their exact expectations.** Mixed-rule fragments, metadata/schema/identity/applicability repairs, eight questions and six unmapped file requirements remain open. See [evidence](improvement/validator-02-evidence.json) and [implementation review](improvement/validator-02/README.md).

### Validator repair — Table 11 and authoritative reporting — 2026-10-19

The user resolved printed `Mail_ContentField` as **`Mail_Field`** and explicitly authorized validator improvements. That interpretation is recorded in authoritative `validator_rule_text.json` as user-approved, not as published errata. Eight unrelated interpretation/dependency questions remain; they do not block this authorized scoped repair. Broader preservation work remains deferred, and creator improvements follow validator work.

The validator now enforces Table 11 immediate children: required children must be immediate, and prohibited defined custom children fail even when required children also exist. New rule **220 `MAIL_CONTENTSET_CHILDREN`** permits zero or more immediate `Mail_Field` children and rejects the other defined custom types. Standard tags and unrelated extensions remain allowed. `Mail_FieldValue` requires an immediate `Mail_Field` parent with a `Mail_FieldName` child. Reading actual parent/child objects avoids conflating direct dictionaries with the same absent object identity.

Custom vocabulary remains optional for standard-only logical structure. Explicit `Document` elements can satisfy the existing multi-email Document count. Corrected rule descriptions now cite `validator_rule_text.json` in both text and attribution instead of retaining a historical DOCX source label.

Verification: **262 Python tests run: 261 pass, one optional PREMIS-schema skip.** All twelve normal/accessibility profile reports PASS PDF/A and EA-PDF, including recursive collection checks. The final focused 13 tests pass, including the last standard-only c/ci count-result guard added while the broad suite was running; that branch does not change the tested native custom-tag profile paths. Five frozen corpus candidates PASS PDF/A: one valid control PASSes EA-PDF, and four malformed candidates fail exactly their declared custom rules. The before-state validator accepted all five as EA-PDF PASS. Source and report hashes preserve the verification boundary; no Maven build, Java-source change or saved-sample regeneration is claimed.

The authoritative inventory remains **301 records**, now mapped to **220 rule codes**, with **eight open questions and six unmapped mandatory/conditional file records**. The repaired Table 11 row is mapped and verified. Scope ownership is now 138 custom, 11 delegated, 11 operational and 60 mixed. Runtime veraPDF routing, remaining schema/identity/applicability repairs and the other unmapped records remain unfinished.

[Evidence](improvement/validator-01-evidence.json), [corpus and reproduction](../tests/fixtures/eapdf/validator-table11/README.md), [authored regressions](../tests/test_validator_requirements_improvements.py), [current expert view](improvement/specification-audit/expert-review.html).

### Requirements and coverage continuation — 2026-10-18

`validator_rule_text.json` remains authoritative. All **219 existing codes** now have reviewed ownership dispositions against all five pinned veraPDF 1.31.18 PDF/A profiles: **137 custom, 11 delegated, 11 operational and 60 mixed** (including the separate ISO/TS declaration/structure split). The former 182 ownership candidates have dispositions; these are static coverage decisions, not proof that checking logic is implemented or executed. The generator verifies each claimed upstream reference against the exact retained XML and hashes.

The inventory now has **301 requirement/guidance records**, all 46 sections and all 19 tables. Four added records separate declaration schema, the registered identifier, optional claim data and per-PDF claim scope. Corrected the full Table 9 role-map description, creator/producer/creation-date meanings and exact GUID identity. Reviewed permitted XMP forms separately from general RDF, FOAF semantics, date types and byte metadata. No runtime or creator repair is claimed.

**Nine expert/dependency questions remain open**, down from 19 after 12 requirement/scope dispositions and two newly recorded representation questions. The [expert review document](improvement/specification-audit/expert-review.html) contains every rule, inventory record, covered/uncovered fragment, representation contract and question. [Seven mandatory/conditional file records](improvement/specification-audit/unmapped-file-requirements.json) still have no executed-rule mapping; recording them does not close implementation coverage. Generic structure integration and the cited PDF/A-4 draft edition remain explicit upstream boundaries.

Verification: **30 focused Python tests pass**, no failures/skips. Regeneration checks unique IDs, all rule links, sections/tables, pinned specification and five profile XML/index pairs, and every claimed upstream rule reference. All 219 rule articles and 795 internal document links resolve. The initial isolated-system-Python test attempts failed to import user-installed pikepdf; the retained successful run uses the available project dependencies. Validator checking logic and Java source hashes are unchanged from this continuation's before-state. No full application suite, Maven build or sample regeneration was needed for these requirements/document-generation changes.

The requirements gate remains open. Obtain dispositions for the nine explicit questions and finish the exact selected dependency/representation review before validator improvements, then creator improvements. B13 and broader preservation expansion remain deferred. No commit, external communication, deployment or external publication occurred.

[Authority](../validator_rule_text.json), [expert questions](improvement/specification-audit/expert-questions.json), [ownership evidence](improvement/verapdf-coverage/README.md), [current summary](improvement/specification-audit/summary.json).

Latest checkpoint: **2026-10-18**. The original handover below was verified on **2026-10-17** against the working tree, local Git history, source/artifact SHA-256 values, Maven test XML and Python/validation logs. This records the existing implementation; it is not a plan to rebuild it.

## 1. Restart checkpoint

### Current scope — validate only requirements not covered by veraPDF

The user narrowed the custom validator to requirements **not covered by veraPDF**. Keep `validator_rule_text.json` authoritative. Its per-rule `validation_scope` now records ownership and profile references. veraPDF owns covered PDF/A/XMP/structure requirements; the custom engine checks only uncovered EA-PDF assertions or uncovered fragments of mixed rules. Reader/viewer behavior is outside this PDF-file validator. Do not build parallel PDF/A or ISO/TS 32005 implementations.

Read-only inspection confirmed installed **veraPDF 1.31.18** and retained its exact five EA-PDF-eligible PDF/A profile XMLs and hashes. Thirteen existing checks have verified delegation mappings; fifteen stronger EA-PDF checks remain custom; eight are operational; ISO32005 declaration/structure coverage is split; 182 remaining rule overlaps are candidates still requiring review. These categories are not counts of newly completed validation checks.

Keep the stricter EA-PDF PDF-version rules, allowed PDF/A subset, required Info presence and Info/XMP crosswalk, EA-PDF keywords and blanket catalog PieceInfo prohibition. The old `PDFA_COVERED_RULES` display list incorrectly groups some of these with inherited PDF/A checks. It is not coverage authority. No corresponding Info/XMP crosswalk check was found in the pinned profiles, correcting the initial assumption that this was delegated.

The installed combined PDFUA-2-ISO32005 profile contains 1636 ISO32005 rules and 91 PDF/UA-2 rules. Do not impose PDF/UA just to validate the shared structure requirements. Pin and integrate an appropriate standalone upstream profile before claiming that coverage. Mere product capability is not a completed run. Required unavailable upstream checks cannot yield overall success.

**Runtime status:** this is the requirements ownership checkpoint. Current Python code still executes duplicate checks; suppression/routing remains pending after the scoped requirement mapping. No checking logic, CLI arguments or creator code was changed. Continue the uncovered-EA-PDF and exact profile-overlap audit, then implement delegation without suppressing the stronger EA-PDF rules. Earlier full inherited-standard requests are not prerequisites for duplicating covered checks.

[Authoritative rules](../validator_rule_text.json), [profile evidence](improvement/verapdf-coverage/README.md), [ownership counts](improvement/verapdf-coverage/ownership-summary.json).


### Authoritative rule requirements checkpoint — 2026-10-18

**`validator_rule_text.json` is the authoritative validation-rule document.** The user's clarification supersedes the earlier separate-inventory approach. Edit descriptions, requirement mappings and review dispositions there. The specification remains the source against which those requirements are checked; DOCX and generated audit files are supporting evidence only.

The JSON now has **219 uniquely numbered descriptions**, including four previously declared but undocumented check codes. It holds the **297 requirement/guidance review records**, all 46 numbered-section/19-table mappings and findings under `VALIDATION_INCOMPLETE.specification_review`. Those review records include guidance, software scope and unimplemented gaps; they are not extra executed rules. `scripts/build_eapdf_requirements_audit.py` now derives its views from that JSON and has no separate authored requirement list.

Eight description/documentation findings are corrected (including Sent, NumberAttachments, In-Reply-To, ISO/TS 32005 applicability, container navigation authority and source-comparison scope); one RF interpretation was resolved from the underlying standard. **19 findings remain open.** The creator/author-field interpretation is explicitly unresolved rather than equating the software required to record a field with the field's value. Missing normative dependencies and specification contradictions still prevent a full requirements-completeness claim.

Verification: the existing focused rule/completion/spec regressions pass **21 tests (12 + 9)**, no failures or skips. Traceability checks cover every declared rule code, unique numbers/IDs, all tables and all DOCX sections; the original 215-description JSON is retained. The intended runtime-input change is the authoritative rule-description JSON. Validator checking logic and creator source remain unchanged; B13 stays stopped. No full application suite, new samples, commit, deployment or publication is claimed.

Next: continue the remaining accuracy/authority/dependency review **in `validator_rule_text.json`**, regenerate supporting views, then improve validator logic, then the creator. Broader work remains deferred.

[Authoritative rule document](../validator_rule_text.json), [audit evidence](improvement/specification-audit/README.md), [findings view](improvement/specification-audit/findings.json), [integrity manifest](improvement/specification-audit/artifact-hashes.json).

**Current priority correction — 2026-10-18:** Audit the complete specification against validator requirements first, with reverse rule-to-spec traceability and explicit gaps. Improve the validator second and the creator third. Broader preservation work is deferred; previous preservation-first instructions and batch estimates are superseded. See [the governing plan](EA_PDF_IMPROVEMENT_PLAN.md) and [current work-log checkpoint](EA_PDF_WORK_LOG.md). B13 is stopped with incomplete final Python acceptance; retain its edits/evidence without marking it complete. The checkpoints below are historical.

**B12 — normalized input reconciliation verified (2026-10-18):** Current single-message, archive and collection writers freeze an immutable inventory before rendering and reconcile reopened final bytes. SHA-256/length come from input streams. GUID, attachment-owner and occurrence checks reject missing messages/assets, updated wrong payload checksums, swaps, reused annotations and missing named children. Isolated source omissions remain valid and native placeholders retain their occurrence checks.

Verification: **152 Java tests pass; 249 Python tests run, 248 pass and one optional PREMIS schema skip**, no failures/errors, in 587.656 seconds. Twelve normal/accessibility profile reports PASS including children. All 25 new corpus candidates and 21 child checks PASS PDF/A: nine inventory accepts and sixteen expected rejects. Nine rejected candidates still PASS PDF-only EA-PDF; seven fail their exact declared PDF-only rules. Six saved samples and collection sidecars were regenerated and verified.

Next: original reader/input enumeration and reconstruction provenance, followed by complete independent staged-output/sidecar acceptance. The captured boundary begins at writer entry; it cannot discover reader or dispatch losses before that point. Broader source/per-occurrence XMP associations, legacy helper integration, arbitrary mixed-profile imports and full standards coverage remain open. Thirty-one scoped register records and the unchanged 72-site N/A inventory remain partial. Earlier checkpoints are historical.

[B12 evidence](improvement/batch-12-latest-evidence.json); [work log](EA_PDF_WORK_LOG.md).

**B11 — final collection child acceptance verified (2026-10-18):** The creator now reopens each child embedded in a final c/ci output and recursively applies its existing consistency checks before atomic replacement. Missing child dates/page connections and corrupt native-attachment checksums reject the parent; existing destination bytes survive. Eight-level nesting and shared count/expanded-byte bounds reject unchecked excess.

Verification: **142 Java tests pass; 249 Python tests run, 248 pass and one optional PREMIS schema skip**, no failures/errors, in 566.697 seconds. All twelve normal/accessibility profile reports PASS independent PDF/A and mandatory EA-PDF, including children. All eight new corpus parents and their children PASS PDF/A: two valid collections PASS EA-PDF; six mutations fail exactly their declared child rule. Six saved samples and collection sidecars were regenerated and verified.

The recovered thread's user-approved priority is preservation assurance. Next: independently inventory expected message/attachment occurrences before rendering and compare their owners/bytes with final outputs. B11 checks a bounded creator subset; original-input completeness, broader ownership, integrated independent acceptance/sidecar gates and complete standards coverage remain open. Thirty scoped register records and the unchanged 72-site N/A inventory remain partial. Earlier checkpoints below are historical.

See [B11 evidence](improvement/batch-11-latest-evidence.json) and [the work log](EA_PDF_WORK_LOG.md).

**B10 — encoded-header failure handling verified (2026-10-18):** Malformed optional source headers no longer abort unrelated validation through HeaderParseError. Decoded comparisons reject supported complete B/Q payloads that require repair and record unavailable evidence; valid words still decode. UserProperties and observed occurrences share this boundary, while raw comparisons remain literal and unexpected engine errors remain visible. This adds no mandatory source-format rule; rule 107 and overall rendered-header coverage remain partial.

Verification: **249 Python tests run, 248 pass, one optional PREMIS schema skip**, no failures/errors, in 497.391 seconds. All six profiles PASS independent PDF/A and mandatory EA-PDF in normal and accessibility integrations, including children. Six new PDFs all PASS PDF/A and mandatory EA-PDF with exact expected attribute PASS/WARN/N/A and per-occurrence decoder outcomes. The B09 baseline reproduces the source-only VALIDATION_ERROR. Creator/JAR and saved samples match accepted hashes; B08’s 138 Java passes are historical, with no fresh Java build or sample regeneration.

Resume from [the work log](EA_PDF_WORK_LOG.md) and [B10 evidence](improvement/batch-10-latest-evidence.json). The register has 29 scoped records; 72 explicit N/A sites remain a partial inventory. Next: rendered-header enumeration across standard/untagged content, occurrence/aggregation semantics, broader source/asset ownership and remaining applicability predicates. Full contextual RFC 2047 semantics, custom-set interpretation/external review, ISO/TS 32005 access and complete standards/inclusion-rule coverage remain open. Earlier checkpoints below are historical.

**B09 — source-header occurrences verified (2026-10-18):** Header evidence now retains parsed source order, raw values and occurrence ordinals. Observed custom fields map to a repeated source occurrence only when one supported display value matches within the fixed message/source owner. Identical, unsupported and unmatched repeated values remain unavailable. Observed text survives optional source absence. Header recovery stays partial; unrelated MIME body defects remain separate. Rule 107 remains partial WARN because complete rendered-header enumeration is still open.

Verification: **240 Python tests run, 239 pass, one optional PREMIS schema skip**, no failures/errors, in 466.932 seconds. All six profiles PASS independent PDF/A and mandatory EA-PDF in normal and accessibility integrations, including children. Six new PDFs all PASS PDF/A: two expected EA-PDF PASS and four exact MESSAGE_SUBJECT_XMP failures for malformed duplicate-Subject probes, independently reproduced with B08. Creator/JAR and saved samples match B08 hashes; its 138 Java passes are historical, with no fresh Java build or sample regeneration.

Resume from [the work log](EA_PDF_WORK_LOG.md) and [B09 evidence](improvement/batch-09-latest-evidence.json). The register has 28 scoped records; 72 explicit N/A sites remain a partial inventory. Next: complete rendered-header enumeration across standard/untagged content, arbitrary occurrence/aggregation semantics, broader source/asset ownership and remaining source-dependent predicates. Custom-set semantics/external review, full ISO/TS 32005 access and complete standards/inclusion-rule coverage remain open. Earlier checkpoints below are historical.

**B08 — observed custom header inventory verified (2026-10-18):** Header diagnostics now decode text from each field’s actual page/Form MCID scope and compare it with the retained source selected through its enclosing message identity. Duplicate IDs, ambiguous owners, repeated headers and unsupported decoding stay unavailable. Known field pages outside the message’s DPart ranges are diagnostic differences. Rule 107 remains partial WARN because standard-tagged, untagged and unrecognized rendered headers are not enumerated.

Verification: **227 Python tests run, 226 pass, one optional PREMIS schema skip**, no failures/errors, in 459.097 seconds. Fresh offline Maven package: **138 Java tests pass**. All six profiles PASS independent PDF/A and mandatory EA-PDF in normal and accessibility integrations, including children. Six new PDFs all PASS PDF/A: five expected mandatory EA-PDF PASS and one exact missing-GUID FAIL. Saved samples and collection sidecars are unchanged by hash; no creator/CLI edits or sample regeneration.

Resume from [the work log](EA_PDF_WORK_LOG.md) and [B08 evidence](improvement/batch-08-latest-evidence.json). The register has 26 scoped records; 72 explicit N/A sites are inventoried without certifying every predicate. Next: complete rendered-header/source occurrence mapping, broader source/asset inventories and ownership assurance, and remaining source-dependent conditions. Custom-set semantics/external review, full ISO/TS 32005 access and complete standards/inclusion-rule coverage remain open. Earlier checkpoints below are historical.

**B07 — tagging recommendation evidence verified (2026-10-18):** A structure root or isolated field tag no longer proves complete coverage. Content Set recommendations inspect marked paint, artifacts, Form content, annotation references, MarkInfo and structure correspondence across declared DPart ranges as a conservative superset of custom sets. Header tagging is conditional on custom EA-PDF tags; observed field pairs remain partial WARN until a complete rendered-header inventory exists. Known valid non-body ranges now give explained N/A for body-media comparison; unknown/unresolved bodies remain incomplete.

Verification: **213 Python tests run, 212 pass, one optional PREMIS schema skip**, no failures/errors, in 393.575 seconds. All six profiles PASS independent PDF/A and mandatory EA-PDF in normal and accessibility integrations, including children. Six new PDFs all PASS PDF/A: four expected EA-PDF PASS, one exact two-rule FAIL and one intentional INCOMPLETE for unavailable Core Field text ownership. B05 creator/JAR and saved samples are unchanged by hash; its 138 Java passes are historical.

Resume from [the work log](EA_PDF_WORK_LOG.md) and [B07 evidence](improvement/batch-07-latest-evidence.json). The register has 24 scoped records; 72 explicit N/A call sites are inventoried without certifying all predicates. Next: source/rendered-header inventories, remaining source-dependent conditions and wider ownership assurance. Custom-set semantics/external review, full ISO/TS 32005 access and complete standards/inclusion-rule coverage remain open. Earlier checkpoints below are historical.

**B06 — recommendation applicability verified (2026-10-18):** Four source/policy recommendations now show unavailable WARN instead of unconditional N/A. Extraction exceptions and missing/duplicate page-analysis results produce unverified WARN and INCOMPLETE. Context assessed on only some pages cannot produce an all-pages text-context PASS. SHOULD findings retain recommendation strength, and mandatory failures remain separate.

Verification: **197 Python tests run, 196 pass, one optional PREMIS schema skip**, no failures/errors, in 397.921 seconds. All six profiles pass PDF/A and mandatory EA-PDF in both normal and accessibility integrations, including children; their unavailable recommendations remain visible. Six new assessment scenarios use two PDF/A-valid PDFs: two normal PASS and four fault-injected INCOMPLETE. B05 creator/JAR and saved examples are unchanged by hash; its 138 Java passes are historical.

Resume from [the work log](EA_PDF_WORK_LOG.md) and [B06 evidence](improvement/batch-06-latest-evidence.json). The register has 21 scoped records; 73 explicit N/A call sites are inventoried without claiming their predicates are all reviewed. Next: custom Content Set/header-tagging applicability, remaining source-dependent conditions and broader inventory/ownership assurance. Full ISO/TS 32005 access and complete applicable errata/inclusion-rule review remain open; public clause 7 corrections are recorded separately. Earlier checkpoints below are historical.

**B05 — effective UserProperties verified (2026-10-18):** ClassMap and direct attributes now share array-order resolution; direct A overrides C and the last P replaces the entire earlier property list. Revisions track updates independently of precedence. Creator updates copy shared attributes locally and preserve unrelated data. Malformed resolution remains visible as INCOMPLETE.

Verification: **138 Java passes; 188 Python tests run, 187 passes and one optional PREMIS schema skip**, no failures/errors. Eighteen new PDFs all pass PDF/A: ten legal cases PASS EA-PDF and eight mutations fail exactly their declared rules. Both normal and accessibility integrations pass all six profiles, including children. Six saved samples and collection PDF/XLSX sidecars were refreshed and verified.

Resume from [the work log](EA_PDF_WORK_LOG.md) and [B05 evidence](improvement/batch-05-latest-evidence.json). The register remains partial. Next: audit remaining explicit N/A recommendations and complete applicable standards/errata records, then wider metadata/source ownership checks. General namespace/attribute inheritance and full source-association assurance remain open. Earlier checkpoints below are historical.

**B04 — source-value recommendations verified (2026-10-18):** Declared mail attributes are now compared with their own inspectable source email. The check follows the enclosing Mail_Message and its XMP GUID before reusing existing source matching, so another email with the same Message-ID cannot satisfy a comparison merely by containing the same text. Raw/decoded fields, internal spaces, dates and counts have distinct comparisons. Changed values warn under the existing SHOULD recommendation; absent sources give explained N/A and ambiguous/unavailable comparisons warn.

Verification: **180 Python tests run: 179 pass, one optional PREMIS schema skip**, no failures/errors. Seventeen new multi-message PDFs all pass PDF/A and mandatory EA-PDF checks: eight source-recommendation PASS, eight expected WARN and one isolated N/A. All six profiles pass normal and accessibility integrations, including children. No Java/creator edits, rebuild or sample regeneration were required; the B01 creator JAR hash matches.

Resume from [the work log](EA_PDF_WORK_LOG.md) and [B04 evidence](improvement/batch-04-latest-evidence.json). Fourteen scoped requirements are documented. Next: attribute class maps, repeated owners and revisions, plus remaining explicit N/A/standards review. Broader source-association assurance and arbitrary header semantics remain open; the overall plan is incomplete. Earlier checkpoints below are historical.

**B03 — Mail_Sent PDF-date validation verified (2026-10-18):** Mail_Sent UserProperties now require a PDF date with valid syntax, calendar day, time and timezone ranges. Permitted shortened dates, legacy timezone apostrophes and decoded UTF-16 dates remain accepted. Raw email date text, XMP Sent checks and the tolerant document-date comparison parser stay separate.

Verification: **170 Python tests run: 169 pass, one optional PREMIS schema skip**, no failures/errors. The 25 new date PDFs have nine valid cases and sixteen exact expected failures; all 25 pass PDF/A. Both normal and accessibility integrations pass PDF/A and EA-PDF for all six profiles, including children. Creator code and saved examples are unchanged; the accepted B01 JAR was reused, with its hash checked. No new Java test run is claimed.

Resume from [the work log](EA_PDF_WORK_LOG.md) and [B03 evidence](improvement/batch-03-latest-evidence.json). Thirteen scoped requirements are documented; the overall plan remains in progress. Next: source-value comparison for UserProperties, remaining explicit N/A recommendations, then attribute-owner/class-map/revision review. Earlier checkpoints below are historical.

**B02 — strict field types and missing-data applicability verified (2026-10-18):** The validator now requires actual PDF strings for text/name fields and actual nonnegative integers for counts. Generic email header values allow text, numbers and Booleans only, on their permitted tags. Absent optional raw headers and absent embedded files/parameters have explicit N/A conditions; existing applicable checks remain required. Profile tests now reject INCOMPLETE child results when PDF/A passes.

Verification: **162 Python tests run: 161 pass, one optional PREMIS schema skip**. The new 27-file corpus has nine valid files and eighteen exact expected failures; all 27 pass PDF/A. All six profiles pass PDF/A and EA-PDF in both normal and accessibility integration runs. The creator was inspected and its accepted B01 JAR reused unchanged; Java was not rebuilt and saved examples were not regenerated for this validator-only batch.

Resume from [the work log](EA_PDF_WORK_LOG.md) and [B02 evidence](improvement/batch-02-latest-evidence.json). Twelve scoped requirement records are documented. Next: PDF-date syntax for `Mail_Sent`, then remaining applicability/source comparisons and attribute-owner/class-map/revision handling. The whole plan and standards register remain incomplete. Earlier checkpoints below are historical.

**B01 — first improvement batch verified (2026-10-18):** Resume from [the work log](EA_PDF_WORK_LOG.md). Fixed PDF attribute/root array traversal and creator attribute preservation; unreported rules now need explicit non-applicability or yield INCOMPLETE, and the web display preserves that distinction. Declared container attachment records receive Table 19 checks. Required Sent checks remain mandatory for mail profiles, while optional logical structure stays optional. A creator-derived corpus has three legal representations and four exact failing mutations, all PDF/A valid. The partial register contains eight scoped records; known Table 12 type/coercion defects and the wider explicit-N/A/standards review remain open.

Current verification: **134 Java passes; 151 Python run, 150 passes and one optional PREMIS schema skip**. All six regenerated primary samples, two original collection children and both PDF/A index sidecars pass. c/ci sidecars and m/mi spreadsheet embedding are preserved. Evidence, before/failed/final logs and generated fixtures are durable under `docs/improvement/`. No implementation permission is pending; continue priorities 1 and 2 from the work log. Older R14 counts below are historical.

**Implementation active — 2026-10-18:** Resume from [EA_PDF_WORK_LOG.md](EA_PDF_WORK_LOG.md). Batch 01 addresses arrays and explicit rule applicability while building the first requirement/fixture records. The detailed audit supersedes earlier planning-only notes.

**Next work — plan added 2026-10-18:** Follow [the prioritized improvement plan](EA_PDF_IMPROVEMENT_PLAN.md). Develop the validator, creator and fixtures together, starting with PDF array handling and explicit rule applicability. The plan records proposed work; implementation has not started. R14 verification below remains the last recorded full run.

**R14 — collection-only finding-aid sidecars complete:** The user resolved R13 by restoring sidecars only where ordinary embedded files are prohibited. `1c`/`1ci` now publish requested `_finding_aid.pdf` and `_index.xlsx` beside the collection. Neither is embedded in the parent, and the ordinary PDF/A-4 index sidecar has no embedded files or synthetic EML. Collection index pages remain front matter. `1m`/`1mi` retain XLSX embedding; single-message source/native-attachment handling is unchanged. The strict Java creator and Python validator are byte-identical to the R13 checkpoint; no conformance exception was introduced.

For collections, existing `embedPdfInCollections` requests a PDF sidecar, while `embedExcel` or `excelEnabled` requests an XLSX sidecar; `pdfEnabled` gates all optional finding aids. The two sidecars are independent. They use `OutputTransaction`, including the existing four-asset allowance when PREMIS is enabled at a one-message limit. Configuration/CLI names, defaults and debug output are preserved.

**Current verification:** Full Maven package passes **132 Java tests**, zero failures/errors/skips. Packaged Python discovery runs **140 tests: 139 passed, one optional PREMIS XSD test skipped** because `EAPDF_PREMIS_SCHEMA` is not configured. All **six freshly regenerated primary samples** pass independent PDF/A and EA-PDF validation; both original collection email children pass both checks. Both ordinary finding-aid PDF sidecars independently pass **PDF/A-4**, and both external XLSX files are valid workbooks. Structural probes confirm c/ci contain no auxiliary PDF/XLSX, sidecar PDFs contain no attachments, m/mi retain their embedded XLSX, and the single-message alias matches. `examples/` now has six primary PDFs plus four collection sidecars.

Current build: `/tmp/eapdf-collection-sidecars-20261017/build/`; logs: `/tmp/eapdf-collection-sidecars-{build,python,samples}.log`; reports: `/tmp/eapdf-collection-sidecars-20261017/{integration,samples}/`. [Current sidecar evidence](reimplementation/collection-sidecars-evidence.json) records source/JAR/sample hashes, exact suite totals and validation summaries. The former failing R13 review candidates were moved to `/tmp/eapdf-collection-sidecars-20261017/superseded-direct-xlsx-review/`. R13's output-policy question and default collection rejection are resolved; its earlier evidence remains historical. No work remains on this sidecar correction. External standards/human/live-account/platform/deployment acceptance requirements still apply. All older checkpoint records below are historical.

**Previous checkpoint — R12 collection finding aids/indexes embedded only:** Removed the four separate collection finding-aid PDF/XLSX files. Regeneration no longer extracts or publishes collection auxiliaries and deletes stale collection auxiliary sample paths after validation. `examples/` now contains **only six primary sample PDFs**, plus the existing single-message alias under `output/`. For c/ci, `excelEnabled` or `embedExcel` places the XLSX inside the root finding-aid EA-PDF and implies that PDF; no external finding-aid PDF or XLSX is created. `excelEnabled` remains an external XLSX option only for flat archives. The existing container PREMIS policy is separate and unchanged; default samples have it disabled and publish no sidecars.

**R12 verification (historical):** Maven package **131 Java tests** and packaged supervised/publication/packaging **four Python tests** pass, zero failures/errors/skips. The c/ci regression enables Excel with both other embedding flags disabled and verifies that only the collection PDF is published and its finding-aid PDF contains a valid XLSX. All **six regenerated primary PDF/A/EA-PDF checks** and **four recursive child checks** PASS, zero FAIL/INCOMPLETE. Current sample and alias hashes, nested ZIP checks, five changed source/test/script entries and JAR hashes: [embedded-only-index-evidence.json](reimplementation/embedded-only-index-evidence.json). The earlier full Python 140-test snapshots remain historical; the full Python suite was not rerun in this targeted correction.

Current CLI JAR: `/tmp/eapdf-embedded-only-20261017/build/emailtopdfa-1.4.0.jar`, SHA-256 `51765d29ebe1967e791b72624c43720d7b3f101d7412e09aa239474f11041e15`; GUI SHA-256 `b7c6dc79122da34ddf305f34e6d06312d9342949b9a3bdb6751c1213cd6ccb45`. Logs: `/tmp/eapdf-embedded-only-20261017-{build,python,samples}.log`; reports: `/tmp/eapdf-embedded-only-20261017/samples/`. Earlier extracted-copy descriptions and sample/build fingerprints below are historical and superseded.

**Previous sample refresh — superseded for auxiliary files:** Removed all 13 old generated sample files, then regenerated **six primary email-profile PDFs, four extracted finding-aid PDF/XLSX examples and the single-message alias**. Retired `examples/sample_facebook_1m.pdf` and `examples/sample_twitter_1m.pdf` because their generators/fixtures are absent. Fresh Maven package passes **130 Java tests**, zero failures/errors/skips. All **six primary PDF/A and EA-PDF checks** and **four recursive child checks** PASS; no FAIL/INCOMPLETE. Nested finding-aid placement, absence of a direct collection XLSX, generated provenance, exact extracted copies and alias equality were verified. All **67 source/config/script/test fingerprints are unchanged** from the previously verified implementation; the full Python suite was not rerun for this sample-only refresh.

**Current sample/build evidence:** [sample-refresh-evidence.json](reimplementation/sample-refresh-evidence.json) records fresh sample/JAR hashes, deleted-file inventory, exact backups and reports. Build and sample logs: `/tmp/eapdf-sample-refresh-20261017-jyft3964/{build,samples}.log`; reports: `/tmp/eapdf-sample-refresh-20261017-jyft3964/samples/`; old sample backups: `/tmp/eapdf-sample-refresh-20261017-jyft3964/removed-samples/`. Current CLI JAR `/tmp/eapdf-sample-refresh-20261017-jyft3964/build/emailtopdfa-1.4.0.jar` SHA-256 `ca4fc253b1187b461f5fcf1e4c21f8cf27ab829b6cef1887b6c172bce89df563`; GUI JAR SHA-256 `2b7d3b72b707a39cbf034cd1668275ee65e1ed2af9e8c433641960fabed1e090`. Earlier build/sample fingerprints below remain historical. External standards, human/accessibility, live account/platform, deployment and release-rights acceptance requirements remain open.

**Earlier README profile guide update:** The README now compares all six profiles by purpose, reading layout, retained source/native attachment bytes and exact local CLI selection. Verified against EA-PDF §3.2, `EmailToPDFA.runArchive`/`processRecords`, `PdfMailWriter.chooseEaPdfConformance`/`writeEaPdfArchive`, and `PdfCollectionSupport`. Explicitly documents the existing per-message PST default of `1si`, the two-message minimum for local m/mi archives, current flat collection groups, generated finding-aid provenance and isolation versus redaction. The README Testing section was subsequently shortened to dependency setup, Java/Python test commands and an optional six-profile integration command, with links to detailed verification/coverage. This update changes documentation only; application code, samples and the completed 130/140-test evidence are unchanged. No build or sample regeneration was needed. Referenced `/tmp` acceptance artifacts are ephemeral; the durable evidence records remain the handover source if those paths have expired.

### Earlier R11 checkpoint — nested finding aid complete and verified

The requested collection arrangement is implemented: root `_finding_aid.pdf` is an EA-PDF; its XLSX is a native attachment inside it, never a direct container member. A PDF/mail-1s generated index message provides truthful source provenance: its visible body and source header explicitly identify it as software-generated, not original correspondence. Its sender is the reserved `finding-aid@eapdf.invalid`; no mail is sent. A ci container can contain this s auxiliary while original email children remain isolated (§7.6 permits mixed child profiles).

`pdfEnabled` controls optional finding aids. `embedPdfInCollections` requests the root finding-aid PDF; `embedExcel` includes the XLSX inside it and implies the containing PDF. For collections, `excelEnabled` also embeds the XLSX and implies the containing PDF. It creates an external XLSX only for flat archives. Container PREMIS stays a sidecar. Collection front matter retains the visible index. The subsequent R12 correction removes all separate collection finding-aid PDF/XLSX examples.

Files changed in this batch: `PdfFindingAidSupport.java` (`buildEmbeddedFindingAid` and explicit external XLSX policy), `PdfMailWriter.java` (root embedding), collection/additional-rule Java tests, generated-profile/supervised Python tests, sample regeneration script, rule 218 explanatory text, all six primary samples, four extracted auxiliary examples, the existing single-message alias and relevant user/engineering documentation. The generated index is separate from the original records, so input/message run statistics retain their original meaning. It adds one independently validated EA-PDF child when enabled and consumes the existing source/body/page/output budgets.

**Fresh verification:** Maven package passes **130 Java tests**; complete packaged discovery passes **140 Python tests in 207.543 seconds**, zero failures/errors/skips. Acceptance has **30 top-level PDF/A PASS**, **28 recursive child PASS**, **28 overall PASS/two expected mutation FAILs**, zero INCOMPLETE. All six regenerated samples PASS with **four recursive child PASS**. Combined new evidence: **36 top-level PDF/A**, **32 child PDF/A**, **10 PREMIS XSD** passes; **34 overall PASS/two expected FAILs**, zero INCOMPLETE. Structural probes confirm no XLSX directly in either collection, a root finding-aid PDF, preserved generated-source label, byte-identical extracted PDF/XLSX examples and a matching single-message alias.

Current JARs under `/tmp/eapdf-nested-finding-aid-20261017/build/`:

- CLI `emailtopdfa-1.4.0.jar`: SHA-256 `c7da90d28d466168586f4a4208bf5c540dc24d0797319018817d3481bdcc06e7`.
- GUI `emailtopdfa-1.4.0-gui.jar`: SHA-256 `1ffe736d38e5ef8ce881dad2fbc9b384c72b00488ca4d0f7dcd8467a2da6cefd`.

Logs: `/tmp/eapdf-nested-finding-aid-{build,acceptance,samples}.log`. Reports: `/tmp/eapdf-nested-finding-aid-20261017/{acceptance,samples}`. Durable [nested-finding-aid-evidence.json](reimplementation/nested-finding-aid-evidence.json) fingerprints all 67 current source entries, both JARs, ten primary/auxiliary examples and the alias. Exactly eight source/config/script/test entries differ from the preceding R10 snapshot. The rule count remains 219, with 215 explanatory JSON entries; validation was not relaxed. Prior R10 evidence below is historical; do not treat its JAR/sample hashes as the current build.


The three confirmed missing checks requested in the resumed thread are implemented in the Python validator and Java creation gate, their rule text is updated, and all six supported email-profile samples are regenerated. Rule 96 already checked array cardinality; its wording and regressions now make that requirement explicit. See [unfixed.md](../unfixed.md) for the active repair ledger and [fixed.md](../fixed.md) for chronological repairs.

**Documentation-phase boundary:** This handover was prepared without changing application code. Existing source and sample hashes were checked against `reimplementation/additional-rules-evidence.json`: all 67 source entries, 10 primary/sidecar samples and both packaged JARs matched. Tests were not rerun merely to write this document; their actual logs/XML were inspected. Fresh suites were subsequently run for R10, as recorded below. A source-level publication-limit question discovered during the audit was reserved for development after this documentation checkpoint (§7).

Local branch `main` is at `0b08414534c4841c03183c767c1e4ad2377bf9ad`; cached `origin/main` has the same commit (ahead/behind 0/0; no remote refresh performed). HEAD is “Remove unused PDF launch helpers,” dated 2026-03-31. Earlier commits include message-header/attachment-marker refinement (`b9094ec`), removal of page attachment icons (`4482f63`), and the commit titled “Release 1.3.2 with monthly archive logging improvements” (`6bfd7c7`). **HEAD's actual VERSION file says 1.3.0**, despite that commit title. Current working-tree VERSION and Maven version say **1.4.0**. Git commit titles are not evidence of the current release contents.

Before this documentation update there were **161 individual changed/untracked paths: 67 tracked modifications/deletions and 94 untracked files; nothing staged**. These changes predate this documentation phase. Git cannot identify their authors or reconstruct their conversational history. Do not discard, restore, stage or commit them indiscriminately. The complete pre-documentation path inventory is in §9. `target/` contents are not the current verified release.

### Earlier R10 continuation after the documentation checkpoint — complete

The documentation phase changed only this new document and `unfixed.md`, verified by comparing baseline SHA-256 values and expanded Git status. Application development then resumed and **R10 was reproduced, repaired and fully regression tested**. `OutputTransaction.publish` now allows four published assets per permitted input rather than three, accommodating the collection PDF and all three optional sidecars. It still rejects five at a one-message limit before publication; byte/storage/hash/path checks remain in place.

The resumed application/test edits are exactly `src/main/java/eapdf/OutputTransaction.java`, `src/test/java/eapdf/SupervisedConversionTests.java` and `tests/test_supervised_conversion.py`. Documentation edits also update `unfixed.md`, `fixed.md` and this file, and add [development-status-evidence.json](reimplementation/development-status-evidence.json). The original working-tree inventory remains below; the two new documentation/evidence files bring the final expanded path count to **163** (67 tracked changes/deletions, 96 untracked; nothing staged).

**R10 verification at that checkpoint:** Maven package passes **130 Java tests**; packaged Python discovery passes **140 tests in 228.331 seconds**; zero failures/errors/skips. All **30 top-level** and **24 recursive child** PDF/A checks pass; overall results are **28 PASS and two intentional EA-PDF mutation FAILs**, zero INCOMPLETE. All **10 PREMIS XSD checks** pass. The actual c/ci CLI regression publishes four nonempty files at `EAPDF_MAX_MESSAGES=1`.

R10 artifacts, under `/tmp/eapdf-r10-20261017/build/`:

- CLI `emailtopdfa-1.4.0.jar`: SHA-256 `3e7540186b6328127813812c2c88543b26b60fb165cb0b99b80245b3aaeb17dd`.
- GUI `emailtopdfa-1.4.0-gui.jar`: SHA-256 `0e02cdf660e851689116a96e89ddf58652b32f31908ffefcfa3032baf19bf2f5`.

Logs: `/tmp/eapdf-r10-20261017/{before.log,build.log,acceptance.log}`. Reports: `/tmp/eapdf-r10-20261017/acceptance/`. The R10 durable evidence fingerprints all 67 source entries at that checkpoint, both JARs and all ten earlier samples. Only the three listed application/test files differ from the earlier source snapshot. **Samples were not regenerated a second time for this publication-count-only repair**; their hashes remain unchanged, and current acceptance freshly generates/validates all six profiles. The sample-generation evidence and source-set probes in §5 remain valid dated records, not additional checks rerun for R10. Existing Maven shade overlap warnings and the observed Log4j missing-provider diagnostic remain; they did not fail the exercised build/conformance checks.

### Working rules

- Actual Java sources are under `src/main/java/eapdf/`, despite the shorter paths in the supplied AGENTS instructions.
- Preserve public CLI arguments, debug output and unrelated working-tree changes. Make targeted edits.
- Do not modify `target/` or existing debug directories. Use the Maven build-directory override below.
- Keep this document and `unfixed.md` current after development; add verified repairs to `fixed.md`.
- Do not infer exhaustive standards conformance from the Java creation gate, this project's validator, or PDF/A results.

## 2. Implemented product and architecture

The application converts **EML, MSG, MBOX, PST and Gmail messages** into EA-PDF profiles **1s, 1si, 1m, 1mi, 1c and 1ci**. It includes local CLI conversion, filename-based monthly grouping, a Swing GUI, a Python EA-PDF validator and a Flask validation/generation service. Facebook/Twitter source classes, scrapers and their tests are deleted in the current working tree; their obsolete sample PDFs were removed during the explicit sample refresh.

| Files/classes | Current responsibility and important decisions |
| --- | --- |
| `EmailToPDFA`, `EapdfGui` | CLI/profile dispatch, bounded parallel preparation with deterministic message IDs, desktop controls and supervised worker launch/cancellation. GUI accessibility remains labelled partial. |
| `EmlReader`, `MsgReader`, `PstReader`, `MboxReader`, `MboxParser` | Source normalization, MIME/body selection, attachments and nested attached messages. MBOX processing streams records and preserves raw message content while handling separator escaping. |
| `GmailDownloadToPdf`, `GmailResumeStore` | Gmail export plus opt-in persistent staging, per-message metadata/fixity and recovery/retry of interrupted downloads/conversions. Small live-account creation and resume acceptance passes at C95; large-mailbox and broader platform checks remain outstanding. |
| `MonthlyFilenameArchiveToPdf` | Filename-date grouping into monthly outputs, including single-message months, profile selection and reporting. |
| `Models` | Package-private `EmailRecord`, `AssociatedFile`, `MailAgent`, `BodyChoice` model. Explicit `sourceSetMember` identifies original companion assets. This is the real model; `reimplementation/message.schema.json` is a proposed replacement interchange format, not a current API. |
| `PayloadStore`, `Common` | Staged source/body/attachment payloads, bounded preparation, IDs/dates/address helpers, atomic file writes and job accounting. Avoid keeping the whole corpus as payload byte arrays. |
| `PdfMailWriter` | HTML/email rendering, visible headers, source and attachment relationships, XMP/profile metadata, PDF navigation and orchestration of archive/collection output. |
| `PdfCollectionSupport`, `PdfFindingAidSupport` | Collection folders/items, staged child PDFs, finding-aid front matter and a root finding-aid EA-PDF containing its XLSX; external XLSX is available only for flat archives. Collection item/navigation interoperability still needs aware-reader review. |
| `PdfDocumentParts` | DPart hierarchy, page bounds, message GUIDs and content-set associations. |
| `SemanticStructure` | Preserves renderer semantics; builds EA roles/namespaces, MCID/MCR/OBJR/ParentTree correspondence and annotation roles. Deliberately omits PDF/UA identification until full acceptance. |
| `EmailStyles`, `FontSupport` | Scoped supported source CSS, reduced-rendering warnings for unsupported/external CSS, embedded fonts and bounded fallback coverage/cache. This does not certify arbitrary HTML fidelity or complex-script shaping. |
| `PremisBuilder` | PREMIS 3 ordering/structure and truthful preparation/provenance events; official schema regressions. |
| `PdfCreationValidator` | Before-publication checks of selected creator invariants: metadata, navigation, DParts, names/folders, attachment/checksum placement, source sets, collection contents, XMP encoding and Document-root shape. It is not the independent full conformance validator. |
| `ProcessingLimits`, `ConversionSupervisor`, `OutputTransaction` | Resource budgets, disposable worker processes, staged output manifests, checksums, recoverable publication and interrupted-publication recovery. See §4. |
| `validate_eapdf.py`, `validator_rule_text.json` | EA-PDF rule registry/diagnostics, recursive child validation, source comparisons and independent veraPDF orchestration. The registry has 219 rule codes and JSON has 215 entries; these counts describe different structures and should not be equated without inspecting operational/fallback codes. |
| `validator_web.py`, `deploy/` | Bounded web jobs, authentication/host/origin controls and deployment/cleanup templates. Templates have not been installed or validated as a live deployment. |

Java targets **21**. Current dependency declarations include PDFBox/XMPBox 3.0.8, OpenHTMLtoPDF 1.1.97, Jakarta Mail 2.1.5/Angus 2.0.5, java-libpst 0.9.3, Outlook parser 1.16.2, POI 5.5.1 and Jsoup 1.23.2. Maven packages separate CLI and GUI shaded JARs, preserves service providers and notices, filters the release version resource, and forces a fresh thin JAR before shading to avoid shading a previously shaded artifact. Font notices are included under the project notice resources. Notice packaging does not settle release-specific licensing/branding permission.

Python web requirements are bounded to Flask 3.x, gunicorn 26.x, pikepdf 10.x, pypdf 6.x and Werkzeug 3.x as specified in `requirements-web.txt`; PREMIS schema tests additionally need `lxml`. The verified environment used Python 3.12.3, pikepdf 10.17.0, pypdf 6.21.0, Flask 3.1.3, Werkzeug 3.1.9 and lxml 6.1.3.

### Preservation and metadata policy

Current generated output is **PDF 2.0 / PDF/A-4f**. Legacy Java method names mentioning A3 are not an assertion that current output uses PDF/A-3. The validator also handles allowed older PDF/A profiles, including the new UTF-8 requirement for PDF/A-3 XMP.

Nonisolated EML/MSG conversion retains original message assets. PST/MBOX/Gmail readers export/reconstruct individual RFC 822 messages before conversion, as EA-PDF §7.2 permits. They do **not** retain the original mailbox container or promise byte-for-byte container reconstruction; preserve original containers separately when required. Descriptions/PREMIS identify derivation rather than claiming original container bytes.

Native message attachments are directly associated through file-attachment annotations instead of being redundantly listed as catalog attachments. Source assets and contained EA-PDF children use their appropriate catalog associations. Isolated profiles omit source/native attachment bytes and report explicit omission placeholders; rendered inline images remain part of the visual message. These are deliberate profile/preservation decisions, not a claim of lossless isolation.

Structured SMTP address metadata is emitted only when representable and valid. Exchange/X.500 or malformed mailbox strings are preserved exactly in the corresponding raw Core Fields, without invented FOAF mailboxes. Python/Java handling of quoted/apostrophe-domain recipients was reconciled and regressed after the real PST sample exposed a parser disagreement; valid apostrophes in local parts remain allowed. Invalid/unverifiable source agreement can still yield FAIL/INCOMPLETE.

### Completed additional rules

| Rule | Verification and creation behavior |
| --- | --- |
| **217 `SOURCE_FILE_SET`** | EA-PDF §§9.1/9.4: declared original multi-file assets require valid primary Source RF entries or matching catalog Supplement companions. RF keys need corresponding EF streams and valid filename/EmbeddedFile pairs. Explicit normalized companions are checked upstream. Independent Source messages are not automatically one file set. |
| **218 `COLLECTION_CONTENT_TYPES`** | EA-PDF §7.6: contained files in c/ci must be original source assets or EA-PDF children. An arbitrary file does not become legal merely because it lacks a collection-item dictionary. The requested finding-aid EA-PDF now embeds at the root and contains the XLSX. External XLSX is available only for flat archives; container PREMIS keeps its separate sidecar policy. Existing config names are preserved. |
| **219 `XMP_UTF8`** | EA-PDF §7.1.1: checks actual metadata bytes and XML encoding declarations throughout document/object XMP, including PDF/A-3. Parsing successfully is insufficient. Creation checks mirror the restriction. |
| **96 `STRUCTTREE_ROOT_DOCUMENT`** | EA-PDF §9.7: root K must be a Document dictionary or an array containing exactly one Document dictionary. This restriction already existed; wording, creation checks and explicit invalid-array tests now preserve it. |

Python `validate`, `validate_pdf` and `validate_one` accept an optional `source_file_sets` mapping from primary PDF filename to required companion filenames. CLI/web do not expose this inventory input. Without original inventory or an in-PDF declaration, wholly omitted companions are unknowable: the rule returns N/A when no set is identified, rather than claiming an original asset was complete. Readers do not add a new multi-file input format or CLI switch.

## 3. Completed repairs and regression scope

`unfixed.md` R1–R7, R9a and R9b are repaired and regression tested: image-only bodies retain referenced source images; filename/schema/folder collisions are checked; required navigation and populated metadata have independent negative mutations; body MIME parameters survive; PREMIS schema/order is valid; PREMIS auxiliaries do not masquerade as email attachments; missing-alt warnings and explicit decorative alternatives behave distinctly; derivation policy is documented; explicit missing configs fail and config defaults agree.

R8 and R9c have implementation and automated acceptance for the stated contracts, with external acceptance still open. Collection children must remain in the worker's internal workspace rather than being redirected as published outputs; the repaired workspace exclusion is regressed. Multi-output rollback, recovery journals, process timeout/termination, aggregate budgets, Gmail resume integrity, web authorization controls and artifact service/notice/font contents have regression coverage. Preserve these implementations instead of restarting the older historical repair plan.

## 4. Execution, recovery and deployment contracts

The public local/Gmail/monthly paths launch supervised worker JVMs; GUI launches use the same process boundary. A private worker workspace contains temporary assets, staged published outputs, ownership identity and manifest. Worker identity includes PID and process start time, avoiding cleanup based only on a reused PID. Ordinary output/debug logging is preserved.

Defaults include worker heap at most 512 MiB (bounded by supervisor heap), direct memory 128 MiB, metaspace 256 MiB, code cache 128 MiB and up to four processors. Linux uses `/usr/bin/prlimit`: default address-space cap 4 GiB, CPU 600 s and bounded file size. Default monitored worker wall time is 600 s, private storage 2 GiB and file count 100,000. Message/input count is 10,000; per-message byte, metadata, body and image limits are additionally heap-aware. Defaults include 10,000 pages per PDF, 20,000 aggregate pages, 64 million aggregate image pixels, 16 MiB per font and 1 GiB output bytes. Consult `ProcessingLimits` and [conversion operations](CONVERSION_OPERATIONS.md) for exact settings; positive configuration values are required.

**Limits have scope:** the deadline monitor bounds worker execution; synchronous publication follows the monitor and is not a strict end-to-end blocked-I/O deadline. Temporary-storage accounting is sampled, not an OS disk quota. Native processing is not exactly measured by image counters. Process limits are not a filesystem/network security sandbox. Strict deployments need an external watchdog, quotas and an isolated service identity/container. Direct library calls without the supervisor/active transaction have a narrower contract.

Published outputs remain staged until the worker succeeds. The supervisor validates manifest paths, sizes, SHA-256, unique destinations and byte budgets; prepares sibling files on destination filesystems; records recovery intent before replacements; rolls back failed publication; and retains recovery material if restoration fails. Incomplete journals restore old outputs; committed journals retain new outputs. Logs are outside output rollback. This is recoverable multi-file publication, not instantaneous all-file visibility, cross-filesystem ACID or a full power-loss durability guarantee. Serialize jobs writing the same destinations.

Web authentication is enabled by `VALIDATOR_REQUIRE_AUTH` or an auth-file setting; **a bare Flask launch is not authenticated by default**. The supplied service enables required auth and fails closed for absent/malformed credentials. Host restrictions need their configured allowlist. Origin/referrer/fetch-site checks guard unsafe requests. The template supports one trusted team and does not provide per-user output ownership. Nginx TLS/HSTS/rate-limit settings need real certificates and deployment testing. Cleanup units exist but their installation/retention behavior requires deployment acceptance.

## 5. Pre-documentation acceptance and sample evidence

The following is the completed additional-rules acceptance snapshot, independently rechecked during documentation by inspecting actual logs/XML and matching then-current source/sample/JAR hashes. These suites preceded the documentation phase; the later R10 verification and current JARs are recorded in §1. Do not treat the older source/JAR hashes as current after R10.

| Check | Actual result and evidence |
| --- | --- |
| Maven package / Surefire | **129 tests, 0 failures/errors/skips**; `final-build/surefire-reports/TEST-*.xml`, `/tmp/eapdf-additional-rules-final-build.log`. |
| Packaged Python discovery | **139 tests, 0 failures/errors/skips**, 211.024 seconds; `/tmp/eapdf-additional-rules-verified-acceptance.log`. There are 16 Python test modules. |
| Main integration reports | 30 top-level PDF/A PASS; 28 overall PASS and two deliberate EA-PDF mutation FAILs; 24 recursively checked child PDFs PASS. |
| Regenerated samples | All six primary PDFs PASS PDF/A and automated EA-PDF; two recursive child checks PASS. Four collection auxiliary sidecars regenerated. |
| File-set probes | Six top-level PDF/A PASS; four positive PASS and two deliberately missing-companion FAILs; three child checks PASS. Supplement and RF routes are both exercised. |
| Combined independent checks | **42 top-level PDF/A PASS, 29 recursive child PASS, 38 overall PASS/four expected FAIL, zero INCOMPLETE**. Negative mutations remaining PDF/A-valid demonstrate independent EA-PDF detection. |
| Official PREMIS 3 XSD | **10** current sidecar/embedded/collection-child schema checks PASS. The old 11-check count belongs to the earlier pre-sidecar-policy snapshot. |
| Repeated packaging | Earlier follow-up verifies repeated packaging retains executable/service/notice contents; generated build-date comments differ. This was not freshly repeated during the documentation audit. |

The earlier additional-rules artifacts remain in `/tmp/eapdf-additional-rules-20261017/final-build/`:

- CLI `emailtopdfa-1.4.0.jar`: `d0c121fdcd5f6e51bdc94c591678968e66b6a322bbcf99fa9a783e99bf9eec18`.
- GUI `emailtopdfa-1.4.0-gui.jar`: `ebc234acb1361521a5f52da54504ec6a149bc20b3b5026b1200415dde4b30c7f`.

Report directories: `/tmp/eapdf-additional-rules-20261017/{verified-acceptance,verified-samples,source-set-probe}`. Sample log: `/tmp/eapdf-additional-rules-verified-samples.log`. Official schema: `/tmp/eapdf-review-20261017/premis.xsd`, SHA-256 `03b8a77a20b32b882ad799e12262671d07ad18210c60233f4e613a1289491cba`.

The durable [additional-rules evidence](reimplementation/additional-rules-evidence.json) records the earlier exact artifacts, source/sample hashes, reports, mutations and remaining boundaries; the later R10 snapshot is [development-status-evidence.json](reimplementation/development-status-evidence.json) and the current nested-finding-aid snapshot is [nested-finding-aid-evidence.json](reimplementation/nested-finding-aid-evidence.json). Earlier `review-evidence.json` (97 Java/112 Python), `repair-evidence.json` (114/123), and `followup-evidence.json` (124/130) are historical snapshots, not current counts. `/tmp` artifacts can disappear: archive source/dependencies, standards, logs, reports and generated output in a release evidence bundle before relying on them for long-term acceptance.

### Samples and scope

`scripts/regenerate_email_samples.py` generates and independently validates before replacing the six `examples/sample_1*.pdf` primary outputs. 1s/1si/1c/1ci use `in/email/test3.eml`; 1m/1mi use `in/email/test.pst`. Collection `_finding_aid.pdf` and `_index.xlsx` are embedded only; regeneration does not write extracted copies into `examples/` and removes any stale collection auxiliary paths. The embedded finding-aid PDF is a standalone EA-PDF describing an explicitly generated index message. Existing `output/sample_1s.pdf` is a byte-identical alias of the regenerated 1s example. Legacy Facebook/Twitter sample PDFs were explicitly removed during the requested cleanup because their generators/fixtures are absent; unrelated imported/saved PDFs were not changed. Historical reports in `VALIDATION_RESULTS.md` do not describe newly regenerated output automatically.

## 6. Reproduction and verification still required

Run from the repository root with Java 21, Maven and the validator dependencies available. Use a fresh build/report path; avoid inherited Python import overrides. Offline Maven works only while the required artifacts are cached.

```sh
mvn -o -Deapdf.build.directory=/tmp/eapdf-next-build package
env -u PYTHONPATH PYTHONNOUSERSITE=1 \
  EAPDF_GENERATOR_CLASSPATH=/tmp/eapdf-next-build/emailtopdfa-1.4.0.jar \
  EAPDF_PREMIS_SCHEMA=/tmp/eapdf-review-20261017/premis.xsd \
  EAPDF_INTEGRATION_REPORT_DIR=/tmp/eapdf-next-acceptance \
  /tmp/eapdf-review-20261017/requirements-venv/bin/python -B -m unittest discover -s tests -v
env -u PYTHONPATH PYTHONNOUSERSITE=1 \
  EAPDF_GENERATOR_CLASSPATH=/tmp/eapdf-next-build/emailtopdfa-1.4.0.jar \
  EAPDF_SAMPLE_REPORT_DIR=/tmp/eapdf-next-samples \
  /tmp/eapdf-review-20261017/requirements-venv/bin/python -B scripts/regenerate_email_samples.py
```

The last command intentionally updates approved samples after validation. Recreate the venv/schema paths if missing; install the declared requirements plus `lxml`. Independent PDF/A needs the configured veraPDF service. The local-only validator cannot substitute for it: skipped/missing independent checks mean INCOMPLETE. A Docker daemon must be able to mount the input workspace; host `/tmp` paths may not be shared with it. A mount error is an infrastructure error, not proof of PDF nonconformance. Use repository-local temporary probe input when required and store reports under `/tmp`.

`reimplementation/review_probes.py` is an observational probe runner: a successful process exit does not mean every observation passed acceptance. Older reimplementation chapters describe pre-repair findings/proposed designs; use `09-repair-follow-up.md`, current source and the latest evidence to resolve contradictions.

Still required: complete normative clause coverage; representative complex-script/source-fidelity and reading-order review; meaningful image alternatives; assistive technology and aware-reader testing; broader Gmail mailbox acceptance beyond the C95 live creation/resume check; interactive GUI cancellation; non-Linux resource enforcement; real TLS/auth/retention/quota/watchdog/publication deployment; release-specific legal/branding review. None is established by the automated counts above.

## 7. Open defects, questions and prioritized next steps

| Priority / ledger reference | State and next action |
| --- | --- |
| **P0 — continue reviewed coverage/ownership work** | The confirmed V14/V15 repairs are accepted. Continue from the current work log against authoritative requirements; retain the eleven expert/dependency questions and broader owner/occurrence boundaries. Earlier array/applicability and first-fixture work is historical. See [the work order](EA_PDF_IMPROVEMENT_PLAN.md). |
| **R14 — collection sidecars complete** | c/ci finding-aid PDF/XLSX are separate sidecars; index PDFs contain no attachments or synthetic email. m/mi retain XLSX embedding. Full 132 Java / 140 Python run (one optional schema skip), six primary samples and two PDF/A sidecars verified. No output-policy question remains; R11–R13 arrangements are superseded. |
| **R10 — fixed and verified after documentation** | Old packaged build reproduced a one-message c job failing before publication with `Too many output manifest entries`. `OutputTransaction` now permits `messages()*4` assets. Java accepts four/rejects five; packaged Python exercises c/ci with all three sidecars. Fresh 130/140-test suites pass; see §1 and the new evidence. No further implementation task remains for R10. |
| **P1 — R9c / complete ISO/TS 32005 coverage** | Obtain licensed applicable full normative text and build a clause-to-positive/negative-fixture register. No complete local copy exists. Local references include EA-PDF v1, PDF 2.0, PDF/A-1/-2/-4 and PDF/UA-1; these do not replace the missing document. Prior publisher spreadsheet access returned 403. Do not claim complete-standard verification. |
| **P1 — issue 34 and R9c accessibility/fidelity** | Preserve partial accessibility status and omitted PDF/UA identifier. Test meaningful alt text, complex scripts, reading order, assistive technology and EA-PDF-aware readers with representative corpora. Selected structure/PDF/A or single-fixture PDF/UA findings are insufficient. |
| **P1 before production — R8/R9c operational acceptance** | Provision real service identity/TLS/auth; enforce quotas and external blocked-I/O watchdogs; serialize destinations; test crash/storage/recovery scenarios in deployment. Publication is recoverable and limits have the scope in §4. Review release licensing/relinking/branding rights. |
| **P2 — R9c account/platform acceptance** | C95 actual Gmail creation/resume passes on the small authorized account sample. Broader mailbox runs, GUI cancellation on supported desktops and native enforcement beyond Linux remain open. |
| **P2 — evidence preservation/release** | Archive the hash-matched source, both JARs, reports/logs/schema and regenerated samples outside ephemeral `/tmp`; review the entire dirty tree before staging. Resolve intentional input moves, deleted social features and untracked IDE/imported output disposition. No commit/deployment has been authorized by this audit. |
| **Conditional — rule 217 inventory** | Decide whether an application needs caller-provided source-set inventory through CLI/web/readers. No such interface change is currently requested; missing undeclared original companions cannot be inferred from PDFs. |

No unimplemented rule remains from the user's four-item validation list. R10 was reproduced after this documentation checkpoint, repaired and verified. Prioritize the remaining external standards, accessibility and deployment acceptance before release. External acceptance questions remain open after this repair. Do not repeat completed rule or isolation repairs.

## 8. Documentation and evidence map

- [unfixed.md](../unfixed.md): active repair IDs and remaining gates; current continuation authority.
- [fixed.md](../fixed.md): completed repair batches, rationale and regressions; counters there are chronological.
- [CONVERSION_OPERATIONS.md](CONVERSION_OPERATIONS.md): resource settings, process/storage/publication/recovery contract.
- [GMAIL.md](GMAIL.md), [DEPLOY_WEB.md](DEPLOY_WEB.md), [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md): account staging, deployment and distribution details.
- [VALIDATOR_COVERAGE.md](VALIDATOR_COVERAGE.md), [CREATOR_COVERAGE_REVIEW.md](CREATOR_COVERAGE_REVIEW.md): rule map and creator review with historical findings/follow-ups.
- [reimplementation/09-repair-follow-up.md](reimplementation/09-repair-follow-up.md): implemented follow-up contracts; earlier chapters include historical/proposed behavior.
- [reimplementation/nested-finding-aid-evidence.json](reimplementation/nested-finding-aid-evidence.json): current nesting, acceptance, source/artifact/sample fingerprint.
- [reimplementation/development-status-evidence.json](reimplementation/development-status-evidence.json): earlier R10 acceptance/source/artifact fingerprint.
- [reimplementation/additional-rules-evidence.json](reimplementation/additional-rules-evidence.json): earlier rule/sample acceptance fingerprint; preserve historical JSON snapshots as dated evidence.

## 9. Complete pre-documentation working-tree inventory

This is the expanded `git status --porcelain=v1 --untracked-files=all` inventory at the start of the audit, excluding subsequent edits to this status document and ledger. `M` means modified tracked file, `D` deleted tracked file, `??` untracked. Entries list files, not implied approval to commit them. The architecture table explains production class responsibilities; this inventory captures all other configuration, tests, samples, input moves, notices, IDE files and documentation as well. No staged changes existed.

```text
 M README.md
 M THIRD_PARTY_NOTICES.md
 M VERSION
 M config/README.md
 M config/eapdf_config.json
 D config/metadata_map.csv
 M deploy/nginx-thinkbox.digital.conf
 M deploy/validator-web.service
 M docs/CHANGELOG.md
 M docs/DEPLOY_WEB.md
 M docs/FEATURES.md
 M docs/GMAIL.md
 M docs/PRIVATE_GITHUB.md
 M docs/THIRD_PARTY_LICENSES.md
 M docs/VALIDATOR_COVERAGE.md
 M docs/eapdf_decision_tree.pdf
 M examples/sample_1c.pdf
 M examples/sample_1ci.pdf
 M examples/sample_1m.pdf
 M examples/sample_1mi.pdf
 M examples/sample_1s.pdf
 M examples/sample_1si.pdf
 D in/email/Agenda and timeline slides.eml
 M in/email/test3.eml
 M pom.xml
 M requirements-web.txt
 M scripts/generate_decision_tree.py
 D scripts/scrape_facebook_page_jsonl.py
 D scripts/scrape_x_user_jsonl.py
 D scripts/www_facebook_com_cookies.txt
 M src/main/java/eapdf/Common.java
 M src/main/java/eapdf/EapdfConfig.java
 M src/main/java/eapdf/EapdfGui.java
 M src/main/java/eapdf/EmailToPDFA.java
 M src/main/java/eapdf/EmlReader.java
 D src/main/java/eapdf/FacebookPostsReader.java
 D src/main/java/eapdf/FacebookPostsToPdf.java
 M src/main/java/eapdf/GmailDownloadToPdf.java
 M src/main/java/eapdf/MboxReader.java
 M src/main/java/eapdf/Models.java
 M src/main/java/eapdf/MonthlyFilenameArchiveToPdf.java
 M src/main/java/eapdf/MsgReader.java
 M src/main/java/eapdf/PdfCollectionSupport.java
 M src/main/java/eapdf/PdfFindingAidSupport.java
 M src/main/java/eapdf/PdfMailWriter.java
 M src/main/java/eapdf/PremisBuilder.java
 M src/main/java/eapdf/PstReader.java
 D src/main/java/eapdf/TwitterPostsReader.java
 D src/main/java/eapdf/TwitterPostsToPdf.java
 M src/test/java/eapdf/CollectionPortfolioTests.java
 D src/test/java/eapdf/FacebookPostsReaderTests.java
 M src/test/java/eapdf/PdfWriterArchiveTests.java
 M src/test/java/eapdf/PdfWriterSingleMessageTests.java
 M src/test/java/eapdf/ReaderTests.java
 M src/test/java/eapdf/TestSupport.java
 D src/test/java/eapdf/TwitterPostsReaderTests.java
 D src/test/resources/eapdf/facebook/scrape/sample.jsonl
 D src/test/resources/eapdf/facebook/scrape/sample_video.mp4
 D src/test/resources/eapdf/twitter/media/dup-a.png
 D src/test/resources/eapdf/twitter/media/dup-b.png
 D src/test/resources/eapdf/twitter/media/fixture_image.png
 D src/test/resources/eapdf/twitter/sample.csv
 D src/test/resources/eapdf/twitter/sample.jsonl
 M third_party_licenses/README.md
 M validate_eapdf.py
 M validator_rule_text.json
 M validator_web.py
?? .idea/.gitignore
?? .idea/compiler.xml
?? .idea/encodings.xml
?? .idea/jarRepositories.xml
?? .idea/libraries/target.xml
?? .idea/misc.xml
?? .idea/modules.xml
?? .idea/vcs.xml
?? deploy/validator-web-cleanup.service
?? deploy/validator-web-cleanup.timer
?? docs/CONVERSION_OPERATIONS.md
?? docs/CREATOR_COVERAGE_REVIEW.md
?? docs/VALIDATION_RESULTS.md
?? docs/reimplementation/01-readiness-review.md
?? docs/reimplementation/02-standards-and-coverage.md
?? docs/reimplementation/03-architecture.md
?? docs/reimplementation/04-data-and-ingestion.md
?? docs/reimplementation/05-pdf-and-metadata.md
?? docs/reimplementation/06-interfaces-and-operations.md
?? docs/reimplementation/07-validation-and-acceptance.md
?? docs/reimplementation/08-porting-plan.md
?? docs/reimplementation/09-repair-follow-up.md
?? docs/reimplementation/README.md
?? docs/reimplementation/additional-rules-evidence.json
?? docs/reimplementation/followup-evidence.json
?? docs/reimplementation/message.example.json
?? docs/reimplementation/message.schema.json
?? docs/reimplementation/repair-evidence.json
?? docs/reimplementation/review-evidence.json
?? docs/reimplementation/review_probes.py
?? examples/sample_1c_finding_aid.pdf
?? examples/sample_1c_index.xlsx
?? examples/sample_1ci_finding_aid.pdf
?? examples/sample_1ci_index.xlsx
?? fixed.md
?? in/Agenda and timeline slides.eml
?? in/email/test3_old.eml
?? output/Agenda and timeline slides_000001.pdf
?? output/bfo.pdf
?? output/sample_1s.pdf
?? output/thinkbox_old.pdf
?? output/uiuc.pdf
?? requirements-docs.txt
?? scripts/regenerate_email_samples.py
?? src/main/java/eapdf/ConversionSupervisor.java
?? src/main/java/eapdf/EmailStyles.java
?? src/main/java/eapdf/FontSupport.java
?? src/main/java/eapdf/GmailResumeStore.java
?? src/main/java/eapdf/MboxParser.java
?? src/main/java/eapdf/OutputTransaction.java
?? src/main/java/eapdf/PayloadStore.java
?? src/main/java/eapdf/PdfCreationValidator.java
?? src/main/java/eapdf/PdfDocumentParts.java
?? src/main/java/eapdf/ProcessingLimits.java
?? src/main/java/eapdf/SemanticStructure.java
?? src/main/main.iml
?? src/main/resources/eapdf-build.properties
?? src/test/java/eapdf/AdditionalSpecRuleTests.java
?? src/test/java/eapdf/AttachmentProfilePolicyTests.java
?? src/test/java/eapdf/CreatorAccuracyTests.java
?? src/test/java/eapdf/EmailContentRegressionTests.java
?? src/test/java/eapdf/GmailDownloadRecoveryTests.java
?? src/test/java/eapdf/MboxPreservationTests.java
?? src/test/java/eapdf/MonthlyFilenameArchiveTests.java
?? src/test/java/eapdf/PreservationRegressionTests.java
?? src/test/java/eapdf/ProcessingLimitRepairTests.java
?? src/test/java/eapdf/ReleaseRegressionTests.java
?? src/test/java/eapdf/RemainingPreservationTests.java
?? src/test/java/eapdf/RemainingWorkflowTests.java
?? src/test/java/eapdf/SpecRepairTests.java
?? src/test/java/eapdf/SupervisedConversionTests.java
?? src/test/java/eapdf/UnfixedRegressionTests.java
?? src/test/resources/eapdf/email/attached-message.pst
?? src/test/resources/eapdf/email/attached-message.pst.md
?? src/test/test.iml
?? tests/test_accessibility_profiles.py
?? tests/test_additional_spec_rules.py
?? tests/test_creator_accuracy.py
?? tests/test_dpart_diagnostics.py
?? tests/test_first_five_fixes.py
?? tests/test_followup_security.py
?? tests/test_generated_profiles.py
?? tests/test_preservation_safety.py
?? tests/test_remaining_workflow.py
?? tests/test_report_observed_values.py
?? tests/test_source_attachment_references.py
?? tests/test_spec_repair_integration.py
?? tests/test_spec_repairs.py
?? tests/test_supervised_conversion.py
?? tests/test_validator_completion.py
?? tests/test_validator_correctness.py
?? third_party_licenses/DejaVu-LICENSE.txt
?? third_party_licenses/Liberation-OFL-1.1.txt
?? unfixed.md
```
