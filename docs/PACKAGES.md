# Patched libraries

Runnable creator/validator JARs, viewer ZIPs, examples and the manual are release
assets. The Packages area publishes the project-maintained OpenHTMLtoPDF builds:

| Maven coordinate | Purpose |
| --- | --- |
| `io.github.openhtmltopdf:openhtmltopdf-core:1.1.105-eapdf.1` | Patched HTML layout |
| `io.github.openhtmltopdf:openhtmltopdf-pdfbox:1.1.105-eapdf.1` | Patched PDFBox output |

Repository URL: `https://maven.pkg.github.com/thinkbox-digital/eapdf`.
Each package includes the original license metadata and matching source JAR.
The pinned upstream source and project patches are recorded in
`third_party/openhtmltopdf/lock.json`. These are EA-PDF-maintained builds, not
unmodified upstream releases. Other dependencies come from their upstream registries.

Verified build copies remain under `third_party/maven/` so a normal source build
needs no GitHub token. `python3 scripts/build_patched_openhtmltopdf.py` verifies
them against the lock. Packages provides a separate distribution channel.

GitHub requires authentication even to download public Maven packages. See
[GitHub's Maven instructions](https://docs.github.com/en/packages/working-with-a-github-packages-registry/working-with-the-apache-maven-registry).
Use private Maven settings with a token granting `read:packages`; never put a token
in the project POM or commit it. Add the repository above with server id `github`
in a consuming project's Maven configuration. Existing EA-PDF builds use their
verified local copies by default and do not need that configuration.

Published packages are listed at
https://github.com/thinkbox-digital/eapdf/packages.

Maintainers publish with the manual **Publish patched libraries** Actions workflow.
It uses the repository's short-lived `GITHUB_TOKEN`, verifies locked bytes before
upload and verifies downloaded bytes afterward. Package versions are immutable;
change the version and lock when changing library bytes.

Use the workflow’s `verify_only` option to recheck existing package versions without republishing.
