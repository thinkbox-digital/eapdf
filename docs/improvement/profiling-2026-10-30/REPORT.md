# Creation, validation and viewing profile — 2026-10-30

The patched OpenHTMLtoPDF integration is merged into `main` at `c2d6065`.
The performance work is closed out on ordinary-input regressions. Further large-PST profiling and acceptance campaigns are excluded at the user’s direction; existing run evidence is retained. Largest-PST creation and exported-message/attachment preservation checks now complete; original PST/MBOX containers remain outside the PDF by design. The available-memory retry now finishes with PDF/A-4f PASS (201,183,288 assertions), selected ISO 32005 PASS (71,118,152 assertions) and zero failed EA-PDF findings. Overall INCOMPLETE remains because four findings record missing/unresolved source information and comparisons. The monitored run took 6,157 seconds with a 19 GiB heap and 25.0 GiB peak process RSS, including file mappings. [Memory retry and targeted improvements](MEMORY_RETRY.md). Nothing has been pushed. The standalone validator was previously rebuilt and installed locally at `dist/eapdf-validator.jar` at the user’s request; [packaging verification and checksum](../validator-packaging-2026-10-30.md). That installed JAR and the creator release are unchanged by this retry.

Viewer measurements below describe separate local work; uncommitted viewer
changes are not included in the creator/validator beta.4 release.

## Current results

| Check | Before | After | Interpretation |
| Largest-PST creation | Out of memory after 2,420.26 s / 11,808.9 MiB | Complete in 1,742.50 s / 11,694.4 MiB | Complete creation now fits the same 10 GiB heap. Peak RSS is only 1% lower; the failed baseline is not a completed-run speed comparison. |
| Largest-PST rendering stage | 1,388.1 s | 848.1 s | 38.9% shorter for the same source; bundled renderer and footer changes both differ. |
|---|---:|---:|---|
| Exhaustive metadata scan, identical largest staged PDF | 2 GiB heap: out of memory after 185.2 s | 2 GiB heap: complete in 148.2 s; 2,231 MiB peak process RSS | Streaming UTF-8 removes whole-XMP text copies. This measures this stage, not whole-file creation or validation. |
| Same metadata scan, 10 GiB ceiling | Unsorted bounded scan stopped after at least 2,377 s in metadata checking | Sorted scan completes in 91.7 s; 7,376 MiB peak RSS | Grouping compressed objects avoids repeated decompression. The stopped scan did not complete; the comparison is a lower bound, not a completed-run ratio. |
| Viewer, same 32,764-page PST 06: DOM nodes | 233,070 | 1,123 | 99.5% fewer nodes; pages, file lists and diagnostics are virtualized. |
| Viewer peak process-tree RSS | 2,893 MiB | 2,134 MiB | 26.2% lower in these runs. |
| Viewer open | 34.74 s | 30.69 s | 11.7% shorter; opening remains dominated by document inspection. |
| Viewer middle / end / first jumps | 1,958 / 2,709 / 2,272 ms | 476 / 228 / 168 ms | 75.7% / 91.6% / 92.6% shorter. |
| MBOX creation with automatic smaller-job budget | 37.70 s / 4,585 MiB | 55.56 s / 1,350 MiB | 70.6% less memory, 47.4% longer; both full validations pass. |
| Small PST creation with automatic budget | 12.95 s / 3,426 MiB | 25.98 s / 836 MiB | 75.6% less memory, about twice as long; both full validations pass. |

These are single local runs on a shared workstation, with other work overlapping.
RSS sums shared pages across a process tree; it is not unique physical memory.
The automatic budget changes heap and worker count together. Renderer upgrades
and other fixes also differ between the compared builds. No isolated attribution
or general speed/memory guarantee is implied. Earlier viewer attempts are retained,
including slower openings before diagnostic rows were virtualized.

Largest-PST creation and its original preservation inventory checks completed:
48,129 messages, 88,765 pages, all expected retained sources/native attachments,
and unchanged source hash. The separate final streaming scan also passes on this
published output at 2 GiB (140.23 seconds, 2,244.6 MiB peak RSS). This last scan
checks metadata, not the full creator or standalone validator within 2 GiB.
The large creation run used the merged renderer/bounded-scanner build before the
subsequent streaming UTF-8/sort optimizations; manifests identify both artifacts.
The latter change affects checks, not generated PDF content.

