# M0 implementation and verification

M0 acceptance is met for the tested desktop browsers. One ordinary PDF and one
existing EA-PDF sample render locally in the installed Chromium and Firefox.
Chromium loads the unpacked Manifest V3 extension; Firefox loads its temporary
add-on. Navigation, zoom, error handling and local asset loading work. No M1
inspection model, extraction code or PDF.js patch has been started.

## Baseline and scope

The full 352-line implementation plan was read before changes. Starting HEAD:
`b73d3fd5144f5e1a3e204342551fc1f63dcbdc88`. Existing Git status is recorded in
`baseline-git-status.txt`; plan and EA-PDF sample hashes are in `baseline.json`.
The project had no browser-viewer directory or Node project. The plan's old
`examples/sample_1s.pdf` path now resolves to `examples/eml/eml_sample_1s.pdf`.

All task-created files are under browser-viewer/. All pre-existing tracked files,
including converter/validator code and print statements, remain byte-identical
to the starting snapshot. An unrelated, previously untracked
`examples/tmp_pdf_file2717275462404108771.pdf` disappeared during the task. No task
command modified or removed it; its deleting actor is unknown. The scope audit
records that exception instead of claiming the entire workspace stayed static.
Existing converter/validator tests were not rerun because those files were not changed.

## Exact final checks

Commands below ran from browser-viewer/ unless stated otherwise. All listed final
commands exited 0. The Node cache flag changes cache placement only.

| Command | Exact result |
| --- | --- |
| `npm ci --cache /tmp/eapdf-m0-npm-cache` | Added 6 installed packages; audited 7; 0 vulnerabilities. Platform-specific optional packages are represented separately in the lock. |
| `sha256sum -c /tmp/eapdf-m0-lock-before.txt` | `package-lock.json: OK`; npm ci did not alter the lockfile. |
| `npm run check` | Strict TypeScript check passed with no diagnostics. |
| `npm run build` | Built dist/chromium and dist/firefox with PDF.js 6.4.299; no PDF.js changes. |
| `CHROMIUM_PATH=/snap/chromium/current/usr/lib/chromium-browser/chrome npm test` | Rebuilt both packages; 4 Node tests passed, 0 failed, 0 skipped; Chromium smoke passed. |
| `sha256sum -c /tmp/eapdf-m0-build-before.txt` | Both build-info.json hashes OK after the second build; the inventories hash every distributed file. |
| `GECKODRIVER_PATH=/tmp/eapdf-m0-geckodriver/geckodriver npm run test:firefox` | Firefox smoke passed using Firefox 158.0.1 and geckodriver 0.37.1. |
| `npm run verify:licenses` | Verified 16 locked packages, 203 pinned PDF.js files, retained notices and matching font sources. |
| `npm audit --json > test-results/npm-audit.json` | Total vulnerabilities 0: info 0, low 0, moderate 0, high 0, critical 0. |
| `git diff --check` (repository root) | No whitespace errors in tracked changes. New project text files also checked for trailing whitespace. |

The hash-list files were created with `sha256sum package-lock.json` and
`sha256sum dist/chromium/build-info.json dist/firefox/build-info.json` before their
respective checks. Temporary builds, test browser profiles and downloaded
diagnostic tools were cleaned after use. The built extension directories remain
available for manual loading and are ignored by Git.

