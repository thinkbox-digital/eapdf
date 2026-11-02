# Interfaces, configuration and operations

These tables describe existing interfaces. The review does not change CLI arguments. A port should retain compatibility unless a separately approved migration changes it.

## Local CLI

Entrypoint: `eapdf.EmailToPDFA`; packaged main: `java -jar emailtopdfa-1.4.0.jar`.

```text
EmailToPDFA [options] INPUT
```

INPUT is one file or a recursively scanned directory. Supported suffixes are `.eml`, `.msg`, `.pst`, `.mbox`, case-insensitive. Directory candidates are sorted; unsupported files and non-mail `.msg` candidates can be skipped with diagnostics. There is no public `--profile s` argument. For normal per-message conversion, embedding selects `s`, omission selects `si`, with the PST special case described in the profile matrix.

| Switch | Meaning / default |
| --- | --- |
| `--out PATH` | Output directory; `out` |
| `--font PATH` | Primary TTF; local Liberation Sans if present, otherwise bundled DejaVu Sans |
| `--icc PATH` | ICC output profile; bundled sRGB |
| `--global-id VALUE`, `-g VALUE` | Archive context metadata; no filtering |
| `--email VALUE`, `-e VALUE` | Repeatable archive-owner metadata; no participant filtering |
| `--jobs N`, `-j N` | Positive worker request; default 1, further bounded by work/CPU/heap |
| `--omit-source` | Normal per-message isolated output |
| `--archive-profile VALUE` | One of `m`, `mi`, `c`, `ci`; explicit archive policy controls embedding |
| `--archive-output PATH` | Full aggregate output path; requires archive profile |
| `--debug` | Retain current diagnostic output and intermediate debug artifacts |
| `--debug-dir PATH` | Default `<out>/_debug` |

Reject unknown options, missing values, extra positionals and invalid numeric/profile values. The current parser does not supply conventional `--help` or `--` processing; do not invent those as existing features.

Normal filenames use safe subject plus six-digit local sequence. Multiple input files additionally use input stem and a global sequence. Aggregate default names use the input stem/profile suffix. Explicit archive paths need not lie under `--out` for local CLI operation. Existing local destinations can be overwritten intentionally. Publication protects the previous PDF when staging/creator validation fails, but not all sidecars form one transaction.

Successful conversion exits 0. Handled user-facing errors print `error:` and exit 2. Unexpected exceptions propagate from `main` and may produce a JVM stack trace/exit 1. Preserve existing debug output; add structured diagnostics alongside it in a replacement. Local jobs can leave already completed PDFs after a later failure.

## Gmail CLI and GUI

Entrypoint: `eapdf.GmailDownloadToPdf`. There is no positional input argument and no `--jobs`. Common font/ICC/archive/global-id/email/debug/omit-source settings apply, with these differences:

| Switch | Meaning / default |
| --- | --- |
| `--user VALUE` | API user identity; `me` |
| `--credentials PATH` | Desktop OAuth JSON; `config/credentials.json`, then root `credentials.json` |
| `--token-dir PATH` | OAuth token store; `~/.gmail_token_store` |
| `--folder VALUE` | Exact label ID/name or selection; `ALL_MAIL` |
| `--out-pdf PATH` | Output directory; `out` |
| `--limit N` | Nonnegative selected-message cap; 0 means no requested cap, still subject to processing limits |
| `--unread-only` | Adds `is:unread` query |

Scope is Gmail read-only; the desktop OAuth receiver listens on port 8888. Resume storage is opt-in and survives success/failure until deleted. The API is not used to modify messages. Live remote behavior and permissions require a separate integration environment.

The GUI JAR main is `eapdf.EapdfGui`. It launches the same Java entrypoints as child processes using temporary JSON settings, captures merged output and forcibly kills the active process on Stop. It exposes branding/finding aid/PREMIS/partial-accessibility toggles, not a full PDF viewer or standards acceptance workflow. Closing/killing processes and private temporary-file cleanup require OS-specific tests in a port.

`eapdf.MonthlyFilenameArchiveToPdf` accepts optional positional directory, out/font/ICC/global-id/email/debug/debug-dir options. Its hardcoded default input path is machine-specific. It groups EML by a valid YYYYMMDD filename prefix and chooses `s` for one message, `m` otherwise. It does not group by the Date header month.

## Application JSON

Load `EAPDF_CONFIG` if set, otherwise `config/eapdf_config.json` or root `eapdf_config.json`. The singleton is cached for the process lifetime. Missing files select built-in defaults, including when an explicit environment path is absent. Invalid JSON/types/version fail; unknown keys warn. Null nested objects are normalized. A replacement should make the effective config explicit and fail an explicitly requested missing config path.

