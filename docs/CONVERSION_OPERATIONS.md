# Supervised conversion and recovery

## Entry points and isolation

Local, Gmail and monthly CLI entry points now supervise a fresh JVM without changing their public arguments. GUI-launched conversions use the same supervisor. The worker inherits configuration/debug properties and forwards ordinary stdout/stderr; user-visible output paths remain the requested paths, not internal staging paths.

Workers use a private temporary workspace, bounded heap/direct/class/code memory, a whole-conversion deadline and monitored temporary storage. On Linux, `/usr/bin/prlimit` (util-linux) additionally bounds native address space, CPU time and individual file size; it is required rather than silently skipping these controls. Other platforms retain JVM/deadline/storage controls but need platform-native resource verification before hostile-input deployment. These controls are process/resource isolation, not a filesystem/network security sandbox for compromised third-party code. Use a dedicated service identity/container and least-privilege read-only inputs for that threat model; do not grant unrestricted Docker-socket privileges casually.

The GUI Stop action requests supervisor shutdown off the UI thread. Shutdown terminates/reaps descendants before cleanup; a hard fallback has a bounded wait. An independent worker watchdog exits when its supervisor disappears. On the next invocation, owned abandoned workspaces are reclaimed only after checking process start identities, not PID alone. Active workspaces and other users' directories are left alone.

## Output transaction

Final PDFs are written privately and checked before publication. Internal temporary collection children are **not** final outputs and remain readable in the private worker workspace. Branding, finding-aid/spreadsheet generation and PREMIS output have been removed. A worker failure, native resource failure or timeout publishes no staged output and cannot overwrite an existing PDF. Gmail completion hashes describe staged bytes; if publication fails, the next resume detects missing/mismatched published files and rebuilds them.

After successful conversion, the supervisor verifies the manifest and payload
checksums. It locks the destination directories across processes, prepares files
on the destination filesystem, and records a recovery journal before replacing
outputs. An automatic filename collision fails publication and preserves the
other job's output. Explicitly requested destinations can be replaced. The creator rejects an archive
destination that would overwrite one of its inputs.

Cancellation before the commit point rolls back completed replacements. The journal
lets recovery restore previous files when it can confirm ownership of the
installed output. Recovery retains the working data when ownership is uncertain.

For a multi-file job, the supervisor also copies the verified results to a
generation under `.eapdf-publication/` and atomically replaces `current.json`
after the batch is complete. Open `current.json` once, then resolve every
`files[].path` relative to `.eapdf-publication/<generation>/`. The manifest gives
each file's byte size and SHA-256. The `Batch manifest` line identifies the
immutable `manifest.json` for that job, even if another job advances `current.json`.
The generation copies stay available as output history for readers holding an
earlier manifest.

The direct PDF paths are compatibility copies with per-file replacement. Use
the manifest to read a consistent batch. A single-PDF job publishes its PDF
atomically and leaves the latest multi-file manifest available.

## Limits

All settings are positive integers, with JVM properties taking precedence over corresponding environment values. Message/body/metadata byte budgets, per-image allocation and parallel-worker resource controls still apply. Content counts and nesting depth do not impose fixed cutoffs.

| Environment | JVM property | Default |
| --- | --- | --- |
| `EAPDF_JOB_TIMEOUT_SECONDS` | `eapdf.jobTimeoutSeconds` | 600 seconds per conversion worker |
| `EAPDF_WORKER_HEAP_BYTES` | `eapdf.workerHeapBytes` | Automatic: half available memory; explicit worker or launcher limits remain effective |
| `EAPDF_WORKER_ADDRESS_BYTES` | `eapdf.workerAddressBytes` | Chosen heap plus 4 GiB native headroom plus the scratch allowance for file mappings (unless explicitly set) |
| `EAPDF_MAX_TEMP_BYTES` | `eapdf.maxTempBytes` | 80% of available working-disk space at job admission |
| `EAPDF_MAX_TEMP_FILES` | `eapdf.maxTempFiles` | No fixed default count; explicit overrides remain effective |
| `EAPDF_MAX_OUTPUT_BYTES` | `eapdf.maxOutputBytes` | Half the working-storage allowance |
| `EAPDF_MAX_FONT_BYTES` | `eapdf.maxFontBytes` | Java heap bytes / 32 per configured font |

