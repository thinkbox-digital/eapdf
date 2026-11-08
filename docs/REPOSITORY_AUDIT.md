## 2026-11-08 — Beta.20 release verification

Creator and validator are `1.6.0-beta.20`; the browser viewer is `0.5.0-beta.20` (manifest `0.5.0.20`). This release includes the reader, indexing, security, creator-option and regeneration changes recorded below. CSV export remains available.

**UI improvement / bug fix:** Embedded-file previews use a fixed filename header and a clearly labeled **× Close** button at the top right. Only the file content scrolls. Escape closes the modal even with Search open behind it; closing returns focus to the originating View button. Chromium checks cover PDF/text previews, long filenames/content and 1600/390-pixel layouts; Firefox acceptance also checks closing and focus return.

**Verification:** Fresh creator verification passed 434 tests (four skips); validator verification passed 266 tests including packaging checks (nine skips). Independent repeat builds produced byte-identical creator, GUI and validator JARs. All 132 viewer unit tests, complete Chromium acceptance, Firefox acceptance, independent inspection oracle, signed search checks and the archive memory regression pass. The association and supplement test hooks were updated to await worker-backed asynchronous metadata interpretation; production assertions remain intact. All four signed-index profiles pass standalone Java/veraPDF validation. Python: 1,294 tests (43 skips), plus 24 public regressions, pass. The regenerated manual passes PDF/A-2b; strict documentation builds pass.

**Visual regression review:** Independent real EML, MSG and MBOX source/attachment fidelity, visual and standalone Java validation checks pass. Baselines were updated for the explicitly removed branding strip and finding-aid front page. All six retained body pages had zero changed pixels outside the retired header/footer-number areas, after mapping old archive pages 2/3 to new pages 1/2. The production visual threshold remains unchanged. The public example bundle retains the existing 26 PDFs and their original beta.19 provenance: 24 PASS, one documented INCOMPLETE and one expected FAIL; it is not a new beta.20 conversion run.

**Code improvement:** New regeneration runs snapshot the versioned creator and validator JARs into their run evidence before starting, so later upgrades cannot change a running job's tools. Five isolated script regressions pass. The already-running bulk regeneration continues on its original beta.19 JARs; their local retirement is deferred until that run and any remaining Java users finish. Beta.20 replacements are installed and verified. This release does not claim completion of that bulk run or a measured bulk speedup.

## 2026-11-08 — Review fixes verified locally

