# RTL ligature regression fixture

`issue16880.pdf` is copied unchanged from [Mozilla PDF.js PR #22521](https://github.com/mozilla/pdf.js/pull/22521), merge commit `5f2d7186f72c87e40150314c5cf856133f0b2466`, under the upstream Apache-2.0 project license. The expected extracted text is `سلام کلاس لاله`.

The source patch is recorded in `vendor/pdfjs-patches/0010-rtl-ligatures.patch` and applies the upstream changes to bidirectional ordering, glyph normalization and the bidirectional unit tests. Both Full and Lite select it. The PDF.js base remains 6.5.438; this is a backport, not a newer release.
