# PDF.js large-archive comparison

This is an engineering experiment on the pinned PDF.js 6.5.438 source, commit
`0a4153349dc473ba950fc17941c5ca7eda43c1de`. It follows the completed responsibility
refactor. Experimental builds are separate from production; no optimization is
removed on the strength of its line count. The measured results and recommendations are recorded below.

## Measured results — 11 November 2026

The complete matrix contains 560 trials: 550 succeeded and produced identical
compared outputs; ten control trials failed explicitly when the valid 77-MB decoded
xref reached the custom 64-MiB stream ceiling. These are five failures per engine,
all in the same control/case. Stock, full and the four individual ablations opened
that case. No timeout or external memory-ceiling failure occurred.

All successful cold/warm common projections match stock within each engine;
custom projections and embedded bytes match among feature-equivalent variants.
The real archives expose 213 embedded file records each; the collection also
exposes eight folders. No full-stack inventory diagnostics occurred.
The existing viewer compatibility gate additionally passes classic/compressed,
incremental, recovered-xref and encrypted controls. The 44 focused production
source tests cover identity after eviction, nested stream lifetime, invalid input,
cycles, decoder budgets and repeated extraction. The selected upstream suite runs
133 specs with zero failures and one pending because Node lacks OffscreenCanvas;
this is not the complete upstream rendering/integration suite.

Medians below include cold opening plus both cold/warm workloads. Stock's total
includes only its ordinary APIs; **do not compare it as an equal-work EA-PDF total**.
PSS is the sampled maximum for the whole browser process tree. Full-versus-control
latencies include all custom inventory/extraction work.

| Engine | Case | Stock listing/render total (ms) | Control total (ms) | Full total (ms) | Full peak PSS (MiB) |
| --- | --- | ---: | ---: | ---: | ---: |
| Chromium | Small control | 233 | 240 | 270 | 237.1 |
| Chromium | Real 1m, 24.2 MiB / 151 pages | 635 | 2347 | 2402 | 421.1 |
| Chromium | Real 1c, 42.4 MiB / 10 outer pages | 528 | 1277 | 1234 | 404.1 |
| Chromium | 150k objects, classic | 531 | 1392 | 1805 | 420.2 |
| Chromium | 150k objects, compressed | 455 | 1403 | 2401 | 413.5 |
| Chromium | 2,500-page flat tree | 293 | 2230 | 1053 | 300.3 |
| Chromium | Sparse high object IDs | 168 | 170 | 169 | 234.8 |
| Chromium | 7m-entry xref / 77 MB decoded | 1382 | Failed: stream limit | 1192 | 505.5 |
| Firefox | Small control | 370 | 408 | 383 | 364.1 |
| Firefox | Real 1m, 24.2 MiB / 151 pages | 794 | 2599 | 2628 | 460.9 |
| Firefox | Real 1c, 42.4 MiB / 10 outer pages | 656 | 1071 | 1123 | 458.5 |
| Firefox | 150k objects, classic | 669 | 1629 | 2002 | 462.1 |
| Firefox | 150k objects, compressed | 660 | 1694 | 2376 | 450.2 |
| Firefox | 2,500-page flat tree | 469 | 4908 | 994 | 443.7 |
| Firefox | Sparse high object IDs | 335 | 343 | 349 | 360.9 |
| Firefox | 7m-entry xref / 77 MB decoded | 1985 | Failed: stream limit | 1781 | 476.2 |

The [numeric summary](benchmarks/pdfjs-large-archives-20261111.json) retains
per-case min/median/max values, paired effects, build/input hashes and coverage.
Differences between independent browser engines are not optimization effects.
Five trials on a shared host support engineering decisions, not significance or
customer capacity claims. The Chromium small-control full/control medians (270/240
ms) also cross the preset 10% review threshold; Firefox does not show the same
regression. Treat small absolute differences cautiously and review cache overhead
rather than claiming uniform gains.

## Recommendations