The former `EAPDF_MAX_MESSAGES`, `EAPDF_MAX_MIME_PARTS`, `EAPDF_MAX_MIME_DEPTH`,
`EAPDF_MAX_PAGES`, `EAPDF_MAX_TOTAL_PAGES` and `EAPDF_MAX_TOTAL_IMAGE_PIXELS`
settings no longer stop content processing. Actual resource exhaustion fails the
job explicitly; incomplete output must not be accepted as complete.

Temporary aggregate size is sampled and publication space is budgeted; use filesystem quotas for a strict kernel-enforced aggregate disk reservation. Native codecs/vectors/fonts are bounded by worker address space/deadline, not a claim of accurate pixel accounting. Trusted filesystem publication can still be delayed by blocked OS storage; an external service watchdog/quota is required for strict host-wide availability guarantees.

## Non-SMTP email addresses

EA-PDF §9.8.3 permits raw Core Field representations when the source value cannot use a structured type. Preserve Exchange/X.500 and malformed/non-SMTP address values exactly in `Raw-From`, `Raw-To`, etc.; never fabricate an SMTP address or put the non-SMTP token into `foaf:mbox`. Valid SMTP agents remain structured, including mixed lists. The validator requires exact source/raw agreement before accepting this representation, rejects invalid FOAF mailbox entries, and retains `INCOMPLETE` when source preservation cannot be established.

## Standards and human acceptance

The full ISO/TS 32005:2023 copy is still needed for a complete clause audit. The publisher's public inclusion spreadsheet is explicitly informative, not a substitute for the normative text; its download was unavailable (HTTP 403) during that audit. Passing the bundled operational checks does not replace a complete clause audit. When Accessibility is enabled, the creator adds the PDF/UA-2 identifier and accessibility repairs. Machine validation does not establish meaningful image descriptions, correct reading order or usability with assistive technology.

Before a production release, retain signed results for meaningful image alternatives, assistive technology, complex-script shaping/reading order, viewer navigation/extraction, live Gmail authorization/resume and interactive GUI cancellation on target platforms. Those require human/account/platform access; passing automated tests alone does not certify them. Release notices now merge into both JARs, but actual distribution/relinking rights and proprietary branding authorization remain release decisions.

## Live conversion progress

Each run writes `progress.json` in a fresh `.eapdf-evidence-*` subdirectory of the output directory.
The creator prints its path on the `Progress:` line. Per-file logs share this job directory.
Debug mode creates a separate job directory under the selected debug directory. The progress file contains
`emails_completed`, `emails_total`, `total_known`, `stage`, `state`, and timestamps.
Archive work also reports `stage_completed`, `stage_total` and `stage_unit` for
bookmarks, document parts, embedding, page numbering, tagging, page streams,
embedded-file checks and output publication. Counts describe that stage only.
Saving reports bytes written with an unknown total. Stages without a measurable
total keep it `null`; no estimated overall percentage is presented.

`elapsed_seconds` and `stage_elapsed_seconds` show elapsed time. `last_progress`
changes when recorded work advances or a stage changes; a heartbeat alone does
not change it. `process_id`, `process_cpu_seconds`, `heap_used_bytes` and
`heap_max_bytes` provide activity information during opaque library operations.
CPU time is cumulative across threads in the current process; the process ID and
CPU counter change when the supervisor takes over publication. CPU time is not a
completion percentage. Creator metadata scanning now reports examined objects
with an unknown total (direct dictionaries also count), followed by counted
metadata streams; attachment annotation checking reports examined attachments. A quiet work counter can reflect a single long operation.

