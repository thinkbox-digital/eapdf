# Validator memory follow-up — 2026-10-30

The standalone validator is runnable. IntelliJ’s available run configurations did
not expose its entry point; that finding only concerns launching an attached IDE
debugger. The largest-file checks run the Java entry point directly, with JFR,
GC logs, native-memory summaries and process samples.

**Close-out scope:** further large-PST work is excluded at the user’s direction.
The stopped run will not be resumed. Retain its evidence and use ordinary-input
regressions to close out the workspace revision; no largest-file gate remains.

The follow-up applies these memory repairs within the current validation model:

- Release logical-structure traversal indexes after their last consumer. Retain
  mail properties and ancestor chains needed by the later source checks.
- Store extracted page text in protected disk scratch, preserving exact UTF-16
  code units. Load only a requested page’s text; remove scratch on close.
- Memoize immutable attachment metadata fields during matching. Preserve source
  I/O fallback, candidate order and ambiguity handling.
- Share immutable queued diagnostic-path prefixes and format paths when rules
  need them. Keep traversal, IDs, diagnostic text and rule profiles unchanged.
- Reuse bounded, thread-local single-byte filter buffers with independent buffers
  for reentrant reads.
- Retain exact disk-backed identity storage and compact ASCII records. The tested
  mapping-window limit was removed after profiling exposed excessive map/unmap
  activity; mappings are released when storage closes.
- Store up to three veraPDF dictionary entries inline, promoting to HashMap on
  the fourth key. Preserve canonical key/value objects, mutable copy aliases,
  backed views and the original default HashMap iteration order. Mutable PDF
  names and values are not interned or shared between independent objects.

The earlier follow-up without compact dictionaries passed 205 validator tests
(nine skips), two executable-JAR checks and all 22 retained real-email matrix
PDFs. Rule/level outcome counts, PDF/A assertion totals and original source/PDF
hashes match. [Acceptance evidence](followup-acceptance.json).

The compact dictionary candidate additionally passes aliasing, backed-view,
mutation and randomized HashMap-order comparisons, including colliding keys.
The frozen bounded-mapping artifact passes **208 validator tests** (nine skips), **two
packaging checks** and **all 22 retained real-email PDFs**, with unchanged
rule/level counts, PDF/A assertion totals and input hashes. Its SHA-256 is
`85253bc826f21c3f723a50947707c89f64ee5ec4632fd888bbe0b54d2d132e13`.
[Frozen artifact acceptance](compact-model-acceptance.json). Its largest-file
comparison was stopped by the user during ISO 32005, after PDF/A passed, using
the same 19 GiB heap and four-processor cap. That run has no final validation verdict and is excluded from further acceptance.
[Stopped-run evidence](followup-stopped.json).
The installed validator has not been replaced.

## Remaining source-evidence findings

All 48,129 embedded EML headers were scanned, with no oversized or skipped
headers. One source candidate matches the unresolved email’s Message-ID and
18,669-byte size, plus raw From, raw To and sent instant. Its embedded role is
Source; the retained bytes have MD5 `8b07757ccbe1745981bdbea3c8435865`.

The preserved source Subject contains two U+0016 control characters omitted
from displayed headers and XML metadata. The creator recorded this limitation
explicitly. Exact/decoded header comparison therefore does not accept the
candidate. The missing body-media comparison follows from that unresolved
binding; it is not evidence of a second missing source email. Nested-source
ambiguity also prevents the universal outer-header contradiction check.

The original PST and unexported MAPI properties were not embedded under the
existing PST-to-RFC822 export policy, also recorded by the creator. The original
PST remains preserved in `in/`. MBOX similarly embeds extracted/processed RFC 822
messages, leaving its container and envelope outside the PDF. This is the selected
preservation scope, not an outstanding container-embedding repair. The derivation
notice alone does not set conversion:status to INCOMPLETE: that status comes from
sourceProblems, which the validator propagates. This PDF also has recorded
header-fidelity and unresolved source-comparison findings. Completed conversion
and validation execution do not establish complete native-container preservation. [Filtered investigation evidence](source-evidence-review.json).
Private diagnostics and the matched EML are retained in the output evidence
folder. The temporary extracted XMP copy was hashed and removed.

Remaining ownership costs include eagerly loaded canonical veraPDF objects,
wrappers and model lists. An object-lifetime redesign requires broader parser
and rule-equivalence work; evicting mutable canonical objects is not part of
this repair.

The initial path/ASCII/property-cache candidate completed in 5,395.27 seconds
with 23.16 GiB peak process RSS, compared with 6,156.96 seconds and 25.04 GiB
for the earlier bounded-cache baseline. All finding counts and both upstream
assertion totals match exactly; input hashes match and scratch is removed.
These are local, non-isolated measurements; diagnostics and overlapping work
differed between runs. [Initial largest-file comparison](initial-candidate-largest.json).

The user stopped the frozen bounded-mapping run. No validator or completion
watcher remains running; its scratch was removed and logs/profiles retained.
The late ISO profile shows mapping/unmapping dominating sampled native activity
(unmap 66.53%, map 16.90%; native samples, not total runtime percentages).
The workspace removes the mapping limit while retaining the other repairs;
that revision passes 208 validator tests (nine skips), two packaging checks and
all 22 retained small PDFs with unchanged outcomes, assertion totals and hashes.
[Final workspace acceptance](closeout-acceptance.json). No
replacement large-file run will be launched under the current scope.
