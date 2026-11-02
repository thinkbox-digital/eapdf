# Standalone validator notices

Project code uses the repository's Apache License 2.0. LICENSE and NOTICE are
included in the JAR under META-INF/eapdf. Upstream notices and license files
are retained and merged during packaging.

This app bundles these Java components:

- jutf7 1.0.0: MIT, legacy UTF-7 source email decoding. Copyright and permission
  notice: META-INF/eapdf/third-party/jutf7-MIT.txt.
  Source: https://jutf7.sourceforge.net/.
- veraPDF core 1.31.1, pdf-model 1.31.1, parser 1.31.7,
  verapdf-xmp-core 1.31.1, validation-model 1.31.17, feature-reporting 1.31.17
  and metadata-fixer 1.31.17: MPL 2.0 / GPL 3 dual licensing. This integration
  uses the MPL option. The local BaseValidator adaptation replaces the
  visited-object identity set with exact disk-backed storage after 100,000 IDs,
  and closes that storage. It also builds diagnostic paths only for objects
  that will be queued or reported as invalid, sharing immutable queued path
  prefixes and formatting diagnostics when needed. The local ASInFilter adaptation
  reuses bounded per-thread single-byte scratch with independent reentrant reads.
  The local COSDictionary adaptation stores up to three entries inline before
  promoting to HashMap, preserving canonical values, copy aliases and map views.
  Rule evaluation, traversal order and profiles are
  unchanged. The adapted MPL source and its Apache-licensed storage helper are
  included under META-INF/eapdf/modified-verapdf-sources in the JAR.
  Sources: https://github.com/veraPDF/veraPDF-library,
  https://github.com/veraPDF/veraPDF-parser,
  https://github.com/veraPDF/veraPDF-validation and
  https://github.com/veraPDF/veraPDF-model.
- The selected ISO 32005 XML profile: veraPDF Consortium, CC BY 4.0.
  Original attribution is retained in the XML. Source selection and provenance
  are documented in the repository's config/verapdf/README.md and
  docs/improvement/verapdf-coverage/iso32005-profile.json.
- Apache POI 5.5.1 and its Commons/Log4j API dependencies: Apache License 2.0.
  Source: https://github.com/apache/poi.
- Apache PDFBox / FontBox / pdfbox-io 3.0.8 and Commons Logging 1.4.0:
  Apache License 2.0.
- Gson 2.14.0, Jackson 2.15.0 and Error Prone annotations 2.48.0:
  Apache License 2.0.
- Jakarta Mail API 2.1.5: EPL 2.0 / EDL 1.0 / GPL 2 with Classpath Exception.
- Eclipse Angus Mail 2.0.5 and Activation 2.0.3: EPL 2.0 / EDL 1.0.
- Jakarta Activation API 2.1.4 and Javax Activation API 1.2.0: EDL 1.0 / BSD.
- JAXB API 2.4.0-b180830.0359, JAXB implementation 2.3.2 and Istack runtime
  3.0.5: upstream CDDL / GPL 2 with Classpath Exception terms.
- Saxon-HE 12.8: MPL 2.0. Sources: https://github.com/Saxonica/Saxon-HE.
- Rhino 1.7.14.1: MPL 2.0. Sources: https://github.com/mozilla/rhino.
- XML Resolver 5.3.3 and its data: Apache License 2.0, with retained upstream
  notices for the bundled schema data.
- StAX utilities 20070216: BSD-style upstream terms.

JUnit Jupiter 6.1.3 (EPL 2.0) is used for tests and is not bundled in the app.
The full MPL 2.0 text is included under META-INF/eapdf/third-party/.

This list describes the validator JAR. The separate creator app has its own
dependency and font notices in the repository's THIRD_PARTY_NOTICES.md.

`IndexedPdfLoader` adapts Apache PDFBox 3.0.8 object-stream loading under
the Apache License 2.0. The source retains its license header and shares one
object-key index per file, using the same loader as the creator checks.

The local `StructureElementAccessObject` adaptation of veraPDF parser 1.31.7
reuses the same parent-number-tree lookup wrapper within an immutable document.
It preserves number-tree lookup and malformed-tree behavior, and clears the
thread-local reference after each upstream check. The adapted MPL 2.0 source is
bundled under META-INF/eapdf/modified-verapdf-sources.

The local `GFPDContentStream` / `BaseValidator` adaptation keeps live resource-name
sets for the two deferred resource-name checks, instead of retaining each stream's
parsed drawing operators. Tests, error arguments, evaluation timing and traversal
are unchanged. Rules using any other expression keep the original model. The
modified MPL 2.0 sources are bundled in the JAR.

The bundled veraPDF Parser 1.31.7 `org.verapdf.parser.XrefStreamParser` is
modified locally to shift xref field bytes as `long`, preserving unsigned four-byte
offsets above 2 GiB and wider fields. The original file's MPLv2+/GPLv3+ notice
is retained, and modified source is embedded in the JAR under
`META-INF/eapdf/modified-verapdf-sources/`. Validation rules are unchanged.
