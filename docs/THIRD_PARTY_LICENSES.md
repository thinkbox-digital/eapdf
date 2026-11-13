# Third-party licensing and redistribution

Reviewed 11 November 2026 against the current source, packaged notices and
[runtime inventory](security/runtime-inventory/DEPENDENCY_LICENSES.md). This
engineering inventory is not a blanket clearance for a distribution model.
Use [project status](PROJECT_STATUS.md) to distinguish released artifacts from
unreleased dependency changes.

## Authoritative inputs

- Java dependency declarations: root pom.xml and validator-desktop/pom.xml;
  resolved component licenses/hashes: the runtime inventory and CycloneDX files.
- Browser dependency/patch inventory and notices: browser-viewer/package-lock.json,
  its vendor maintenance records, and the SBOM/notices in each extension package.
- Python runtime: requirements-web.txt and its hash lock. Website, manual, quality
  checks and the frozen browser inspection oracle have separate dependency sets.
  Their versions must not be represented as one deployment environment.
- Application LICENSE/NOTICE, third_party_licenses, and notices retained inside
  the exact JAR/ZIP being distributed. Inventory declarations alone do not prove
  that a particular binary contains every required notice/source component.

## Renderer and patched dependencies

The creator uses **openhtmltopdf 1.1.113-eapdf.1** under LGPL-2.1-or-later, not
the earlier 1.1.97 dependency. [Renderer provenance](RENDERER.md) records pinned
upstream/patch commits, builds and replacement instructions; [library packages](PACKAGES.md)
describes distribution of maintained patched libraries. Keep the applicable
license, modification/corresponding-source records and replacement/relinking
arrangements available for the chosen release model. A shaded JAR and merged
notices do not by themselves establish compliance with those obligations.

PDFBox, XMPBox, POI, the Google clients and other components have their own
licenses in the inventory. Eclipse components have component-specific declared
licenses; use those records rather than a blanket label for all Jakarta/Eclipse
libraries. The validator also packages modified veraPDF sources/notices as
configured in its POM; retain and review those materials.

java-libpst 0.9.3 declares Apache-2.0 in its POM; source headers also include
LGPL-3.0-or-later wording and the upstream README describes both licenses.
Preserve that provenance and applicable notices rather than discarding source
headers based only on a Maven label.

## Fonts and branding

| Assets | Recorded notices and disposition |
| --- | --- |
| Liberation Sans and Serif | Version 2.1.5; SIL OFL-1.1 text is present in third_party_licenses/Liberation-OFL-1.1.txt. The earlier statement that this notice was absent is obsolete. |
| DejaVu Sans/Mono | Version 2.37; Bitstream Vera/Arev notices are present in third_party_licenses/DejaVu-LICENSE.txt. |
| Droid fallback font | Retain third_party_licenses/DroidSansFallback-LICENSE.txt and the matching provenance. |
| ThinkBox logos/footer assets | No general open-source grant for these brand assets is supplied. Confirm the owner's intended distribution rights separately from the code's Apache license. |

Current creator/GUI packaging retains project and font notices under
META-INF/eapdf, and merges colliding dependency license/notice resources. Browser
packages retain their own notices, font sources and SBOM. Verify the resulting
release bytes; do not carry forward an old packaging result as a new audit.

## Python and ancillary tools

The working-tree requirements currently select pikepdf 10.18.1 and pypdf 7.1.0;
these dependency refreshes must be reconciled with the intended release and
installed service environment. See the lock and runtime inventory for exact
versions and declared licenses. Installing/rebuilding a release is distinct from
editing its requirement files.

Flask/Werkzeug, pypdf and ReportLab record BSD-family licenses; Gunicorn records
MIT, and pikepdf records MPL-2.0. Confirm transitive dependencies and bundled/native
components against the specific environment. Test-only dependencies do not
necessarily ship, but their fixture/data redistribution still needs review.

metadata-extractor 2.22.0 records Apache-2.0. Its xmpcore dependency records
BSD-3-Clause; the Adobe notice is retained in
third_party_licenses/Adobe-XMP-Core-BSD-3-Clause.txt because the dependency JAR
has no standalone notice file.

## Release checklist

1. Inventory the actual JARs, extension ZIPs, installers and hosted environment,
   distinguishing runtime, development and test-only material.
2. Check license texts, modification notices, corresponding-source and
   replacement instructions against the exact bundled versions and build mode.
3. Confirm branding and fixture/document redistribution rights; exclude private
   correspondence, credentials and unapproved original mail.
4. Retain checksums, build/source provenance and the applicable notices with the
   release. Assign an owner for later dependency and obligation updates.

No renderer migration or dependency/license change was made by this documentation
consolidation. A renderer replacement would require a separate architecture and
fidelity decision; it is not a drop-in documentation repair.
