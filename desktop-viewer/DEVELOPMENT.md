# Desktop viewer development

## Build and package

From the checkout root, use Maven and JDK 21 or later to run from source on
Linux, including x86-64.

```sh
mvn -f desktop-viewer/pom.xml javafx:run
```

From the checkout root, build the JAR with Maven and JDK 21 or later.

```sh
desktop-viewer/package.sh
```

The command writes one versioned viewer JAR to `dist/`. Set
`EAPDF_VIEWER_DIST` to choose another directory. A later build prunes the
previous JAR when the saved checksum still matches. Files outside that
inventory, including older packages and edited copies, are preserved. GitHub Actions builds the JAR
on viewer changes and attaches it to releases published from a tag that
includes the desktop release workflow.

The viewer uses PDFViewFX 3.6.1 and PDFBox 3.0.8. Maven resolves both dependencies.
The standalone viewer is shared across all three editions. Secure's signature
and provenance verification is available through its browser reader and
Security Validator.
