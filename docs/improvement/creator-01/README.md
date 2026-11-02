# C01: creator header operands and archive ownership

The user stopped validator development at V85 and authorized creator improvements. This batch fixes two documented causes of failures in ordinary creator output.

Header fields were renamed in the structure tree while their marked-content operands stayed `Span`. The creator now uses each page or Form's ParentTree ownership to rename the corresponding BDC operand. Named property dictionaries, repeated MCIDs in different scopes and recursive Form resources are handled. Stream identities and their existing references are preserved.

Noncollection archive finding aids were root-level Content Sets without a Mail_Message ancestor. Their FrontMatter now belongs to the first represented email in both the logical structure and DPart trees, following the independently authored V53 positive controls. Each email's BodyRendering ownership stays distinct. Collection parents keep their separately reviewed structure and configured finding-aid PDF/XLSX sidecars; m/mi retain their embedded XLSX indexes.

Fresh IntelliJ logpoints captured the header role changes and `archive front parent=Document` before editing production code. The debugger reported no evaluation errors. Two header tests and the archive test failed before repair; all four focused tests pass afterward. The temporary remote configuration and three agent breakpoints were removed, preserving the two disabled user exception breakpoints.

Verification:

- Isolated `mvn -o -Deapdf.build.directory=/tmp/eapdf-creator-01-build package`: 165 tests, zero failures, errors or skips; package succeeds.
- Six profiles (`1s`, `1si`, `1m`, `1mi`, `1c`, `1ci`) generated in both normal and accessibility fixture configurations: all twelve pass actual PDF/A, verified ISO and unchanged V85 EA-PDF checks. Contained email PDFs also pass.
- Four collection finding-aid PDFs pass actual PDF/A checks without embedded files; all four accompanying XLSX files contain a workbook. All four m/mi outputs retain embedded XLSX indexes.
- Native header probe: all 362 operators and their properties/drawing commands are identical after accounting for the ten corrected BDC tag names. Retained original email bytes have the same hash.
- Validator source, rule document and web wrapper retain their pre-batch hashes. Build output is outside `target` and debug directories. Existing examples are not regenerated.

Reproduce profile checks with `python3 -B docs/improvement/creator-01/verify-profiles.py` after the isolated Maven package command. The harness retains generated PDFs, source fixtures, configurations and reports here. `CreatorStructureProbe.java` and `verify-preservation.py` record the bounded native comparison. [Acceptance evidence](evidence.json), [profile outcomes](profile-results.json), [runtime proof](before-tracepoints.json).

Acceptance covers these repairs and fixtures. Complete source-format preservation, rendering equivalence across arbitrary inputs, PDF/UA conformance, full validator obligation coverage and release acceptance remain separate work. The historical V24 creator PDFs remain failing controls. The next creator review should address ordinary conversion behavior and remaining documented creator defects using the fixed V85 checks; V86 validator development remains stopped.
