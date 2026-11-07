# November 7, 2026 repository audit follow-up

The supplied audit reviewed public commit `8f0b079`. Remediation below describes
source changes prepared for beta.18. Verification below describes local runs;
remote workflow status is reported by GitHub Actions.
The previously completed search-pane, Calc XLSX and PDF.js scheduling fixes are
preserved.

| Finding | Disposition |
| --- | --- |
| EA-001 | Added separate Chromium and Firefox CI jobs. Chromium runs the complete standard suite, inspection oracle and archive-memory probe; both run type checks. Branch protection still requires repository configuration. |
| EA-002, EA-018 | Packaging uses unconditional version/hash checks, an exact manifest file set, validated in-memory payloads and post-write ZIP verification. Optimized-Python negative tests pass. |
| EA-003 | Restored a public EML/MSG/MBOX independent conversion gate with source hashes, preservation checks, reviewed raster baselines and standalone Java EA-PDF/PDF-A results. Eight deliberate-loss tests pass. This is a limited subset, not restored private/PST acceptance. |
| EA-004 | Collection text search traverses declared members only; unavailable members are explicit. Regression includes an unrelated matching PDF and an unreadable member. |
| EA-005 | Metadata filtering includes Cc, Bcc and both raw variants, each tested in isolation. |
| EA-006 | Updated feature version and replaced developer-machine links with portable references/local-output descriptions. |
| EA-007 | Replaced obsolete checkpoint introduction and viewer limits; linked current viewer/deployment controls. Limits are still manually maintained. |
| EA-008 | Preserved scalable application defaults; deployment explicitly sets a 600-second synchronous request budget under 660-second server/proxy limits. Documented 600/1800-second subprocess minima, 24-hour caps and request-deadline precedence; tested deadline/admission behavior. |
| EA-009 | Both systemd templates use `/opt/eapdf`; installation instructions explain path substitution and service-account access. No live service changed. |
| EA-010 | Added 18 public validator/worker/web regressions and their own CI job. Private tests remain excluded; comprehensive parity is not claimed. |
| EA-011 | Added five upstream-versus-patched browser cases for classic/compressed xrefs, incremental updates, broken-xref recovery and encryption. Repeated text/render/cleanup agrees. Full upstream rendering corpus and independent parser-ownership review remain outstanding. |
| EA-012 | Firefox CI and the local release gate require a fresh temporary extension installation and UI tests. Firefox 158.0.1/WebDriver 0.36.0 passes locally. Signed installation and other platforms remain unverified. |
| EA-013, EA-017 | Added repeat-open/extraction/render/search testing of the public 44,440,369-byte archive with V8 old-space set to 512 MiB. Complete search found 104 matching pages; extracted bytes agree across opens. Post-close main-thread heap was about 3.4 MB. Worker/native/process memory is excluded. Multi-GiB acceptance remains open; the 4 GiB ceiling is not a capacity guarantee. Full-buffer loading remains because range transport also allocates file storage and incomplete scans restart. |
| EA-014 | Already implemented at the audited commit: `EmptyPageContextTests` includes full validation plus missing/empty Contents variants. All ten tests pass; no duplicate Java test added. |
| EA-015 | Added a Python 3.12 deployment lock with transitive versions and artifact hashes, verified hash-required installation, and documented update procedure. |
| EA-016 | Resolved existing GitHub action tags to immutable upstream commits and pinned all workflows, including new jobs. Browser/driver downloads are checksum-pinned. Future updates still need review. |

The public conversion baseline was reviewed with accessibility enabled and PREMIS
disabled. Two pages matched the old baseline exactly; five changed in generated
version text and accessibility-related header formatting. Independent source and
attachment expectations were unchanged. A fresh conversion passed all seven
reviewed raster pages and both Java validation outcomes for each of three inputs.
No rejected or incomplete conversion was accepted.

The complete Chromium suite, Firefox UI acceptance, focused parser comparisons,
public Python regressions, oracle negative controls and existing empty-page Java
tests pass locally. Workflow execution and required-check settings on GitHub are
not established by these local results. Native MSG evidence limits, inaccessible
or over-budget archives, actual accessibility and cross-platform acceptance
remain documented limitations; they must not be relabeled as complete coverage.
