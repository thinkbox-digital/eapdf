# Contributing

This is beta demonstration software. Keep changes focused and describe the user
problem, validation performed and remaining limitations.

- Read [AGENTS.md](AGENTS.md).
- Use Java 21 or later. Build with `mvn -Deapdf.build.directory=/tmp/eapdf-build package`.
- Viewer instructions and checks are in [browser-viewer/README.md](browser-viewer/README.md).
- Preserve original test inputs and their provenance. Do not weaken assertions,
  change visual thresholds or accept new baselines without explaining the difference.
- Do not commit credentials, tokens, private email, comparison documents or scraped
  debug captures. Use redacted logs and the smallest appropriate reproduction.
- Do not attach private PDFs to public issues. See [SECURITY.md](SECURITY.md).

Contributions must be yours to share under the project license. Keep third-party
license notices and identify the origin and redistribution terms of new fixtures.