| Key | Built-in default | Checked-in override / behavior |
| --- | --- | --- |
| `configVersion` | 1 | Only version 1 accepted |
| `branding.enabled` | true | Branding footer |
| `findingAid.pdfEnabled` | true | Optional index; collection front matter remains required |
| `findingAid.excelEnabled` | false | Built-in and checked-in settings agree; controls XLSX sidecar |
| `findingAid.embedExcel` | true | Independent of sidecar flag; relevant when finding aid enabled |
| `findingAid.embedPdfInCollections` | true | Embed copied finding-aid PDF |
| `premis.enabled` | false | Optional XML sidecar and non-isolated embedding |
| `accessibility.addPdfUaFeatures` | false | Enables partial accessibility; not a conformance claim |
| `accessibility.markInfoMarked` | true | Used when accessibility features enabled |
| `accessibility.language` | `en` | Catalog language in enhanced mode |
| `accessibility.readingDirection` | `L2R` | Recognized values L2R/R2L |
| `accessibility.pageLabels.enabled` | true | Catalog labels in enhanced mode; also used by visible context helper |
| `accessibility.pageLabels.singleMessage` | false | Catalog page-label policy |
| `accessibility.pageLabels.archiveAndCollection` | true | Catalog page-label policy |
| `accessibility.pageLabels.frontMatterPrefix` | `Index-` | Blank value normalizes to default |
| `accessibility.experimental.artifactGeneratedBranding` | false | Unavailable; true warns, no implementation effect |
| `accessibility.experimental.semanticFindingAidTags` | false | Unavailable; true warns |
| `accessibility.experimental.altTextGeneratedImages` | false | Unavailable; true warns |

There is no supported metadata-map CSV input or `premis.writeSidecar` setting. Legacy FOP configs do not configure this Java renderer.

## Processing settings

JVM property takes precedence over the corresponding environment variable. Numeric settings must be positive integers; current heap caps apply even if a larger value is requested.

| Environment / JVM property | Default / effective limit |
| --- | --- |
| `EAPDF_MAX_MESSAGE_BYTES` / `eapdf.maxMessageBytes` | min(64 MiB, max(1024, heap/16)) |
| `EAPDF_MAX_BODY_CHARS` / `eapdf.maxBodyChars` | min(2,097,152 chars, max(1024, heap/64)) |
| `EAPDF_MAX_MESSAGES` / `eapdf.maxMessages` | 10,000; also used for inputs/folder memberships |
| `EAPDF_MAX_METADATA_BYTES` / `eapdf.maxMetadataBytes` | min(32 MiB, max(1024, heap/8)); estimated metadata/warnings/checkpoint budget |
| `EAPDF_MBOX_FORMAT` / `eapdf.mboxFormat` | `auto`; alternatives mboxo/mboxrd/mboxcl/mboxcl2 |
| `EAPDF_FALLBACK_FONTS` / `eapdf.fallbackFonts` | Bundled fallback + configured platform-path-separated TTFs; at most 16 fallback paths total |
| `EAPDF_GMAIL_RESUME_DIR` / `eapdf.gmailResumeDir` | Unset; no persistent checkpoint |

These do not cap total RSS, image pixels or wall time (R8). Capture actual effective limits and font/ICC hashes with each run.

## Validator CLI and result contract

```text
python3 validate_eapdf.py PDF_OR_DIRECTORY [--jobs N] [--json]
    [--html [PATH]] [--no-html]
```

Directory validation uses only directly contained `*.pdf` files, not a recursive walk. Default worker count is CPU count. HTML is generated by default at a unique name under `validation/`; `--no-html` disables it. A single JSON result contains `summary`, `pdfa`, `items`; the callable `validate_one` also returns `html_output`. A batch result contains batch `summary` and `files`.

Each finding has `level`, stable `code`, human `message` and structured `details`. Levels are PASS, WARN, FAIL, N/A. Summary includes finding counts and collapsed rule counts; do not confuse them. Overall status is PASS, FAIL or INCOMPLETE. The CLI exits 0 only for combined PASS, otherwise 2. Argument parsing errors also use argparse's exit 2. `validate()` alone omits the independent top-level PDF/A run; production callers should understand that difference.

The current status rule permits some normal warnings with PASS, causing R3. Replacement results should separately represent normative validity, fidelity, accessibility, source preservation and acceptance policy. Preserve codes as diagnostics where useful, but do not freeze their current severity mistakes.

## Web HTTP contract

The service renders HTML; it is not a JSON REST API and has no stored user accounts.

| Method/path | Input / output |
| --- | --- |
| GET `/` | Validation form |
| POST `/` | Multipart `pdfs` list; validation/comparison HTML |
| GET `/generate` | Local generation form |
| POST `/generate/local` | Multipart `inputs` and/or `input_folder`; form `profile`, `archive_output`, `output_dir` |
| GET `/generated/<id>` | UUID bearer capability; generated PDF inline |
| GET `/validation-report/<id>` | UUID bearer capability; saved HTML report |

