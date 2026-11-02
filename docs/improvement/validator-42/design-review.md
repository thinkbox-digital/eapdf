# V42 active user property occurrences — review in progress

V41 is accepted. Four exact-byte probes under
`../validator-41/next-userproperty-occurrence-probes/` all pass PDF/A/ISO. A uniform
duplicate GUID passes. A wrong GUID before a correct one incorrectly passes,
while reversing those two properties fails. Two wrong occurrences fail.

ISO32000-2:2020 14.7.6.4 Tables361/362 states that every P-array dictionary is an
individual property. EA-PDF9.7 Tables12/13 constrain the named individual
properties. No within-array last-value priority or duplicate-name prohibition
has been established. Inspect every applicable supplied property; keep uniform
legal duplicates. Preserve the separately reviewed whole-P-array replacement
across attribute objects/classes/revisions.

Target GUID, Content Group type and supplied Content Set subtype comparisons.
Retain exact N names and occurrence indices. A contradicted individual property
fails even when a later property matches; unknown evidence remains explicit.
Do not broaden this into an unsupported uniqueness/cardinality requirement.
Optional attribute presence remains distinct from each supplied value's matching.
Unbound XMP group types retain V41's explicit incomplete mapping limit.

Other scalar lookups, FrontMatter nesting interpretation, source recommendation
ownership, and subtype formatting remain separate review work. V42 also binds subtype comparisons to independently resolved connected DPart data; missing counterparts remain explicitly unavailable. Preserve
all known creator failures, eleven listed questions and broader coverage limits.
No CLI/debug changes or target/debug edits are authorized by this review.
