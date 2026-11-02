# Attached-message PST fixture

`attached-message.pst` is Apache Tika's `testPST.pst`, used to exercise real
embedded-message extraction and the attached email's nested DOCX file.

- Source: https://github.com/apache/tika/blob/dc571dddba324485fdb6dc1d665163e56267d0fc/tika-parsers/tika-parsers-standard/tika-parsers-standard-modules/tika-parser-microsoft-module/src/test/resources/test-documents/testPST.pst
- Upstream project license: Apache License 2.0, https://github.com/apache/tika/blob/dc571dddba324485fdb6dc1d665163e56267d0fc/LICENSE
- SHA-256: `f2a6b1d2cad00f574e3d1c1211c4b1c854d6526caea77213adc3da92b7813ae3`

The fixture contains public mailing-list messages and a forwarded message
with an embedded email named "First email". Its body says "Docx file
attached." and its `attachment.docx` contains "This is a docx attachment."
