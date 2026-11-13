# Security controls

Current source security baseline (reviewed November 7, 2026). Historical
checkpoint results below are evidence for their recorded versions, not a claim
that every acceptance check was rerun for the latest release. See the
[browser security boundaries](../../browser-viewer/docs/security.md) for current
reader controls. The former web service has been removed.

## Changes

- The creator only emits HTTP(S), mail and internal links it can resolve safely.
  Script, data, local-file and unknown-scheme links are removed from the rendered
  copy. HTML event attributes are removed. Original source bytes remain unchanged.
- PST and MSG compressed RTF headers and stored sizes are checked **before**
  expansion. Nested MSG storage has depth/count bounds before Outlook parsing.
  Existing message/body limits apply; oversized content stops with an explanation.
- Both validators bound decoded attachment/metadata bytes, filter chains, XML
  depth and attachment counts. Declared attachment sizes are not trusted.
  Java decoding writes through bounded private disk files before returning bytes;
  Python uses bounded incremental generalized decoders. Resource/unsupported-codec
  limits leave an incomplete assessment, including when a lower check catches the
  decoding error. A completed, independent violation can still produce FAIL.
- Direct CLI validation uses disposable worker processes. The Java GUI also supervises workers. Native memory/file/descriptor limits, wall time and
  aggregate scratch/report budgets limit parser damage; abnormal completion
  replaces stale success reports with INCOMPLETE. Workers and private scratch are
  removed on normal completion, cancellation and failure. The Java storage monitor
  tolerates files/directories removed while the worker is running.
- Production browser sessions enforce input, aggregate decoded-byte and concurrency bounds;
  ancestor cycles are rejected before work. Normal PDF.js rendering now shares
  the allocation guard used for attachment extraction. Linked-file fetches count
  streamed bytes and have a deadline. No PDF scripting interpreter is shipped;
  CSP excludes remote scripts, JavaScript eval, objects and forms. Metadata is text;
  HTML/SVG attachments remain inert source. Opening/extraction is user-initiated.

## Default limits

| Path | Default |
| --- | --- |
| Java disk-streamed non-image payload | Work-file allowance derived from available disk |
| Java in-memory payload / Python decoded stream | One quarter of worker heap / Python job allowance; Java array capacity also applies |
| Java/Python metadata stream | One sixteenth of worker heap / Python job allowance |
| Completed decodes / embedded-file count | No default lifetime byte/count cutoff; active memory and disk budgets remain enforced |
| Python optional lifetime quotas | Explicit `VALIDATOR_MAX_DECODED_BYTES` / `VALIDATOR_MAX_EMBEDDED_FILES` only |
| Python generalized stream filter chain | 8 filters |
| XML | Python: 64 levels / 100,000 nodes; Java and browser have no fixed node/depth cutoff |
| Validation worker open files | OS hard allowance; explicit `VALIDATOR_MAX_OPEN_FILES` remains effective |
| Java validation work file / aggregate scratch | One eighth / one quarter of free working-disk space; explicit overrides supported |
| Python CLI worker | Half available host/container memory; scratch uses half free project-disk space, with half that share per work file; 600 s; explicit overrides supported |
| Java worker | Heap bounded by launcher `-Xmx` and available-memory budget; Linux virtual-address allowance includes heap, 4 GiB native headroom and admitted scratch mappings |
| Java validation time | 600 s minimum, proportional to input bytes, maximum 24 h |
| Browser root / embedded stream / session extracted bytes | 4 GiB / 32 MiB / 512 MiB concurrent reservations |
| Browser retained child PDFs / child depth / active or pending workers | No fixed child-count/depth cutoff; 3 workers per session |
| PDF.js ordinary decoded buffer / cumulative buffer growth per worker | 64 MiB / 256 MiB |
| Browser image decode / PDF.js canvas bytes | 16 million pixels / 64 MiB |
| Explicit linked PDF | 64 MiB streamed bytes, 30 s, no cookies or redirects |