Runtime/tool versions: Node 24.19.0; npm 11.17.0; TypeScript 5.9.3; Playwright
1.64.1; PDF.js 6.4.299, commit `d0991a0d53f3bb3a7307e3b83f279c1d117aafb3`.
The Firefox driver came from the
[official 0.37.1 Linux release](https://github.com/mozilla/geckodriver/releases/download/v0.37.1/geckodriver-v0.37.1-linux64.tar.gz),
verified against its published SHA-256
`e815130ea95983e162ae91843b48d3a3ce991735635fce83a647afde21e09f7e`.
It is a development tool, not an extension dependency.

## What browser checks established

Chromium 156.0.8078.47 installed the real unpacked extension, started its service
worker and opened the viewer tab through the same tabs API used by the action.
The ordinary fixture's first page had the expected blue rectangle; its second
page had the expected red rectangle. A real packaged PDF.js worker was present.
Next, direct page entry, out-of-range rejection and 150% zoom passed. An invalid
PDF displayed an error, cleared the old canvas and allowed reopening a valid PDF.
The existing two-page EA-PDF rendered and navigated successfully. The entire
file-opening sequence ran offline with zero attempted HTTP(S) requests and zero
uncaught page errors. Screenshots were inspected: text and page layout were visible.

Firefox 158.0.1 accepted the separate MV3 manifest as a temporary add-on. Its
ordinary-page rendering, next-page navigation, 150% zoom and EA-PDF rendering
passed while Firefox was explicitly offline. No HTTP(S) page resources appeared.
Firefox emitted the host warning `CanCreateUserNamespace() ... EPERM` and, during
shutdown, a browser-internal `PrivateBrowsingUtils.sys.mjs` docShell error. The
checks completed successfully; these environment messages were not suppressed.

EA-PDF sample SHA-256:
`b4fb687d52613c4f49a9e26b7c41498dc4bb39b34c04bd198f0cca6c163d4d48`.
Rendering this sample does not validate EA-PDF conformance or test attachment
extraction. The sample remains in its original repository location.

## Corrections during implementation

The first Chromium run failed direct page-entry navigation: control refresh
reset the requested value before it was read. The event handler now captures
the value before starting work; the same test passed unchanged afterward.
Initial Firefox automation attempts used the system-access flag in the wrong
place. The final harness passes `--allow-system-access` to geckodriver, which
starts a fresh test browser with the appropriate option. These failed attempts
are not counted as passing runs.

The original Liberation 1.07.4 source URL returned 404. The original source tarball
was recovered from Cygwin's source distribution and bundled with its hash,
editable font sources and build instructions. It is not mislabeled as SIL OFL.
No npm dependencies or PDF.js internals were patched to obtain passing tests.

## Remaining work

- Firefox permanent signing, release ID selection, AMO/store review and headed
  browser-toolbar interaction checks remain. Automated installation and page
  behavior passed; no manual toolbar click or store approval is claimed.
- Only the listed desktop browser builds were tested. Other OSes, older versions
  and Android remain unevaluated. Minimum manifest versions reflect tested majors.
- M0 provides a canvas page display, not accessible text navigation, search,
  password entry, printing, attachment opening or email semantics. Collection
  PDFs show their outer pages only. M1 and later milestones remain separate work.
- The 100 MiB file and 16-million-pixel canvas guards are not a complete hostile
  PDF resource policy. Cancellation, parser/decompression budgets and broader
  malformed-document testing belong to the later hardening milestone.
- Font/codec notices and corresponding Liberation sources are retained. Full
  release/store licensing and privacy review remains an M6 deliverable.

Machine-readable results: `m0-verification.json` and `scope-verification.json`.
Browser screenshots and raw smoke/audit results are in ignored test-results/.

## Changed files

All entries below are new and relative to browser-viewer/. Generated dist/,
node_modules/ and test-results/ are local build/test outputs, not source changes.

- `.gitignore`
- `.npmrc`
- `.nvmrc`
- `LICENSE`
- `README.md`
- `THIRD_PARTY_NOTICES.md`
- `docs/baseline-git-status.txt`
- `docs/baseline.json`
- `docs/compatibility.md`
- `docs/decisions.md`
- `docs/m0-results.md`
- `docs/m0-verification.json`
- `docs/scope-verification.json`
- `package-lock.json`
- `package.json`
- `scripts/browser-smoke.mjs`
- `scripts/build.mjs`
- `scripts/firefox-smoke.mjs`
- `scripts/licenses.mjs`
- `scripts/make-fixture.mjs`
- `src/extension/background.ts`
- `src/extension/manifest.json`
- `src/pdfjs-adapter.ts`
- `src/viewer.css`
- `src/viewer.html`
- `src/viewer.ts`
- `tests/fixtures/manifest.json`
- `tests/fixtures/ordinary.pdf`
- `tests/package.test.mjs`
- `tsconfig.json`
- `vendor/pdfjs-pin.json`
- `vendor/sources/liberation-fonts-1.07.4.tar.gz`
