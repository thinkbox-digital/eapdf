# Email to EA-PDF

**Your email—complete, authentic, accessible, structured.**

[EA-PDF](docs/reference/EA-PDF-v1.pdf) is a specification for archiving email in PDF/A. It supports individual messages, combined archives and collections, with attachments, email metadata and optional original source messages.

Turn saved emails and mailboxes into PDFs you can read, search and archive.
Supports **EML, MSG, PST, MBOX and Gmail**.

[User guide](https://thinkbox-digital.github.io/eapdf/) · [PDF manual](docs/EA-PDF_User_Manual.pdf) · [Downloads](https://github.com/thinkbox-digital/eapdf/releases/tag/v2.2.0-beta.1)

**Beta:** creator and validator 2.2.0-beta.1; full and Lite readers 1.2.0-beta.1.
Java applications need **Java 21 or later**. The readers run locally in your browser.

## Download

| Tool | What it does |
| --- | --- |
| [Creator](https://github.com/thinkbox-digital/eapdf/releases/download/v2.2.0-beta.1/emailtopdfa-2.2.0-beta.1-gui.jar) | Convert email using a desktop window. |
| [Command-line creator](https://github.com/thinkbox-digital/eapdf/releases/download/v2.2.0-beta.1/emailtopdfa-2.2.0-beta.1.jar) | Convert email from a terminal or script. |
| [Validator](https://github.com/thinkbox-digital/eapdf/releases/download/v2.2.0-beta.1/eapdf-validator-2.2.0-beta.1.jar) | Check the finished PDFs and save a report. |
| Full reader: [Chromium](https://github.com/thinkbox-digital/eapdf/releases/download/v2.2.0-beta.1/eapdf-viewer-1.2.0-beta.1-chromium.zip) / [Firefox](https://github.com/thinkbox-digital/eapdf/releases/download/v2.2.0-beta.1/eapdf-viewer-1.2.0-beta.1-firefox.zip) | Read messages, folders and attachments; includes Conversations, Portfolio view and larger-archive support. |
| Lite reader: [Chromium](https://github.com/thinkbox-digital/eapdf/releases/download/v2.2.0-beta.1/eapdf-viewer-lite-1.2.0-beta.1-chromium.zip) / [Firefox](https://github.com/thinkbox-digital/eapdf/releases/download/v2.2.0-beta.1/eapdf-viewer-lite-1.2.0-beta.1-firefox.zip) | The same reading layout with fewer features. PDFs up to 32 MiB and 2,048 pages. |

The [release page](https://github.com/thinkbox-digital/eapdf/releases/tag/v2.2.0-beta.1)
also has configuration, examples, the manual, Lite web/source packages and checksums.
Browser extensions are unsigned; Firefox installation is temporary.

## Get started

1. Download the Creator and configuration ZIP, and extract them into one folder.
2. Start the Creator:

   ```sh
   java -jar emailtopdfa-2.2.0-beta.1-gui.jar
   ```

3. Choose your email files, output folder and format, then convert.
4. Open the finished PDF in the Validator and read its report.
5. Use either reader to browse messages and attachments. See [installation](browser-viewer/README.md).

Keep the original emails and mailboxes. A PDF does not replace every property of
the original format. Validation can find problems; it does not prove that every
aspect of the email or its appearance was preserved.

## Choose an output

| Format | Result |
| --- | --- |
| **1s** | One PDF per message, with its source and attachments. |
| **1m** | Two or more messages in one continuous PDF. |
| **1c** | A collection containing separately openable message PDFs. |
| **1si / 1mi / 1ci** | The same layouts with original sources and native attachment contents omitted. |

The isolated formats do **not** anonymize or redact email. Visible text, addresses,
metadata and inline images can remain. For PST, MBOX and Gmail, retained sources
are exported messages; keep the original mailbox separately.

All formats use PDF 2.0 / PDF/A-4f. See the [format guide](docs/manual/user-manual.html#basics)
for details and [examples](examples/) for sample files.

## From the command line

```sh
java -Xmx2g -jar emailtopdfa-2.2.0-beta.1.jar --jobs 2 --out /tmp/eapdf-output message.eml
```

For a collection, add `--archive-profile c --archive-output /tmp/eapdf-output/archive_1c.pdf`.
Use `m`, `mi` or `ci` for the other archive formats. See
[conversion instructions](docs/CONVERSION_OPERATIONS.md), [Gmail setup](docs/GMAIL.md)
and [configuration](config/README.md).

## Build and contribute

Use Java 21 and Maven:

```sh
mvn -Deapdf.build.directory=/tmp/eapdf-build verify
```

Reader builds need Node.js; see the [reader guide](browser-viewer/README.md#build).

[Tests](https://github.com/thinkbox-digital/eapdf/actions/workflows/quality.yml) ·
[Contribute](CONTRIBUTING.md) · [Project status](docs/PROJECT_STATUS.md) ·
[Code layout](docs/ARCHITECTURE.md) · [Report a security problem](SECURITY.md) ·
[Library packages](docs/PACKAGES.md)

## Regenerate local outputs

Maintainers can rebuild local samples with `scripts/eapdf-regenerate sample`.
The `everything` option processes all local inputs. These commands create files
under `out/` and keep logs separately. Read the
[regeneration instructions](docs/CONVERSION_OPERATIONS.md#regenerating-local-outputs)
before running them.

## License

[Apache License 2.0](LICENSE). Bundled libraries have their own licenses;
see [notices](THIRD_PARTY_NOTICES.md) and the [dependency list](docs/security/runtime-inventory/DEPENDENCY_LICENSES.md).
