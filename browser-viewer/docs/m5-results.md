# M5 — Browser interface and cross-browser packaging

Completed 2026-10-27. Restart here after an interrupted session.

**Acceptance gate:** User opens a file, selects messages, views pages, browses
collections, and extracts an attachment in both supported browsers. Normal use
requires no helper app, external server, or network connection.

**Result: met for the tested Firefox and Chromium versions.** The saved M3/M4
reports and implementation were present after the crash, although the README
still described M2. This task connected that implementation to the viewer. It
did not retrieve a separate chat transcript. M6 has not started.

## Delivered

- Message and collection/folder navigation, including nested child PDFs.
- Stored message metadata, separate Mail GUID and Internet Message-ID, declared
  profile/revision, and an explicit **Conformance: Not evaluated** statement.
- Message selection opens its first uniquely owned BodyRendering page. Unknown
  or ambiguous ownership shows metadata and an explanation without a page guess.
- Document-level file lists include unknown owners; message-level lists include
  only known associations. Every extractable stream has its own download button.
- Exact-byte downloads, safe save names and no automatic attachment opening.
- PDF.js text search within the selected document, with page navigation and
  explicit search limits. Close/cancel terminates workers and clears the view.
- The same interface in both generated manifests, with no added permissions.

Unpacked builds: `browser-viewer/dist/chromium/` and
`browser-viewer/dist/firefox/`. Run the build, reload the installed extension,
and reopen its tab after changes. See the [user guide](../README.md).

## Verification

Commands run from `browser-viewer/` unless a path prefix is shown:

| Command | Final result |
| --- | --- |
| `npm run check` | Strict TypeScript and generated contract check pass. |
| `CHROMIUM_PATH=/snap/chromium/current/usr/lib/chromium-browser/chrome npm test` | Both builds; 10 Node tests; ordinary/EA-PDF rendering checks; 6 DPart comparisons; 13 M5 interface cases pass. |
| `CHROMIUM_PATH=/snap/chromium/current/usr/lib/chromium-browser/chrome npm run test:semantics` | All 27 independent semantic records, including recursive child records, agree offline. |
| `M5_UI=1 GECKODRIVER_PATH=/tmp/eapdf-m5-geckodriver/geckodriver npm run test:firefox` | Actual temporary Firefox extension; rendering and the same 13 M5 interface cases pass offline. |

Each browser exercised **21 message selections and 17 actual saved downloads**.
Download lengths and SHA-256 values match the independent M1 records, including
empty isolated streams. Cases include all six profiles, nested mixed collections,
corrupt children, duplicate GUIDs, missing streams, invalid page references and
folder cycles. Search cancellation and reopening pass in both; cancelling file
opening is additionally checked in Chromium. No fixture expectation was changed.

Chromium **156.0.8078.47** recorded zero HTTP(S) requests and zero uncaught page
errors. Firefox **158.0.1**, geckodriver **0.37.1**, ran with the browser offline
and recorded no HTTP(S) page resources. Firefox still emits the previously seen
host sandbox/GLib warnings and browser-internal PrivateBrowsingUtils shutdown
error; final test process exit is zero. These are not hidden as clean browser logs.

The M5 numerical reports and source/build fingerprints are in
[m5-verification.json](m5-verification.json). Full test logs and screenshots are
in ignored `browser-viewer/test-results/`. They are not packaged. Temporary saved
attachments and browser profiles are removed by the harness. The temporary
Firefox driver used above is removed after testing; restore the official pinned
0.37.1 release to run that exact command again.

## Changed files

Within `browser-viewer/` only:

- `src/viewer.ts`, `src/viewer.html`, `src/viewer.css`, `src/navigation.ts`.
- `src/extension/manifest.json` (name/description only; no permissions added).
- `scripts/ui-checks.mjs`, `scripts/ui-smoke.mjs`,
  `scripts/firefox-smoke.mjs`, `tests/navigation.test.mjs`, `package.json`.
- `README.md`, `docs/compatibility.md`, `docs/decisions.md`,
  `docs/m4-results.md`, this checkpoint and its verification record.
- Generated ignored extension builds and test results.

No converter, validator, interpreter, PDF.js patch/bundle, rulebook, fixture
expectation or original email file was edited in this task.

## Remaining work

Next task: **M6 — Security hardening, interoperability, release.** Start with
malformed/resource-limit and cancellation review; verify affected upstream tests
and third-party EA-PDF behavior before preparing release packages and dependency
maintenance documentation. Do not treat this M5 gate as release approval.

Current limits: 32 MiB input; 16 MiB per embedded stream; 64 MiB cumulative decoded
streams; four child levels and 32 children; 30-second worker-operation timeouts.
Child inspection is eager within those limits. Search stops at 2,000 pages or
ten million characters and has no cross-child index, match highlighting or OCR.
There is no text-selection/accessibility layer, printing, password entry,
URL/context-menu opening, automatic PDF interception or active annotation UI.
Full logical-structure, original-email and content-stream association inspection
remain not evaluated. Other OS/browser versions, signed installation, store
submission and comprehensive hostile-document protection are unproven.
