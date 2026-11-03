# Supervised conversion and recovery

## Entry points and isolation

Local, Gmail and monthly CLI entry points now supervise a fresh JVM without changing their public arguments. GUI-launched conversions use the same supervisor. The worker inherits configuration/debug properties and forwards ordinary stdout/stderr; user-visible output paths remain the requested paths, not internal staging paths.

Workers use a private temporary workspace, bounded heap/direct/class/code memory, a whole-conversion deadline and monitored temporary storage. On Linux, `/usr/bin/prlimit` (util-linux) additionally bounds native address space, CPU time and individual file size; it is required rather than silently skipping these controls. Other platforms retain JVM/deadline/storage controls but need platform-native resource verification before hostile-input deployment. These controls are process/resource isolation, not a filesystem/network security sandbox for compromised third-party code. Use a dedicated service identity/container and least-privilege read-only inputs for that threat model; do not grant unrestricted Docker-socket privileges casually.

The GUI Stop action requests supervisor shutdown off the UI thread. Shutdown terminates/reaps descendants before cleanup; a hard fallback has a bounded wait. An independent worker watchdog exits when its supervisor disappears. On the next invocation, owned abandoned workspaces are reclaimed only after checking process start identities, not PID alone. Active workspaces and other users' directories are left alone.

## Output transaction

Final PDFs, PREMIS and spreadsheet sidecars are written privately and checked before publication. Internal temporary collection children are **not** final outputs and remain readable in the private worker workspace. For c/ci, requested finding-aid PDFs and XLSX indexes are separate sidecars; the ordinary index PDF has no embedded files or synthetic email. Its rendered pages and bytes consume the existing page/output limits. The legacy `embedPdfInCollections` setting requests a PDF sidecar and `embedExcel` or `excelEnabled` requests an XLSX sidecar. Flat m/mi archives retain XLSX embedding through `embedExcel`; `excelEnabled` independently requests an external XLSX. Container PREMIS remains a sidecar. A worker failure, native resource failure or timeout publishes no staged output and cannot overwrite an existing PDF. Gmail completion hashes describe staged bytes; if publication fails, the next resume detects missing/mismatched published files and rebuilds them.

After successful conversion, the supervisor verifies the bounded manifest and payload checksums, prepares same-filesystem destination files, records a recovery journal, and replaces the requested outputs with rollback backups. Ordinary publication/disk failures restore original files and remove new outputs. Crash journals distinguish incomplete replacement (restore originals) from committed replacement (keep new files and remove backups). Failed recovery retains its directory/backups and reports their location; do not delete them manually before recovery. Cooperative GUI cancellation waits around the commit phase instead of interrupting a half-completed rename sequence.

This is recoverable multi-file publication, not instantaneous multi-file visibility or an ACID transaction across filesystems/power loss. External readers should wait for successful process exit; serialize independent jobs targeting the same destinations. User-requested intentional overwrite behavior remains unchanged. Logs retain failure evidence and are not rolled back as if the attempt had never happened.

## Limits

All settings are positive integers, with JVM properties taking precedence over corresponding environment values. Message/body/metadata byte budgets, per-image allocation and parallel-worker resource controls still apply. Content counts and nesting depth do not impose fixed cutoffs.

| Environment | JVM property | Default |
| --- | --- | --- |
| `EAPDF_JOB_TIMEOUT_SECONDS` | `eapdf.jobTimeoutSeconds` | 600 seconds per conversion worker |
| `EAPDF_WORKER_HEAP_BYTES` | `eapdf.workerHeapBytes` | Automatic: half available memory; explicit worker or launcher limits remain effective |
| `EAPDF_WORKER_ADDRESS_BYTES` | `eapdf.workerAddressBytes` | Chosen heap plus 4 GiB of native address space (unless explicitly set) |
| `EAPDF_MAX_TEMP_BYTES` | `eapdf.maxTempBytes` | 2 GiB temporary workspace and publication-preparation budget |
| `EAPDF_MAX_TEMP_FILES` | `eapdf.maxTempFiles` | 100,000 temporary files/inodes |
| `EAPDF_MAX_OUTPUT_BYTES` | `eapdf.maxOutputBytes` | 1 GiB per PDF/output and total staged output |
| `EAPDF_MAX_FONT_BYTES` | `eapdf.maxFontBytes` | 16 MiB per configured font |

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

Future runs write `progress.json` in the output directory. The file contains
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

The web application's default request cap follows disk space, up to 16 GiB.
Worker deadlines follow input bytes, bounded at 24 hours; explicit job/request
limits still win. Worker memory is shared across the configured job-slot count.
The per-file disk limit follows free disk space, up to 64 GiB per file. Conversion
logs are retained on disk; truncating their on-page tail does not stop conversion.
Validation JSON is retained separately from its bounded console log. Docker
veraPDF uses the same size-based deadline calculation (the existing
`VERAPDF_TIMEOUT` override still wins) and a share of each web job's memory allowance,
with four CPU cores and a finite container memory limit. Its old two-minute /
512 MiB defaults no longer override the larger web job allowance. These changes
do not prove that the Python/Docker engine can validate the full large corpus;
the standalone Java engine remains the required conversion regression validator.

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
