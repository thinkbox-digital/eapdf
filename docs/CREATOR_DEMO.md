# Creator demo

Use [current status](PROJECT_STATUS.md), the [user manual](manual/user-manual.html)
and [example inventory](../examples/README.md) for the supported workflow and
recorded results. Earlier demo checkpoints are historical; this page does not
carry a second release-version or test-count table.

On the tested Linux setup, use Java 21, util-linux prlimit and a graphical session
for the GUI. Fonts, ICC and validator engines are bundled in the versioned JARs.
Download the matching release or build into a temporary directory:

```sh
mvn -Deapdf.build.directory=/tmp/eapdf-demo-build package
eapdf_version=$(cat VERSION)
java -jar "/tmp/eapdf-demo-build/emailtopdfa-${eapdf_version}-gui.jar"
```

For a command-line demonstration, preserve the original input and choose a
separate temporary destination. Use bounded jobs, enable accessibility and keep
optional PREMIS disabled in any custom configuration. Validate the output with
the matching standalone Java validator and read its full PASS/FAIL/INCOMPLETE
report. The [conversion guide](CONVERSION_OPERATIONS.md) owns recovery and limits.

The creator is a working beta. A successful small demo is not unrestricted
mailbox capacity, all-platform acceptance or a conformance/accessibility certificate.
