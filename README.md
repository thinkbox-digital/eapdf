# EmailToPDFA

**[Read the documentation](https://thinkbox-digital.github.io/eapdf/)** — installation, conversion, validation and viewing guides.

[Conversion quality](https://github.com/thinkbox-digital/eapdf/actions/workflows/quality.yml) · [Contributing](CONTRIBUTING.md) · [Security reporting](SECURITY.md) · [Library packages](docs/PACKAGES.md)

[User manual (PDF/A)](docs/EA-PDF_User_Manual.pdf) — beginner instructions for the creator, both validators and viewer, with screenshots.

Convert email into EA-PDF files using Java, PDFBox, and XMP metadata.

Current beta version: `2.0.0-beta.1`

The separate [browser viewer beta](browser-viewer/docs/beta-release.md) opens local
EA-PDF files and embedded PDFs recursively in Firefox and Chromium. It also
shows metadata, image and text attachments, with normal downloads for Office files.
Viewer version `1.0.0-beta.1` includes collection-wide email search, CSV finding
aids with folder and attachment details, and paced file downloads. Installation and tested limits
are documented in that guide.

Latest release: [v2.0.0-beta.1](https://github.com/thinkbox-digital/eapdf/releases/tag/v2.0.0-beta.1). Java 21 or later is required.

The current source tree adds optional [embedded metadata and full-text indexes](docs/EMBEDDED_SEARCH_INDEX.md).
Full-text generation uses built-in PDFBox extraction and shared text normalization.
Conversion and viewing remain offline-capable.

For the current proof of concept, see [the short demo guide](docs/CREATOR_DEMO.md). Runnable creator and standalone-validator builds are available from the release linked above.

The [standalone Java validator](validator-desktop/README.md) builds into one JAR
with its libraries, rules and specification references. It checks EA-PDF structure,
metadata and available source relationships, alongside PDF/A validation.
See [validation coverage and limitations](docs/VALIDATOR_COVERAGE.md).

This codebase was produced with Codex/ChatGPT assistance and is not designed for production use without additional engineering, review, and hardening.

## Regenerating local outputs

The installed `eapdf-regenerate` launcher prints basic instructions without arguments:

```bash
eapdf-regenerate sample
eapdf-regenerate everything
eapdf-regenerate everything --dry-run
eapdf-regenerate everything --workers 4 --jobs 2
```

Running either generation mode clears this checkout’s `out/` directory. `--dry-run`
only prints the plan. The default creator is `Thinkbox.DIGITAL`; no creator argument
is needed. Concurrent Java processes are bounded automatically by available CPU
and memory, with two creator threads per process by default. `sample` selects the
two smallest sources of each type. `everything` covers every supported source in
appropriate profiles. Each PST/MBOX source produces whole-source `1m`, `1mi`,
`1c` and `1ci` archives only; neither mode extracts individual emails into standalone
PDFs. EML/MSG inputs retain standalone `1s`/`1si` outputs and are also randomly
grouped by type into archives of up to 20 messages, using all four archive profiles.
Every selected source is covered; groups are balanced to avoid singleton remainders.
A one-message source cannot produce 1m/1mi and is reported as not applicable.
The run’s `plan.json` records the exact random group membership; a dry run makes
an independent preview plan.

Each creator job publishes its completed PDFs immediately and prints their paths,
before validation starts. Validation results follow separately; a single archive
PDF must finish converting before it can appear. Other jobs continue concurrently.

Final PDFs go directly into `out/eml/`, `out/msg/`, `out/mbox/`, and `out/pst/`.
Each run snapshots its versioned creator and validator JARs so later upgrades
do not interrupt it. Source hashes, logs, progress and standalone Java validation reports go into the
run’s evidence directory under `output/`. Each process has isolated temporary
staging. Accessibility and both indexes are enabled; signing is disabled. Original
inputs are preserved. Failed or incomplete conversions are reported with a nonzero
exit status. Existing conversions using `out/` must finish before regeneration.
Conversion staging and Java scratch use private directories under
`.generator_web_tmp/` on the project disk, including when `/tmp` is memory-backed.
Each job remains isolated; temporary files are removed after success or failure.

Regeneration divides half the currently available output-disk space among its
concurrent jobs for scratch, with half each job's share available to one work
file or report. There is no fixed MiB/GiB report ceiling. Explicit
`VALIDATOR_MAX_WORK_FILE_BYTES` and `VALIDATOR_MAX_SCRATCH_BYTES` settings take
precedence; each document's result records its budgets.

If validation returns FAIL or INCOMPLETE, or is unavailable, regeneration retains the initial
reports under `initial-validation/`, regenerates once with a visible
**WARNING — Validation requires review** banner, then validates the replacement.
The banner discloses failed or unfinished checks; it does not turn the result into PASS.
Source-data warnings remain separate. A failed replacement keeps the original PDF
and reports the failure. This requires the updated creator JAR.

At completion, the terminal lists FAIL and INCOMPLETE jobs, affected output files
(or sources that produced no PDF), and their conversion logs and validation reports.
Counts distinguish passed, failed, incomplete and not-applicable jobs. The same
report is saved as `summary.txt` beside the detailed `summary.json` in the run
evidence directory.
The maintained sources are `scripts/eapdf-regenerate` and `scripts/regenerate_out.py`.

## What This Project Does

This repository generates EA-PDF outputs from:

- local email files: `.eml`, `.msg`, `.pst`, `.mbox`
- Gmail mailboxes/labels via the Gmail API

It supports six EA-PDF profiles. Choose by the reading experience you need and whether the output must retain source email assets; see [EA-PDF profiles](#ea-pdf-profiles) below.

The generator also includes:

- PDF/A validation workflow
- EA-PDF-specific validation
- optional PDF/UA-style catalog features
- per-file generation logs

## EA-PDF Profiles

The profile names describe **how emails are packaged**, separately from PDF/A conformance:

- `1` identifies EA-PDF version 1.
- `s` means **single message**, `m` means **multiple messages in one PDF**, and `c` means **collection of embedded EA-PDF files**.
- The suffix `i` means **isolated from a complete set of preserved original source email assets**. This generator also omits original native attachment bytes in isolated email outputs. The EA-PDF specification permits some attachments in isolated profiles; omitting their native bytes is this application's policy.

| Profile | What it is for | What you open and read | Source email and native attachments in this generator |
| --- | --- | --- | --- |
| **`1s` — Single** ([sample](examples/eml/eml_Agenda_and_timeline_slides_1s.pdf)) | Preserve an individual message as a separately usable PDF. | One message's headers/body, potentially spanning several pages, plus attachment notices. | Source email asset embedded; original native attachment bytes retained as file-attachment annotations. |
| **`1si` — Single, isolated** ([sample](examples/eml/eml_Agenda_and_timeline_slides_1si.pdf)) | Read or distribute an individual message without bundling its source email or original native attachments. | One message's rendered headers/body and explicit notices for omitted attachment payloads. | Original source email omitted; native attachment markers contain zero bytes. |
| **`1m` — Multiple** ([sample](examples/pst/pst_quality-attached-message_1m.pdf)) | Read, search or print two or more messages as one continuous document while retaining their source assets. | All message pages in one PDF, with navigation/bookmarks. There is no collection folder tree to browse. | Source email assets embedded; original native attachments retained with their owning messages. |
| **`1mi` — Multiple, isolated** ([sample](examples/pst/pst_quality-attached-message_1mi.pdf)) | Read or distribute two or more messages as one continuous document without source emails or original native attachments. | The same flat reading layout as `1m`, with notices for omitted attachment payloads. | Original source emails omitted; native attachment markers contain zero bytes. |
| **`1c` — Collection** ([sample](examples/eml/eml_Agenda_and_timeline_slides_archive_1c.pdf)) | Keep messages separately openable inside one archive, organized into collection folder/label groups. | The outer PDF shows collection context/index pages. Open the embedded message EA-PDFs to read the emails; this generator creates `1s` message children. | Message source assets and native attachments retained in the children; source email assets are also listed in the outer collection. |
| **`1ci` — Collection, isolated** ([sample](examples/eml/eml_Agenda_and_timeline_slides_archive_1ci.pdf)) | Distribute a grouped collection of separately openable message copies without their original sources or native attachment payloads. | The outer PDF shows context/index pages; original message children use `1si` and must be opened separately. | Original email sources and original native attachment bytes omitted. |

### Choosing and opening a profile

Use `1s` for separate preserved messages, `1m` for a continuous archive to read/print, or `1c` for folder/label browsing and separately extractable messages. Choose the corresponding `i` profile when you intentionally want the source omissions described above, while retaining originals separately if needed.

**Isolated does not mean anonymized or redacted.** Rendered headers, body text, metadata and inline images remain. Isolated outputs are not complete substitutes for their original email assets.

For `1c`/`1ci`, use a PDF reader that supports embedded files and PDF Collections. A reader that only displays the outer document's pages shows the front matter, not every archived email. Slash-separated source folders and labels become nested collection folders. Directory imports retain relative EML/MSG folder names. Multiple label memberships can place a message in more than one group; names that sanitize to the same filename receive distinct sibling names.

`1m`/`1mi` require at least two messages in the local archive command; a one-message archive request is rejected. Use a single-message output for one message. `1c`/`1ci` may contain just one original message.

### Selecting profiles with the local CLI

| Desired profile | Selection |
| --- | --- |
| `1s` | Default per-message conversion: omit `--archive-profile` and `--omit-source`. |
| `1si` | Per-message conversion with `--omit-source`. |
| `1m` | `--archive-profile m` |
| `1mi` | `--archive-profile mi` |
| `1c` | `--archive-profile c` |
| `1ci` | `--archive-profile ci` |

Without `--archive-profile`, a mailbox or multiple inputs produce separate message PDFs, not an automatic combined archive. Per-message PST conversion retains the exported message and native attachments as `1s`; select `--omit-source` explicitly for `1si`. For archive/collection output, select isolation through `mi`/`ci`; `--omit-source` controls the per-message path and does not override an explicit archive profile. The CLI accepts `m`, `mi`, `c` or `ci` after `--archive-profile`, without a leading `1`.

### What “preserved source” means

For EML/MSG inputs, non-isolated output retains the original message asset. For PST, MBOX and Gmail, it retains the individual RFC 822 message exported/reconstructed by the reader, **not the original PST/MBOX container or a complete Gmail mailbox**. Keep those originals separately when their full storage format and properties matter. These source policies are separate from whether the rendered PDF faithfully reproduces every source layout.

### Finding aids and conformance

For `1m`/`1mi`, an enabled PDF index appears before the message pages and `embedExcel` embeds the XLSX in the archive. `excelEnabled` can also write an external XLSX.

Oversized spreadsheet fields are preserved in numbered chunks on `Continuation` sheets. The main index links to the first chunk; each chunk identifies the email, field and part number. No text is truncated.

Branding, finding-aid generation and PREMIS output have been removed from the creator GUI and command line. Collections retain their required cover page.

See [configuration](config/README.md) for the supported settings.

The EA-PDF standard allows collections to contain other EA-PDF profiles, including mixed and nested collections; the `1s`/`1si` original-message children above describe this generator's current behavior. Profile definitions are in §3.2 of the [EA-PDF 1.0 specification](docs/reference/EA-PDF-v1.pdf).

All six profiles currently use PDF 2.0 / PDF/A-4f output in this generator. Selecting an EA-PDF profile does not assert PDF/UA accessibility conformance or exhaustive EA-PDF certification; see [validator coverage](docs/VALIDATOR_COVERAGE.md).

## Release Downloads

| Download | Purpose |
| --- | --- |
| [Creator GUI](https://github.com/thinkbox-digital/eapdf/releases/download/v2.0.0-beta.1/emailtopdfa-2.0.0-beta.1-gui.jar) | Convert local email sources through the desktop app. |
| [Creator CLI](https://github.com/thinkbox-digital/eapdf/releases/download/v2.0.0-beta.1/emailtopdfa-2.0.0-beta.1.jar) | Run local email and Gmail workflows from the command line. |
| [Standalone validator](https://github.com/thinkbox-digital/eapdf/releases/download/v2.0.0-beta.1/eapdf-validator-2.0.0-beta.1.jar) | Validate a PDF using the bundled Java engines and current rules. |
| [Chromium viewer](https://github.com/thinkbox-digital/eapdf/releases/download/v2.0.0-beta.1/eapdf-viewer-1.0.0-beta.1-chromium.zip) | Browse EA-PDF messages and embedded files locally. |
| [Firefox viewer](https://github.com/thinkbox-digital/eapdf/releases/download/v2.0.0-beta.1/eapdf-viewer-1.0.0-beta.1-firefox.zip) | The same viewer, packaged for temporary Firefox installation. |
| [EML, MSG, PST and MBOX examples](https://github.com/thinkbox-digital/eapdf/releases/download/v2.0.0-beta.1/ea-pdf-examples-2.0.0-beta.1.zip) | Regenerated examples with source hashes and recorded validation results. |

Each JAR includes its runtime libraries. The release also includes the updated specification-author review document, license notices and `SHA256SUMS.txt`. Run a desktop app with `java -jar downloaded-file.jar`.

Use the explicit versions above; GitHub's Latest badge may select an older stable
entry. This beta includes the hardening/workflow corrections and user manual.
Obtain public configuration from this revision's [`config/`](config/) folder.
Future configuration ZIPs must use `python3 -B scripts/package_configuration.py
/tmp/ea-pdf-configuration.zip`; it includes only allowlisted public files and
excludes local Gmail credentials, tokens and signing keys. Supply your own OAuth
credentials. Include the PDF/A user manual and its checksum in future release
bundles, and verify them against the exact applications being released.

## Quick Start

The creator defaults to `Thinkbox.DIGITAL` in code and configuration. Override `creator` in `config/eapdf_config.json` when needed. This identifies the entity responsible for the EA-PDF, separately from the software. The GUI also provides **Creator identity...** for the current session.

Build:

```bash
mvn package
```

Artifacts:

- CLI fat jar: `target/emailtopdfa-2.0.0-beta.1.jar`
- GUI fat jar: `target/emailtopdfa-2.0.0-beta.1-gui.jar`

The current creator build is also available in `dist/`:

```bash
java -Xmx2g -jar dist/emailtopdfa-2.0.0-beta.1.jar --jobs 2 --out out file.eml
java -jar dist/emailtopdfa-2.0.0-beta.1-gui.jar
```

The web creator uses the newer of `dist/emailtopdfa-2.0.0-beta.1.jar` and the current-version
CLI JAR in `target/`. Restart an already
running web process to load a changed application or creator JAR selection.

Convert a local email source:

```bash
java -Xmx2g -cp target/emailtopdfa-2.0.0-beta.1.jar eapdf.EmailToPDFA --jobs 2 file.eml
```

Write output to a custom directory:

```bash
java -Xmx2g -cp target/emailtopdfa-2.0.0-beta.1.jar eapdf.EmailToPDFA --jobs 2 --out out file.pst
```

Generate an archive profile:

```bash
java -Xmx2g -cp target/emailtopdfa-2.0.0-beta.1.jar eapdf.EmailToPDFA \
  --jobs 2 --archive-profile m \
  --archive-output archive_1m.pdf \
  file.mbox
```

Validate a PDF with the standalone Java engine:

```bash
java -Xmx4g -cp dist/eapdf-validator-2.0.0-beta.1.jar eapdf.JavaValidation archive_1m.pdf validation.json
```

The CLI example gives the JVM 4 GiB; choose a heap suited to the file and available
RAM. The desktop validator sizes its child worker automatically. CLI progress and
compact summaries are saved beside the full report.

For conversion work, use bounded parallel processing and the Java validator.
`--jobs` enables parallel archive/collection rendering, processing of multiple
input files, and separate JVM rendering processes for per-email output from a
MBOX or PST. Each source is parsed once; PST messages stream to available workers
and their staged payloads are released after rendering. Outputs publish only after
every worker succeeds. Process count and child heaps are
bounded by CPU and available memory; `--jobs 1` keeps sequential rendering.
PST extraction itself remains sequential. Allow sufficient heap
for the requested workers; archive rendering budgets roughly 1 GiB per worker. The Python validator
remains available for its regression tests and explicitly requested comparisons
or diagnostics.

Run the separate [Arlington object-model audit](docs/ARLINGTON_VALIDATION.md)
locally on the published example PDFs:

```bash
python3 scripts/install_arlington.py
python3 scripts/validate_arlington.py examples --reports /tmp/eapdf-arlington --jobs 2
```

This uses a pinned standalone Java checker and preserves per-file reports and
hashes. Embedded PDF payloads require a separate pass; see the linked scope notes.

Run the web validator:

```bash
python validator_web.py
```

Launch the desktop GUI:

```bash
java -jar target/emailtopdfa-2.0.0-beta.1-gui.jar
```

Tracked examples are grouped by source format under [`examples/`](examples/):

| Input format | Examples | Source inputs |
| --- | --- | --- |
| EML | [eml/](examples/eml/) | Agenda correspondence and an affordable-housing committee email from `in/` |
| MSG | [msg/](examples/msg/) | `TxtSampleEmailWithAttachment.msg` and `test.MSG` from `in/` |
| PST | [pst/](examples/pst/) | All seven messages in `in/quality-attached-message.pst` |
| MBOX | [mbox/](examples/mbox/) | Both messages in `in/enron-sample.mbox` |

Each folder contains all six profiles (`1s`, `1si`, `1m`, `1mi`, `1c`, `1ci`)
and a `manifest.json` with source paths, hashes and validation results.
Output filenames identify the input type, source and profile, for example
`eml_Agenda_and_timeline_slides_1s.pdf`. Multiple-input samples add `_archive`.
The 24 primary examples were regenerated and validated on 2026-11-07 using
`1.6.0-beta.19`, with Accessibility enabled, embedded search indexes enabled for
supported profiles, and optional PREMIS and finding aids disabled. Gmail is an
account download workflow, not a separate accepted local file type.

For EML and MSG, single-message examples use the first listed input; archives and
collections contain both inputs. For PST and MBOX, the single-message examples
are exact copies of a message child from the corresponding collection; archives
and collections contain every message in the input mailbox. Standalone PST conversion also preserves exports and attachments by default as `1s`.
The compact MBOX example uses the two-message Enron subset. [Profile/source details](examples/README.md).
The bundle also includes the larger Enron archive (validation incomplete because
of a known veraPDF runtime exception) and a deliberate missing-Date negative
example. Their manifests distinguish these outcomes from the 24 passing examples.

Regenerate the 24 primary examples after building the current release and setting
a nonblank creator identity in the configuration:

```bash
python3 -B scripts/regenerate_email_samples.py
```

To use the local verified build and retain detailed validation reports:

```bash
EAPDF_GENERATOR_CLASSPATH="$PWD/dist/emailtopdfa-2.0.0-beta.1.jar" \
EAPDF_VALIDATOR_JAR="$PWD/dist/eapdf-validator-2.0.0-beta.1.jar" \
EAPDF_SAMPLE_REPORT_DIR="/tmp/eapdf-example-reports" \
python3 -B scripts/regenerate_email_samples.py
```

The standalone Java validator bundles the PDF/A and selected ISO engines; Docker
is not needed for this workflow. Its bundled rule JSON must match the current
rulebook. The script validates all 24 EA-PDF candidates and their collection
children before replacing any saved example. It rejects incorrect profiles,
mandatory failures, incomplete required comparisons, and missing or failed PDF/A
or selected ISO checks. The per-folder manifests record the actual results.

Superseded generic `*_sample_1*` example paths are removed only after the
complete replacement set passes. Temporary inputs and PDF candidates stay in
`/tmp` and are removed on success or failure. Java child checks retain cycle detection and resource accounting without fixed child-count, depth or lifetime decoded-byte ceilings.

The script defaults to two concurrent format pipelines and four creator workers
per conversion, bounded by available CPU and memory. It allocates the corresponding
creator heap automatically. `EAPDF_SAMPLE_WORKERS` and `EAPDF_SAMPLE_JOBS` set the
requested pipeline and rendering worker counts. Independent Java validations run
in separate processes alongside the other format pipelines.

## Main Entry Points

- Local email conversion: [`src/main/java/eapdf/EmailToPDFA.java`](src/main/java/eapdf/EmailToPDFA.java)
- Gmail download + conversion: [`src/main/java/eapdf/GmailDownloadToPdf.java`](src/main/java/eapdf/GmailDownloadToPdf.java)
- PDF writer: [`src/main/java/eapdf/PdfMailWriter.java`](src/main/java/eapdf/PdfMailWriter.java)
- Validator CLI logic: [`validate_eapdf.py`](validate_eapdf.py)
- Validator web UI: [`validator_web.py`](validator_web.py)

## Supported Workflows

### Local Email Files

Use [`eapdf.EmailToPDFA`](src/main/java/eapdf/EmailToPDFA.java) for:

- `.eml`
- `.msg`
- `.pst`
- `.mbox`

Named CLI switches currently supported:

- `--out`
- `--font`
- `--icc`
- `--global-id` / `-g`
- `--email` / `-e`
- `--jobs` / `-j`
- `--omit-source`
- `--archive-profile`
- `--archive-output`
- `--debug`
- `--debug-dir`

### Gmail

Use [`eapdf.GmailDownloadToPdf`](src/main/java/eapdf/GmailDownloadToPdf.java) for live Gmail mailbox/label downloads.

Supported switches include:

- `--user`
- `--credentials`
- `--token-dir`
- `--folder`
- `--out-pdf`
- `--debug-dir`
- `--font`
- `--icc`
- `--global-id` / `-g`
- `--email` / `-e`
- `--limit`
- `--omit-source`
- `--archive-profile`
- `--archive-output`
- `--unread-only`
- `--debug`

Setup details are in [`docs/GMAIL.md`](docs/GMAIL.md).

## Configuration

Project configuration lives in [`config/eapdf_config.json`](config/eapdf_config.json).

Current config-controlled feature areas include:

- PDF/UA-style catalog features
- optional [outer-file digital signatures](docs/DIGITAL_SIGNATURES.md)

Supporting configuration files live under [`config/`](config/).

## Output Locations

Local per-email conversion keeps existing files and adds a numbered suffix when needed. Repeating an input creates another PDF. Explicit archive and collection output paths retain their existing replacement behavior.

Generated PDFs and per-file logs are stored locally, outside the published repository.


## Repository Layout

- [`src/`](src/) - Java source and tests
- [`config/`](config/) - runtime and support configuration files
- [`assets/`](assets/) - branding assets and bundled font
- [`docs/`](docs/) - user/operator documentation
- [`in/`](in/) - local sample/test email inputs
- [`scripts/`](scripts/) - helper scripts such as doc generation

## Documentation

## Libraries And Dependency Versions

Last checked: **2026-10-16**. "Used" means the version declared in
[`pom.xml`](pom.xml), not the newest release available. The Java tables cover
every direct dependency (including runtime providers and tests), plus selected
transitive libraries imported by the source or used by the PDF renderer. They
are not a complete transitive-dependency inventory or vulnerability scan.
Latest versions exclude snapshots and prereleases; release numbers link to
Maven Central metadata. License names describe the versions used, based on
their published POMs (including inherited declarations) and distribution notices.

### Java Libraries

| Library / Maven artifact | Used | Declared license(s) | Latest stable / status |
| --- | --- | --- | --- |
| PST reader — `com.pff:java-libpst` | 0.9.3 | [Apache-2.0][dependency-apache] | [0.9.3](https://repo.maven.apache.org/maven2/com/pff/java-libpst/maven-metadata.xml) — current |
| MSG reader — `org.simplejavamail:outlook-message-parser` | 1.16.2 | [Apache-2.0][dependency-apache] | [1.16.2](https://repo.maven.apache.org/maven2/org/simplejavamail/outlook-message-parser/maven-metadata.xml) — current |
| Mail API — `jakarta.mail:jakarta.mail-api` | 2.1.5 | [EPL-2.0; GPL-2.0 + Classpath exception; EDL-1.0](https://repo.maven.apache.org/maven2/jakarta/mail/jakarta.mail-api/2.1.5/jakarta.mail-api-2.1.5.pom) (see note below) | [2.1.5](https://repo.maven.apache.org/maven2/jakarta/mail/jakarta.mail-api/maven-metadata.xml) — current |
| Mail runtime — `org.eclipse.angus:angus-mail` | 2.0.5 | [EPL-2.0; GPL-2.0 + Classpath exception; EDL-1.0](https://repo.maven.apache.org/maven2/org/eclipse/angus/angus-mail/2.0.5/angus-mail-2.0.5.pom) (see note below) | [2.0.5](https://repo.maven.apache.org/maven2/org/eclipse/angus/angus-mail/maven-metadata.xml) — current |
| Activation API — `jakarta.activation:jakarta.activation-api` | 2.1.4 | [EDL-1.0][dependency-edl] | [2.1.4](https://repo.maven.apache.org/maven2/jakarta/activation/jakarta.activation-api/maven-metadata.xml) — current |
| Activation runtime — `org.eclipse.angus:angus-activation` | 2.0.3 | [EDL-1.0 (BSD-3-Clause)][dependency-edl] | [2.0.3](https://repo.maven.apache.org/maven2/org/eclipse/angus/angus-activation/maven-metadata.xml) — current |
| Excel / OLE — `org.apache.poi:poi`, `org.apache.poi:poi-ooxml` | 5.5.1 each | [Apache-2.0][dependency-apache] | [POI 5.5.1](https://repo.maven.apache.org/maven2/org/apache/poi/poi/maven-metadata.xml), [OOXML 5.5.1](https://repo.maven.apache.org/maven2/org/apache/poi/poi-ooxml/maven-metadata.xml) — current |
| HTML parser — `org.jsoup:jsoup` | 1.23.2 | [MIT](https://jsoup.org/license) | [1.23.2](https://repo.maven.apache.org/maven2/org/jsoup/jsoup/maven-metadata.xml) — current |
| HTML → PDF — `io.github.openhtmltopdf:openhtmltopdf-pdfbox` | 1.1.97 | [LGPL-2.1-or-later](https://github.com/openhtmltopdf/openhtmltopdf#license) | [1.1.97](https://repo.maven.apache.org/maven2/io/github/openhtmltopdf/openhtmltopdf-pdfbox/maven-metadata.xml) — current |
| RTF → HTML — `com.github.bbottema:rtf-to-html` | 2.0.3 | [Apache-2.0][dependency-apache] | [2.0.3](https://repo.maven.apache.org/maven2/com/github/bbottema/rtf-to-html/maven-metadata.xml) — current |
| PDF / XMP — `org.apache.pdfbox:pdfbox`, `org.apache.pdfbox:xmpbox` | 3.0.8 each | [Apache-2.0][dependency-apache] | [PDFBox 3.0.8](https://repo.maven.apache.org/maven2/org/apache/pdfbox/pdfbox/maven-metadata.xml), [XMPBox 3.0.8](https://repo.maven.apache.org/maven2/org/apache/pdfbox/xmpbox/maven-metadata.xml) — current |
| Google API client — `com.google.api-client:google-api-client` | 2.9.1 | [Apache-2.0][dependency-apache] | [2.9.1](https://repo.maven.apache.org/maven2/com/google/api-client/google-api-client/maven-metadata.xml) — current |
| OAuth — `com.google.oauth-client:google-oauth-client`, `google-oauth-client-jetty`, `google-oauth-client-java6` | 1.39.0 each | [Apache-2.0][dependency-apache] | [Core 1.39.0](https://repo.maven.apache.org/maven2/com/google/oauth-client/google-oauth-client/maven-metadata.xml), [Jetty 1.39.0](https://repo.maven.apache.org/maven2/com/google/oauth-client/google-oauth-client-jetty/maven-metadata.xml), [Java6 1.39.0](https://repo.maven.apache.org/maven2/com/google/oauth-client/google-oauth-client-java6/maven-metadata.xml) — current |
| Google HTTP Gson adapter — `com.google.http-client:google-http-client-gson` | 2.2.0 | [Apache-2.0][dependency-apache] | [2.2.0](https://repo.maven.apache.org/maven2/com/google/http-client/google-http-client-gson/maven-metadata.xml) — current |
| HTTP transport — `com.google.http-client:google-http-client` | 2.2.0 | [Apache-2.0][dependency-apache] | [2.2.0](https://repo.maven.apache.org/maven2/com/google/http-client/google-http-client/maven-metadata.xml) |
| JSON — `com.google.code.gson:gson` | 2.14.0 | [Apache-2.0][dependency-apache] | [2.14.0](https://repo.maven.apache.org/maven2/com/google/code/gson/gson/maven-metadata.xml) |
| Gmail API bindings — `com.google.apis:google-api-services-gmail` | v1-rev20261005-2.0.0 | [Apache-2.0][dependency-apache] | [v1-rev20261005-2.0.0](https://repo.maven.apache.org/maven2/com/google/apis/google-api-services-gmail/maven-metadata.xml) — current |
| Tests only — `org.junit.jupiter:junit-jupiter` | 6.1.3 | [EPL-2.0][dependency-epl] | [6.1.3](https://repo.maven.apache.org/maven2/org/junit/jupiter/junit-jupiter/maven-metadata.xml) — current |

The Jakarta Mail and Angus Mail POMs list multiple licenses. Their distribution
notices describe EPL-2.0 and a conditional GPL-2.0 secondary license with the
Classpath exception; EDL-1.0 is also declared in the POMs. These declarations
are not a statement that every file can be relicensed under any listed license.
Angus Activation **2.0.3** declares EDL-1.0, not EPL-2.0.

Selected transitive versions below come from `mvn dependency:tree` for this POM;
they are not separately pinned direct dependencies. The POI scratchpad module is
managed at 5.5.1 to match POI/OOXML, and the Google HTTP client BOM keeps its
transport modules aligned at 2.2.0:

| Library / Maven artifact | Resolved version | License | Latest stable |
| --- | --- | --- | --- |
| PDF font support — `org.apache.pdfbox:fontbox` | 3.0.8 | [Apache-2.0][dependency-apache] | [3.0.8](https://repo.maven.apache.org/maven2/org/apache/pdfbox/fontbox/maven-metadata.xml) |
| HTML renderer core — `io.github.openhtmltopdf:openhtmltopdf-core` | 1.1.97 | [LGPL-2.1-or-later](https://github.com/openhtmltopdf/openhtmltopdf#license) | [1.1.97](https://repo.maven.apache.org/maven2/io/github/openhtmltopdf/openhtmltopdf-core/maven-metadata.xml) |

### Python Libraries

Python dependencies use audited minimum versions with upper bounds on the next
major release. The ranges come from [`requirements-web.txt`](requirements-web.txt)
and [`requirements-docs.txt`](requirements-docs.txt); actual installed versions
vary by environment. All ranges admit the latest stable versions checked below.
Upgrade an activated virtual environment with `python -m pip --isolated install --upgrade -r requirements-web.txt -r requirements-docs.txt`.
PyPI links provide upstream release and license metadata.

| Library / use | Configured range | Latest upstream | License |
| --- | --- | --- | --- |
| Flask — validator web application | `>=3.1.3,<4` | [3.1.3](https://pypi.org/project/Flask/3.1.3/) | BSD-3-Clause |
| Gunicorn — production web server | `>=26.2.0,<27` | [26.2.0](https://pypi.org/project/gunicorn/26.2.0/) | MIT |
| pikepdf — PDF validation / sample processing | `>=10.17.0,<11` | [10.17.0](https://pypi.org/project/pikepdf/10.17.0/) | MPL-2.0 |
| pypdf — PDF inspection | `>=7.0.0,<8` | [7.0.0](https://pypi.org/project/pypdf/7.0.0/) | BSD-3-Clause |
| ReportLab — documentation PDF generation | `>=5.0.1,<6` | [5.0.1](https://pypi.org/project/reportlab/5.0.1/) | BSD-3-Clause |
| Werkzeug — directly imported web helpers | `>=3.1.9,<4` | [3.1.9](https://pypi.org/project/Werkzeug/3.1.9/) | BSD-3-Clause |

### Build Plugins

These are build tools, not application libraries. All three are Apache-2.0 licensed.

| Maven plugin (`org.apache.maven.plugins`) | Used | Latest stable |
| --- | --- | --- |
| `maven-compiler-plugin` | 3.16.0 | [3.16.0](https://repo.maven.apache.org/maven2/org/apache/maven/plugins/maven-compiler-plugin/maven-metadata.xml) |
| `maven-surefire-plugin` | 3.6.0 | [3.6.0](https://repo.maven.apache.org/maven2/org/apache/maven/plugins/maven-surefire-plugin/maven-metadata.xml) |
| `maven-shade-plugin` | 3.6.2 | [3.6.2](https://repo.maven.apache.org/maven2/org/apache/maven/plugins/maven-shade-plugin/maven-metadata.xml) |

[dependency-apache]: https://www.apache.org/licenses/LICENSE-2.0
[dependency-edl]: https://www.eclipse.org/org/documents/edl-v10.php
[dependency-epl]: https://www.eclipse.org/legal/epl-2.0/

## License

Project code in this repository is licensed under [Apache License 2.0](LICENSE),
unless otherwise noted.

Important exceptions and release caveats:

- `openhtmltopdf-pdfbox` is LGPL-licensed and affects redistribution of shaded
  fat jars
- ThinkBox branding assets under [`assets/`](assets/) should be treated as
  proprietary unless separately licensed
- both runnable JARs merge upstream dependency license/notice resources and
  include project notices under `META-INF/eapdf/`; redistribution and LGPL
  source/relinking obligations still need release-specific review
- exact notices for bundled Liberation Sans/Serif 2.1.5, DejaVu Sans/Mono 2.37,
  Droid Sans Fallback and Nanum Gothic are packaged under
  `META-INF/eapdf/third_party_licenses/`

These are outstanding redistribution checks, not a licensing clearance. See
[`docs/THIRD_PARTY_LICENSES.md`](docs/THIRD_PARTY_LICENSES.md) before shipping binaries.

## Building

Use Java 21 or later and Maven:

```bash
mvn -Deapdf.build.directory=/tmp/eapdf-build package
mvn -f validator-desktop/pom.xml -Deapdf.build.directory=/tmp/eapdf-validator-build package
```

See [Contributing](CONTRIBUTING.md) for development guidance and
[validator coverage](docs/VALIDATOR_COVERAGE.md) for assessment limitations.

## Notes For GitHub Users

- For private sharing, use the ignored local credential/session files plus the example templates documented in [`docs/PRIVATE_GITHUB.md`](docs/PRIVATE_GITHUB.md).
- This repository currently includes project-specific sample/config files; review [`config/`](config/) before publishing or forking publicly.

## Contact

- Name: Greg Lepore
- Email: greg@thinkbox.digital
- Website: https://thinkbox.digital
