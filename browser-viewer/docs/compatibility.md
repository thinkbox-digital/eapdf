# Browser compatibility and Manifest V3

Both builds use the same viewer, public PDF.js API and local module worker.
The only browser-specific code is generated manifest configuration.

| Item | Chromium | Firefox |
| --- | --- | --- |
| Tested installed browser | 156.0.8078.47 | 158.0.1 |
| Manifest | V3 | V3 |
| Background | Module service worker | Module event-page script |
| Permissions / optional site access | contextMenus, activeTab / HTTP(S) | contextMenus, activeTab / HTTP(S) |
| Installation verified | Unpacked development extension | Temporary development add-on |
| Ordinary PDF / EA-PDF pages | Passed offline | Passed offline |
| Older versions / Android | Not evaluated | Not evaluated |
| Store signing / approval | Not performed | Not performed |

The Chromium manifest bundles all executable JavaScript and WebAssembly. It has
no remote code, inline script, eval permission, sandbox page, content script or
web-accessible resource declaration. CSP permits packaged resources and HTTP(S) fetch for explicitly opened PDF links;
optional host access is requested on that action. It does not permit remote code. `wasm-unsafe-eval`
permits those bundled codecs; it does not permit JavaScript string evaluation.
Inline styles are allowed for PDF.js font/style handling; document content is
never inserted as HTML. Font/blob and image/data sources are local rendering
resources. PDF.js's scripting sandbox module is not distributed or invoked.

Firefox uses `background.scripts` because its background lifecycle differs from
Chromium service workers. Its manifest also contains a stable development Gecko
ID and `data_collection_permissions.required: ["none"]`. The Chromium build
contains neither Firefox-specific field. No file:// permission is necessary:
the picker reads bytes and the worker receives those bytes, not a file URL.

Still needed for a release: signing and AMO packaging, selection of a release
extension ID, store permission/privacy review, headed browser-toolbar interaction
checks, tests on other operating systems and versions, and accessibility review.
The automated checks verified installation and extension-page behavior using
fresh profiles; they did not click browser-owned toolbar UI. Firefox 158 emitted
a host sandbox warning and a browser-internal shutdown error in this environment;
these are recorded in M0 results rather than hidden as extension failures.

Primary references checked during M0:

- [Chromium extension CSP](https://developer.chrome.com/docs/extensions/reference/manifest/content-security-policy)
- [Playwright extension testing](https://playwright.dev/docs/chrome-extensions)
- [Mozilla background manifest differences](https://developer.mozilla.org/en-US/docs/Mozilla/Add-ons/WebExtensions/manifest.json/background)
- [Firefox data-consent manifest requirements](https://extensionworkshop.com/documentation/develop/firefox-builtin-data-consent/)
- [PDF.js display layer and distributions](https://mozilla.github.io/pdf.js/getting_started/)

The M2 [browser parity report](m2-browser-parity.json) separately records the new
API checks. Firefox signing, older browsers and store approval remain unevaluated.

Beta 0.2.0 adds recursive embedded-PDF navigation, chunked local-file reads, lazy
children, selectable text and search highlights. Both browsers pass the shared
large-file and seven-level recursion tests. Native context-menu invocation and
site-access prompt acceptance remain manual checks. Permission API references:

- [Mozilla optional host permissions](https://developer.mozilla.org/en-US/docs/Mozilla/Add-ons/WebExtensions/manifest.json/optional_host_permissions)
- [Mozilla permission requests](https://developer.mozilla.org/en-US/docs/Mozilla/Add-ons/WebExtensions/API/permissions/request)
- [Chrome permission declarations](https://developer.chrome.com/docs/extensions/develop/concepts/declare-permissions)