Validator settings use `VALIDATOR_MAX_STREAM_BYTES`, `VALIDATOR_MAX_METADATA_BYTES`,
`VALIDATOR_MAX_WORK_FILE_BYTES`, `VALIDATOR_MAX_SCRATCH_BYTES`,
`VALIDATOR_MAX_OPEN_FILES` and
`VALIDATOR_JOB_TIMEOUT`. Java also accepts the corresponding JVM properties for
stream/storage settings. Python additionally supports explicit lifetime quotas
`VALIDATOR_MAX_DECODED_BYTES` and `VALIDATOR_MAX_EMBEDDED_FILES`. Python CLI memory uses `VALIDATOR_JOB_MEMORY_MB`;
`VALIDATOR_MAX_CONCURRENT_JOBS` caps directory workers. Existing CLI arguments are
unchanged. Browser input/decoding ceilings can only be lowered by application session options. The 4 GiB input ceiling is an acceptance ceiling, not a guarantee that a browser has enough memory to open that input.
Python CLI and regeneration disk budgets divide half available disk among concurrent jobs.
Headroom shares are admission budgets, not reservations or promises that another process
will leave those resources free. Exhaustion remains an explicit incomplete outcome.
Raising limits cannot recover missing source data.

The Python bounded stream path supports unfiltered, Flate, ASCII85, ASCIIHex and
RunLength streams and combinations. LZW, predictor transforms and image codecs
are explicitly unavailable on that path. Java additionally supports LZW and
bounded predictor rows, but does not invoke image codecs for embedded payloads.
These restrictions can reject valid third-party files; do not describe INCOMPLETE
as proof of nonconformance. PDF/A/structure engines have their own parsing inside
resource-limited workers. A conformance PASS is not an attachment safety verdict.

## Limits

Resource exhaustion or unavailable evidence produces an incomplete assessment.
A conformance result does not establish attachment safety. Human accessibility,
platform compatibility and provenance require separate assessment. Linux worker
resource controls use util-linux `prlimit`; other platforms have different controls.

## Dependencies, updates and packaging

[Runtime inventories](runtime-inventory/DEPENDENCY_LICENSES.md) include CycloneDX
SBOMs for the resolved creator/Java validator runtimes and the tested Python CLI
runtime, with an exact Python version snapshot. They identify pre-shading JAR
hashes separately from application artifact hashes. `packaging` is needed to run
the inventory utility. Browser SBOMs, notices, license source material and hashes
are inside both extension builds. Font and proprietary-branding notices remain
in `third_party_licenses/`; local renderer corresponding source and replacement
instructions remain in `docs/RENDERER.md`.
The legacy stax-utils dependency lacks resolved license metadata and remains
explicitly marked REVIEW REQUIRED; automatic inventory is not license clearance.

Generate Maven runtime lists with the dependency plugin's `list` goal using
`-DincludeScope=runtime -DoutputAbsoluteArtifactFilename=true -DoutputFile=/tmp/...`.
Build into separate `/tmp` directories, then run:

```sh
python scripts/security_inventory.py \
  --creator-dependencies /tmp/creator-dependencies.txt \
  --validator-dependencies /tmp/validator-dependencies.txt \
  --creator-jar /tmp/creator-build/emailtopdfa-2.2.0-beta.2.jar \
  --validator-jar /tmp/validator-build/eapdf-validator-2.2.0-beta.2.jar \
  --output docs/security/runtime-inventory
```

Use an isolated Python environment satisfying `requirements-validator.txt`. The version
snapshot records this platform's installed runtime closure, not a portable wheel
hash lock. Do not inherit unrelated user-site packages via `PYTHONPATH`.

For a dependency update: resolve exact artifacts; review upstream advisories and
license/source obligations; reproduce local patches; run creator, validator,
Python, corpus, affected upstream and both native browser suites; compare frozen
semantic/source-byte expectations; regenerate SBOMs and hashes; build twice in
separate directories; publish with accurate limitations. A successful build does
not replace review of security advisories. The npm audit is recorded separately;
Java/Python dependencies have not received a fresh exhaustive advisory review in
this checkpoint. No advisory-free claim is made for them.

Use [PDF.js patch maintenance](../../browser-viewer/docs/pdfjs-patch-maintenance.md)
for source/hash/patch updates and [extension submission instructions](../../browser-viewer/docs/beta-release.md)
for signing, privacy declarations, clean-install and store checklists. There is
no store approval or automatic deployment in this work. Existing published
release artifacts stay immutable; candidate artifacts require a new release
version before distribution as a release.
