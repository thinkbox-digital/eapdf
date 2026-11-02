# V15 — supplied attachment identity type

Table 19 makes attachment Mail_GUID presence conditional on Message-ID. Its
prescribed text type still applies when a Mail_GUID is supplied. Previously, a
valid Message-ID skipped that type check and URI, array or structured Mail_GUID
values could pass. The change checks a supplied Mail_GUID independently of the
presence exception. It introduces no GUID lexical pattern or additional
presence requirement.

Eight exact-byte PDF probes cover five legal forms and three intended failures.
Normal text, optional absence, qualified text, a property attribute on an inner
RDF Description, and an unrelated foreign-namespace extension remain legal.
The malformed forms fail exactly rule 199. The unchanged V14 engine accepts the
three malformed cases while independent PDF/A and upstream ISO pass.

The initial attribute recipe incorrectly combined property attributes with
parseType Resource. Its PDF/A serialization failure remains in the initial
snapshot and reports. The corrected recipe uses an inner RDF Description node.
Seven original PDFs are retained byte-for-byte; only attribute.pdf changes.
[Correction record](fixture-construction-correction.json).

The final runtime snapshot is `.eapdf-verification-v15-final`. All final lanes completed.
`assemble-evidence.py` verifies
381 unit passes/32 skips, eight independently checked before/after PDFs, all
133 custom-only fixture comparisons (only the three declared findings change),
and sixteen actual CLI/isolated-worker checks before writing
[evidence.json](evidence.json).

V14's prior 125 complete-pipeline cases have identical PDF bytes and unchanged
current custom results. Its twelve generated profiles remain a separately
bounded prior gate. There is no new V15 twelve-profile, full packaged-suite or
Java-build claim. Creator source/resources/JAR, CLI arguments and debug output
are unchanged. The initial worker Docker client pthread_create failure remains
retained; the final entry run reduces concurrent lanes and uses GOMAXPROCS=2 for
the Go client without relaxing validation limits.

[Supporting metadata inspection](metadata-review.json) records this bounded
Table 16/18/19 continuation. It is not a second authority or completeness claim.
The eleven expert/dependency questions, broader owner/occurrence review,
original-source completeness and inherited-edition boundary remain open.
Broader preservation B13 remains deferred.
