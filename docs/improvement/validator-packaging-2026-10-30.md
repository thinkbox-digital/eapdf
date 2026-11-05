# Validator executable JAR — 2026-10-30

Corrected distributable: `dist/eapdf-validator.jar` (Java 21).

SHA-256:

```text
c7218612db3f7a0d30373ecea3a46c0fb1901ab090119000429da9495f6cfa64
```

The original local beta.3 JAR and a fresh download of the GitHub beta.3 asset
were identical (`0b24ec7d50d8836895b7b3116af89495cd9e15b6f638bcad9a42c5ac6b2552ba`).
Both had a valid `META-INF/MANIFEST.MF`, correct CRCs, no duplicate entries and
`Main-Class: eapdf.ValidatorGui`. The user confirmed the reported Chromebook
failure was their mistake. No missing-manifest defect in those artifacts was
reproduced, and no other ZIP structural defect was found.

The build is now harder to publish incorrectly:

- `validator-desktop/pom.xml` explicitly configures `eapdf.ValidatorGui` in both
  Maven JAR and Shade stages. JAR Plugin 3.5.0 uses a fixed output timestamp.
- Shade produces the executable main artifact with all runtime dependencies;
  minimization is disabled, and service registrations/resources are retained.
  The separate worker entry point remains `eapdf.JavaValidation`.
- `mvn verify` checks the **finished shaded JAR**, its manifest, every entry's
  size/CRC, duplicate/unsafe entry names, Java 21 bytecode, required runtime classes,
  mail service registration, rules and profiles. It also launches `java -jar`
  from a clean directory in headless mode and verifies the existing desktop-required
  message, rather than accepting an invalid JAR error.
- `scripts/package_validator.py` builds in a private `/tmp` directory, runs the
  verification gate, then atomically replaces the distributable. Failed builds
  leave the previous JAR intact. It writes `dist/eapdf-validator.jar.sha256` and
  updates `dist/validator-build.json` with source hashes.

Rebuild from the repository root with Java 21 selected for Maven:

```sh
python3 scripts/package_validator.py
(cd dist && sha256sum -c eapdf-validator.jar.sha256)
unzip -p dist/eapdf-validator.jar META-INF/MANIFEST.MF
java -jar dist/eapdf-validator.jar
```

Verification passed on Linux with OpenJDK 21.0.12.1:

- 194 validator tests: zero failures/errors, nine skips; two packaging checks pass.
- `unzip -t` passes for the distributable; its manifest names `eapdf.ValidatorGui`.
- The actual `java -jar` command opened the validator's Swing window from an
  isolated directory. Its window was matched to the launched process and then
  the owned test process was closed.
- With development configuration/classpath variables removed and a fresh Java
  user directory, a directory containing only this JAR and a copied real example
  PDF passed standalone validation: PDF/A PASS, EA-PDF PASS, zero failed checks.
  The original PDF hash was unchanged. No checkout, Maven cache, Python or external
  veraPDF installation was used by that running application.

The Java 21 Linux artifact has no development-path `Class-Path` dependency and is
suitable for a graphical Java 21 Crostini session. This test used Linux, not a
physical Chromebook. A full Java desktop runtime supplies Swing/AWT and the normal
Linux graphical libraries. A headless session intentionally cannot show the GUI.
Existing logging, including the nonfatal Log4j-provider message during validation,
is retained. Packaging success is separate from large-file conformance acceptance.

Logs and the clean-directory launch/report evidence are retained under
local verification records, including
`validator-distributable-build.log`, `validator-zip-test.log` and
`packaging-clean-smoke/result.json`. GitHub release assets were not changed.
