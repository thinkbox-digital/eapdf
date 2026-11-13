# Library packages

Looking for an application? Use [Releases](https://github.com/thinkbox-digital/eapdf/releases).
GitHub Packages contains our modified HTML-to-PDF libraries for developers.

| Library | Version |
| --- | --- |
| `io.github.openhtmltopdf:openhtmltopdf-core` | `1.1.113-eapdf.1` |
| `io.github.openhtmltopdf:openhtmltopdf-pdfbox` | `1.1.113-eapdf.1` |

These builds include our PDF/A and PDF/UA changes. Each has a matching source JAR
and license information. See [renderer details](RENDERER.md).

Normal EA-PDF source builds use the verified copies in `third_party/maven/`.
**No GitHub token is needed.**

Other projects can use `https://maven.pkg.github.com/thinkbox-digital/eapdf`.
GitHub requires a token with `read:packages`, even for public Maven packages.
Follow [GitHub's setup guide](https://docs.github.com/en/packages/working-with-a-github-packages-registry/working-with-the-apache-maven-registry).
Keep tokens in private Maven settings, never in this repository.

Maintainers publish through the **Publish patched libraries** workflow. It checks
the uploads against `third_party/openhtmltopdf/lock.json`. Use a new package version
when library contents change; `verify_only` checks an existing publication.
