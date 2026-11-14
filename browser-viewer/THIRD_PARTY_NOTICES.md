# Third-party materials

The application code is Apache-2.0 (see LICENSE). Dependency licenses are separate.
The build uses PDF.js 6.5.438 with the local generic DPart/DPM inspection patch
recorded in `vendor/pdfjs-patches/` in the source project. Generated display/worker
bundles retain their Apache-2.0 headers. Rendering assets and their license files
remain unchanged. The exact source revision and patch hash are in build-info.json.

| Distributed material | License and retained notice |
| --- | --- |
| PDF.js 6.5.438 JavaScript | Apache-2.0; `vendor/pdfjs/LICENSE` |
| Adobe character maps | BSD-style; `vendor/pdfjs/cmaps/LICENSE` |
| Foxit/PDFium standard fonts | BSD-3-Clause; `vendor/pdfjs/standard_fonts/LICENSE_FOXIT` |
| Liberation Sans 1.07.4 fonts | GPL-2.0 with the Liberation font exceptions; `vendor/pdfjs/standard_fonts/LICENSE_LIBERATION` contains the terms and full GPL text |
| OpenJPEG and its PDF.js wrapper | BSD-style notices in `vendor/pdfjs/wasm/LICENSE_OPENJPEG` and `LICENSE_PDFJS_OPENJPEG` |
| QCMS and its PDF.js wrapper | MIT and BSD-style notices in `vendor/pdfjs/wasm/LICENSE_QCMS` and `LICENSE_PDFJS_QCMS` |
| JBIG2 and its PDF.js wrapper | PDFium BSD and Apache notices in `vendor/pdfjs/wasm/LICENSE_JBIG2` and `LICENSE_PDFJS_JBIG2` |
| ICC profiles | CC0-1.0; `vendor/pdfjs/iccs/LICENSE` |

Liberation 1.07.4 is **not** SIL OFL. Its original editable SFD sources, build
scripts and licenses are included in `license-sources/liberation-fonts-1.07.4.tar.gz`.
The upstream download now returns 404; the original source archive was recovered
unchanged from the [Cygwin source distribution](https://www.sourceware.org/pub/cygwin/noarch/release/liberation-fonts/liberation-fonts-1.07.4-1-src.tar.xz).
Archive SHA-256: `ad98b7498dc2992f7f0868f79b65ce4a720a3acdb63ab3f1f1cb6881117a5406`.
PDF.js confirms these are unmodified 1.07.4 fonts in its
[pinned source README](https://github.com/mozilla/pdf.js/blob/0a4153349dc473ba950fc17941c5ca7eda43c1de/external/standard_fonts/README.md).
Retain the separate notices and source archive when redistributing the extension.

Development tools are not copied into the extension: TypeScript 7.0.2,
Playwright and playwright-core 1.65.0 are Apache-2.0. Acorn 8.19.0 is MIT and is used only to select development tests. PDF.js's optional Node canvas
packages are MIT according to their npm manifests; they are not used or shipped
by this browser application. Platform-specific optional dependencies remain
recorded in package-lock.json. Playwright's bundled NOTICE files and third-party
tool notices remain in node_modules for developers.

`npm run verify:licenses` checks pinned dependency declarations, lockfile
integrities, license hashes and the font-source hash. Build output records
SHA-256 for every distributed file. This is an inventory and preservation check,
not a claim of store approval or completion of the M6 release review.

The unmodified upstream web/pdf_viewer.mjs component bundle (Apache-2.0) is
included for StructTreeLayerBuilder only. Its source is in the same pinned
PDF.js upstream revision; the PDF scripting interpreter is excluded.

## XML interpretation worker

@xmldom/xmldom 0.9.12 (MIT) is bundled for background XML interpretation. Its license is shipped as xmldom-LICENSE. esbuild 0.28.2 (MIT) is a build-only dependency.