| Component | Evidence | Disposition |
| --- | --- | --- |
| Single page-tree scan | Full is 58.8% faster in Chromium and 80.6% faster in Firefox than the repeated-lookup ablation on the flat tree. | **Keep.** Clear measured benefit for complete DPart/annotation mapping; avoid claiming the same gain on ordinary small emails. |
| Compact xref entries | On the 7m-entry xref, full reduces paired sampled peak PSS by 15.9% in Chromium and 3.8% in Firefox versus array storage. | **Keep for large dense xrefs.** Chromium meets the preset memory threshold; gains are engine/workload dependent. An adaptive small/sparse fallback is a candidate, not a tested improvement. |
| Bounded parsed cache and weak identity | On 150k compressed objects, full costs 35.4% more time in Chromium and 14.2% more in Firefox than the unbounded strong-cache ablation. Cache pressure and weak bookkeeping are changed together; their costs are not separately attributed. | **Simplify/review.** Keep an explicit memory bound and live-object identity contract. Investigate registering weak identities on eviction and avoid unnecessary bookkeeping for small documents. These are unmeasured design candidates; do not remove weak identity alone while retaining eviction. |
| Decoder reclamation | All ten full-stack compressed-payload trials complete twelve reads with the independent expected hash. All ten no-reclaim trials exhaust the worker allowance after seven completed rounds. | **Keep.** Correct allocation-lifetime accounting matters under our added worker budget. This is not proof of a heap leak or a measured general memory reduction. |
| Scoped xref allowance / explicit exhaustion | Stock opens the valid large xref; the control with the custom ordinary-stream ceiling rejects it. Full and the individual ablations open it without changing output. | **Keep.** This makes our added limit compatible with valid structural streams; it does not fix a demonstrated upstream inability to read large xrefs. |
| Lazy object-stream parsing / decoded-stream cache | Included in full; the control restores upstream eager parsing. The four requested individual ablations do not isolate this subsystem. Dense full-inventory scans favor the combined control, while its effects are coupled with parsed-cache policy. | **Retain pending targeted simplification.** No independent performance benefit is established by this matrix. Preserve existing sparse-access, malformed-neighbor and nested-buffer tests before replacing it. |

Production patches are unchanged. The revised upstream-oriented priority is described below. Any cache/identity
simplification must retain the existing lifetime regressions and rerun this matrix;
there is no evidence supporting wholesale removal of the archive support.

## Upstream suitability — revised selection criterion

The project owner clarified that upstream adoption matters alongside local benefit.
Prefer small, generally useful changes that can be maintained in PDF.js itself.
An optimization's measured usefulness here does not establish maintainer interest
or acceptance. The rankings below are engineering judgments, not upstream decisions.
No change has been submitted or discussed with maintainers by this assessment.

| Component | Upstream prospect and next gate |
| --- | --- |
| Single page-tree scan | **Strongest conditional candidate.** The approach is simple and the measured complete-scan gain is substantial. Our current result concerns custom DPart/annotation inspection. Demonstrate the same redundant-work problem in an upstream-supported operation, or pair it with a separately accepted inspection API; do not promise ordinary-viewer gains from this result. |
| Compact xref storage | **Credible candidate requiring more evidence.** Dense-xref memory savings are general rather than email-specific. Show representative real PDFs, ordinary startup/rendering performance, sparse layouts, recovery, editing/saving and persistent-reference correctness against current upstream. Current inspected upstream XRef still uses array entries and a Map cache. |
| Bounded cache / weak identity | **Poor candidate in its current form.** The measured slowdowns and policy complexity weaken its case. Simplify and isolate eviction, identity bookkeeping and object-stream behavior before proposing changes. Do not send the entire combined cache rewrite as an initial contribution. |
| Decoder reclamation | **Conditional after generalization.** The existing proof establishes correct accounting under our added worker allowance. An upstream proposal needs a demonstrated lifetime/memory problem in upstream's own workflows, with safe handling of borrowed/nested streams. Our allowance exhaustion alone is not that proof. |
| Scoped xref allowance | **Primarily local compatibility.** Unmodified PDF.js already opens the tested large xref. This adjustment prevents our added ceiling from rejecting it; it offers no demonstrated independent upstream improvement. Keep any necessary local protection minimal and separate. |
| Lazy object-stream cache | **Not ready for an upstream proposal.** Its independent benefit remains unmeasured, and the integration scan shows costs in the combined implementation. Isolate this change and cover ordinary, sparse and full-inventory access before deciding. |

