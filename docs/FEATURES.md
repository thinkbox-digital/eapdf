# Feature List

This file summarizes the current feature set of the project at a high level.

Current tracked release:
- `1.4.0`

Release 1.4.0 adds opt-in Gmail checkpoints, bounded processing, streaming PDF completion, multilingual font fallback, and semantic accessibility structures across all six profiles. Full PDF/UA conformance remains unverified.

See also:
- [CHANGELOG.md](/media/lepore/Work/thinkbox/convert/docs/CHANGELOG.md)
- [eapdf_decision_tree.pdf](/media/lepore/Work/thinkbox/convert/docs/eapdf_decision_tree.pdf)

## Input Types

- Local `.eml`
- Local `.msg`
- Local `.pst`
- Local `.mbox`
- Gmail mailbox/label download via Gmail API

## EA-PDF Output Profiles

- `1s`
- `1si`
- `1m`
- `1mi`
- `1c`
- `1ci`

## Email Conversion Features

- RFC822 email normalization
- MSG parsing
- PST mailbox extraction
- MBOX splitting and conversion
- HTML + text body handling
- attachment harvesting and embedding
- EA-PDF metadata generation
- PDF/A validation workflow

## Gmail Features

- Gmail API OAuth desktop-app flow
- mailbox/label selection
- unread-only filter
- message limit filter
- per-message output
- archive/container output

## Finding Aid Features

- PDF index pages for archive/container outputs
- separate finding-aid PDF and XLSX sidecars for `1c`/`1ci`; neither is embedded
- XLSX embedding retained for flat `1m`/`1mi` archives
- optional external XLSX controlled by `excelEnabled`
- ordinary finding-aid PDF sidecars contain no XLSX or synthetic EML
- clickable finding-aid message links

## Metadata Features

- EA-PDF XMP metadata
- PDF/A metadata
- optional PREMIS XML embedding
- optional PREMIS pretty-printed sidecar XML

## Accessibility / PDF-UA-Style Features

Config-gated features include:

- `/MarkInfo`
- `/Lang`
- reading direction
- page labels
- selected PDF/UA-style catalog improvements

## GUI Features

- desktop GUI for local email and Gmail workflows
- per-run GUI feature toggles for branding, finding aid, PREMIS, and PDF/UA settings
- tooltip help for GUI feature toggles

## Validation Features

- EA-PDF rule validation
- veraPDF PDF/A validation
- combined comparison web UI
- PDF/A fail also treated as EA-PDF fail in web UI
- per-file HTML validation reports
- direct links to saved validation reports from the web UI

## Logging And Debugging

- per-file logs in local per-file logs
- optional debug output directories
- validation HTML reports in [validation](/media/lepore/Work/thinkbox/convert/validation)

## Examples

- checked-in sample PDFs in [examples](/media/lepore/Work/thinkbox/convert/examples)
- includes the six core email profile samples

## Performance Features

- threaded local conversion via `--jobs`
- parallel archive input reading
- parallel archive message rendering
- parallel collection member generation

## Documentation

- Gmail setup guide
- deployment notes
- validator coverage notes
- searchable workflow decision tree PDF
