# Third-Party License Audit

This project ships Java code, fonts, and branding assets. This note summarizes the
licenses for the currently included runtime components and flags anything that
deserves review before private or commercial distribution.

This is a practical engineering summary, not legal advice.

## Summary

Most bundled Java dependencies are under permissive or business-friendly licenses:

- Apache 2.0
- MIT
- EPL 2.0 / EDL 1.0

The following items need special attention:

1. `io.github.openhtmltopdf:openhtmltopdf-pdfbox:1.1.97`
   - LGPL 2.1 or later
   - Usually usable in commercial software, but distribution obligations apply
2. ThinkBox branding assets in [`assets`](../assets)
   - no open-source license is present in the repository
   - treat them as proprietary unless you have explicit rights to distribute them
3. Release license/notice packaging
   - Shade merges provider services and colliding license/notice variants instead of retaining an arbitrary dependency's notice
   - both runnable artifacts retain project notices and exact font notices under `META-INF/eapdf/`; GUI shading runs before CLI shading from a freshly rebuilt thin JAR
   - redistribution/source/relinking obligations still require release-specific review; merged notices alone are not legal clearance
4. Font license coverage
   - the local Liberation Sans and bundled Liberation Serif fonts identify themselves as version 2.1.5 under SIL OFL-1.1
   - bundled DejaVu Sans and DejaVu Sans Mono identify themselves as version 2.37 with Bitstream Vera and Arev notices
   - exact applicable notices are included in `third_party_licenses/DejaVu-LICENSE.txt` and `third_party_licenses/Liberation-OFL-1.1.txt`, and packaged in both artifacts

## Java Runtime Dependencies

### Apache 2.0

These are generally safe for private and commercial use, including bundled
distribution, provided notices are preserved where required.

- `com.pff:java-libpst:0.9.3`
- `org.simplejavamail:outlook-message-parser:1.16.2`
- `com.github.bbottema:rtf-to-html:2.0.3`
- `org.apache.poi:poi:5.5.1`
- `org.apache.poi:poi-ooxml:5.5.1`
- `org.apache.pdfbox:pdfbox:3.0.8`
- `org.apache.pdfbox:xmpbox:3.0.8`
- `com.google.api-client:google-api-client:2.9.1`
- `com.google.oauth-client:google-oauth-client:1.39.0`
- `com.google.oauth-client:google-oauth-client-jetty:1.39.0`
- `com.google.oauth-client:google-oauth-client-java6:1.39.0`
- `com.google.http-client:google-http-client-gson:2.2.0`
- `com.google.http-client:google-http-client:2.2.0`
- `com.google.code.gson:gson:2.14.0`
- `com.google.apis:google-api-services-gmail:v1-rev20261005-2.0.0`

Notes:

- `outlook-message-parser` and `rtf-to-html` include `LICENSE-2.0.txt` in their jars.
- Google client libraries are published from Apache-2.0 upstream repositories.

### MIT

- `org.jsoup:jsoup:1.23.2`

This is generally safe for commercial use.

### EPL 2.0 / EDL 1.0 / GPL with Classpath Exception

- `jakarta.mail:jakarta.mail-api:2.1.5`

The API jar declares:

- EPL 2.0
- GPL 2 with Classpath Exception
- EDL 1.0

For normal commercial distribution, the EPL/EDL paths are the practical ones to rely on.

### EPL 2.0

- `org.eclipse.angus:angus-mail:2.0.5`

These jars include Eclipse license files. EPL 2.0 is generally compatible with
commercial software distribution.

### EDL 1.0

- `jakarta.activation:jakarta.activation-api:2.1.4`
- `org.eclipse.angus:angus-activation:2.0.3`

This is generally business-friendly.

### LGPL 2.1 or later

- `io.github.openhtmltopdf:openhtmltopdf-pdfbox:1.1.97`

This is the main license that deserves review before distributing the fat jar.

Practical impact:

- commercial use is usually possible
- redistribution is not as simple as Apache/MIT-only distributions
- you should preserve the LGPL notice and provide the corresponding license text
- because this project builds a shaded fat jar, review whether your chosen
  distribution model satisfies LGPL expectations for relinking/replacement in your context

This is not automatically a blocker, but it is the one dependency that makes the
commercial story less clean than a pure Apache/MIT/EPL stack.

## Python And Test Dependencies

