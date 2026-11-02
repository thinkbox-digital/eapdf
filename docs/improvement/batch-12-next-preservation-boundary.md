# Next preservation boundary after B12

B12 completes a bounded normalized writer inventory across the six current
profiles: expected messages, native attachment occurrences/owners, source bytes,
named collection children, and deliberate isolation omissions. It detects loss
after those records reach writer entry, including cases that pass PDF-only checks.

Next establish original reader/input completeness. `Common.JobReport.stage`
counts already-normalized records. It cannot prove that an MBOX iterator found
every input message, a PST traversal visited every eligible message/folder, a MIME
or MAPI attachment disappeared before normalization, or a dispatcher dropped a
reader result before calling the writer. Gmail download enumeration and resume
state add account/API boundaries that require their own evidence.

A bounded next batch should independently count/identify eligible entries in
small authored MBOX and PST/MIME fixtures, record every selected/excluded/failed
occurrence with its reason, and reconcile reader output against that inventory.
Keep non-email PST items, malformed records, explicitly skipped input paths and
intentional source isolation distinct. Use source bytes/entry identities rather
than generated XMP as the oracle. Add a skipped reader occurrence and an omitted
MIME attachment as before-fix probes; require that neither silently becomes a
completed input.

Retain reconstruction provenance: native EML/MSG bytes differ from extracted MBOX
messages and synthesized PST exports. B12 compares the conversion source it was
given; it does not establish bit-for-bit preservation of a whole PST/MBOX store.
The existing derivation notices remain, and richer machine-readable provenance
is still open.

Independent PDF/A/full EA-PDF validation of exact staged outputs and configured
sidecars remains a separate publication requirement. Complete XMP/source ownership,
arbitrary mixed-profile imports, unused legacy helper integration and full
standards/human/platform acceptance also remain open.
