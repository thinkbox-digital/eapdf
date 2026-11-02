# Third-Party License Texts To Ship

This directory documents the standard license texts that should accompany
release bundles for this project. Both runnable JARs merge upstream dependency
license/notice resources at their original paths and include this directory,
project LICENSE/NOTICE and the dependency summary under `META-INF/eapdf/`.

Exact bundled font notices are present as `DejaVu-LICENSE.txt` (Bitstream Vera
and Arev notices extracted from the bundled font) and `Liberation-OFL-1.1.txt`
(font copyright plus the SIL OFL text supplied by its distribution). These are
not covered by the project's Apache license. A remaining checklist below is
for separate release bundles; it does not mean dependency license resources
are absent from the JARs.

For a release package, include the following standard texts or exact upstream
license files:

- `Apache-2.0.txt`
- `MIT.txt`
- `EPL-2.0.txt`
- `EDL-1.0.txt`
- `LGPL-2.1.txt`
- `LGPL-3.0.txt`
- `GPL-2.0-with-Classpath-Exception.txt` if you want full coverage for the
  Jakarta Mail API alternatives noted in its bundled license metadata
- the upstream font license text for `LiberationSans-Regular.ttf`

Also include a project-specific note for proprietary assets:

- `PROPRIETARY-ASSETS.md`

This repository currently documents the dependency/license mapping in:

- [`../THIRD_PARTY_NOTICES.md`](../THIRD_PARTY_NOTICES.md)
- [`../docs/THIRD_PARTY_LICENSES.md`](../docs/THIRD_PARTY_LICENSES.md)

`DroidSansFallback-LICENSE.txt` records the bundled Droid Sans Fallback Full font attribution and the Apache License 2.0 text. The unmodified font is bundled as a fallback for Chinese, Japanese and Korean text.

`Adobe-XMP-Core-BSD-3-Clause.txt` contains Adobe’s upstream BSD notice accompanying the metadata-extractor transitive XMP Core dependency.
