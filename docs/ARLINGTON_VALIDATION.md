# Arlington validation

Arlington is an additional, separate check of PDF object-model requirements. It
runs locally through the pinned standalone veraPDF Arlington Java CLI, with
the reproducible implementation corrections described below. It does
not replace the project's Java EA-PDF/PDF/A validator or establish source-email
completeness, visual correctness, or accessibility conformance.

Install the pinned checker:

```sh
python3 scripts/install_arlington.py
```

Check the published example PDFs:

```sh
python3 scripts/validate_arlington.py examples --reports /tmp/eapdf-arlington --jobs 2
```

The input may also be a single PDF. The runner reads inputs without modifying
them and records hashes before and after checking. It retains the upstream JSON
and stderr log for each PDF, plus `summary.json`, `files.csv`, and `RESULTS.md`. Aggregated rule findings
are in `findings.json` and `findings.csv`, with affected-file and occurrence counts.
Reports refer to the exact bytes checked. Existing reports should be retained by
using a new report directory for each audit.

This runner checks PDF files found on disk, including finding-aid PDFs. Embedded
collection PDFs are not separately extracted or opened. Checking a collection's
outer object graph does not validate its embedded PDF payloads.

## Pinned tool and model

`config/arlington.json` pins veraPDF Arlington 1.30.3, the installer archive hash,
installed CLI JAR hash, and bundled PDF 2.0 profile hash. Upstream release notes
identify Arlington model revision `c48b363e9b78902deea03e958693c09339248a3a`.
The installer stores the tool under `dist/tools/arlington-1.30.3/`. Downloads and
installer working files use a task-owned `/tmp` directory and are removed.

The pinned release has a hard-coded 10,000-result retention ceiling, even with
both CLI limits disabled. The installer therefore downloads the exact released
`core-arlington` source archive, verifies its pinned hash, and compiles a narrow
`BaseValidator` adapter with that retention ceiling removed. The adapter is
loaded ahead of the unchanged upstream JAR. Model rules, assertions, and rule
counts are unchanged. Patched source and compiled class hashes are checked and
recorded. Installation requires JDK 11 or newer (`javac`); checking only requires Java.
A real April-PST regression retained all 10,025 findings instead of 10,002,
with identical per-rule totals and upstream compliance result.

The installer also builds `model-corrections` from the pinned released
`validation-model-arlington` sources. Two implementation errors are corrected:

- Binary `/CheckSum` length is measured in bytes, not decoded characters.
  Two actual 16-byte MD5 values beginning with FE FF were misread as UTF-16.
- A number-tree child receives its parent dictionary through the `/Kids` array,
  instead of receiving the array itself as its dictionary parent.

The profile XML and every rule predicate remain unchanged. Source and compiled
class hashes are recorded in each audit. `tests/java/ArlingtonSemanticsRegression.java`
checks 15-, 16- and 17-byte checksums and both root and child parent contexts.
Baseline reports in local records predate these semantic
corrections; later reports explicitly record the additional adapter. A PASS
therefore means PASS with the documented corrected implementation, not an
unmodified upstream binary.

The runner uses automatic PDF-version selection and explicitly enables the
`EAPDF_1` extension. Both `--maxfailures` and `--maxfailuresdisplayed` are `-1`;
there is no finding-count cutoff. Defaults are two concurrent Java processes,
4 GiB heap per process, and a 30-minute wall-clock allowance per PDF. The
runner's `--jobs`, `--heap-mb`, and `--timeout` options adjust those execution
resources. Scratch directories under `.generator_web_tmp` are removed on
success, error, or timeout. No Docker service or file upload is needed.

## Interpreting results

- `PASS`: the checker completed without reported deviations.
- `WARNINGS_ONLY`: only explicit PDF-version deprecation rules were reported.
- `DEVIATIONS`: at least one non-deprecation deviation was reported.
- `INCOMPLETE`: the process/report could not establish completed checking,
  finding details were truncated, or input bytes changed.

The upstream checker counts deprecations as failed checks. Its original
`compliant` result and full report are retained; the local category does not
rewrite that result. Counts represent reported occurrences, not distinct PDF
objects: one object may be reached and reported through several relationships.
A completed audit can contain deviations; findings need review against the
specification and the pinned model before changing generator behavior.

Exit codes are 0 for all PASS, 1 for completed runs containing deviations or
warnings, and 2 when any file is incomplete. Installation or argument errors
also return nonzero.

Arlington does not model content-stream operators or PDF file-layout rules.
The veraPDF implementation also documents unsupported predicates, including
`AlwaysUnencrypted`, `FontHasLatinChars`, `Ignore`,
`IsLastInNumberFormatArray`, `IsMeaningful`, and `KeyNameIsColorant`. It documents
additional checks and model interpretations. Completion here means completion
within that implementation's stated scope, not exhaustive ISO certification.

Sources: [Arlington model](https://github.com/pdf-association/arlington-pdf-model),
[veraPDF implementation notes](https://github.com/veraPDF/veraPDF-arlington-tools),
[release notes](https://github.com/veraPDF/veraPDF-arlington-tools/blob/master/RELEASENOTES.md).