The next step is to separate generic optimization candidates from EA-PDF inspection
APIs and local resource policy, then prepare one small, independently reviewable
candidate at a time. Rebase and test against current upstream before submission.
Necessary EA-PDF functionality and safeguards remain supported locally; this
criterion does not authorize removing them without a replacement and regression
checks. The aim is to minimize the permanent fork, not merely accumulate locally
successful patches.

PDF.js documents its [contribution/testing process](https://github.com/mozilla/pdf.js/wiki/Contributing)
and [before/after performance workflow](https://github.com/mozilla/pdf.js/wiki/Benchmarking-your-changes).
Those upstream tests and benchmarks remain necessary; our selected specs and
archive matrix do not replace them. The inspected [upstream XRef source](https://raw.githubusercontent.com/mozilla/pdf.js/master/src/core/xref.js)
is a moving reference, reviewed for this assessment on 11 November 2026.

## Repeated-extraction diagnostic

Twenty additional real-collection trials (five per variant per engine) completed
twelve reads with identical output, both with and without reclamation. Independent
inspection found all 213 embedded streams had no compression filter, so those
reads did not exercise the private Flate-decoder allocation path.

A separate 16-MiB Flate-compressed payload isolates that path under the unchanged
64-MiB stream and 256-MiB worker allowances. All ten full-stack trials completed
twelve reads; every associated payload hash matches the independently known
source hash. All ten no-reclaim trials failed after seven completed rounds with
`Worker decoded-stream allocation limit exceeded`. The small encoded file is a
synthetic lifetime test, not a real-mail capacity result. No partial extraction
was accepted as success. Actual browser heap exhaustion was not observed.

## Standard attachment-byte verification

The initial harness incorrectly treated `getAttachments()` as an object. The
pinned API returns a `Map` and retrieves content lazily with
`getAttachmentContent()`. Consequently the 560-trial matrix timed the listing
request but did not compare stock attachment bytes. Its custom associated-file
hashes and all other output comparisons remain valid. The original timings are
retained with that narrower scope, rather than relabelled as byte-download timings.
The corrected supplement completes 18 successful trials: one cold/warm pass per
engine, case and implementation (stock/control/full). Named attachment metadata
and hashes match exactly in both rounds: 143 entries for the real 1m, 213 for the
real 1c, and one for the compressed diagnostic. Every named payload hash is also
present in the full custom inventory; the compressed payload additionally matches
the independent source hash. The 1m custom inventory includes 213 records because
it discovers files outside the named-attachment list too. This correctness check
is separate from the five-trial timing estimates.

Across the main matrix, the two lifetime supplements and this byte check, there
are 618 completed trials: 598 successes and twenty explicit limit failures in
reduced controls. There are no compared-output differences. Interrupted and
harness-diagnostic attempts are retained separately and excluded from these totals.

## Implementations

- **Stock:** the unchanged, hash-pinned `pdfjs-dist` dependency. Compare ordinary
  opening, metadata, attachment listing, selected-page text and pixels. Exact
  standard attachment bytes are checked in a separate supplement. It has no custom
  EA-PDF inspection API, so its total workload and memory are not directly
  comparable with a complete EA-PDF inventory.
- **Full:** the complete production patch stack, rebuilt byte for byte.
- **Control:** the XRef implementation before patch 0003, including upstream eager
  object-stream parsing, array entries and an unbounded strong parsed-object map;
  decoder reclamation and the single page-tree scans are disabled. Later
  zero-width-xref handling, resource-error propagation and a no-op cleanup API are
  retained for compatibility. Later metadata/extraction APIs, scoped metadata
  allowances, cooperative scheduling and asset traversal remain shared with full.
  This is a feature-equivalent control, not a literal reversal of every line of 0003.
- **Ablations:** full with, separately, array-backed xref storage; an unbounded
  strong parsed-object cache without weak identity tracking; decoder reclamation
  disabled; or repeated page lookups instead of one page-tree traversal.
  Array storage uses a small adapter to the current accessor interface; timings
  include that adapter. The control also changes lazy object-stream handling, so
  attribute effects to individual ablations before drawing conclusions.

## Protocol

Input bytes are immutable and hashed. Real 1m/1c archives and a small control are
supplemented by isolated dense classic/compressed object collections, a flat
2,500-page/DPart tree, sparse high object IDs and a dense xref stream whose decoded
layout exceeds 64 MiB. Synthetic files isolate behavior; they do not establish
real-mail capacity. The xref stream contains seven million entries, most free.

Five trials alternate forward/reverse implementation order. Each case and variant
uses a fresh Chromium or Firefox process and one document worker. A cold workload
opens the file, reads metadata and requests the lazy standard attachment listing, then renders/extracts
text from its first, middle and last pages. Feature-equivalent variants also inspect
assets, metadata, collection folders and DParts, then hash every discovered embedded
payload. The warm workload repeats after PDF.js cleanup within the same document.
This does not render every page or recursively open every embedded child PDF.

Per-browser text, pixel and metadata hashes must match exactly; the separate
standard-byte supplement also requires exact attachment hashes;
custom inspection and payload projections must match among feature-equivalent
variants. Cold/warm output equality is checked too. Explicit failures and missing
results remain visible. Pixel equality is not asserted between browser engines.

Linux browser-process-tree PSS is sampled every 100 ms, including document workers,
native buffers and browser overhead. Sampled maxima are lower bounds on true peaks.
Retained PSS is sampled with the document open and after destruction; this does not
prove garbage collection or a leak. Each workload has a 90-second deadline and
1-GiB external browser-PSS ceiling. Chromium additionally has a 512-MiB old-space setting per V8 isolate
; Firefox has no equivalent portable setting, so compare variants within
an engine. The production decoded-buffer/aggregate allowances remain 64/256 MiB
where applicable. Filesystem caches and unrelated host activity are not controlled.

Thresholds were recorded before the five-trial measurements: a 15% median latency
or sampled-PSS reduction, or avoidance of an explicit failure with identical output,
is a candidate benefit. A median increase over 10% on small controls calls for
review. Report ranges and paired variability; five trials do not establish
statistical significance or a capacity guarantee. Recommendations are scoped to
the measured workloads and existing correctness tests.

## Reproduction

Use the pinned Node/npm versions and activate the documented hash-locked Python
runtime (pikepdf 10.18.0). Install Chromium, Firefox, geckodriver and `pdfinfo`.
The real archive fixtures are optional local inputs; missing files are errors,
not silently replaced with synthetic content.

```sh
export PDFJS_COMPARISON_WORK="$(mktemp -d /tmp/eapdf-pdfjs-comparison-XXXXXX)"
node browser-viewer/scripts/large-archive-prepare.mjs
node browser-viewer/scripts/large-archive-variants.mjs
python3 browser-viewer/scripts/large-archive-corpus.py
node browser-viewer/scripts/large-archive-comparison.mjs
python3 browser-viewer/scripts/large-archive-summary.py
```

Set `CHROMIUM_PATH`, `FIREFOX_PATH` and `GECKODRIVER_PATH` when needed. The driver
records the corpus, build hashes, protocol, raw measurements and summary in the
explicit temporary directory. Retain the evidence before removing that directory.
`PDFJS_COMPARISON_CASES`, `PDFJS_COMPARISON_VARIANTS`, `PDFJS_COMPARISON_BROWSERS`
and `PDFJS_COMPARISON_TRIALS` allow diagnostic subsets; label these as pilots,
not the complete five-trial acceptance run. No repository settings or production
bundles are changed by this workflow.

To reproduce the supplemental compressed-decoder test after the main matrix:

```sh
PDFJS_COMPARISON_CASES=compressed-payload \
PDFJS_COMPARISON_VARIANTS=full,no-reclaim \
PDFJS_COMPARISON_ROUNDS=12 \
PDFJS_COMPARISON_RESULTS=compressed-reclamation.json \
node browser-viewer/scripts/large-archive-comparison.mjs
PDFJS_COMPARISON_RESULTS=compressed-reclamation.json \
PDFJS_COMPARISON_SUMMARY=compressed-reclamation-summary.json \
python3 browser-viewer/scripts/large-archive-summary.py
```

The compressed payload is diagnostic-only and excluded from the default matrix.
The real-collection supplement uses `real-archive-c` with the same overrides.
Execution metadata records the requested subset and repetition count. For the
standard-byte comparison, set `PDFJS_COMPARISON_TRIALS=1` and select `stock,control,full`, the two real archives and
`compressed-payload`, set `PDFJS_COMPARISON_ATTACHMENT_BYTES=1`, use two rounds and
choose a separate result filename. Keep those timings separate from the original
listing-only matrix.