The conversion log prints `[progress]` lines on stage changes, final known counts
and at ten-second intervals. The GUI shows these in its status line as well as
Details. Reopen an already-running GUI after installing the updated JAR.
While reading an input, `emails_total` is `null` until the exact count is known;
`emails_discovered` shows how many source emails have been read so far. No extra
full input scan is performed just to obtain a total.

For an archive, completed emails have been rendered and added to the archive.
For a collection, they are unique completed email children (folder aliases do not
increase the count). For separate-message output, they have passed the creator's
output checks and have been staged. Gmail includes already completed messages
reused during resume. A streamed input can therefore have completed emails while
its final total remains unknown. Snapshots refresh once per second, including during long assembly stages.
Stage changes, final email counts, input completion and job completion are written
immediately. The `updated` timestamp indicates a status refresh, not necessarily
another completed email.

Even when `emails_completed` equals `emails_total`, assembly, signing, checks, and
publication can remain. Only `state: COMPLETED` means the conversion has published
its outputs. A separate standalone-validator run is not included in that state.
FAILED or CANCELLED retains the last recorded counts. An abrupt process kill can
leave the last snapshot; it is not proof of successful conversion. For concurrent
jobs, use different output directories; the latest job owns a shared directory's
progress file.

Automatic heap selection happens once, before the worker starts. It uses Linux
MemAvailable (or the platform memory API) and container headroom when available,
and leaves at least half available memory for the OS, other programs, native
allocations and scratch storage. The default launcher JVM heap no longer acts as
an accidental worker cap. Explicit worker settings and explicit launcher heap
limits remain effective. The native address-space allowance grows with the heap.
This is headroom budgeting, not a guarantee that every workload fits in memory.
Existing running processes are unaffected.

To monitor a run, watch `progress.json` and follow the conversion log if output
has been redirected to one. Older builds may have quiet logs during assembly; `top -p PID`
shows process activity, not a completion percentage. Successful rendering alone
does not mean the PDF is ready: it remains staged until checks and publication
finish. A running process uses the build with which it started; installing a new
JAR does not update that process.

### Large PST working storage

When the default Java temporary directory is memory-backed (for example Linux
`/tmp` on tmpfs), large PST jobs use `<output>/.eapdf-work/` on the output disk.
The selected location is printed before conversion. If both locations are
memory-backed, choose a disk output directory or set the existing JVM
`-Djava.io.tmpdir` property to a disk directory. No CLI arguments were added.

The automatic large-PST temporary allowance uses 80% of currently usable space
on that working filesystem, leaving 20% headroom. The aggregate monitor and
explicit temporary-storage overrides remain enforced. The OS per-file limit
allows the larger of the temporary and output budgets, because a shared scratch
file can be larger than the finished PDF. Final output limits are enforced
separately before publication. Capacity changes caused by other programs can
still exhaust storage; this is not a promise of unlimited conversion capacity.

### Updated archive and validation allowances

Large local mailbox files and mixed input folders now receive the same automatic
budget selection and disk-backed scratch as large PSTs (128 MiB aggregate input
threshold). Final output
may use half the temporary-storage allowance; the former 8x input / 16 GiB cap is
removed. Explicit limits still win. Four rendering jobs are requested by the
corpus runner and web converter; large corpus inputs run alone.

