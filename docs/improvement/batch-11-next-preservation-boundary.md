# Next preservation assurance boundary

User direction: prioritize preservation assurance, as approved at the end of
thread `01a14fec-a1ff-7361-9b28-0bec55c15499`.

B11 closes recursive final-child consistency. The next bounded repair should
establish an expected inventory independently of generated XMP/PDF objects.

Observed input path: `EmailToPDFA.readAny` dispatches readers and
`Common.JobReport.stage` counts normalized records, applies limits and stages
payloads. `convertArchive` concatenates reader results, writes them, then marks
input paths completed. Those counters do not provide a per-message/per-attachment
reconciliation against the final serialized output. An asset omitted together
with its XMP is not necessarily discoverable by PDF-only validation.

A bounded next batch can freeze normalized input identities plus SHA-256/size
for retained sources and native attachments before rendering; record explicit
isolation omissions; and compare final message/attachment occurrences within
their owning GUID and child. Establish failing tests by dropping an entire record
or native payload together with its generated metadata, swapping equal-named
attachments between owners, and preserving existing destinations on rejection.
Byte hashes must be computed from input streams, not copied from generated PDF
metadata. Repeated attachments require occurrence counts, not set membership.

This first inventory would establish the reader-to-writer boundary. Proving that
readers found every message/attachment in PST/MBOX/Gmail and distinguishing native
source bytes from reconstructed exports requires separate reader/input inventories
and reconstruction provenance. Optional isolated omissions remain explicit policy;
no absence is automatically a preservation failure. Independent PDF/A/full EA-PDF
publication gates and complete configured-sidecar validation also remain open.
