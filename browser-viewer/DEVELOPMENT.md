# Browser reader development

## Build

Before regenerating extension distributables, bump the reader minor version in
`package.json` and `package-lock.json` using the three-part format `1.x.0`. Rebuild
Full for Chromium and Firefox using one version per regeneration batch. Verification
rebuilds in that batch retain the chosen version. Keep local installation paths
stable; reload the extensions and reopen viewer tabs after updating their files.

Use the Node.js/npm versions listed in `package.json`, then run from this folder:

```sh
npm ci
npm run check
npm test
npm run package:beta
```

Full Chromium and Firefox packages appear in `releases/`. The shared
exporter also supports the separate [EA-PDF Lite repository](https://github.com/thinkbox-digital/eapdf-lite).

## Tests

`npm test` runs the full unit and Chromium suites. `npm run test:firefox` covers
Firefox. The independent PDF inspection checks use Python and
`reference/requirements.txt`; run them with `npm run test:m1`.
The inspection provenance records exact package versions and source hashes. After
changing those requirements, install the complete pins in an isolated CPython 3.12
environment and run `python -B reference/fixtures.py --write`. Review the manifest
and extracted-record differences, then run `npm run test:m1` and
`npm run fixtures:check` with that environment on `PATH`. Tests never refresh
their expected records automatically.
Browser tests need the corresponding browser and driver installed. Use
`CHROMIUM_PATH`, `FIREFOX_PATH` and `GECKODRIVER_PATH` when they are outside the default locations.

For PDF.js API and fixture details, see the [inspection contract](docs/inspection-schema.md).

Build uses TypeScript and PDF.js **6.5.438**.
PDF.js upstream commit: `0a4153349dc473ba950fc17941c5ca7eda43c1de`.