The Python validator runs as a CLI. It accepts regular files up to 64 GiB
(`VALIDATOR_MAX_INPUT_BYTES`) and directories with up to 16,384 entries
(`VALIDATOR_MAX_DIRECTORY_ENTRIES`) and 1,024 PDF candidates
(`VALIDATOR_MAX_INPUT_FILES`). It creates private job directories under the
system temporary directory. With Snap Docker and system scratch under `/tmp`, it
uses `.generator_web_tmp` under the writable veraPDF mount root, which defaults
to the checkout. A read-only mount root falls back to the home directory. Set
`VALIDATOR_TEMP_ROOT` to an existing directory visible to Docker to override scratch.
The aggregate decode budget defaults to the larger of
8 GiB or 16 times the worker memory allowance, with an embedded-file limit of
100,000. Set `VALIDATOR_MAX_DECODED_BYTES` and `VALIDATOR_MAX_EMBEDDED_FILES`
to override those limits. `VERAPDF_MAX_REPORT_BYTES` overrides the report cap,
which defaults to the smaller of 64 MiB or one eighth of the worker memory allowance.
The CLI worker defaults to a 600-second deadline (`VALIDATOR_JOB_TIMEOUT`). Docker
veraPDF uses an input-sized deadline up to 24 hours (`VERAPDF_TIMEOUT`) and four
CPU cores with a finite container memory limit.

The standalone Java validator writes a durable incomplete checkpoint before work,
updates stage progress, and preserves a completed root PDF/A outcome if later
checking fails. A normal report, a compact `.summary.json`, and `.progress.json`
are separate files. Failure to finish is not itself a proven PDF rule violation.

Each upstream PDF/A or ISO 32005 pass runs on its own thread, which terminates
before the next PDF model opens. This releases the rule engine's private
thread-local state as well as its documented caches. Merely closing the parser
left a large model retained during the later EA-PDF checks in a real PST run.

Standalone validation reserves one third of currently available memory for native
allocations, mapped files and system headroom; the Java heap receives the other
two thirds. The corpus runner divides that heap allowance across concurrent
validation jobs and runs large inputs alone. This differs from the creator's
one-half automatic heap allowance. The change follows the measured 16 GiB
large-PST PDF/A pass; a smaller available heap can still prevent completion.


## Conversion diagnostics

`progress.json` retains the existing `conversion_warnings` text and adds
`conversion_diagnostics`. Each entry has a stable code, severity, source,
subject, Message-ID, message GUID, details, effect and suggested action.
Read these even when the job completes and validation passes. Keep the progress
file, logs and validator reports with the PDFs; a later job can replace progress.

| Code | Meaning |
| --- | --- |
| SOURCE_INCOMPLETE | Some source content could not be recovered. |
| TEXT_RECOVERED | Fallback decoding may have changed displayed characters. |
| DATE_PRECISION_REDUCED | Native PST fractional seconds became whole seconds. |
| BODY_FALLBACK | An available body or plain-text alternative was used. |
| SIGNATURE_UNVERIFIED | The email signature has not been verified. |
| ATTACHMENT_REFERENCE_UNRESOLVED | An inline attachment reference needs review. |
| ATTACHMENT_TYPE_RECOVERED | Declared attachment type and content disagree. |
| HEADER_CONFLICT | Source header values disagree; details identify the chosen value. |
| RENDERING_REDUCED | The displayed representation has a limitation. |
| REVIEW_REQUIRED | Another existing warning needs review; read its details. |

The entries do not certify that all content survived. Native PST/MBOX containers
are not embedded; reconstructed message derivatives are checked instead.
The creation receipt identifies published bytes and records creator checks.
Its independent PDF/A and EA-PDF checks remain NOT_RUN: keep the separate
standalone validator report for those results.

### Skipping creation validation for an explicitly requested run

Set `"validateOnCreation": false` in that run's `EAPDF_CONFIG` JSON to skip
post-write creator validation, including in-memory collection children. The
default is true. Skipped creation checks are recorded as `SKIPPED`; preservation
comparison and standalone PDF/A/EA-PDF validation are `NOT_RUN`. This option
does not disable atomic publication, output hashing, cancellation or resource
safeguards. It does not establish conformance. Future regeneration configs must
also enable `accessibility.addPdfUaFeatures`, per the user's preference.


## Creator GUI logs

