# Current project status

Reviewed 12 November 2026. This is the current engineering handover summary.
[Architecture and separation decisions](ARCHITECTURE.md) describe ownership in the
code. [Documentation map](README.md) identifies the maintained guides. Dated
release notes and audits describe their own snapshots, not current acceptance.

## Current release

Creator and validator **2.1.0-beta.1**, with browser viewer **1.1.0-beta.1**,
are the 12 November 2026 minor beta release. Download versioned assets and
checksums from [the release](https://github.com/thinkbox-digital/eapdf/releases/tag/v2.1.0-beta.1).
Java 21 or later is required. Browser packages are unsigned beta extensions.

**Bug fixes:** readable sender/recipient metadata, stable bookmark navigation,
Portfolio active-file downloads and Parent navigation, complete file descriptions,
folder alignment and source-preparation error reporting. Narrow-screen opening
and retained reader zoom have targeted browser regressions. The creator and
validator desktop workflows have opt-in runtime coverage.

**Code improvement:** the agreed separation of creation/rendering, GUI process
handling, viewer Files/tree/search controllers, Java report parsing, Python
validation families and web job/page services is complete. Public interfaces and
operation ownership remain. See [architecture](ARCHITECTURE.md#extraction-progress).
No refactor speed or memory benefit has been measured.

The Python/web runtime requires pikepdf 10.18.0 and pypdf 7.0.1; install the
hash-locked requirements and restart deployed workers. Java resolves Log4j API
2.25.5. A source update does not establish that deployed services were upgraded.
Runtime inventories describe resolved dependencies, not an independent security
or licensing certification.

The [controlled PDF.js comparison](PDFJS_LARGE_ARCHIVE_COMPARISON.md) records
618 completed trials: 598 successes with matching compared outputs and twenty
explicit limit failures in reduced controls. Production PDF.js patches are unchanged.
The user's 11 November Qodana export and reviewed dispositions are dated evidence;
the user owns Qodana reruns.

Local fixture deletions and private samples are excluded from this release.
Clean-checkout acceptance retains the original tracked PST regression fixtures.
Historical refactor acceptance on 11 November recorded 403 creator passes,
287 Java-validator unit passes and two executable-JAR integration passes,
183 viewer unit passes and 59 public Python passes. Current release checks are
recorded separately; these historical totals are not new acceptance results.

## Product scope

- Creator: EML, MSG, PST, MBOX and Gmail inputs; EA-PDF profiles 1s, 1si, 1m, 1mi,
  1c and 1ci. Generated PDFs use PDF 2.0 / PDF/A-4f. Profile selection is not
  independent certification. See [features](FEATURES.md) and [validation coverage](VALIDATOR_COVERAGE.md).
- Source preservation: non-isolated EML/MSG exports retain their source assets;
  PST/MBOX/Gmail retain per-message RFC822 exports or derivatives. Preserve the
  original containers separately. Isolated profiles omit original source assets.
- Covers: 1c/1ci collections have a statistics/instructions cover. 1m/1mi begin
  with message content. Creator finding-aid, XLSX, branding and PREMIS generation
  are removed; the viewer's independent metadata export remains available.
- Reader: local message/collection navigation, whole-word search and filtering,
  Conversations, attachments, message-as-PDF download, CSV field selection and
  original-XMP export. [Viewer use](../browser-viewer/README.md) owns these details.
  Its optional Portfolio mode supports generic PDF collections with separately
  tested opening, layout and navigation behavior; it is not full Portfolio certification.
- Validation: the standalone Java validator is the normal conversion checker.
  Python/web validation remains supported for its documented workflows and
  regressions. PASS/FAIL/INCOMPLETE must keep their documented meanings.
- Optional embedded indexes and signing have a defined compatibility workflow;
  see [embedded indexing](EMBEDDED_SEARCH_INDEX.md) and [signatures](DIGITAL_SIGNATURES.md).

## Supported operation and unresolved acceptance

| Area | Established boundary | Work before broader commercial claims |
| --- | --- | --- |
| Desktop platforms | Recorded desktop acceptance is principally Linux with Java 21; native Linux worker limits use prlimit. | Name the supported OS versions and verify installation, cancellation, recovery and native resource controls on each. |
| Browser installation | Chromium unpacked and Firefox temporary installations are tested. | Store/signing, update and headed permission/toolbar acceptance. The ZIPs are not store-approved installers. |
| Fidelity and standards | Real-input and mutation regressions, six-profile checks and independent veraPDF checks exist. Source/layout/decoding limitations remain explicit. | Independent EA-PDF/interoperability review and a current supported-input/capacity matrix; human accessibility acceptance before PDF/UA claims. |
| Capacity | Worker, decode, memory, scratch and time budgets are enforced within documented scopes. | Measure promised customer workloads. Admission ceilings are not capacity guarantees; resource limits are not a filesystem/network sandbox. |
| Hosted service | Authenticated shared trusted-team deployment is documented. | Per-user ownership/isolation and deployment acceptance before a public multi-customer service. |
| Gmail | Desktop OAuth and read-only download/resume are implemented. | Set the intended customer onboarding, account ownership and applicable verification process. |
| Redistribution | Notices, runtime inventories and patched-library source/replacement instructions exist. | Review the exact release, LGPL/source/replacement arrangements, fonts, branding and fixture rights. |
| Support | Versioned releases, checksums, CI and security reporting exist. | Installer/update/rollback policy, support ownership and a maintained defect/acceptance register. |

These are scoped engineering gaps, not a claim that all earlier audit findings
remain open. [Hardening](security/HARDENING.md), [deployment](DEPLOY_WEB.md),
[license review](THIRD_PARTY_LICENSES.md) and [coverage](VALIDATOR_COVERAGE.md)
own their specific boundaries.

## Handover acceptance

A new maintainer should reproduce a clean-checkout build, run the applicable CI
gates, distinguish required fixtures from optional private samples, and explain
one conversion, one validation and one viewer navigation end to end. Then agree
a prioritised launch backlog with measurable acceptance criteria. Record skipped,
failed and incomplete checks separately; do not replace them with historical totals.

Local development logs and private correspondence are ignored and are not inputs
to the documentation website. Summarise verified outcomes here without publishing
private evidence, credentials or message contents.

Bug fix (12 November 2026): the viewer hides Message details when the selected message has no folder path to display.

Bug fix (12 November 2026): navigation folder rows use consistent arrow, icon, label and count columns, including wrapped names and empty folders.

Bug fix (12 November 2026): Message details starts expanded with a visible disclosure arrow; folder labels and paths align in two columns.

Code improvement (12 November 2026): consistent concise embedded email-source descriptions across EML/MSG/MBOX/PST/Gmail. Viewer normalization supports known legacy generator descriptions; detailed preprocessing provenance remains in PDF metadata. Both viewer packages rebuilt; targeted creator and viewer regressions pass.

Code improvement: the metadata-export checkbox is labelled “PDF filename / path”, with help explaining that it identifies the containing PDF and its path inside a collection. The exported CSV column remains named PDF for compatibility.

Code improvement: attached emails from PST use “Attached email exported from PST”. Omitted-content notices and original size/checksum details remain. The viewer also shortens the known legacy attachment description in existing PDFs.

Bug fix: in Portfolio view, the page toolbar offers “Download active file” and downloads the displayed PDF unchanged, including ordinary PDFs without email metadata. Email-view message extraction remains unchanged. Portfolio filenames use one tooltip on hover or focus.

UI improvement: Chromium viewer scrollbars use 24px tracks in both directions. Firefox retains system scrollbar sizing.

UI improvement: Parent returns to the parent overview and resets left-pane navigation to the top in Portfolio and EA-PDF views. Center-pane vertical padding is halved at each responsive size; horizontal padding is unchanged.

Bug fix / accessibility improvement (12 November 2026): source preparation failures in the render path now report their cause; Portfolio filenames have one readable tooltip on hover or focus. Chromium scrollbars use 24px tracks and thumbs with a 48px minimum length. A permanent Portfolio browser regression covers byte-identical active-file download and Parent navigation in Portfolio and EA-PDF views.

UI improvement (12 November 2026): PDF Bookmarks starts collapsed when opening a document in both EA-PDF and Portfolio views. The disclosure remains available for bookmark navigation.

Bug fix (12 November 2026): same-document message navigation keeps PDF Bookmarks mounted instead of clearing and rebuilding it. Open PDF remains usable while a navigation operation is busy; a selected new file is queued until that operation finishes.

UI improvement (12 November 2026): PDF Bookmarks has a visible caret in EA-PDF and Portfolio views that rotates when the section opens.

Bug fix (12 November 2026): file descriptions offer Show more / Show less to read the complete stored description, including text omitted from a compact or simplified preview. PDF Bookmarks behavior is unchanged.

Bug fix (12 November 2026): the browser viewer reads FOAF sender/recipient attributes and elements, reports conflicting values, and displays and filters readable names. The real PST reproduction now shows no encoded sender rows and finds the readable name; address-form browser regressions pass. Four real EML/MSG/MBOX/PST outputs passed standalone Java EA-PDF/PDF-A validation; conversion warnings and the separate limits of source fidelity and accessibility review remain applicable.

UI improvements (12 November 2026): description expansion is offered only for shortened or clipped text and responds to pane width, including virtualized rows. Narrow-screen opens default to Fit width when no explicit PDF opening destination overrides it; reader-selected zoom survives navigation and resizing. Creator and validator desktop workflow tests passed, including pickers, invalid input, completion, cancellation, findings and report saving. The GUI-created PDF passed standalone Java validation. Accessibility wording and bookmark policy were not changed.
