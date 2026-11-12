Project: Email → EA-PDF generator

Build:
mvn package

Main entry point:
src/main/java/eapdf/EmailToPDFA.java

Email source readers:
src/main/java/eapdf/EmlReader.java
src/main/java/eapdf/MsgReader.java
src/main/java/eapdf/PstReader.java
src/main/java/eapdf/GmailDownloadToPdf.java

PDF generation:
src/main/java/eapdf/PdfMailWriter.java

Shared helpers:
src/main/java/eapdf/Common.java
src/main/java/eapdf/Models.java

Goal:
Convert email sources (EML, MSG, PST, Gmail) into EA-PDF compliant files using PDFBox and XMPBox.

Rules:
- Work on EA-PDF creation, validation, and reader development; the user authorized reader development on 2026-11-03
- Do not add new features without the user's express permission
- Keep output text plain and succinct
- Whenever the user requests a push to GitHub, also publish the corresponding GitHub Release with versioned distributable assets and checksums; use prerelease status for beta versions. Retain only the current release and its immediate predecessor; verify the new release assets before deleting older releases.
- Keep GitHub release notes brief: versions, a few user-visible changes, essential setup information, and beta status. Put implementation details, benchmarks, test counts, and audit evidence in project documentation.
- Do not modify CLI arguments unless asked
- Preserve existing debug output
- Prefer minimal targeted edits
- For future file regenerations, enable Accessibility (`accessibility.addPdfUaFeatures: true`), including when using a custom configuration
- For future conversion and example-regeneration runs, disable optional PREMIS (`premis.enabled: false`), including in custom configurations, unless the user requests it.
- For conversion runs, use bounded parallel processing wherever supported and use the standalone Java validator. Retain the Python validator for its regression tests and explicitly requested comparisons or diagnostics.
- Keep example outputs in examples/<input-type>/ and prefix their filenames with the lower-case input type (for example, eml_sample_1s.pdf).
- Use the real-world files under in/ for future conversion regressions. Preserve originals, record source hashes, and retain conversion logs and Java validation reports; report rejected or incomplete files explicitly. Use synthetic fixtures only to isolate additional edge cases.
- Do not modify files in target/ or debug directories
- Put temporary builds and working copies in /tmp; remove obsolete copies after use and at task checkpoints. Clean only task-owned temporary files; preserve active files and unrelated /tmp contents
- PDF validation staging may use the Docker-shared .generator_web_tmp directory; always remove staged files after success or failure

- For future generated outputs, append the export profile to the filename (for example, `_1s.pdf` or `_1c.pdf`). For full `in/` to `out/` regenerations, choose suitable profiles and include a representative mix of profiles across input types.
- For future bulk `in/` to `out/` generations, publish generated documents directly under `out/eml/`, `out/msg/`, `out/mbox/`, and `out/pst/`, without per-input or other deeper subfolders. Keep logs, JSON manifests/progress files, and validation reports outside `out/`, in the run evidence directory under `output/`. Use isolated staging directories to prevent concurrent jobs from overwriting progress files before publishing final documents.

- Public documentation must not cite or link to unpublished generated documents or evidence under the local output directories. Summarize verified results in tracked documentation; use published examples or release assets for downloadable files. Use explicit temporary destinations in command examples.

- Whenever the user requests a new version or a version bump, also bump the browser viewer version and rebuild both viewer packages.
- Representative bulk samples must be balanced by output count: retain only a handful of standalone 1s/1si PDFs per input type, group EML/MSG sources into 1m/1mi archives, and use predominantly 1c/1ci/1m/1mi for PSTs. Preserve every source in the grouped/archive coverage.

- Signature-compatible embedded full-text indexing was authorized on 2026-11-07; see docs/EMBEDDED_SEARCH_INDEX.md for its supported signing workflow and limits.
- Produce distributable JARs only with the project version in their filenames; do not create unversioned creator, GUI, or validator JAR aliases.

- When summarizing fixes, classify the work as Bug fix, Code improvement, New code, Speed improvement, Memory improvement, or another clear top-level category. Distinguish measured improvements from expected ones.

- Keep only the latest versioned creator, GUI, and validator JARs in `dist/`; remove superseded JARs and their checksum files after verifying replacements.
- Keep the viewer’s independent CSV metadata export; removing creator finding-aid generation does not remove CSV export.

- The major-version bump and publication were authorized on 2026-11-09: creator/validator 2.0.0-beta.1 and browser viewer 1.0.0-beta.1. Future publication requires user authorization.

- For eapdf-regenerate, both sample and everything must keep each PST/MBOX as whole-source 1m/1mi/1c/1ci archives; never schedule individual-email 1s/1si extraction from those containers. Random EML/MSG archive groups must be bounded (currently 20 sources), cover every selected source and record their membership in run evidence.

- Keep private correspondence and sample contributors anonymous in first-party documentation and release notes. Use generic interoperability labels; do not publish contributor names, company names or private sample filenames. Preserve required legal attribution and original input bytes. Run `python3 scripts/check_public_docs.py` before publication.

- Maintain current handover status in docs/PROJECT_STATUS.md and separation decisions in docs/ARCHITECTURE.md. Keep docs/DEVELOPMENT_STATUS.md and docs/private local and ignored. Update the authoritative user/reference guide when behaviour changes; historical audit counts remain dated evidence.