The first independent validator run is **INCOMPLETE**, with two distinct defects:
veraPDF Parser 1.31.7 shifts xref bytes in 32 bits, turning the actual object-stream
offset 2,463,914,353 into -1,831,052,943. It then fails resolving PageTree object
737274 through object stream 11061872. A local `long`-shift correction preserves
upstream license/source provenance and leaves rules unchanged. Our EA-PDF phase
then exhausted its 10 GiB heap during the generic associated-file graph walk.
The replacement discovers all reachable AF entries with bounded parser caches,
replays only file references into the canonical document, and uses compact
PDF dictionaries. Equivalence, direct-file-spec, cyclic-reference, limit and
unsigned-offset regressions pass. After the offset correction, veraPDF itself
still exhausted the 10 GiB heap while building its PDF model (288.5 seconds,
before the first rule assertion). Its 14 GiB retry also exhausted memory after
415.5 seconds and eight assertions. The separate EA-PDF-only probe exhausted
10 GiB after 1,621.6 seconds during page-content checks. Its saved summary still
says RUNNING, but the exit code 1 and OutOfMemoryError log establish failure.

The subsequent bounded decoded-object-stream cache retains at most 1,024
reconstructible parser buffers, closing evicted streams. Its real-PDF tests
compare complete assertion totals and failures with unbounded parsing, including
a deliberately removed embedded font. With that cache, the 14 GiB full retry
reached 2,673,555 assertions and 12,087,250 processed objects before interruption
(exit 143, 331.6 seconds). A later 21.25 GiB run was also interrupted during
PDF/A; it has no completed metrics or process-exit record. Neither interruption
establishes a memory failure or success at those budgets. Reports explicitly
remain INCOMPLETE. Those interrupted retries established no complete verdict.
The subsequent available-memory run now establishes PDF/A/selected ISO passes
and zero failed EA-PDF findings, with source-related INCOMPLETE remaining; its
separate evidence and newer memory candidates are in [the retry report](MEMORY_RETRY.md).

The largest-file Chromium viewing attempt also fails: `Array buffer allocation
failed`. PDF.js's range stream still reserves one array the size of the entire
file; a bare Chromium 145 probe accepts 1 GiB but rejects 2 GiB and this 2.5 GiB
size. Page/sidebar virtualization does not fix that separate parser storage
limit. The 32,764-page archive and fresh Gmail files continue to pass. Replacing
the parser's byte storage is a separate, unresolved viewer limitation; the
100,000-page layout probe does not establish large-byte-file support.

## Seven ranked recommendations

| Rank | Implementation and acceptance |
|---|---|
| 1 — Creator metadata memory | Implemented: bounded read-only object enumeration, compressed-object grouping, metadata-reference deduplication, compact dictionaries and streaming UTF-8 checks. Orphan and untyped metadata checks remain. The identical largest staged output passes the new scan at 2 GiB; creator and preservation acceptance now pass. The final streaming scan also passes on the published large file. Completed standalone validation evidence is retained; further large-file retries are excluded from close-out. |
| 2 — Archive footer lookup | Implemented: retain the last imported page rather than traverse the archive page tree for every email. Footer placement/resource regressions pass. Earlier PST 06 JFR had zero page-tree-lookup samples versus 25.4% in the original large-PST snapshot; those are different inputs, not an end-to-end speed ratio. |
| 3 — Smaller-job budgets | Implemented: automatic worker heap at most 2 GiB for local inputs below 128 MiB and explicit Gmail limits up to 200. Explicit overrides win; larger jobs retain their allowances. Real MBOX/PST memory reductions are measured above, with a speed tradeoff. |
| 4 — Fonts and PDF copies | Implemented within the existing ownership model: bounded 128-entry font-metric cache and removal of the PDF reparse used only to count pages. PDFont objects remain document-owned. Finalization/import serialize-and-reopen steps remain; removing those would require a separate ownership refactor. No independent time/memory saving is claimed for this item. |
| 5 — Validator identities | Implemented: bounded exact cache (2,048 identities, each at most 1,024 characters) before existing disk comparisons. Full-string collision checks and spill/cleanup behavior remain. Tests pass; an end-to-end validation speed improvement has not yet been established. The later large-file retry additionally corrects xref offset decoding and bounds association discovery without skipping reachable objects. |
| 6 — Spreadsheet memory | Implemented: 128-row streaming workbook, compressed scratch sheets, fixed readable widths, streamed sidecar/embedded output and incremental MD5. Long values retain lossless continuation sheets and Unicode boundaries. Tests pass; largest-stage results are being collected. |
| 7 — Viewer virtualization | Implemented: at most nine page sections, nearby canvas retention within the existing 32-million-pixel budget, lazy message/file/PDF/diagnostic lists, and compressed offscreen space to stay within browser height limits. Same-file results above and a 100,000-page layout probe pass. |

## Verification