- Flask 3.1.3, Werkzeug 3.1.9, pypdf 6.21.0 and ReportLab 5.0.1: BSD-3-Clause
- Gunicorn 26.2.0: MIT
- pikepdf 10.17.0: MPL-2.0
- JUnit Jupiter 6.1.3 (Java tests only): EPL-2.0

These Python versions are the minimums required by the requirement files, not a
lockfile for every deployed environment. The renderer remains LGPL-2.1-or-later
after migration to its community-maintained Maven coordinates. The current
version audit and upstream links are in the README's dependency tables.

## Assets

### Liberation Sans Font

File:

- [`assets/LiberationSans-Regular.ttf`](../assets/LiberationSans-Regular.ttf)

Status:

- appears to be an open-source Liberation Sans build
- the repository does not currently include an explicit license text for this file

Action:

- add the font’s upstream license text to the repository before external distribution

### ThinkBox Branding Assets

Files:

- [`assets/thinkbox_footer.png`](../assets/thinkbox_footer.png)
- [`assets/thinkbox_footer_optimized.png`](../assets/thinkbox_footer_optimized.png)
- [`assets/thinkbox_footer_scaled.png`](../assets/thinkbox_footer_scaled.png)
- [`assets/thinkbox_logo.jpg`](../assets/thinkbox_logo.jpg)

Status:

- no open-source license is present in the repository
- these should be treated as proprietary brand assets

Action:

- fine for private/internal use if you own the rights
- do not assume they are okay for public redistribution without explicit permission

## Compatibility / Commercial Use Assessment

### No blanket clearance

This dependency review does not establish complete license compliance. In
particular, the current shaded-jar resource handling and incomplete release
license-text directory leave redistribution work outstanding. Applicable source
availability and replacement/relinking arrangements for the LGPL renderer also
need review; updating dependency versions does not satisfy those obligations.

`java-libpst` 0.9.3 declares Apache-2.0 in its POM. Its published source headers
also contain LGPL-3.0-or-later wording, and the upstream README describes both
licenses. Retain this provenance and the applicable notices when documenting the
Apache licensing option rather than discarding the source-file notices.

### Commercial distribution

Commercial use is likely feasible, but review these two items first:

1. `openhtmltopdf-pdfbox` under LGPL 2.1+
2. ThinkBox branding assets, which appear proprietary

### Easiest private GitHub position

For a private GitHub repository, the current stack is much easier to justify than
for a public release, as long as:

- secrets stay ignored
- proprietary branding assets remain intentional
- sample/social data is reviewed separately

## Recommended Follow-Up

Before broader sharing or release packaging:

1. Add the exact license text for `LiberationSans-Regular.ttf`.
2. Confirm whether the ThinkBox assets may be redistributed outside your organization.
3. Decide whether the LGPL renderer dependency is acceptable for your intended release model.

## Apache-Licensed Replacement Options For `openhtmltopdf`

There is no clear Apache-licensed drop-in replacement for `openhtmltopdf` in
the current HTML/CSS-based rendering design.

The realistic Apache-licensed alternatives are:

1. Apache FOP
   - Apache License 2.0
   - strong PDF/PDF-A tooling
   - not a drop-in replacement
   - requires converting the rendering layer from HTML/CSS templates to XSL-FO
2. Direct rendering on top of Apache PDFBox
   - Apache License 2.0
   - preserves an Apache-only dependency story
   - requires owning and maintaining the layout engine directly

So if the goal is “keep the current HTML/CSS templating model with minimal code
changes,” there is no obvious Apache-only substitute identified here. If the
goal is “remove LGPL from the runtime stack,” Apache FOP is the most credible
migration target, but it is a renderer-architecture change rather than a simple
library swap.

## JPEG orientation support

`com.drewnoakes:metadata-extractor:2.19.0` is Apache-2.0 and supplies Exif metadata reading. Its transitive `com.adobe.xmp:xmpcore:6.1.11` declares BSD-3-Clause in the published POM. The Adobe upstream BSD notice is included in `third_party_licenses/Adobe-XMP-Core-BSD-3-Clause.txt`, because that dependency JAR has no standalone notice file. Source: https://github.com/drewnoakes/metadata-extractor/tree/2.19.0 and https://github.com/adobe/XMP-Toolkit-SDK/blob/main/LICENSE.
