# Config

Project runtime and support configuration files live here.

Current contents include:
- `eapdf_config.json`
- `credentials.example.json`
- `credentials.json` (local, ignored)
- `ea-pdf-linux.json`
- `ea-pdf-fop.xconf`

## Runtime Settings

`EAPDF_CONFIG` selects the JSON configuration file. An explicitly selected missing or unreadable file is an error; only an absent default configuration falls back to built-in settings. The supported `configVersion` is `1`; unsupported versions or invalid value types stop loading with an explanation. Unknown keys produce warnings that include the full setting name.

- `searchIndex.enabled` defaults to `true` for embedded search indexes. `searchIndex.fullTextEnabled` independently controls full-text generation for `1m`, `1mi`, `1c`, and `1ci`; it uses built-in PDFBox extraction. Extraction failures or exceeded limits produce a warning and retain ordinary PDF.js searching. See [format, limits and fallback](../docs/EMBEDDED_SEARCH_INDEX.md).
- `accessibility.addPdfUaFeatures` enables semantic headings, paragraphs, tables, image descriptions, and annotation associations in message PDFs and collection/archival front matter. It remains partial accessibility support, not proven PDF/UA conformance.
- The remaining `accessibility.experimental` setting are unavailable. Setting them to `true` produces a warning; they do not change output.

## Processing And Fonts

All conversion entry points now use supervised subprocesses, including GUI-launched work. Whole-job/native/storage/page/aggregate-image/font limits and recoverable PDF/sidecar publication are described in [conversion operations](../docs/CONVERSION_OPERATIONS.md). Public CLI arguments are unchanged.

These environment variables also have JVM property equivalents:

| Environment variable | JVM property | Default |
| --- | --- | --- |
| `EAPDF_MAX_MESSAGE_BYTES` | `eapdf.maxMessageBytes` | One sixteenth of the Java heap |
| `EAPDF_MAX_BODY_CHARS` | `eapdf.maxBodyChars` | Java heap bytes / 64 characters |
| `EAPDF_MAX_METADATA_BYTES` | `eapdf.maxMetadataBytes` | One eighth of the Java heap for estimated record/warning/checkpoint metadata |
| `EAPDF_MAX_IMAGE_PIXELS` | `eapdf.maxImagePixels` | Java heap bytes / 32 pixels per raster image |
| `EAPDF_WORKER_TIMEOUT_SECONDS` | `eapdf.workerTimeoutSeconds` | 300 seconds per awaited parallel worker |
| `EAPDF_WORKER_SHUTDOWN_SECONDS` | `eapdf.workerShutdownSeconds` | 5 seconds to wait for cancelled workers |
| `EAPDF_FALLBACK_FONTS` | `eapdf.fallbackFonts` | Bundled DejaVu Sans, Droid Sans Fallback and Nanum Gothic; up to 16 fallback TTF paths in total |
| `EAPDF_GMAIL_RESUME_DIR` | `eapdf.gmailResumeDir` | Unset: no persistent email staging |

Numeric processing limits must be positive integers. Oversized inputs stop with an explanation; the program does not silently truncate email content. Heap-aware worker limits also apply to local parallel conversion. Limits bound accepted workloads, not total process RSS; font/native runtime memory is additional.

MIME preflight bounds traversal and cumulative decoded leaf bytes before body selection. Raster dimensions are checked from image headers before decoding inline/CID resources. A renderer rejection may produce the existing explicitly warned reduced-rendering fallback, not a fidelity guarantee. These are not process-isolation controls: initial third-party MIME parsing, single-threaded work, vector images, pathological codecs/fonts, and uninterruptible workers can still require an external process supervisor. A worker-shutdown warning means the process must be discarded rather than reused; daemon workers do not prevent JVM exit.

Separate fallback font paths using the platform path separator (`:` on Linux, `;` on Windows). Only explicitly configured fonts and the bundled fonts are used. Missing glyph warnings include field names and Unicode code points. Complex-script shaping and language-specific reading order still require review.

Gmail resume storage contains original private email bytes and checkpoint metadata. It is created only when configured, uses private POSIX permissions where supported, and remains until explicitly deleted. See [GMAIL.md](../docs/GMAIL.md) for recovery behavior.
# Upstream validation profiles

The Python validator ships a pinned [ISO 32005 profile selection](verapdf/README.md).
Deploy `config/verapdf/` with the validator and authoritative rule JSON. Complete
validation requires verified upstream execution for PDF 2.0 logical structure;
the profile excludes PDF/UA requirements.

## Digital signatures

Optional signing applies only to each outer EA-PDF after creation and repairs.
Enable **Sign output** and choose a `.p12`/`.pfx` file in **Signing settings...**
in the creator GUI. The password is requested when conversion starts.
For configuration, supported keys and limitations, see
[digital signatures](../docs/DIGITAL_SIGNATURES.md). CLI arguments are unchanged.

## Responsible creator

The creator defaults to `Thinkbox.DIGITAL`, including when older configuration files leave it blank. No command-line value is required. Override the top-level `creator` string or use the GUI’s **Creator identity...** button when a different responsible entity is needed.

The value becomes the first `dc:creator` item. `xmp:CreatorTool` and
`pdf:Producer` continue to identify software. Validation checks the required
metadata structure separately from provenance-backed attribution.

Branding, finding-aid generation and PREMIS output have been removed. Old configuration keys for these features are ignored with an unknown-setting warning. Collections retain their required cover page. The GUI checkbox is **Accessibility (PDF/UA)**.

The regeneration workflow sets `validationWarning` after a failed or incomplete
validation attempt. The “WARNING — Validation requires review” banner and its explanation are rendered visibly in message PDFs, with a notice
on collection covers. The initial and final validation reports remain in the
run evidence directory. The normal default is an empty string.