The combined creator suite passes **353 tests, zero failures/errors, four skips**.
The earlier bounded-cache-only validator verification passes **198 tests, zero failures/errors, nine skips**,
plus **two executable-JAR packaging checks**. The fresh offline `mvn verify`
build reproduces the earlier bounded-cache candidate byte for byte, SHA-256
`c1e35a85150fa72b89710590caf5f4bf1e277b96f46e280d2141edb69c31009d`.
This candidate is frozen separately from the installed validator; packaging
verification of `dist/` remains the earlier result linked above.
All **22 retained real-email matrix PDFs** also pass the bounded-cache candidate
at a 2 GiB heap with four active processors, sequentially. Each report retains
the earlier per-code/per-level finding counts and PDF/A assertion total. PDF and
original source hashes match before and after. The eleven accessibility-enabled
outputs are included, but the explicit PDF/UA-2 profile was not rerun in this
continuation. The fresh suite includes both cache-eviction equivalence tests,
including the missing-font negative case. [Hash-matched continuation evidence](cache-acceptance.json).
Logs, reports, the frozen candidate and runner remain in
local verification records;
the temporary Maven build has been removed.

The subsequent path/identity/property-cache candidate passes **199 validator
tests** (nine skips), **two packaging checks** and the same **22 outputs** with
unchanged rule outcomes and PDF/A assertion totals. Source/PDF hashes match.
Its separate acceptance and completed largest-file baseline are recorded in
[the memory retry report](MEMORY_RETRY.md); its own largest-file run now finishes
with identical findings and both upstream assertion totals (5,395 seconds,
23.16 GiB peak RSS). [Further repairs and investigation](FOLLOWUP.md).

The next largest-file work is to compare the newer memory candidate with the
completed frozen baseline and investigate the documented remaining retention
costs. The interrupted 21.25 GiB run supplies no complete timing or exit status.
IntelliJ’s available run configurations did not expose a validator entry point.
The standalone Java validator is runnable; these continuations used it and
retained test/runtime logs.
Tests cover malformed/truncated UTF-8, chunk boundaries, long declarations,
unreachable and untyped metadata, spreadsheet continuation, footer placement,
archive structures, renderer configuration and explicit budget overrides.

Fresh automatic-budget Gmail selections of 10 and 178 messages pass creation, standalone Java EA-PDF /
PDF/A validation and Chromium viewing at both the small and larger selection.
The mailbox changed between runs; Gmail timings are not a matched speed comparison.
All 22 combined renderer matrix outputs pass standalone EA-PDF and PDF/A-4f checks across all six profiles, with accessibility features off and on. All 11 enabled outputs also pass explicit PDF/UA-2 machine checks. Source hashes are unchanged.

Viewer checks pass the existing 13 UI cases / 17 message selections, exact saved
attachment bytes, scrolling, search, zoom, cancellation, recursive navigation,
folder boundaries and metadata CSV export. The new layout-only probe verifies
100,000-page jumps, scrollbar midpoint, natural nearby scrolling, 200% zoom and
100,000 sidebar entries. Synthetic pages isolate browser layout behavior; they
are not conversion/completeness evidence. Real PST/Gmail files provide that check.
Chromium 145 HTTP mode was used: extension-mode UI startup timed out because the
cached browser is below the manifest minimum. This is not a fresh extension-install
or Firefox acceptance claim. No uncaught page errors were observed in passing runs.

A first combined matrix was interrupted when a package command replaced its live
JAR: later cases failed with `NoClassDefFoundError: jakarta/mail/Address`. It is
retained as failed evidence; all 22 cases pass when rerun against frozen artifacts. Production
code or validation rules were not changed to accommodate that test setup failure.

## Sources, completeness and limitations

The real small/large EML, MSG, MBOX and PST inputs are hashed before and after
conversion. Original files remain unchanged. EML's large sample is only 93,500
bytes; it is large relative to the available EML corpus. PST 16 is 1,262,420,992
bytes, with 48,129 messages and 88,765 pages in the previous staged output.

The original frozen beta.3 PST 16 attempt exhausted a 10 GiB heap during metadata
graph traversal after 2,420.26 seconds, peaking at 11,808.9 MiB process-tree RSS.
It had saved a staged output but published no final PDF. That is a failed baseline,
not an independently validated archive.

The earlier PST 06 output completed creation in 569.15 seconds and standalone Java
validation in 956.30 seconds. PDF/A passes, with zero failed EA-PDF checks, but the
overall result is **INCOMPLETE** because source comparisons are unavailable or
ambiguous. Ten findings cover missing source information, ambiguous source binding,
three unreadable To headers counted by two comparisons each, nested-message
ambiguity and uncertain original body format. They do not mean ten damaged emails.
Creator-detected source problems appear above affected messages and in the finding
aid. Validator-only limitations do not alter the PDF or add a viewer-wide banner.
Performance changes do not repair missing source information.