The GUI keeps a recent tail of up to 500,000 detail characters and 100,000 summary
characters. Complete merged stdout/debug output is saved per conversion as UTF-8
under `~/.eapdf/logs/`; the current path appears in the GUI and final summary.
Display updates are batched without discarding the saved output. Existing logs
are retained rather than automatically deleted. The display limits bound retained
history, not the temporary allocation for an individual exceptionally long line.


## Regenerating local outputs


The installed `eapdf-regenerate` launcher prints basic instructions without arguments:

```bash
eapdf-regenerate sample
eapdf-regenerate everything
eapdf-regenerate everything --dry-run
eapdf-regenerate everything --workers 4 --jobs 2
```

Both modes preserve the files already in `out/`. `--dry-run`
only prints the plan. The default creator is `Thinkbox.DIGITAL`; no creator argument
is needed. Concurrent Java processes are bounded automatically by available CPU
and memory, with two creator threads per process by default. `sample` selects the
two smallest eligible sources of each type. `everything` covers every eligible
supported source in appropriate profiles. Both modes skip PSTs larger than
250 MB (250,000,000 bytes), printing each skip and recording its size and reason
in the run’s `sources.json`. Each PST/MBOX source produces whole-source `1m`, `1mi`,
`1c` and `1ci` archives only; neither mode extracts individual emails into standalone
PDFs. EML/MSG inputs retain standalone `1s`/`1si` outputs and are also randomly
grouped by type into archives of up to 20 messages, using all four archive profiles.
Jobs stay together by type: EML groups and standalone PDFs, then MSG groups and
standalone PDFs, then MBOX/PST archives. Every selected source is covered; groups
are balanced to avoid singleton remainders.
A one-message source cannot produce 1m/1mi and is reported as not applicable.
The run’s `plan.json` records the exact random group membership; a dry run makes
an independent preview plan.

The run stages and validates every PDF before publishing. A run with failures
leaves prior outputs in place. When a name is occupied by different bytes, the
new PDF gets a digest suffix before its profile suffix.

Final PDFs go directly into `out/eml/`, `out/msg/`, `out/mbox/`, and `out/pst/`.
Each run snapshots its versioned creator and validator JARs so later upgrades
do not interrupt it. Source hashes, logs, progress and standalone Java validation reports go into the
run’s evidence directory under `output/`. Each process has isolated temporary
staging. Accessibility and both indexes are enabled; signing is disabled. Original
inputs are preserved. Failed or incomplete conversions are reported with a nonzero
exit status. Existing conversions using `out/` must finish before regeneration.
Conversion staging and Java scratch use private directories under
`.generator_web_tmp/` on the project disk, including when `/tmp` is memory-backed.
Each job remains isolated; temporary files are removed after success or failure.

Regeneration divides half the currently available output-disk space among its
concurrent jobs for scratch, with half each job's share available to one work
file or report. There is no fixed MiB/GiB report ceiling. Explicit
`VALIDATOR_MAX_WORK_FILE_BYTES` and `VALIDATOR_MAX_SCRATCH_BYTES` settings take
precedence; each document's result records its budgets.

If validation returns FAIL or INCOMPLETE, or is unavailable, regeneration retains the initial
reports under `initial-validation/`, regenerates once with a visible
**WARNING — Validation requires review** banner, then validates the replacement.
The banner discloses failed or unfinished checks; it does not turn the result into PASS.
Source-data warnings remain separate. The warning attempt and its reports help
diagnose the failure. Both attempts stay unpublished. This requires the updated creator JAR.

At completion, the terminal lists FAIL and INCOMPLETE jobs, affected output files
(or sources that produced no PDF), and their conversion logs and validation reports.
Counts distinguish passed, failed, incomplete and not-applicable jobs. The same
report is saved as `summary.txt` beside the detailed `summary.json` in the run
evidence directory.
The maintained sources are `scripts/eapdf-regenerate` and `scripts/regenerate_out.py`.