Updated the web parser requirement to pypdf >=7.0.0,<8 and regenerated the hash lock at 7.0.0. Upstream [GHSA-59jv-x5x2-gg4c](https://github.com/py-pdf/pypdf/security/advisories/GHSA-59jv-x5x2-gg4c) confirms the pre-7.0 cross-reference-stream memory issue. Locked dependencies installed successfully; historical runtime inventories remain historical, and deployed services must reinstall/rebuild to receive the update. No exploit reproduction claimed.

Web conversion now supplies one Java temporary directory under the monitored output tree, replacing caller-supplied temporary-directory properties. A real JVM writes two files exceeding the aggregate budget; rejection, no writes to the displaced directory, and scratch cleanup all pass.

Whole-document and collection match navigation clear the email filter before rendering. The browser regression selects a filtered email, searches unique text on another page, and verifies the displayed page and highlight. Backported PDF.js PR 22465's bounded zero-width XRef handling with its upstream fixture; prior-revision entries remain available without reconstruction. Maintained source/bundle hashes refreshed.

PDF.js remains 6.5.438. Retained its image-size guard after reviewing PR 22471; the maintenance guide records the required large-image/cancellation assessment before 7.x, since the canvas-area option is not a decode-memory cap. Refreshed the test guide's frozen-fixture paths and tool-version distinctions. Corrected a local sample-regeneration test's stale report filename.

Validation: 1,294 Python tests pass (43 skips), 19 web/worker tests pass; 131 viewer units, type/contract checks, filter-then-search Chromium acceptance, 36 indexed/scanned comparisons across four profiles, five differential PDF.js rendering fixtures, and Firefox acceptance pass. PDF.js rebuild: 39 source checks and 133 upstream specs, zero failures (one OffscreenCanvas-dependent spec pending). Both existing-version viewer ZIPs rebuilt with integrity checks. No version bump, push, release, email regeneration, or index-limit changes.

# November 7, 2026 repository audit follow-up

The supplied audit reviewed public commit `8f0b079`. Remediation below describes
source changes prepared for beta.18 and extended for beta.19. Verification below describes local runs;
remote workflow status is reported by GitHub Actions.
The previously completed search-pane, Calc XLSX and PDF.js scheduling fixes are
preserved.

## Beta.19 release verification

Creator and validator `1.6.0-beta.19` and viewer `0.5.0-beta.18` include the
follow-up fixes below. The beta.18 work was local; beta.17 was the preceding
published GitHub release.

Fresh release builds passed 445 creator tests (four documented skips), 264
validator tests (nine documented skips), and both executable-validator-JAR
checks. The skips match the maintained allowlist. Independent clean rebuilds
produced identical bytes for the creator, GUI and validator JARs. All four signed
index profiles passed standalone Java EA-PDF and veraPDF validation.

The 18 public Python regressions, eight deliberate-loss oracle tests and three
independent EML/MSG/MBOX conversion cases passed. These conversion cases compare
source and attachment preservation, rendered output and standalone Java/veraPDF
results. The PDF/A-2b manual and strict documentation build also passed.
All Chromium suite stages passed, including 131 unit tests and 36 unsigned plus
36 signed indexed/scanned comparisons. Firefox fresh temporary installation, UI
and signed-index checks passed. The inspection contract/reference checks and
44 MB repeated-open/search/extraction probe also passed. Rebuilt browser packages
are byte-identical. Tests that specifically exercise scanning now explicitly
select that path; a small frozen public fixture retains optional PREMIS coverage
while refreshed examples omit PREMIS. GitHub Actions remains the authority for
remote workflow status. GitHub's creator run skips 45 additional optional
real-input checks because the private local corpus is not distributed; the local
run above exercised those available inputs. The refreshed indexed example also
required the RSA/EC signing regression to exclude only the two intentionally
resealed XMP binding fields from byte equality, while separately verifying their
bindings and retaining page, attachment, other-metadata and CMS checks.

The public bundle contains 26 freshly generated PDFs: all 24 primary examples
pass EA-PDF and PDF/A validation across the six profiles; the missing-date
negative example retains its expected `MESSAGE_SENT_XMP` failure; the 1,433-message
Enron archive is explicitly `INCOMPLETE` because bundled veraPDF encounters the
same runtime exception recorded in beta.18. It is not a conformance-passing
fixture. The [example inventory](../examples/README.md) and manifests record these
distinctions, source hashes and output hashes. Accessibility and supported search
indexes are enabled; optional finding aids and PREMIS are disabled. The 44
superseded optional sidecars were removed after verifying their recorded hashes.

Fresh conversions of the 69.09 MB May PST and 130.80 MB April PST produced indexed
`1ci` files of 84.39 MB and 91.84 MB. Both roots and all 3,704 embedded message PDFs
passed standalone Java EA-PDF, veraPDF and selected ISO checks. Original PST
hashes are unchanged; existing header-conflict and date-precision diagnostics
remain explicit. Conversion took 249.2/260.0 seconds and validation 283.0/265.2
seconds during concurrent release work. Sampled conversion peak process-tree
PSS was 742/833 MiB; validation was 1,793/1,801 MiB. These are fresh-conversion
measurements, not controlled speedup comparisons. Isolated profiles intentionally
omit original email bytes and use attachment placeholders; comparison with
normalized reader records does not establish independent PST completeness.

The balanced local regeneration covered all 79 inputs below 60 MB and produced
90 PDFs: 20 of each archive profile and five of each standalone profile. All
creator/preservation receipts passed, all 80 archive indexes are complete, and
all 88 original input hashes are unchanged; nine larger PSTs were excluded from
this set. Standalone validation returned 74 PASS, 10 INCOMPLETE and six FAIL.
Six incomplete assessments reproduce existing veraPDF runtime errors; four new
grouped EML/MSG archives expose duplicate-source or native-MSG association
limits. The six failures reproduce missing-date errors in the Drafts/msg MBOX
inputs. All 82 directly comparable outputs retain their previous statuses and
failure codes; eight grouped archives are newly exercised coverage. No validator
checks were weakened and these exceptions are not represented as passes.

## Original audit findings

| Finding | Disposition |
| --- | --- |
| EA-001 | Added separate Chromium and Firefox CI jobs. Chromium runs the complete standard suite, inspection oracle and archive-memory probe; both run type checks. Branch protection still requires repository configuration. |
| EA-002, EA-018 | Packaging uses unconditional version/hash checks, an exact manifest file set, validated in-memory payloads and post-write ZIP verification. Optimized-Python negative tests pass. |
| EA-003 | Restored a public EML/MSG/MBOX independent conversion gate with source hashes, preservation checks, reviewed raster baselines and standalone Java EA-PDF/PDF-A results. Eight deliberate-loss tests pass. This is a limited subset, not restored private/PST acceptance. |
| EA-004 | Collection text search traverses declared members only; unavailable members are explicit. Regression includes an unrelated matching PDF and an unreadable member. |
| EA-005 | Metadata filtering includes Cc, Bcc and both raw variants, each tested in isolation. |
| EA-006 | Updated feature version and replaced developer-machine links with portable references/local-output descriptions. |
| EA-007 | Replaced obsolete checkpoint introduction and viewer limits; linked current viewer/deployment controls. Limits are still manually maintained. |
| EA-008 | Preserved scalable application defaults; deployment explicitly sets a 600-second synchronous request budget under 660-second server/proxy limits. Documented 600/1800-second subprocess minima, 24-hour caps and request-deadline precedence; tested deadline/admission behavior. |
| EA-009 | Both systemd templates use `/opt/eapdf`; installation instructions explain path substitution and service-account access. No live service changed. |
| EA-010 | Added 18 public validator/worker/web regressions and their own CI job. Private tests remain excluded; comprehensive parity is not claimed. |
| EA-011 | Added five upstream-versus-patched browser cases for classic/compressed xrefs, incremental updates, broken-xref recovery and encryption. Repeated text/render/cleanup agrees. Full upstream rendering corpus and independent parser-ownership review remain outstanding. |
| EA-012 | Firefox CI and the local release gate require a fresh temporary extension installation and UI tests. Firefox 158.0.1/WebDriver 0.36.0 passes locally. Signed installation and other platforms remain unverified. |
| EA-013, EA-017 | Added repeat-open/extraction/render/search testing of the public 44,440,369-byte archive with V8 old-space set to 512 MiB. Complete search found 104 matching pages; extracted bytes agree across opens. Post-close main-thread heap was about 3.4 MB. Worker/native/process memory is excluded. Multi-GiB acceptance remains open; the 4 GiB ceiling is not a capacity guarantee. Full-buffer loading remains because range transport also allocates file storage and incomplete scans restart. |
| EA-014 | Already implemented at the audited commit: `EmptyPageContextTests` includes full validation plus missing/empty Contents variants. All ten tests pass; no duplicate Java test added. |
| EA-015 | Added a Python 3.12 deployment lock with transitive versions and artifact hashes, verified hash-required installation, and documented update procedure. |
| EA-016 | Resolved existing GitHub action tags to immutable upstream commits and pinned all workflows, including new jobs. Browser/driver downloads are checksum-pinned. Future updates still need review. |

The public conversion baseline was reviewed with accessibility enabled and PREMIS
disabled. Two pages matched the old baseline exactly; five changed in generated
version text and accessibility-related header formatting. Independent source and
attachment expectations were unchanged. A fresh conversion passed all seven
reviewed raster pages and both Java validation outcomes for each of three inputs.
No rejected or incomplete conversion was accepted.

The complete Chromium suite, Firefox UI acceptance, focused parser comparisons,
public Python regressions, oracle negative controls and existing empty-page Java
tests pass locally. Workflow execution and required-check settings on GitHub are
not established by these local results. Native MSG evidence limits, inaccessible
or over-budget archives, actual accessibility and cross-platform acceptance
remain documented limitations; they must not be relabeled as complete coverage.


## Follow-up creator and viewer improvements — November 7

Implemented the subsequent code-review improvements, excluding the proposed
manual “Verify using PDF.js” action. File-backed MSG parsing remains deferred.

- MIME scopes, body selection, attachment discovery and debug traversal now use
  iterative walks. A 3,000-level, 203,933-byte MIME fixture that raised
  `StackOverflowError` now parses under a 256 MiB heap and 1 MiB stack. Decoded-byte
  accounting accompanies extraction; ignored and nested parts remain checked,
  avoiding a redundant decoding pass without caching all decoded payloads.
- Index serialization hashes a bounded disk spool and streams into gzip. The
  version-3 payload and signature-compatible binding remain unchanged.
- Creator GUI logs keep bounded recent display text while preserving complete
  UTF-8 run logs; display updates are batched. See [operations](CONVERSION_OPERATIONS.md#creator-gui-logs).
- Messages-filter edits preserve scroll unless they change the displayed page
  range. Large collection-result lists are virtualized with keyboard navigation,
  matching-page highlighting, and precomputed cumulative counts. Search-form
  submissions reset the cursor without discarding the bounded page-text cache.
- Inactive child metadata is evicted by count/estimated memory, restoring lazy
  navigation when reopened. Current/root navigation state remains available.
  [Resource policy](../browser-viewer/docs/security.md) documents the exemptions.
- CI now generates signed fixtures, independently validates all four profiles,
  and supplies those same PDFs to Chromium and Firefox acceptance. Unsigned and
  signed reports are retained separately. Remote Actions execution is not claimed.

### Measured isolated workloads

| Workload | Before | After |
| --- | ---: | ---: |
| Agenda/PPTX EML: median time for 100 parses | 531.75 ms | 486.71 ms |
| Same MIME workload: main-thread allocated bytes | 116,265,928 | 105,394,536 |
| 1,000 distinct messages: median index serialization/gzip time | 197.728 ms | 152.230 ms |
| Same serialization stage: main-thread allocated bytes | 63,395,456 | 18,869,688 |
| 50,000-line GUI display model: median processing time | 4,231.416 ms | 1,019.572 ms |
| GUI retained detail characters | 5,950,000 | 500,000 |
| GUI append/scroll updates | 50,000 | 391 |
| 1,000 matching pages: mounted result rows | 1,000 | 48 |
| Result presentation, including the first match | 210.3 ms | 81.6 ms |
| Six refined queries: page-text extractions | 6 | 1 |
| Retained children after 64 visits | 64 | 18 |
| Serialized retained metadata in that probe | 2,041,761 characters | 863,163 characters |

MIME and index figures use warm-up followed by three measured trials; GUI timings
use three fresh JVMs. MIME source/body/metadata/attachment fingerprints agree for
all 31 real EML inputs. The index benchmark uses 1,000 distinct real messages;
compressed bytes remain exactly identical (754,713 bytes). Heap-pool peaks and
allocation counters are not equivalent to whole-process peak memory. The GUI
model is headless and excludes disk I/O and visible desktop interaction.

Viewer presentation/retention figures are single shared-host trials. Synthetic
pages isolate DOM scaling; cache/retention probes use unchanged real PDF content
under multiple logical references. Six-query latency was 16.9 → 19.0 ms and
64-child traversal was 11.19 → 16.39 s in those trials: these establish reduced
work/retention, not a reliable speed gain for those paths. Retained children plateau
at 16/18/18 for visits 16/32/64; root and active documents are excluded from the
inactive budget. Serialized metadata size is not browser heap or RSS.

### Verification and reproducibility

The integrated creator package passed 442 tests (four skipped), and all 122 viewer
unit tests passed. Complete Chromium acceptance, Firefox UI acceptance, both
signed-index paths, the independent inspection oracle, and the 44 MB constrained
archive test passed. The independent real EML/MSG/MBOX conversion gate retained
its source/attachment and visual expectations and passed Java/veraPDF checks.
All four signed-index profiles pass, as do new 100- and 1,000-distinct-message
collections, with zero failures (nine warnings per diverse collection).

Reusable probes are `scripts/benchmarks/MimeReaderBenchmark.java`,
`scripts/benchmarks/CreatorMemoryBenchmark.java`,
`browser-viewer/scripts/viewer-improvements-smoke.mjs`,
`scripts/benchmark_diverse_index.py`, and
`browser-viewer/scripts/diverse-index-benchmark.mjs`. Java probes document their
invocation in their source. The viewer probe supports `EAPDF_VIEWER_BASELINE`
pointing to an earlier Chromium package directory. Use explicit temporary output
destinations for private corpora. No commits, pushes or releases were performed.


### Diverse corpus and complete-conversion measurements

The new benchmark uses the first 100 and 1,000 distinct records from the existing
Enron mailbox, without changing the original. The 1,000-message collection has
1,271 pages, 1,254 unique normalized page texts and 15,806 terms. Indexed and
PDF.js-scanned results agree exactly on per-page destinations and counts for the
recorded common, rare and phrase queries. Indexed queries opened zero children;
scanned queries opened all 1,000. The selected phrase (`email collection`) is a
cover-page phrase; these three queries supplement, not replace, the 36-query
four-profile regression.

On the updated 1,000-message file, opening took 1.361 s and index loading 2.398 s.
The common query took 0.687 s indexed versus 45.667 s scanned; rare and phrase
queries took 0.086/0.453 s indexed versus 61.914/99.539 s scanned. These were
instrumented runs concurrent with other acceptance checks, so the scan spread
is not an estimate of query complexity or a before/after speedup. All paths return
complete result sets; time to first result equals complete query time here.
Sampled peak browser PSS was 620,539 KiB, versus 607,940 KiB in the earlier run;
there is no established total-browser-memory reduction from this benchmark.

Two later alternating baseline/updated conversion pairs ran sequentially with no
other task workloads. Complete 1,000-message conversion times were 49.574/53.867 s
before and 52.379/52.932 s after (medians 51.721 → 52.656 s). This establishes no
whole-conversion speedup. GNU time maximum RSS ranged from 1,761,996–1,803,524 KiB
before and 1,758,968–2,110,264 KiB after; no whole-process peak-memory improvement
is claimed. GNU time's maximum is not an aggregate concurrent-worker memory
measurement. PDFs remained approximately 41.19 MB; small byte-size differences
include ordinary generated identifiers/metadata and are not a compression gain.

The earlier concurrent conversion timings (45.737 → 70.689 s for 1,000 messages)
are retained as raw evidence, not used to infer a regression or improvement.
The isolated allocation and UI-retention results above are the demonstrated
benefits. Multi-GiB and broad cross-platform capacity remain unverified.


A second viewer pair ran sequentially after the other task workloads completed.
Result presentation was 195.8 → 49.2 ms with the same 1,000 → 48 mounted rows.
Six-query median time was 13.7 → 10.4 ms, and 64-child traversal was
10.353 → 10.826 s. This reinforces the presentation improvement and retained-work
reductions, but does not establish a broad browsing speedup. Both the earlier
shared-load observations and this sequential pair are retained; no slower result
was discarded.


### Larger PST acceptance tests

Two real public PST inputs above the previous 60 MB cutoff were converted with
creator/validator 1.6.0-beta.18 and tested in Chromium with viewer 0.5.0-beta.17.
Both use indexed 1ci, accessibility enabled, PREMIS disabled and no finding aid.
Original source hashes remained unchanged; message counts match prior inventories.

| Input | Source size | Messages | PDF size | Conversion | Recursive Java validation |
| --- | ---: | ---: | ---: | ---: | ---: |
| May 2003 public PST | 69.09 MB | 1,795 | 84.40 MB | 131.94 s | PASS, 318.82 s |
| April 2003 public PST | 130.80 MB | 1,909 | 91.85 MB | 182.33 s | PASS, 308.75 s |

All 3,704 embedded messages completed validation. Both collection roots and their
children pass EA-PDF and veraPDF PDF/A-4f checks with zero failures. Each root
report retains nine warnings concerning provenance evidence and recommended
metadata, tagging, structure and asset policies; child reports retain applicable
recommendations. Creator diagnostics include Message-ID/header conflicts, reduced
date precision and invalid date strings retained as raw metadata. Creator
preservation comparisons pass against normalized reader records, which do not
independently prove PST parser completeness. Isolated 1ci intentionally omits
original email bytes and uses attachment placeholders.

Conversion used a 3 GiB parent heap, two jobs and 1 GiB worker heaps. Validation
used a 2 GiB parent, four child processes and a 256 MiB work-file allowance;
child heaps were 512 MiB for May and 384 MiB for April. GNU time's maximum
individual-process RSS was 810.68/713.83 MiB during May/April conversion, not an
aggregate concurrent-worker measurement. April's sampled process-tree peaks
were 789.57 MiB RSS / 746.21 MiB PSS for conversion and 1,950.23 MiB RSS /
1,813.23 MiB PSS for validation. May's initial descendant sampler missed worker
processes; no aggregate conversion peak is claimed for that run.

Metadata indexes cover all 1,795/1,909 messages. Full-text indexes cover
2,878/3,268 pages and occupy 2.53/2.92 MB compressed, respectively. These are
payload sizes, not measured PDF overhead against equivalent unindexed output.

| Chromium measurement | May | April |
| --- | ---: | ---: |
| Root opening | 3.732 s | 2.33 s |
| Metadata index load | 0.161 s | 0.110 s |
| Full-text index load | 6.897 s | 5.11 s |
| First rare-term indexed query | 0.190 s | 0.189 s |
| First phrase indexed query | 0.240 s | 0.208 s |
| Combined three-query PDF.js scan | 232.727 s | 92.79 s |
| Child PDFs opened by indexed queries / full scan | 0 / 1,795 | 0 / 1,909 |
| Sampled peak browser PSS | 831 MiB | 803 MiB |

The independent scan extracts every page once and answers all three queries in
that pass. Its timing is for the combined workload, not three independent scans.
These are instrumented single trials on a shared host; May overlapped validator
load, while April ran later. They establish current behavior, not a before/after
speedup or a comparison between the two inputs.

May's rare query `abandoned` (two occurrences on two pages) and phrase
`economic damages` (29 occurrences on 11 pages) agree exactly on document route,
page, message identity and occurrence count. Its common query `email` exceeded
the existing two-second indexed-query budget twice during concurrent validation.
A later actual UI run stayed indexed, but reported 4,144 occurrences versus
4,145 from PDF.js across the same 2,053 matching pages. That query does not pass
search parity; the differing page was not retained by the initial comparison
harness. No claim of universal PDFBox/PDF.js equivalence is made.

April's `email` (4,476 occurrences / 2,215 pages), `aback` (two / one) and
`petersburg times` (27 / 19) all agree exactly with PDF.js. First/repeated common
queries took 1.762/1.776 s, rare queries 0.189/0.192 s and phrases 0.208/0.222 s.
Both independent scans completed without missing children. Actual UI selection
verified first/last highlights and destinations; root navigation returned to page
one at Fit Width with the original filename. Common-query lists mounted 48 rows
initially and 29 at the final window. Session entries and pending work were empty
after disposal; browser main heaps after explicit test GC were about 5 MiB.

A broad May query entered the actual PDF.js fallback. Cancellation completed in
76 ms, explicitly reported that no complete result set was produced, and cleared
the isolated scan session. Full fallback completion within the default 120-second
budget was not measured.

This run covers two unsigned 1ci files on Linux/Chromium. Other profiles,
Firefox, signed large files and multi-GiB capacity were not retested here.
Generated documents, source hashes, configurations, logs and detailed reports
were retained locally. No production code, version or release was changed.


### Search correctness and query performance follow-up

The larger-PST discrepancy was localized to deferred link text painted out of
reading order. Joining `Belmonte` directly to a separately positioned `mailto:`
run manufactured an `email` occurrence in the PDF.js scan. A complete scan after
fixing that boundary found a second phantom, `White` + `mailto:`, present in both
old PDFBox and PDF.js text. Thus the original May figures (4,144 indexed / 4,145
scanned) included one/two false occurrences respectively; the corrected count is
4,143 across the same 2,053 pages. This was not a lost email or missing PDF page.

Viewer search and streamed text-layer highlights now share a conservative
separator for disjoint backward horizontal LTR runs. Contiguous words, overlaps,
marks, RTL/rotated text and existing EOL handling retain their behavior. PDFBox
index extraction applies the boundary through its public text-position callback,
preserving emitted Unicode and normal word/line separators. Ambiguous position
mapping fails optional index creation with an explicit diagnostic. No PDF.js
patch, visible page, original asset or CLI behavior changes.

The corrected extraction uses engine `pdfbox-nfkd-space-fold-v3` within the
unchanged version-3 wire schema. Earlier engines fall back to PDF.js scanning;
regeneration supplies a current index. A retained real engine-v2 fixture tests
that compatibility path. [Index architecture and limits](EMBEDDED_SEARCH_INDEX.md#search-semantics)
describe the narrow rule and remaining extraction differences.

Indexed queries previously normalized canonical page text again and allocated
original-offset match arrays. They now count literal non-overlapping matches
directly in strings already validated during index parsing. Precise PDF.js glyph
offsets are still established on selection. No cache or increased resource
limit was added. The fallback's existing 120-second budget now also applies
before opening members/streams, including malformed and zero-page children.

#### Controlled query-stage measurements

Three alternating baseline/updated Chromium trials used identical real embedded
index payloads, with no competing task workloads. The root inventory was
reconstructed from verified index rows to isolate querying; PDF opening, index
loading and rendering are excluded. These medians measure the query optimization
independently of the subsequent extraction-engine change.

| Query | May before → after | April before → after |
| --- | ---: | ---: |
| Common `email` | 1,597.1 → 214.4 ms | 1,773.0 → 252.3 ms |
| Broad `e` | 2 s timeout → 288.3 ms | 2 s timeout → 311.9 ms |
| Rare term (`abandoned` / `aback`) | 176.6 → 168.4 ms | 185.3 → 190.6 ms |
| Phrase (`economic damages` / `petersburg times`) | 231.7 → 168.1 ms | 211.7 → 189.6 ms |

The broad baseline timed out in all six trials. Every successful before/after
query retained its complete destination/page/count/message-identity fingerprint.
An independent legacy canonical-text matcher also verified broad-query results
(282,908 May / 332,715 April occurrences). This establishes substantial common-
and broad-query improvement, not an improvement for every query; the rare April
query was slightly slower. The two-second query deadline remains unchanged.


#### Integrated current-engine verification

Current-engine May indexed/scanned results agree at 4,143 occurrences over 2,053
pages; April agrees at 4,476 over 2,215 pages. Repeated common queries took
215–221 ms and 260–277 ms respectively, opening zero children. May's complete
current-viewer fallback scan took 81.37 s; April's took 92.85 s. Both completed
all children/pages within the unchanged 120-second budget. May's scan was reused
after verifying identical child routes/checksums/sizes/page counts, root page
text and the exact text-assembly module; its earlier incomplete parity result
was retained separately. April's new scan also checked rare/phrase counts from
the same extracted text. No broader extraction-equivalence claim is made.

Both affected May pages stay indexed when selected and display exactly two and
three real highlights. First/last-result navigation, 48/29 mounted rows, root
page-one Fit Width and disposal passed. Query time excludes PDF opening and
initial index loading. The final May memory sample excludes a new full scan, so
it is not evidence of reduced scan memory.

Verification completed: full creator package (445 tests, four skips), 131 viewer
unit tests, TypeScript/inspection contract, 36 unsigned and 36 signed Chromium
indexed/scanned comparisons, current/legacy engine behavior, and unsigned/signed
Firefox acceptance. The real PDF text-layer regression forces one-item stream
chunks and checks split words, EOL/hyphenation, backward runs and exact highlight
offsets. Focused tests retain Unicode, marks, overlaps, RTL/rotation, cancellation
and malformed/empty-child deadline coverage. Strict documentation build and
optimized-Python package integrity checks passed.

The four unsigned and four signed current-engine profile fixtures pass standalone
Java EA-PDF and veraPDF checks with zero failures (nine/ten root warnings
respectively). Refreshed large PDFs add only 2,263 / 2,399 bytes. All non-root-XMP
decoded streams, page content/geometry and remaining root XMP were verified
unchanged, including embedded children and original assets. Original test PDFs
remain unchanged. Versioned creator/GUI JARs and both local viewer packages were
refreshed without a version bump or publication.


Both refreshed large roots and all 3,704 children subsequently completed
standalone Java EA-PDF and veraPDF validation: zero failures and nine root
warnings each, in 379.25 / 374.33 seconds. These were index-only refreshes of
copies, not new source conversions. Original PST and prior PDF hashes remained
unchanged. Task-owned temporary builds and validation scratch were removed.
The general PDFBox/PDF.js reading-order caveat remains; the measured fixes do not
establish equivalence for arbitrary PDFs or all multilingual layouts.

## Reader and regeneration follow-up (November 8)

**Bug fixes:** Messages-pane Up/Down now opens the previous/next email, including virtualized rows; filter and collection-search result arrows open the highlighted result and restore keyboard focus. Reopening the same file clears prior filtering status. First filtering in a flat 1m/1mi PDF reuses the loaded metadata instead of reopening the PDF. Whole-document search continues to clear a restrictive message filter before navigating.

**Code and speed improvements:** Large XMP interpretation now runs in a cancellable worker with bounded result batches. Background subject discovery updates only changed visible labels. The 944 MiB `pst_06_1999_1c.pdf` contains 33.36 MB of root XMP and no embedded metadata index. A measured 7.04-second main-thread XML parse was moved off the UI thread; the final recorded initial long tasks reached 0.784 seconds, with a later 1.059-second task. These are sampled single-run observations, not a peak-latency guarantee. Overall open time was 32.05 seconds before and 33.79 seconds after, so no total-load speedup is claimed. The Messages pane remained populated in initial, child-open and scrolled checks; the reported persistent blank pane was not independently reproduced. No total memory improvement is established.

**New code:** The GUI’s **Embed searchable index** setting builds separate metadata/filter and full-text indexes. Compact metadata format v2 stores child identity/bindings and uses authoritative catalog metadata instead of duplicating headers; v1 remains readable. Explicit gzip/decoded/row/count bounds remain; retained search characters are bounded at 32 million. Newly generated 1c/1ci files browse and filter with zero child-PDF opens, then verify/open the selected child. Flat 1m/1mi files use their already loaded metadata. Existing PDFs without indexes still need the bounded fallback; optional indexes may still be skipped when their documented limits are exceeded.

**Feature removal:** Branding, Finding Aid and PREMIS creation controls/configuration/implementation are removed from the creator GUI and CLI. Collections retain their required cover page. The accessibility checkbox is **Accessibility (PDF/UA)**. Old retired settings are ignored with unknown-setting warnings. **CSV export remains**, as expressly requested: it reads email metadata independently of embedded finding aids. Its header button is now **Export CSV**. General PDF/email and attachment viewing remains available.

**Defaults and housekeeping:** Creator identity defaults to `Thinkbox.DIGITAL` in code and configuration, including legacy blank values. Explicit identity overrides remain supported. Only the latest versioned creator, GUI and validator JARs remain under `dist/`; 15 superseded product JARs and companion checksums were removed after current artifacts were verified. Third-party validator tooling is retained. Both viewer packages and the creator/GUI JARs were refreshed at existing versions. No commit, push or release.

**New code / expected speed improvement:** Installed `eapdf-regenerate` in `/home/lepore/.local/bin/`. No arguments prints help; `sample` chooses two smallest inputs per type; `everything` plans every applicable profile for every supported source. Both generation modes clear this checkout’s `out/`; `--dry-run` does not. The full preview covers 88 sources and 296 profile jobs. Concurrent Java processes are CPU/memory bounded automatically, with configurable `--workers` and `--jobs`. Each has isolated staging. Completed PDFs are atomically published and their paths printed immediately after each creator job, before validation; per-file validation status follows separately. A single archive or a mailbox job producing standalone PDFs must finish its creator job first. Final documents are flat under `out/{eml,msg,mbox,pst}`, and source hashes, logs and Java validation reports remain under the run evidence directory in `output/`. Accessibility and both indexes are enabled; signing is disabled. Failed/incomplete inputs and inapplicable one-message 1m/1mi outputs are reported explicitly. Existing conversions using `out/` block clearing. No bulk speed multiplier has been measured.

**Verification:** Creator package: 434 tests, four skipped, no failures. Viewer: 132 unit tests, type/contract checks, Chromium loading/filter/keyboard, reader-polish, progressive loading and virtual-result navigation checks, Firefox acceptance, and preserved CSV progress/cancel/retry/export checks pass. Four isolated regeneration tests cover profile coverage, symlink-safe clearing, atomic publication failure cleanup and output/evidence separation. End-to-end regeneration with two concurrent processes used two real EML sources: eight outputs across all six profiles pass standalone Java validation, with unchanged source hashes. New compact 1c/1ci index browsing/filtering opens zero children before selection. Original `out/` documents were not cleared or regenerated by these checks. Earlier review/security checks remain recorded below.

## 2026-11-08 — Viewer 0.5.0-beta.19 and Messages keyboard follow-up

**Bug fix:** Reproduced the remaining Messages-pane failure after an ordinary mouse click. Opening the email disables its tree button and loses focus; collection children also replace that row. Rendering now restores focus to the selected row. Search results already worked because their controls followed a different path. The earlier test explicitly refocused the row and missed this failure; the replacement regression uses natural clicks without that workaround and fails on the old build.

**Version update:** Viewer bumped to `0.5.0-beta.19` (browser manifest `0.5.0.19`) and both Chromium/Firefox ZIPs rebuilt and verified, including hashes, archive integrity, footer version and packaged keyboard fix. Creator/validator remains `1.6.0-beta.19`; the active regeneration continues using those JARs. This is a local viewer update, not a GitHub release. Published download references retain their actual released versions. Reload/reinstall the updated extension and close old viewer tabs; verify the new footer version before retesting.

**Verification:** All 132 viewer units and type/contract checks pass. Chromium and Firefox natural mouse-click checks pass for flat-message and replaced collection-child rows, including Up/Down opening, restored focus, and retained CSV functionality. Chromium also passes pane-focus navigation, filtering, same-file reset and reader-polish checks. Strict documentation build passes for 25 pages. No commit, push or release.
