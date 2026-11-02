# V41 Content Group correspondence review in progress

V40 is accepted. The corrected probes under
`../validator-40/next-content-group-probes/` preserve the existing groups and
change only one supplied `Mail_ContentSetType` from BodyRendering to FrontMatter.
Both actual before pipelines PASS PDF/A, verified ISO32005 and custom checks.
The first insertion recipe accidentally wrapped an existing group and correctly
failed MAIL_CONTENTGROUP_CHILDREN; preserve that failed attempt.

EA-PDF9.7 Table13 requires a supplied group type to match DPM and XMP where
present. Compare connected Content Set DPM types, not unrelated values elsewhere.
Retain optional omission as a recommendation warning, but a supplied empty string
must undergo the conditional comparison. PDF name values and their string
representation retain exact characters; do not collapse spaces in opaque types.

Extend the V39 actual-content candidate helper to expose independently resolved
DPart Content Set types alongside GUIDs. Keep unknown type and unknown GUID
ownership separate. Shared/ambiguous content with compatible candidates remains
unverified; known universal contradictions fail. Never select a candidate using
the type being tested. Reuse one bounded content inspection for messages/groups.

The existing guessed XMP properties Mail_ContentSetType/ContentSetType do not
establish group ownership. Table13 names XMP but Table18 does not define the
corresponding group field/binding. If these candidate properties are supplied,
report that comparison as unverified rather than applying unrelated global values.
This remains an explicit source/implementation mapping gap; do not claim full
XMP correspondence or add a new metadata-presence requirement. No new expert
answer or external contact has been obtained; eleven listed questions and broader
limits remain open.

Expected next evidence: valid owner matching, foreign global type, padded/empty
values, permitted omission, opaque extension names with spaces, absent/ambiguous
actual content/type, known contradictions alongside unavailable evidence, and
unscoped XMP candidate values. Preserve source-media and original-source fidelity
limits. Creator phase remains deferred; no CLI/debug changes or target/debug edits.
