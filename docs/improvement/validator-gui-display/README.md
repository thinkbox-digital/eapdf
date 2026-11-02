# GUI display update

- Text is at least 18 points throughout the app and its Swing dialogs.
- Starting size: 2,000 × 1,100 pixels.
- Esc clears the keyword filter, including from the result table.
- Count column removed. Grouping and occurrence details remain.

`validation_scope` remains in the rulebook because Python uses it to reject
upstream-only rule entries and audit tools use it to order operational diagnostics
and verify ownership. It does not select or execute veraPDF checks.

All 79 Java checks pass, including the real desktop. The 29 validation classes,
rulebook and other backend resources are byte-identical to the previous delivery.
Full Java/web parity remains unfinished; overall PASS stays disabled.

![Updated GUI](window.png)

[Build evidence](evidence.json)
