# EA-PDF contributor guidance

Build with Java 21 and `mvn package`. For verification in a temporary build directory, run `mvn -Deapdf.build.directory=/tmp/eapdf-build verify`. Start with the [Where to work map](docs/ARCHITECTURE.md#where-to-work) for creator, validator, and reader code.

## Development

- Keep edits focused. Preserve CLI arguments and existing debug output unless the requested change requires otherwise. Leave generated files in `target/` and debug directories alone.
- Main ships the Full browser viewer. Retain the shared Lite exporter for EA-PDF Lite and the viewer’s independent CSV metadata export. C2PA/PAdES development belongs in Secure. For embedded search, follow the [supported signing workflow](docs/EMBEDDED_SEARCH_INDEX.md).
- Main is the source of truth for the common implementation. Preserve Lite and Secure additions and intentional edition differences. When changing Main, apply every shared change to each affected edition in the same task and verify it there. This includes contributor instructions and documentation. A change that applies to all three must reach Main, Lite, and Secure without a separate request. Follow the [cross-edition workflow](docs/ARCHITECTURE.md#cross-edition-improvements) and report the result for each edition.

## Conversion and validation

- Leave optional conversion features off in GUI runs, CLI runs, custom configurations, and regeneration unless the user explicitly requests each feature. This includes Accessibility (PDF/UA), embedded metadata and full-text search indexes, digital signing, C2PA provenance, and PREMIS where supported. Use regeneration helpers that respect these choices.
- Use bounded parallelism where supported and validate with the standalone Java validator. Use the Python validator for its regression tests and requested comparisons or diagnostics.
- Use the real inputs under `in/` for conversion regressions. Preserve originals, record source hashes, retain logs and Java validation reports, and identify rejected or incomplete files. Use synthetic fixtures to isolate additional edge cases.
- Append the export profile to PDF filenames. Store published examples under `examples/<input-type>/` with a lower-case type prefix, such as `eml_sample_1s.pdf`.

## Documentation and releases

- Update the user guide when behaviour changes, [project status](docs/PROJECT_STATUS.md) for handovers, and [architecture](docs/ARCHITECTURE.md) for edition decisions. Keep historical audit counts dated.
- Anonymize private correspondence, contributors, companies, and sample filenames. Preserve required legal attribution. Link to published examples or release assets, summarize verified local results, and use explicit temporary destinations in command examples. Run `python3 scripts/check_public_docs.py` before publication.
- For version bumps and extension regeneration, increment the browser viewer minor version once per batch and rebuild Full for Chromium and Firefox together. Use plain `1.x.0` consistently in the viewer package, lockfile, browser manifests, distributable filenames, and current reader documentation. Reuse the version for verification builds.
- Keep only the latest versioned creator, GUI, validator, and desktop viewer JARs in `dist/`. Verify replacements before removing superseded JARs. Generate checksums in release staging.
- For an authorized release, publish the versioned assets and checksums and verify the uploaded files. The creator and validator version determines prerelease status. Keep release notes to versions, user-visible changes, essential setup, and beta status. Record benchmarks, test counts, and audit evidence in project documentation.
