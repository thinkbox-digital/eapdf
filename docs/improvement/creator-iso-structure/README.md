# Creator repair — mixed inline content in an imported Div

The new upstream ISO pass found `Table 5. Art-content` failures in both original
image review probes. PDF/A and custom EA-PDF checks passed. The selected ISO
predicates remain unchanged.

The IntelliJ logpoint at `SemanticStructure.normalizeBlocks` observed
`role=Div inline=true block=true`, with two child references and raw MCID 12.
The previous normalizer converted only wholly inline Divs to paragraphs. A mixed
Div stayed transparent to the upstream inclusion model, exposing its raw content
to the containing Art. [Runtime evidence](tracepoints.json) and the saved PDFs
establish the producing path. The temporary debugger session/configuration and
agent breakpoints were removed; the two disabled user breakpoints were retained.

The repair groups consecutive inline children/content into paragraph elements,
preserves their order, updates moved structure parents and retargets ParentTree
ownership for MCID/MCR/object references. Unmatched imported ownership stops
creation rather than publishing an inconsistent tree. Source painting commands,
images and alternative text are preserved.

All 161 Java tests pass. Both original image probes now pass PDF/A, the custom
checks and all 1,636 selected ISO rules. The packaging gate also passes. The
before/after comparison verifies identical decoded images, alternative text and
page content bytes. Only `SemanticStructure.class` and Maven timestamp metadata
changed relative to the complete preceding JAR.

Saved-sample regeneration completed with all six profiles passing. The frozen
V11 full packaged suite completed with 375 passes and 1 optional schema skip.
Logs and [evidence](evidence.json) retain the source/artifact boundaries. No public CLI
arguments, debug output, external deployment or publication changed.