Generation accepts relative upload names under a fresh staging root, rejects absolute/drive/parent paths and duplicate exclusive file creation, and only accepts supported mail suffixes. Output is confined to project `out/`, with per-request `.web-jobs/<uuid>`. PST uploads require aggregate profiles in this web workflow even though the CLI has a per-message path. Flask routes and forwarded-prefix handling must remain consistent behind a trusted proxy.

Uploads are deleted after processing. Result/job capability records are atomically stored under `.validator_web_tmp/registry`, including kind/path/expiry. Results survive worker restarts until expiry. Cross-process POSIX file locks limit active jobs; extra requests receive 429. Invalid input returns 400; oversized requests 413; unexpected/generation-process failures 500. A successful process alone can yield a generation PASS banner even if generated validation fails, so a replacement should use separate conversion and acceptance labels.

## Web/validator environment

| Setting | Current default |
| --- | --- |
| `HOST`, `PORT` (development server) | 127.0.0.1, 8766 |
| `VALIDATOR_RETENTION_SECONDS` | 86400, minimum 60 |
| `VALIDATOR_MAX_CONCURRENT_JOBS` | 2 |
| `VALIDATOR_MAX_UPLOAD_FILES` | 3 |
| `GENERATOR_MAX_UPLOAD_FILES` | 100 |
| `VALIDATOR_MAX_CONTENT_LENGTH` | 64 MiB per request |
| `VALIDATOR_REQUEST_TIMEOUT` | 600 seconds overall |
| `VALIDATOR_JOB_TIMEOUT`, `GENERATOR_JOB_TIMEOUT` | 600 / 300 seconds |
| `VALIDATOR_JOB_MEMORY_MB`, `GENERATOR_JOB_MEMORY_MB` | 2048 / 8192 MiB address space |
| Generator JVM heap | 512 MiB, plus class-space/code-cache limits |
| Captured output / file-size limit | 4 MiB; RLIMIT_FSIZE also affects files written by worker processes |
| `VERAPDF_DOCKER_IMAGE` | `ghcr.io/verapdf/cli:v1.31.18` |
| `VERAPDF_TIMEOUT` | 120 seconds per invocation |
| `VERAPDF_CONTAINER_NAME`, `VERAPDF_MOUNT_ROOT` | `verapdf-daemon`, repository root |
| `VERAPDF_JOB_CONTAINER` | Web-managed private container identity; not arbitrary user text |
| `VALIDATOR_CHILD_MAX_DEPTH`, `VALIDATOR_CHILD_MAX_COUNT`, `VALIDATOR_CHILD_MAX_BYTES` | 8, 256, 256 MiB decoded child budget |
| `VALIDATOR_CHILD_TEMP_ROOT` | Validator mount root; web assigns private report child directory |
| `EAPDF_GENERATOR_CLASSPATH` | Test/sample helper override; not web JAR selection |
| `EAPDF_INTEGRATION_REPORT_DIR`, `EAPDF_SAMPLE_REPORT_DIR` | Test/helper evidence directories |

Web generator JAR resolution tries versioned `target/`, then `dist/emailtopdfa.jar`, then newest candidate in `target/`. This can select a stale artifact; pin and verify the exact deployed artifact hash for production. The test override does not automatically update the web service.

## Operational handover

Use Java 21, an explicit Python environment and compatible pinned dependencies. Normal Maven output is `target/`; build elsewhere with `-Deapdf.build.directory=...`. Packaging produces CLI and `-gui` shaded JARs. Resources include fonts/ICC/branding/version metadata; resolve them without depending on the repository launch directory. Run headless workers independently of desktop UI.

The provided Gunicorn unit binds loopback port 8876 with two workers/four threads, 660 second timeout and www-data ownership. nginx forwards `/ea-pdf-validator/`; ProxyFix trusts one hop. The supplied example only shows an HTTP listener: provision authenticated HTTPS access explicitly for private mail. Keep proxy/application limits and cleanup-service retention aligned. Do not expose a proxy-trusting application directly.

Web validation uses private no-network Docker jobs, read-only input mounts and memory/CPU/PID limits. CLI validation may instead reuse a persistent container with a writable project mount and fewer outer controls. Docker socket access remains a host-level privilege boundary; isolate the validator service account appropriately. A future replacement should use a dedicated restricted worker interface instead of exposing general container control to the web request process.

Classify logs, debug XHTML/assets, reports and checkpoints as private mail data. `Cache-Control: no-store` and UUIDs do not implement tenant authorization. Define retention, access logging without message bodies, deletion, backup/restore, audit export and incident response. Test disk-full, out-of-memory, timeouts, abrupt kill and restarts, including orphan cleanup and container cleanup. Monitor counts, runtime, queue/capacity rejection, validation failures, incomplete results, reduced-rendering warnings, temporary disk and retained result size.
