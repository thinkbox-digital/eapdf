# Technical guide

## From the command line

```sh
java -Xmx2g -jar emailtopdfa-2.4.0-beta.3.jar --jobs 2 --out /tmp/eapdf-output message.eml
```

For a collection, add `--archive-profile c --archive-output /tmp/eapdf-output/archive_1c.pdf`.
Use `m`, `mi` or `ci` for the other archive formats. See
[conversion instructions](docs/CONVERSION_OPERATIONS.md), [Gmail setup](docs/GMAIL.md)
and [configuration](config/README.md).

## Reload local readers

In Chromium, choose **Load unpacked** once at the new checkout location.

- Full — `browser-viewer/local-install/full/chromium`

Future builds should refresh these same folders. Use **Reload** on the extension
card and reopen the viewer tab.

## Build and contribute

Use Java 21 and Maven:

```sh
mvn -Deapdf.build.directory=/tmp/eapdf-build verify
```

Reader builds need Node.js; see the [reader guide](browser-viewer/README.md#build).

[Tests](https://github.com/thinkbox-digital/eapdf/actions/workflows/quality.yml) ·
[Contribute](CONTRIBUTING.md) · [Project status](docs/PROJECT_STATUS.md) ·
[Code layout](docs/ARCHITECTURE.md) · [Architecture diagram (PDF)](docs/EA-PDF_Software_Architecture.pdf) · [Report a security problem](SECURITY.md) ·
[Library packages](docs/PACKAGES.md) · [Privacy](docs/site/privacy.md)

## Regenerate local outputs

Maintainers can rebuild local samples with `scripts/eapdf-regenerate sample`.
The `everything` option processes all local inputs. These commands create files
under `out/` and keep logs separately. Read the
[regeneration instructions](docs/CONVERSION_OPERATIONS.md#regenerating-local-outputs)
before running them.

## Release package acceptance

Run the same acceptance check locally with the candidate JARs and viewer ZIPs.
Use JDK 21, Python 3.12 with `pikepdf`, and the browser viewer's Node dependencies
and Playwright Chromium. For Firefox set `FIREFOX_PATH` and `GECKODRIVER_PATH`
if the executables are outside PATH. For the desktop JAR use a graphical session
or `xvfb-run` and set `JAVAFX_LIB` when JavaFX is installed separately.

```sh
python3 -B scripts/check_release_packages.py \
  --creator "$CREATOR_JAR" --validator "$VALIDATOR_JAR" \
  --viewer "$CHROMIUM_ZIP" --viewer "$FIREFOX_ZIP" \
  --desktop-viewer "$DESKTOP_JAR" --output "$(mktemp -d)/acceptance"
```

Pass each browser or web ZIP with a separate `--viewer` argument. The gate
generates all six profiles from dedicated EML fixtures, checks PDF structure
and payloads, requires standalone EA-PDF and veraPDF PDF/A-4f success, rejects
missing and invalid collection children, and opens the same PDFs in the
package readers. Successful checks write `SHA256SUMS` beside the logs and reports.

For a GitHub release, dispatch **Release package acceptance** on the
`v<VERSION>` tag with **publish** enabled. Its publish job uploads the
verified packages after rechecking their hashes.

Chrome Web Store submission looks up a successful package acceptance run for
the release tag's commit on protected `main` and compares the ZIP's checksum
with that run before submitting it.
The Chrome Store workflow also requires the successful acceptance run ID.
