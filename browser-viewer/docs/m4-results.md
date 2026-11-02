# M4 — EA-PDF semantic interpretation

Acceptance gate: All six sample profiles are interpreted correctly; selecting
each message resolves the expected page or embedded child; attachment ownership
matches reference data. No false claims of validation PASS.

The semantic data portion passed before UI work: all 27 independent M1 records,
including recursive child inspections, match browser output for declared profiles,
message identities, body ranges, message/attachment ownership, source uncertainty,
file roles, decoded lengths and checksums. All 27 browser records also validate
against the unchanged inspection-v1 JSON schema. No frozen expected data was
rewritten. Message-selection interaction is exercised by the M5 UI gate.

The pure TypeScript interpreter accepts generic records and an injected XML
parser; no PDF.js core access or PDF parsing is used. It retains exact GUIDs,
separate Internet Message-ID, URI/type failures, duplicates and filename-only
candidate matches. Source ownership without an explicit link remains unavailable.
Zero-byte isolated streams remain present. Validation is always not-evaluated.

InspectionSession handles loaded documents and recursive children separately,
with content-hash identities, ancestor detection, child count/depth limits, shared
decoded-byte budget, dedicated cancellable workers and 30-second request timeouts.
Metadata headers for display are kept separate from identity/binding decisions.

Commands: npm run check; npm run build; Chromium semantic-smoke.mjs (27 PASS,
offline); Draft202012Validator over test-results/semantic-actual.json (27 PASS).
Machine-readable evidence is in m4-semantic-parity.json. Full logical-structure,
source-email and content-stream AF interpretation remain explicitly not evaluated.

M5 now completes the message-selection interaction gate in both browsers. See
[the M5 checkpoint](m5-results.md) for exact results and the next task.