The all-seven-large-PST campaign is excluded from the close-out scope at the
user’s direction. Signed-output acceptance retains its separate status. PDF/UA machine checks do not establish human accessibility quality.
The [patched-renderer report](../openhtmltopdf-1.1.105/INTEGRATION.md) records its
separate 34-output matrix, upstream tests and reproducible dependency build.

## Baseline observations

| Input | Creation s / peak MiB | Validation s / peak MiB | Result | Viewer open ms / peak MiB | Pages |
|---|---:|---:|---|---:|---:|
| eml-large | 3.08 / 260.7 | 4.07 / 198.0 | PASS | 532.8 / 0.0 | 5 |
| eml-small | 3.05 / 287.0 | 3.08 / 157.2 | PASS | 476.8 / 0.0 | 2 |
| mbox-large | 31.59 / 4841.0 | 42.86 / 3309.5 | PASS | 3123.3 / 0.0 | 1111 |
| mbox-small | 4.09 / 428.2 | 3.08 / 160.8 | PASS | 450.5 / 0.0 | 3 |
| msg-large | 5.1 / 523.4 | 4.1 / 261.1 | PASS | 748.5 / 0.0 | 3 |
| msg-small | 3.06 / 302.1 | 3.08 / 151.9 | PASS | 414.0 / 0.0 | 2 |
| pst-large | 2420.26 / 11808.9 | — / — | CONVERSION_FAILED | — / — | — |
| pst-small | 13.26 / 2609.9 | 13.24 / 1079.5 | PASS | 2109.5 / 0.0 | 151 |
| gmail-large | 127.2 / 1328.1 | 51.88 / 1421.1 | PASS | 5491.9 / 1359.4 | 694 |
| gmail-small | 15.86 / 855.8 | 11.13 / 455.6 | PASS | 746.0 / 1131.9 | 30 |
| pst-large (06, modified build) | 569.15 / 10505.3 | 956.3 / 13860.1 | INCOMPLETE | 34477.5 / 2774.6 | 32764 |

PASS means the automated standalone Java EA-PDF checks and bundled veraPDF pass; it is not independent certification. Missing native-source comparisons and incomplete validation must remain explicit in the final large-PST result.

## Evidence and reproduction

The screenshot example `examples/pst/pst_sample_1m.pdf` was generated by beta.2
on 2026-10-29. Its page caption says `message.eml`, while embedded names are
numbered. Source-asset metadata does not establish a mandatory message owner.
The selected-message pane now explains when files exist but cannot be linked
reliably; selecting the document name still exposes all embedded files.

Private PDFs, tokens, screenshots, logs, JFR recordings, per-second samples,
source hashes and standalone Java reports stay ignored under
local verification records. `control/` is the matched seven-input
control series; `after/` contains the first performance candidate; `automatic/`
exercises the merged renderer and new default budgets; `combined/` is the
largest-PST acceptance series; `final-matrix/` uses frozen combined artifacts.
`metadata-probes/` distinguishes failed, intermediate and final stage probes.
`artifacts/sha256.json` identifies the retained final candidate JARs.
Earlier baseline evidence remains in local records.

Build without writing the repository's `target/`:

```sh
mvn -Deapdf.build.directory=/tmp/eapdf-creator-build package
mvn -f validator-desktop/pom.xml -Deapdf.build.directory=/tmp/eapdf-validator-build package
python3 scripts/profile_eapdf_pipeline.py \
  --creator /tmp/eapdf-creator-build/emailtopdfa-1.6.0-beta.3.jar \
  --validator /tmp/eapdf-validator-build/eapdf-validator.jar \
  --output /tmp/eapdf-profile-new-run
```

Select cases with repeated `--case`; `--automatic-budget` exercises the new creator
worker policy while retaining the selected supervisor/validator heap ceiling.
Otherwise `--heap-mib` forces a matched worker ceiling. Choose a new output
directory to rerun cases. Full selection includes live Gmail reads and requires
the existing OAuth credentials/token store. No credentials are printed or committed.

For the viewer, run `npm run check && npm run build` in `browser-viewer/`, then
from the repository root run `node browser-viewer/scripts/profile-pipeline.mjs
<result.json> <PDF> ...`. Set `CHROMIUM_PATH` if needed. In `browser-viewer/`,
`node scripts/virtualization-smoke.mjs` checks the browser layout limit.
The patched dependency build remains reproducible from the versioned artifact
repository; see the renderer report for clean-machine instructions.
