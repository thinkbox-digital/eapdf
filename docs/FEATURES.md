# Feature inventory

Current scope is summarised here; release and unreleased status live in
[project status](PROJECT_STATUS.md). Operational details belong to the linked
guides, not duplicate release-version tables.

## Creator

- EML, MSG, PST, MBOX and Gmail inputs, with original containers retained separately.
- Six EA-PDF profiles: 1s, 1si, 1m, 1mi, 1c and 1ci; PDF 2.0 / PDF/A-4f output.
- Non-isolated source/message exports and attachment contents; explicit source
  omissions for isolated profiles.
- HTML/text rendering, bounded font/image handling, source metadata and file ownership.
- 1c/1ci cover statistics/instructions and folder memberships; 1m/1mi message content.
- Optional embedded metadata/full-text indexes, accessibility structures and signing.
- Bounded parallel processing, supervised workers and recoverable staged publication.
- Gmail read-only OAuth, selection/limits and optional resumable checkpoints.
- Desktop Local Email/Gmail tabs, per-run index/accessibility/signing controls,
  creator identity, label/field help, progress, cancellation and Summary/Details.

Creator branding, finding-aid PDFs/XLSX and PREMIS generation are removed. Legacy
artifacts can contain these assets; the reader can still expose them. These
removals do not remove reader CSV export. See [conversion operations](CONVERSION_OPERATIONS.md),
[configuration](../config/README.md) and [Gmail](GMAIL.md).

## Reader

- Local message/folder and recursive embedded-PDF navigation; adjustable panes,
  natural folder names and initial viewing destinations.
- Whole-word document/collection search and message filtering; verified indexes
  with ordinary traversal fallback.
- Conversations with per-open-document caching, expandable trees, right-pane
  message selection and within-conversation keyboard/edge-scroll navigation.
  Optional subject inference groups chains; Confirmed header relationships remain distinct.
- File categories sorted oldest first by owning-message date, undated last;
  View/Open PDF only for supported previews, and downloads including empty files.
- Download the displayed message as PDF; metadata field/scope selection for CSV;
  byte-preserving original-XMP download/ZIP export. CSV excludes Message-ID and Mail GUID.
- Archive totals in the status bar; 1c/1ci cover remains visible.
- Optional generic Portfolio presentation, stored opening targets, folders,
  metadata views and explicit embedded navigation.

The reader does not validate conformance or execute attachments/PDF scripts.
Office documents use normal downloads. See [viewer use](../browser-viewer/README.md)
and [browser security](../browser-viewer/docs/security.md).

## Validation and limits

The standalone Java validator combines EA-PDF checks with veraPDF checks and
recursive member assessment. The optional Python CLI remains for regression tests and diagnostics.

Accessibility structures are not proof of PDF/UA conformance. Document/profile
identification, preservation fidelity, human accessibility, signature trust and
attachment safety are separate questions. See [coverage](VALIDATOR_COVERAGE.md),
[security boundaries](security/HARDENING.md), [signing](DIGITAL_SIGNATURES.md) and
[embedded index limits](EMBEDDED_SEARCH_INDEX.md).
