Project: Email → EA-PDF generator

Build:
mvn package

Main entry point:
src/main/java/EmailToPDFA.java

Email source readers:
src/main/java/EmlReader.java
src/main/java/MsgReader.java
src/main/java/PstReader.java
src/main/java/GmailDownloadToPdf.java

PDF generation:
src/main/java/PdfMailWriter.java

Shared helpers:
src/main/java/Common.java
src/main/java/Models.java

Goal:
Convert email sources (EML, MSG, PST, Gmail) into EA-PDF compliant files using PDFBox and XMPBox.

Rules:
- Work on EA-PDF creation, validation, and reader development; the user authorized reader development on 2026-11-03
- Do not add new features without the user's express permission
- Keep output text plain and succinct
- Keep GitHub release notes brief: versions, a few user-visible changes, essential setup information, and beta status. Put implementation details, benchmarks, test counts, and audit evidence in project documentation.
- Do not modify CLI arguments unless asked
- Preserve existing debug output
- Prefer minimal targeted edits
- For future file regenerations, enable Accessibility (`accessibility.addPdfUaFeatures: true`), including when using a custom configuration
- For conversion runs, use bounded parallel processing wherever supported and use the standalone Java validator. Retain the Python validator for its regression tests and explicitly requested comparisons or diagnostics.
- Keep example outputs in examples/<input-type>/ and prefix their filenames with the lower-case input type (for example, eml_sample_1s.pdf).
- Use the real-world files under in/ for future conversion regressions. Preserve originals, record source hashes, and retain conversion logs and Java validation reports; report rejected or incomplete files explicitly. Use synthetic fixtures only to isolate additional edge cases.
- Do not modify files in target/ or debug directories
- Put temporary builds and working copies in /tmp; remove obsolete copies after use and at task checkpoints. Clean only task-owned temporary files; preserve active files and unrelated /tmp contents
- PDF validation staging may use the Docker-shared .generator_web_tmp directory; always remove staged files after success or failure
