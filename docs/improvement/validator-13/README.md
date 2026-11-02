# V13 — original versus native attachment filenames

Table 19 requires XMP to retain the original MIME attachment filename and
explicitly permits a different PDF embedded filename. A retained valid PDF passed
PDF/A but failed custom annotation coverage after changing only its native
filename, including when an exact payload checksum was supplied. The same false
failure occurred with the optional XMP checksum omitted.

The generated-name alternative in Table 19 applies when the original MIME
attachment has neither a filename nor a name parameter. This repair preserves
the existing case-sensitive original-source filename comparison; it changes
association with the permitted different PDF filename.

The repair accepts checksum-supported native filename differences. When CheckSum
is absent, a bounded uniquely selected owner/source can supply raw payload
evidence; an actual empty policy placeholder is also directly comparable.
Unresolved source-omitted aliases remain unavailable and make validation
INCOMPLETE. Original MIME filename case, visible annotations, CropBox bounds,
actual stream parameters, supplied checksums, sizes and media-type comparisons
keep their checks. No generic upstream predicate was copied locally.

Nine focused unit tests pass. Twelve new probes include legal large integers:
the initial size gate treated a failed Python integer conversion as absence,
allowing a PDF/A-valid 5,000-digit incorrect SizeInBytes to pass. The final gate
compares decimal values directly and preserves legal long zero padding.
The initial source-omission fixture attempt left
dangling OBJR/annotation ParentTree references; the validator correctly rejected
three fixtures. The recipe now removes those references while preserving their
rendered-label MCID owners. Initial logs and the correction description are
retained separately from final results.

Final frozen V13 acceptance runs the current unit suite, all 110 retained complete
pipeline cases, both normal/accessibility generated-profile integration tests,
and actual CLI/isolated-worker checks in independent lanes. `evidence.json`
records completed results and exact source/artifact boundaries. Earlier V11 full
and V12 scoped gates remain historical results, not a claimed V13 full-suite run.

The initial `.eapdf-verification-v13` snapshot, its successful twelve profile and
eight entry-point checks, and its interrupted 108-case corpus log remain separate.
The size bypass was confirmed during that run. Final inputs are in
`.eapdf-verification-v13-final`; final logs/manifests/capsule use a `final` suffix.

The Java creator artifact is unchanged from its 161-test package. Eleven
expert/dependency questions, broader ownership assurance and complete coverage
limits remain explicit. No CLI arguments, debug output, target/debug files,
commit, deployment or external publication changed.

## Completed acceptance

Frozen V13: 365 unit passes / 29 expected integration/schema skips;
all 110 retained exact outcomes; all twelve normal/accessibility profile reports;
all ten actual CLI/worker checks. Two profile integration tests pass. Source,
test and fixture hashes still match the freeze. Exact-byte old-engine results
and `association-observations-final.json` retain the supporting observations.

No V13 full packaged-suite run is claimed. The linked V11 full suite and Java
package keep their historical boundaries. The expert/dependency and coverage
limits listed above remain open.
