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

- `searchIndex.enabled` defaults to `true` for embedded search indexes. `searchIndex.fullTextEnabled` independently controls full-text generation for `1m`, `1mi`, `1c`, and `1ci`; it uses built-in PDFBox extraction, with no Node.js requirement. Extraction failures or exceeded limits produce a warning and retain ordinary PDF.js searching. See [format, limits and fallback](../docs/EMBEDDED_SEARCH_INDEX.md).
- `branding.enabled` controls branding, including collections.
- `findingAid.pdfEnabled` defaults to `false` and controls the optional finding-aid pages and related embedded or sidecar spreadsheets. Collections still include required front matter when the index is disabled.
- `findingAid.excelEnabled` defaults to `false`, both in built-in settings and the checked-in configuration; `embedExcel` independently defaults to `true`.
- For collection profiles `1c`/`1ci`, the existing `embedPdfInCollections` setting requests a separate `_finding_aid.pdf` sidecar. `embedExcel` or `excelEnabled` requests a separate `_index.xlsx` sidecar. These legacy setting names do not cause collection embedding: EA-PDF §7.6 prohibits ordinary finding-aid PDFs and spreadsheets as collection members. The finding-aid PDF has no embedded XLSX or synthetic email; its index pages also remain in the collection front matter. Disabling `pdfEnabled` disables optional finding aids and their sidecars.
- For flat archives `1m`/`1mi`, `embedExcel` still embeds the XLSX in the archive and `excelEnabled` independently writes an external XLSX. Single-message source/native-attachment handling is unchanged.
- `premis.enabled` controls PREMIS metadata and its sidecar. There is no `premis.writeSidecar` setting.
- Collection PREMIS is sidecar-only at the container level; non-isolated email children may retain their own embedded PREMIS.
- `accessibility.addPdfUaFeatures` enables semantic headings, paragraphs, tables, image descriptions, and annotation associations in message PDFs and collection/archival front matter. It remains partial accessibility support, not proven PDF/UA conformance.
- The three `accessibility.experimental` settings are unavailable. Setting them to `true` produces a warning; they do not change output.

## Processing And Fonts

All conversion entry points now use supervised subprocesses, including GUI-launched work. Whole-job/native/storage/page/aggregate-image/font limits and recoverable PDF/sidecar publication are described in [conversion operations](../docs/CONVERSION_OPERATIONS.md). Public CLI arguments are unchanged.

These environment variables also have JVM property equivalents:

| Environment variable | JVM property | Default |
| --- | --- | --- |
| `EAPDF_MAX_MESSAGE_BYTES` | `eapdf.maxMessageBytes` | 64 MiB, capped at one sixteenth of the Java heap |
| `EAPDF_MAX_BODY_CHARS` | `eapdf.maxBodyChars` | 2,097,152 characters, capped at one sixty-fourth of the Java heap |
| `EAPDF_MAX_MESSAGES` | `eapdf.maxMessages` | 10,000 messages/inputs or collection members |
| `EAPDF_MAX_METADATA_BYTES` | `eapdf.maxMetadataBytes` | 32 MiB of estimated record/warning/checkpoint metadata, capped at one eighth of the Java heap |
| `EAPDF_MAX_MIME_DEPTH` | `eapdf.maxMimeDepth` | 32 multipart nesting levels, never more than 64 |
| `EAPDF_MAX_MIME_PARTS` | `eapdf.maxMimeParts` | 2,000 MIME parts per message |
| `EAPDF_MAX_IMAGE_PIXELS` | `eapdf.maxImagePixels` | 16,000,000 pixels per raster image, capped at Java heap bytes / 32 |
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

Set the top-level `creator` string to the person, organization or service
responsible for creating the EA-PDF resource, for example
`"creator": "Example Archives"`. The GUI's **Creator identity...** button sets
this value for its conversion session. Save it in the configuration for future
sessions. Conversion requires a nonblank value; no identity is inferred from
the software name, email sender or operating-system account.

The value becomes the first `dc:creator` item. `xmp:CreatorTool` and
`pdf:Producer` continue to identify software. Validation checks the required
metadata structure separately from provenance-backed attribution.
